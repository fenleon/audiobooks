package com.lightphone.audiobooks.server.player

import android.content.Context
import android.util.Log
import com.lightphone.audiobooks.server.PlaybackForegroundService
import com.lightphone.audiobooks.server.PlaybackSettingsStore
import com.lightphone.audiobooks.server.library.Audiobook
import com.lightphone.audiobooks.server.library.AudiobookProgressStore
import com.lightphone.audiobooks.server.library.EmbeddedChapter
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "LocalPlayback"
private const val PROGRESS_SAVE_INTERVAL_MILLISECONDS = 7_000L
private const val END_EPSILON_MILLISECONDS = 1_500L
private const val SILENT_FAILURE_TIMEOUT_MILLISECONDS = 3_000L
/** How long a non-playing state must hold before the foreground service stops (seek re-buffers dip isPlaying briefly). */
private const val FGS_STOP_DEBOUNCE_MILLISECONDS = 2_000L

/**
 * Owns playback via the SDK's [LightAudioPlayer] (ExoPlayer queue) and keeps
 * [PlayerState] + the foreground service in sync. The queue is the whole book
 * (one item per part); the global timeline math in [MultiPartTimeline] maps
 * book positions to queue items.
 *
 * State is event-driven: the player's StateFlows (isPlaying, item index,
 * resolved duration) drive updates, with a 1 s ticker **only while playing**
 * for the time-based work (embedded-chapter boundaries, progress
 * persistence). While paused there is no polling: the media session's
 * position is extrapolated by the platform from the last transition.
 */
object LocalPlaybackController {
    private lateinit var appContext: Context
    private lateinit var player: LightAudioPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlayerState())
    private var partDurations = LongArray(0)
    private var activeBook: Audiobook? = null
    private var speed = 1f
    private var lastSavedAt = 0L
    private var pendingSeekMilliseconds: Long? = null
    private var lastPartIndex = -1
    private var lastChapterPartIndex = -1
    private var lastChapterIndex = -1
    private var lastResolvedDuration = 0L
    private var pendingFgsStop: Job? = null
    /** Play intent: true from play() until pause()/close()/end. The reported
     *  isPlaying is this, not the raw player state, so a seek re-buffer (which
     *  briefly dips isPlaying false) doesn't flip the UI to paused. */
    private var playRequested = false

    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        player = LightAudioPlayer(appContext, LightAudioUsage.Speech)
        PlaybackMediaSession.init(appContext)
        // Speed is a global setting; report it even before any book is open.
        speed = PlaybackSettingsStore.playbackSpeed.coerceIn(0.5f, 2f)
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
        collectPlayerEvents()
    }

    fun open(book: Audiobook, autoPlay: Boolean = false) {
        Log.d(TAG, "open() called for '${book.title}'")
        try {
            openInternal(book, autoPlay)
        } catch (error: Exception) {
            Log.w(TAG, "open() failed", error)
            PlaybackForegroundService.update(appContext, false)
            playRequested = false
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                readiness = PlayerReadiness.Error,
            )
        }
    }

    private fun openInternal(book: Audiobook, autoPlay: Boolean = false) {
        persistProgress()
        PlaybackForegroundService.update(appContext, false)
        player.stop()
        // stop() keeps playWhenReady from whatever was playing before; an open
        // must never auto-play the new book (only the explicit autoPlay path
        // below may start it).
        player.pause()
        activeBook = book
        val saved = AudiobookProgressStore.read(book.source, book.id)
        // Speed is global across books, not restored per book.
        speed = PlaybackSettingsStore.playbackSpeed.coerceIn(0.5f, 2f)
        val references = book.parts.map { it.playbackReference }
            .ifEmpty { listOf(book.playbackReference) }
        partDurations = LongArray(references.size) { index ->
            book.parts.getOrNull(index)?.durationMilliseconds ?: 0L
        }
        val savedPosition = saved.positionMilliseconds.coerceAtLeast(0L)
        // Fall back to part 0 when part durations are unknown: locatePart would
        // otherwise land on the last part, corrupting the resume position.
        val durationsKnown = partDurations.any { it > 0 }
        val startPart = if (durationsKnown) locatePart(savedPosition, partDurations).index else 0
        val items = references.map { reference ->
            LightAudioItem(
                source = LightAudioSource.UrlSource(reference),
                metadata = LightMediaMetadata(
                    title = book.title,
                    artist = book.author.takeIf { it.isNotBlank() },
                ),
            )
        }
        mutableState.value = PlayerState(
            bookId = book.id,
            title = book.title,
            chapter = book.author.takeIf { it.isNotBlank() },
            positionSeconds = savedPosition / 1000.0,
            durationSeconds = (book.durationMilliseconds.takeIf { it > 0 }
                ?: saved.durationMilliseconds) / 1000.0,
            currentPartIndex = startPart,
            partCount = book.parts.size.coerceAtLeast(1),
            partTitle = book.parts.getOrNull(startPart)?.title,
            playbackSpeed = speed.toDouble(),
            readiness = PlayerReadiness.Preparing,
        )
        pendingSeekMilliseconds = null
        lastResolvedDuration = 0L
        playRequested = false
        runCatching {
            player.setMediaQueue(items, startIndex = startPart.coerceIn(0, items.lastIndex))
            player.speed = speed
            // Applied once the start item's duration resolves (LightAudioPlayer
            // clamps seeks to a known duration).
            pendingSeekMilliseconds = if (durationsKnown) {
                locatePart(savedPosition, partDurations).positionMilliseconds
            } else {
                null
            }
            lastPartIndex = startPart
        }.onFailure { error ->
            Log.w(TAG, "queue set failed: ${error.message}")
            PlaybackForegroundService.update(appContext, false)
            playRequested = false
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                readiness = PlayerReadiness.Error,
            )
            PlaybackMediaSession.update(mutableState.value)
        }
        // Seed the embedded-chapter tracker at the resume position so the
        // auto-play-off boundary pause never fires spuriously on the first tick.
        lastChapterPartIndex = startPart
        lastChapterIndex = chapterIndexAt(
            chaptersForPart(startPart),
            pendingSeekMilliseconds ?: 0L,
        )
        applyPendingSeekIfResolved()
        updateState()
        if (autoPlay) play()
        armSilentFailureCheck()
    }

    fun play() {
        if (mutableState.value.readiness != PlayerReadiness.Ready) return
        // An ended book restarts from the beginning, like the old MediaPlayer
        // did. Restart via seekTo(0): a full queue rewind to chapter 1 that
        // also updates the state (title/position) and persisted progress —
        // seeking only the current item would replay the last chapter and
        // leave the UI showing stale chapter/percent.
        if (!player.isPlaying.value && player.durationMs.value > 0 &&
            player.currentMediaItemIndex.value == partDurations.lastIndex &&
            player.positionMs.value >= player.durationMs.value - END_EPSILON_MILLISECONDS
        ) {
            seekTo(0L)
        }
        playRequested = true
        player.play()
        armSilentFailureCheck()
    }

    fun pause() {
        pausePlayback()
        // Stop the foreground service immediately on an explicit pause; the
        // isPlaying collector's debounced stop covers transient re-buffer dips.
        pendingFgsStop?.cancel()
        pendingFgsStop = null
        PlaybackForegroundService.update(appContext, false)
    }

    /** Clears play intent and pauses (user pause, or a by-design boundary pause). */
    private fun pausePlayback() {
        playRequested = false
        player.pause()
    }

    /** Whether the given book is the one currently loaded on the player. */
    fun isBookLoaded(bookId: String): Boolean = activeBook?.id == bookId

    fun seekBy(deltaMilliseconds: Long) {
        seekTo(currentGlobalPosition() + deltaMilliseconds)
    }

    /** Seeks on the book's global timeline, crossing part boundaries. */
    fun seekTo(positionMilliseconds: Long) {
        activeBook ?: return
        val total = totalDuration().coerceAtLeast(0)
        val target = positionMilliseconds.coerceIn(0, total.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val part = locatePart(target, partDurations)
        val currentIndex = player.currentMediaItemIndex.value.coerceAtLeast(0)
        if (part.index != currentIndex) {
            repeat((part.index - currentIndex).coerceAtLeast(0)) { player.skipToNext() }
            repeat((currentIndex - part.index).coerceAtLeast(0)) { player.skipToPrevious() }
            // With Auto-Play off, crossing into another chapter pauses there —
            // a manual skip past a chapter end behaves like a natural chapter
            // boundary instead of flowing into the next chapter.
            if (!PlaybackSettingsStore.autoPlayNext) {
                pausePlayback()
            }
        }
        // Applied now when the target item's duration is already resolved,
        // otherwise once its duration resolves.
        pendingSeekMilliseconds = part.positionMilliseconds
        applyPendingSeekIfResolved()
        // A manual seek is not a natural part boundary; keep the boundary
        // tracker in sync so it only fires on real queue advancement.
        lastPartIndex = part.index
        // Same for the embedded-chapter tracker: a seek into a later chapter
        // must not look like a natural chapter crossing.
        lastChapterPartIndex = part.index
        lastChapterIndex = chapterIndexAt(
            chaptersForPart(part.index),
            part.positionMilliseconds,
        )
        mutableState.value = mutableState.value.copy(
            positionSeconds = target / 1000.0,
            currentPartIndex = part.index,
        )
        PlaybackMediaSession.update(mutableState.value)
        // Persist the seek target itself, not the player-derived position: the
        // target item's duration is still resolving right after skipToNext, so
        // the player-derived path would early-return and leave the *previous*
        // chapter's position saved (a quick exit + reopen then resumes wrong).
        persistProgress(target)
    }

    /** Jumps to the start of the given part (chapter), preserving play/pause state. */
    fun seekToPart(index: Int) {
        if (partDurations.isEmpty()) return
        seekTo(globalPartPosition(index.coerceIn(0, partDurations.lastIndex), 0, partDurations))
    }

    fun setSpeed(value: Double) {
        speed = value.toFloat().coerceIn(0.5f, 2f)
        player.speed = speed
        PlaybackSettingsStore.playbackSpeed = speed
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
        PlaybackMediaSession.update(mutableState.value)
        persistProgress()
    }

    fun persistProgress(explicitPositionMilliseconds: Long? = null) {
        val book = activeBook ?: return
        // A manual seek target is authoritative even while the target item's
        // duration is still resolving. The player-derived path, by contrast,
        // is skipped while the current queue item is unresolved: position and
        // part index are unreliable, and a corrupt value persisted here once
        // broke resume (book jumped to its last part).
        val position = explicitPositionMilliseconds
            ?: currentGlobalPosition().takeIf { it > 0 }
            ?: (mutableState.value.positionSeconds * 1000).toLong()
        if (explicitPositionMilliseconds == null &&
            (!::player.isInitialized || player.durationMs.value == 0L)
        ) return
        val duration = totalDuration()
            .takeIf { it > 0 } ?: (mutableState.value.durationSeconds * 1000).toLong()
        AudiobookProgressStore.saveLocal(
            book,
            position.coerceIn(0, duration.coerceAtLeast(position)),
            duration,
            speed,
        )
        lastSavedAt = System.currentTimeMillis()
    }

    fun close() {
        persistProgress()
        PlaybackForegroundService.update(appContext, false)
        player.stop()
        activeBook = null
        partDurations = LongArray(0)
        pendingSeekMilliseconds = null
        lastPartIndex = -1
        lastChapterPartIndex = -1
        lastChapterIndex = -1
        lastResolvedDuration = 0L
        playRequested = false
        mutableState.value = PlayerState(
            readiness = PlayerReadiness.Unavailable,
        )
        PlaybackMediaSession.update(mutableState.value)
    }

    // --- event plumbing ------------------------------------------------------

    private fun collectPlayerEvents() {
        scope.launch {
            player.isPlaying.collect { playing ->
                if (playing) {
                    pendingFgsStop?.cancel()
                    pendingFgsStop = null
                    PlaybackForegroundService.update(appContext, true)
                } else {
                    // A seek re-buffer can dip isPlaying false briefly; keep
                    // play intent (the UI shows playing through the buffer)
                    // unless the queue actually ended.
                    if (player.durationMs.value > 0 &&
                        player.positionMs.value >= player.durationMs.value - END_EPSILON_MILLISECONDS
                    ) {
                        playRequested = false
                    }
                    // ...and don't tear down the foreground service (and its
                    // notification) for a transient dip — only a pause that sticks.
                    pendingFgsStop?.cancel()
                    pendingFgsStop = scope.launch {
                        delay(FGS_STOP_DEBOUNCE_MILLISECONDS)
                        PlaybackForegroundService.update(appContext, false)
                    }
                    persistProgress()
                }
                PlaybackForegroundService.refresh(appContext)
                emitState()
            }
        }
        scope.launch {
            player.currentMediaItemIndex.collect { index ->
                // With Auto-Play off, a queue advance is a chapter boundary:
                // pause at the new part's start instead of flowing into it.
                if (index > lastPartIndex && lastPartIndex >= 0 &&
                    player.isPlaying.value && !PlaybackSettingsStore.autoPlayNext
                ) {
                    pausePlayback()
                }
                lastPartIndex = index
                emitState()
            }
        }
        scope.launch {
            player.durationMs.collect { duration ->
                // Emits on every position poll while playing; act on real changes.
                if (duration > 0 && duration != lastResolvedDuration) {
                    lastResolvedDuration = duration
                    applyPendingSeekIfResolved()
                    val index = player.currentMediaItemIndex.value.coerceAtLeast(0)
                    if (index in partDurations.indices) partDurations[index] = duration
                    emitState()
                }
            }
        }
        scope.launch {
            player.positionMs.collect { position ->
                val index = player.currentMediaItemIndex.value.coerceAtLeast(0)
                mutableState.update {
                    it.copy(positionSeconds = globalPartPosition(index, position, partDurations) / 1000.0)
                }
            }
        }
        // Time-based work only while playing; paused there is no periodic wake.
        scope.launch {
            while (scope.isActive) {
                delay(1000)
                if (player.isPlaying.value && activeBook != null) tickPlaying()
            }
        }
    }

    private fun tickPlaying() {
        val index = player.currentMediaItemIndex.value.coerceAtLeast(0)
        // With Auto-Play off, pause when playback crosses an embedded chapter
        // end inside the current file (a natural chapter boundary, same
        // semantics as a part ending). Re-seed the tracker whenever the
        // current part changes.
        if (!PlaybackSettingsStore.autoPlayNext) {
            if (index != lastChapterPartIndex) {
                lastChapterPartIndex = index
                lastChapterIndex = chapterIndexAt(chaptersForPart(index), player.positionMs.value)
            }
            val chapters = chaptersForPart(index)
            if (chapters.isNotEmpty() && lastChapterIndex >= 0) {
                val currentChapter = chapterIndexAt(chapters, player.positionMs.value)
                if (currentChapter > lastChapterIndex) pausePlayback()
                lastChapterIndex = currentChapter
            }
        }
        if (System.currentTimeMillis() - lastSavedAt >= PROGRESS_SAVE_INTERVAL_MILLISECONDS) {
            persistProgress()
        }
    }

    /** Updates [state] and mirrors it into the platform media session. */
    private fun emitState() {
        updateState()
        PlaybackMediaSession.update(mutableState.value)
    }

    private fun updateState() {
        val position = currentGlobalPosition()
        val resolvedTotal = totalDuration()
        // Prefer the player-resolved timeline once known; until then keep the
        // duration the state was opened with (partDurations start unresolved,
        // and clobbering with 0 would show 0:00 — the old poll loop masked this
        // by re-updating within half a second; the paused tool no longer polls).
        val duration = resolvedTotal.takeIf { it > 0 }
            ?: (mutableState.value.durationSeconds * 1000).toLong()
        val currentIndex = player.currentMediaItemIndex.value.coerceAtLeast(0)
        val playing = playRequested
        val errored = mutableState.value.readiness == PlayerReadiness.Error
        mutableState.value = mutableState.value.copy(
            positionSeconds = position / 1000.0,
            durationSeconds = duration / 1000.0,
            currentPartIndex = currentIndex,
            partTitle = activeBook?.parts?.getOrNull(currentIndex)?.title,
            isPlaying = playing,
            playbackSpeed = speed.toDouble(),
            readiness = if (errored) PlayerReadiness.Error else PlayerReadiness.Ready,
        )
    }

    /** Applies the pending seek once the target item's duration is resolved. */
    private fun applyPendingSeekIfResolved() {
        val pending = pendingSeekMilliseconds ?: return
        if (player.durationMs.value > 0) {
            player.seekTo(pending)
            pendingSeekMilliseconds = null
        }
    }

    /**
     * Flags "could not be played" if the queue item never resolves a duration
     * (a silent load failure). One-shot per open/play, so there is no periodic
     * checking while paused.
     */
    private fun armSilentFailureCheck() {
        scope.launch {
            delay(SILENT_FAILURE_TIMEOUT_MILLISECONDS)
            if (activeBook != null && mutableState.value.readiness != PlayerReadiness.Error &&
                player.durationMs.value == 0L && !player.isPlaying.value
            ) {
                PlaybackForegroundService.update(appContext, false)
                playRequested = false
                mutableState.value = mutableState.value.copy(
                    isPlaying = false,
                    readiness = PlayerReadiness.Error,
                )
                PlaybackMediaSession.update(mutableState.value)
            }
        }
    }

    private fun totalDuration(): Long = partDurations.sum()

    private fun currentGlobalPosition(): Long {
        val index = player.currentMediaItemIndex.value.coerceAtLeast(0)
        val within = player.positionMs.value
        return globalPartPosition(index, within, partDurations)
    }

    /** Embedded chapters of the given part (single-file books with embedded chapters). */
    private fun chaptersForPart(index: Int): List<EmbeddedChapter> =
        activeBook?.parts?.getOrNull(index)?.chapters.orEmpty()

    /** Index of the chapter containing [positionMs] (an offset within the part). */
    private fun chapterIndexAt(chapters: List<EmbeddedChapter>, positionMs: Long): Int {
        if (chapters.isEmpty()) return -1
        val index = chapters.indexOfFirst { positionMs < it.endMs }
        return if (index >= 0) index else chapters.lastIndex
    }
}
