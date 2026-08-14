package com.lightphone.audiobooks.server.player

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Owns the platform [MediaSession] so Android surfaces the companion as a media app:
 * lockscreen/system media controls (with seek bar), transport actions on the
 * foreground notification, and media-key routing. Playback itself stays in
 * [LocalPlaybackController]; this only mirrors its [PlayerState].
 */
object PlaybackMediaSession {
    private val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private lateinit var session: MediaSession

    fun init(context: Context) {
        if (::session.isInitialized) return
        session = MediaSession(context, "AudiobooksPlayback").apply {
            setCallback(callback, Handler(Looper.getMainLooper()))
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
        }
    }

    val token: MediaSession.Token?
        get() = if (::session.isInitialized) session.sessionToken else null

    /** Mirrors the current [PlayerState] into the platform session. */
    fun update(state: PlayerState) {
        if (!::session.isInitialized) return
        val playState = when {
            state.readiness == PlayerReadiness.Error -> PlaybackState.STATE_ERROR
            state.readiness == PlayerReadiness.Preparing -> PlaybackState.STATE_BUFFERING
            state.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    playState,
                    (state.positionSeconds * 1000).toLong(),
                    state.playbackSpeed.toFloat(),
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapter)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (state.durationSeconds * 1000).toLong())
                .build(),
        )
        session.isActive = state.isPlaying
    }

    /**
     * Forwards an ACTION_MEDIA_BUTTON broadcast (from
     * [com.lightphone.audiobooks.server.MediaButtonReceiver]) to the session callback.
     * The platform [MediaSession] has no `onMediaButtonEvent`, so keycodes are
     * dispatched straight to the callback (same result, no system round-trip).
     */
    fun handleMediaButtonEvent(intent: Intent) {
        if (!::session.isInitialized) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        val playing = LocalPlaybackController.state.value.isPlaying
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                if (playing) callback.onPause() else callback.onPlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> callback.onPlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> callback.onPause()
            KeyEvent.KEYCODE_MEDIA_REWIND -> callback.onRewind()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> callback.onFastForward()
            KeyEvent.KEYCODE_MEDIA_NEXT -> callback.onSkipToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> callback.onSkipToPrevious()
        }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = LocalPlaybackController.play()
        override fun onPause() = LocalPlaybackController.pause()
        override fun onSeekTo(position: Long) = LocalPlaybackController.seekTo(position)
        override fun onRewind() = LocalPlaybackController.seekBy(-15_000)
        override fun onFastForward() = LocalPlaybackController.seekBy(15_000)
        override fun onSkipToNext() =
            LocalPlaybackController.seekToPart(LocalPlaybackController.state.value.currentPartIndex + 1)
        override fun onSkipToPrevious() =
            LocalPlaybackController.seekToPart(LocalPlaybackController.state.value.currentPartIndex - 1)
        override fun onSetPlaybackSpeed(speed: Float) =
            LocalPlaybackController.setSpeed(speed.toDouble())
    }
}
