package com.lightphone.audiobooks.server.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LocalPlayback"
private const val PROGRESS_SAVE_INTERVAL_MILLISECONDS = 7_000L
private const val END_EPSILON_MILLISECONDS = 1_500L
private const val SILENT_FAILURE_POLLS = 6

/**
 * Owns playback via the SDK's [LightAudioPlayer] (ExoPlayer queue) and keeps
 * [PlayerState] + the foreground service in sync. The queue is the whole book
 * (one item per part); the global timeline math in [MultiPartTimeline] maps
 * book positions to queue items.
 */
object LocalPlaybackController {
    private lateinit var appContext: Context
    private lateinit var player: LightAudioPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlayerState(diagnostic = "No local book loaded"))
    private var partDurations = LongArray(0)
    private var activeBook: Audiobook? = null
    private var speed = 1f
    private var lastSavedAt = 0L
    private var wasPlaying = false
    private var pendingSeekMilliseconds: Long? = null
    private var silentFailureStreak = 0
    private var lastPartIndex = -1
    private var lastChapterPartIndex = -1
    private var lastChapterIndex = -1

    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        player = LightAudioPlayer(appContext, LightAudioUsage.Speech)
        PlaybackMediaSession.init(appContext)
    }

    fun open(book: Audiobook, autoPlay: Boolean = false) {
        Log.d(TAG, "open() called for '${book.title}'")
        try {
            openInternal(book, autoPlay)
        } catch (error: Exception) {
            Log.w(TAG, "open() failed", error)
            PlaybackForegroundService.update(appContext, false)
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                diagnostic = "This audiobook could not be played.",
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
        speed = saved.playbackSpeed.coerceIn(0.5f, 2f)
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
            title = book.title,
            chapter = book.author.takeIf { it.isNotBlank() },
            positionSeconds = savedPosition / 1000.0,
            durationSeconds = (book.durationMilliseconds.takeIf { it > 0 }
                ?: saved.durationMilliseconds) / 1000.0,
            currentPartIndex = startPart,
            partCount = book.parts.size.coerceAtLeast(1),
            partTitle = book.parts.getOrNull(startPart)?.title,
            playbackSpeed = speed.toDouble(),
            diagnostic = "Preparing…",
            readiness = PlayerReadiness.Preparing,
        )
        pendingSeekMilliseconds = null
        runCatching {
            player.setMediaQueue(items, startIndex = startPart.coerceIn(0, items.lastIndex))
            player.speed = speed
            // Applied by the poll loop once the start item's duration resolves
            // (LightAudioPlayer clamps seeks to a known duration).
            pendingSeekMilliseconds = if (durationsKnown) {
                locatePart(savedPosition, partDurations).positionMilliseconds
            } else {
                null
            }
            lastPartIndex = startPart
        }.onFailure { error ->
            Log.w(TAG, "queue set failed: ${error.message}")
            PlaybackForegroundService.update(appContext, false)
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                diagnostic = "This audiobook could not be played.",
                readiness = PlayerReadiness.Error,
            )
        }
        // Seed the embedded-chapter tracker at the resume position so the
        // auto-play-off boundary pause never fires spuriously on the first poll.
        lastChapterPartIndex = startPart
        lastChapterIndex = chapterIndexAt(
            chaptersForPart(startPart),
            pendingSeekMilliseconds ?: 0L,
        )
        updateState()
        if (autoPlay) play()
        scheduleUpdates()
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
        player.play()
        PlaybackForegroundService.update(appContext, true)
        PlaybackForegroundService.refresh(appContext)
        updateState()
        scheduleUpdates()
    }

    fun pause() {
        player.pause()
        PlaybackForegroundService.update(appContext, false)
        PlaybackForegroundService.refresh(appContext)
        updateState()
        persistProgress()
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
                player.pause()
                PlaybackForegroundService.update(appContext, false)
            }
        }
        // Applied once the target item's duration resolves.
        pendingSeekMilliseconds = part.positionMilliseconds
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
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
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
        mutableState.value = PlayerState(
            diagnostic = "No audiobook loaded",
            readiness = PlayerReadiness.Unavailable,
        )
        PlaybackMediaSession.update(mutableState.value)
    }

    private fun updateState() {
        val position = currentGlobalPosition()
        val duration = totalDuration()
        val currentIndex = player.currentMediaItemIndex.value.coerceAtLeast(0)
        val playing = player.isPlaying.value
        val errored = mutableState.value.readiness == PlayerReadiness.Error
        mutableState.value = mutableState.value.copy(
            positionSeconds = position / 1000.0,
            durationSeconds = duration / 1000.0,
            currentPartIndex = currentIndex,
            partTitle = activeBook?.parts?.getOrNull(currentIndex)?.title,
            isPlaying = playing,
            playbackSpeed = speed.toDouble(),
            diagnostic = if (errored) mutableState.value.diagnostic else "Local audiobook ready",
            readiness = if (errored) PlayerReadiness.Error else PlayerReadiness.Ready,
        )
        PlaybackMediaSession.update(mutableState.value)
    }

    private fun scheduleUpdates() {
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            // Keep part durations accurate once the platform resolves them.
            val index = player.currentMediaItemIndex.value
            if (index in partDurations.indices) {
                player.durationMs.value.takeIf { it > 0 }?.let { partDurations[index] = it }
            }
            // Apply a pending seek once the target item's duration is known.
            if (pendingSeekMilliseconds != null && player.durationMs.value > 0) {
                player.seekTo(pendingSeekMilliseconds!!)
                pendingSeekMilliseconds = null
            }
            // With "Auto-Play: next chapter" off, stop when a part ends instead
            // of flowing into the next one. The queue advances one index past
            // the boundary, so pause right at the new part's start.
            if (index > lastPartIndex && lastPartIndex >= 0 &&
                player.isPlaying.value && !PlaybackSettingsStore.autoPlayNext
            ) {
                player.pause()
                PlaybackForegroundService.update(appContext, false)
            }
            lastPartIndex = index
            // With Auto-Play off, also pause when playback crosses an embedded
            // chapter end inside the current file (a natural chapter boundary,
            // same semantics as a part ending). Re-seed the tracker whenever
            // the current part changes.
            if (player.isPlaying.value && !PlaybackSettingsStore.autoPlayNext) {
                if (index != lastChapterPartIndex) {
                    lastChapterPartIndex = index
                    lastChapterIndex = chapterIndexAt(chaptersForPart(index), player.positionMs.value)
                }
                val chapters = chaptersForPart(index)
                if (chapters.isNotEmpty() && lastChapterIndex >= 0) {
                    val currentChapter = chapterIndexAt(chapters, player.positionMs.value)
                    if (currentChapter > lastChapterIndex) {
                        player.pause()
                        PlaybackForegroundService.update(appContext, false)
                    }
                    lastChapterIndex = currentChapter
                }
            }
            updateState()
            val playing = player.isPlaying.value
            if (playing && System.currentTimeMillis() - lastSavedAt >= PROGRESS_SAVE_INTERVAL_MILLISECONDS) {
                persistProgress()
            }
            // Queue ended: the platform reached the end of the last item.
            if (wasPlaying && !playing && player.positionMs.value > 0 &&
                player.durationMs.value > 0 &&
                player.positionMs.value >= player.durationMs.value - END_EPSILON_MILLISECONDS
            ) {
                PlaybackForegroundService.update(appContext, false)
                persistProgress()
            }
            wasPlaying = playing
            // Silent load failure: the item never resolves a duration or plays.
            if (!playing && player.durationMs.value == 0L && activeBook != null &&
                mutableState.value.readiness != PlayerReadiness.Error
            ) {
                silentFailureStreak++
                if (silentFailureStreak >= SILENT_FAILURE_POLLS) {
                    silentFailureStreak = 0
                    PlaybackForegroundService.update(appContext, false)
                    mutableState.value = mutableState.value.copy(
                        isPlaying = false,
                        diagnostic = "This audiobook could not be played.",
                        readiness = PlayerReadiness.Error,
                    )
                    PlaybackMediaSession.update(mutableState.value)
                }
            } else {
                silentFailureStreak = 0
            }
            handler.postDelayed(this, 500)
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
