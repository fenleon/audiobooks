package com.stan.libbylight.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stan.libbylight.PlaybackForegroundService
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookProgressStore
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
        activeBook = book
        val saved = AudiobookProgressStore.read(book.source, book.id)
        speed = saved.playbackSpeed.coerceIn(1f, 2f)
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
        }.onFailure { error ->
            Log.w(TAG, "queue set failed: ${error.message}")
            PlaybackForegroundService.update(appContext, false)
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                diagnostic = "This audiobook could not be played.",
                readiness = PlayerReadiness.Error,
            )
        }
        updateState()
        if (autoPlay) play()
        scheduleUpdates()
    }

    fun play() {
        if (mutableState.value.readiness != PlayerReadiness.Ready) return
        // An ended book restarts from the beginning, like the old MediaPlayer did.
        if (!player.isPlaying.value && player.durationMs.value > 0 &&
            player.positionMs.value >= player.durationMs.value - END_EPSILON_MILLISECONDS
        ) {
            player.seekTo(0L)
            pendingSeekMilliseconds = null
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
        }
        // Applied once the target item's duration resolves.
        pendingSeekMilliseconds = part.positionMilliseconds
        mutableState.value = mutableState.value.copy(
            positionSeconds = target / 1000.0,
            currentPartIndex = part.index,
        )
        persistProgress()
    }

    /** Jumps to the start of the given part (chapter), preserving play/pause state. */
    fun seekToPart(index: Int) {
        if (partDurations.isEmpty()) return
        seekTo(globalPartPosition(index.coerceIn(0, partDurations.lastIndex), 0, partDurations))
    }

    fun setSpeed(value: Double) {
        speed = value.toFloat().coerceIn(1f, 2f)
        player.speed = speed
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
        persistProgress()
    }

    fun persistProgress() {
        val book = activeBook ?: return
        // Skip while the current queue item is unresolved: position and part
        // index are unreliable, and a corrupt value persisted here once broke
        // resume (book jumped to its last part).
        if (!::player.isInitialized || player.durationMs.value == 0L) return
        val position = currentGlobalPosition()
            .takeIf { it > 0 } ?: (mutableState.value.positionSeconds * 1000).toLong()
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
}
