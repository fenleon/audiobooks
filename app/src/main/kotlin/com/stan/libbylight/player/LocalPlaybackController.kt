package com.stan.libbylight.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stan.libbylight.PlaybackForegroundService
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.library.AudiobookSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LocalPlayback"
private const val PROGRESS_SAVE_INTERVAL_MILLISECONDS = 7_000L

object LocalPlaybackController {
    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlayerState(diagnostic = "No local book loaded"))
    private val players = mutableListOf<MediaPlayer>()
    private var partDurations = LongArray(0)
    private var activePartIndex = 0
    private var preparedPartCount = 0
    private var openGeneration = 0
    private var activeBook: Audiobook? = null
    private var speed = 1f
    private var lastSavedAt = 0L

    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun open(book: Audiobook, autoPlay: Boolean = false) {
        persistProgress()
        PlaybackForegroundService.update(appContext, false)
        releasePlayer()
        val generation = ++openGeneration
        activeBook = book
        val saved = AudiobookProgressStore.read(book.source, book.id)
        speed = saved.playbackSpeed.coerceIn(1f, 2f)
        mutableState.value = PlayerState(
            title = book.title,
            chapter = book.author.takeIf { it.isNotBlank() },
            positionSeconds = saved.positionMilliseconds / 1000.0,
            durationSeconds = (book.durationMilliseconds.takeIf { it > 0 }
                ?: saved.durationMilliseconds) / 1000.0,
            playbackSpeed = speed.toDouble(),
            diagnostic = "Preparing…",
            readiness = PlayerReadiness.Preparing,
        )

        val playbackReferences = book.parts.map { it.playbackReference }
            .ifEmpty { listOf(book.playbackReference) }
        partDurations = LongArray(playbackReferences.size)
        preparedPartCount = 0
        activePartIndex = 0

        try {
            playbackReferences.forEachIndexed { index, playbackReference ->
                val mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    val playbackUri = Uri.parse(playbackReference)
                    if (playbackUri.scheme.equals("http", true) || playbackUri.scheme.equals("https", true)) {
                        setDataSource(playbackReference)
                    } else if (playbackUri.scheme.isNullOrBlank()) {
                        setDataSource(playbackReference)
                    } else {
                        setDataSource(appContext, playbackUri)
                    }
                    setOnPreparedListener { prepared ->
                        if (generation != openGeneration) return@setOnPreparedListener
                        partDurations[index] = prepared.duration.toLong().coerceAtLeast(0)
                        preparedPartCount++
                        if (preparedPartCount == players.size) {
                            players.zipWithNext().forEach { (current, next) ->
                                runCatching { current.setNextMediaPlayer(next) }
                            }
                            val total = totalDuration()
                            val target = saved.positionMilliseconds.coerceIn(
                                0,
                                total.takeIf { it > 0 } ?: Long.MAX_VALUE,
                            )
                            seekPreparedPlayers(target, resume = false)
                            updateState()
                            if (autoPlay) {
                                players.getOrNull(activePartIndex)?.start()
                                PlaybackForegroundService.update(appContext, true)
                            }
                            updateState()
                            Log.d(TAG, "playback prepared parts=${players.size}")
                            scheduleUpdates()
                        }
                    }
                    setOnInfoListener { mediaPlayer, what, _ ->
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                                mutableState.value = mutableState.value.copy(
                                    readiness = PlayerReadiness.Buffering,
                                    diagnostic = "Buffering…",
                                )
                            }
                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> updateState()
                        }
                        false
                    }
                    setOnCompletionListener { completed ->
                        if (generation != openGeneration) return@setOnCompletionListener
                        if (index < players.lastIndex) {
                            activePartIndex = index + 1
                            players.getOrNull(activePartIndex)?.let(::applySpeedPreservingState)
                            updateState()
                            scheduleUpdates()
                        } else {
                            PlaybackForegroundService.update(appContext, false)
                            updateState()
                            persistProgress()
                        }
                    }
                    setOnErrorListener { _, what, _ ->
                        Log.w(TAG, "playback failed reason=$what")
                        PlaybackForegroundService.update(appContext, false)
                        mutableState.value = mutableState.value.copy(
                            isPlaying = false,
                            diagnostic = "This audiobook could not be played.",
                            readiness = PlayerReadiness.Error,
                        )
                        true
                    }
                    prepareAsync()
                }
                players += mediaPlayer
            }
        } catch (_: Exception) {
            Log.w(TAG, "playback failed reason=unreadable")
            PlaybackForegroundService.update(appContext, false)
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                diagnostic = "This audiobook could not be played.",
                readiness = PlayerReadiness.Error,
            )
        }
    }

    fun play() {
        if (mutableState.value.readiness != PlayerReadiness.Ready) return
        players.getOrNull(activePartIndex)?.runCatching {
            start()
            PlaybackForegroundService.update(appContext, true)
            updateState()
            scheduleUpdates()
        }
    }

    fun pause() {
        players.getOrNull(activePartIndex)?.runCatching {
            if (isPlaying) pause()
            PlaybackForegroundService.update(appContext, false)
            updateState()
            persistProgress()
        }
    }

    fun seekBy(deltaMilliseconds: Long) {
        seekTo(currentGlobalPosition() + deltaMilliseconds)
    }

    fun seekTo(positionMilliseconds: Long) {
        if (players.isEmpty() || preparedPartCount != players.size) return
        val target = positionMilliseconds.coerceIn(0, totalDuration().coerceAtLeast(0))
        val wasPlaying = players.getOrNull(activePartIndex)?.runCatching { isPlaying }
            ?.getOrDefault(false) == true
        seekPreparedPlayers(target, resume = wasPlaying)
        mutableState.value = mutableState.value.copy(positionSeconds = target / 1000.0)
        persistProgress()
    }

    fun setSpeed(value: Double) {
        speed = value.toFloat().coerceIn(1f, 2f)
        players.getOrNull(activePartIndex)?.let(::applySpeedPreservingState)
        mutableState.value = mutableState.value.copy(playbackSpeed = speed.toDouble())
        persistProgress()
    }

    fun persistProgress() {
        val book = activeBook ?: return
        val position = currentGlobalPosition()
            .takeIf { it > 0 } ?: (mutableState.value.positionSeconds * 1000).toLong()
        val duration = totalDuration()
            .takeIf { it > 0 } ?: (mutableState.value.durationSeconds * 1000).toLong()
        AudiobookProgressStore.saveLocal(book, position, duration, speed)
        lastSavedAt = System.currentTimeMillis()
    }

    fun close() {
        persistProgress()
        PlaybackForegroundService.update(appContext, false)
        releasePlayer()
        activeBook = null
        mutableState.value = PlayerState(
            diagnostic = "No audiobook loaded",
            readiness = PlayerReadiness.Unavailable,
        )
    }

    private fun applySpeed(mediaPlayer: MediaPlayer) {
        runCatching {
            mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
        }
    }

    private fun applySpeedPreservingState(mediaPlayer: MediaPlayer) {
        val wasPlaying = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
        applySpeed(mediaPlayer)
        if (!wasPlaying) {
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
            }
        }
    }

    private fun updateState() {
        val current = players.getOrNull(activePartIndex)
        val duration = totalDuration()
        val position = currentGlobalPosition()
        val playing = runCatching { current?.isPlaying }.getOrDefault(false) == true
        mutableState.value = mutableState.value.copy(
            positionSeconds = position / 1000.0,
            durationSeconds = duration / 1000.0,
            isPlaying = playing,
            playbackSpeed = speed.toDouble(),
            controlsFound = true,
            diagnostic = "Local audiobook ready",
            readiness = PlayerReadiness.Ready,
        )
    }

    private fun scheduleUpdates() {
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val current = players.getOrNull(activePartIndex) ?: return
            updateState()
            if (current.isPlaying && System.currentTimeMillis() - lastSavedAt >= PROGRESS_SAVE_INTERVAL_MILLISECONDS) {
                persistProgress()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(updateRunnable)
        players.forEach { it.runCatching { release() } }
        players.clear()
        partDurations = LongArray(0)
        activePartIndex = 0
        preparedPartCount = 0
    }

    private fun totalDuration(): Long = partDurations.sum()

    private fun currentGlobalPosition(): Long {
        val current = players.getOrNull(activePartIndex)
        val withinPart = runCatching { current?.currentPosition?.toLong() }.getOrNull() ?: 0L
        return globalPartPosition(activePartIndex, withinPart, partDurations)
    }

    private fun seekPreparedPlayers(positionMilliseconds: Long, resume: Boolean) {
        if (players.isEmpty()) return
        val target = locatePart(positionMilliseconds, partDurations)
        players.getOrNull(activePartIndex)?.runCatching {
            if (isPlaying) pause()
        }
        activePartIndex = target.index
        players[target.index].seekTo(target.positionMilliseconds.toInt())
        applySpeedPreservingState(players[target.index])
        if (resume) players[target.index].start()
    }
}
