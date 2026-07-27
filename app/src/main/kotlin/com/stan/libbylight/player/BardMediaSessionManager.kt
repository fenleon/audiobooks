package com.stan.libbylight.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.session.MediaButtonReceiver
import com.stan.libbylight.LibbyBridge
import com.stan.libbylight.LibbyPlaybackForegroundService
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookSource

/**
 * The single Android media session for Bard. It publishes only safe display metadata and delegates
 * every command to the already-active playback backend.
 */
object BardMediaSessionManager {
    private const val TAG = "BardMediaSession"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deduplicator = MediaCommandDeduplicator()
    private lateinit var appContext: Context
    private lateinit var session: MediaSessionCompat
    private var activeBook: Audiobook? = null
    private var state = PlayerState()

    fun init(context: Context) {
        if (::session.isInitialized) return
        appContext = context.applicationContext
        session = MediaSessionCompat(appContext, "Bard").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(callback, mainHandler)
            isActive = true
        }
        publish()
    }

    val sessionToken: MediaSessionCompat.Token?
        get() = session.takeIf { ::session.isInitialized }?.sessionToken

    fun update(book: Audiobook?, playerState: PlayerState) {
        if (!::session.isInitialized) return
        activeBook = book
        state = playerState
        publish()
    }

    /** Immediate Libby foreground-host update without allowing an inactive Libby frame to stop
     * native Local/RSS playback. The next authoritative state snapshot still performs reconciliation.
     */
    fun onLibbyPlayingChanged(isPlaying: Boolean) {
        if (!::session.isInitialized || activeBook?.source != AudiobookSource.Libby) return
        state = state.copy(isPlaying = isPlaying)
        publish()
    }

    fun notificationSnapshot(): NotificationSnapshot {
        val metadata = safeMediaMetadata(activeBook?.title.orEmpty(), activeBook?.author.orEmpty())
        return NotificationSnapshot(
            title = metadata.title,
            subtitle = metadata.subtitle,
            isPlaying = state.isPlaying,
        )
    }

    fun handleMediaButtonIntent(intent: android.content.Intent?) {
        if (::session.isInitialized) MediaButtonReceiver.handleIntent(session, intent)
    }

    private fun publish(updatePlaybackHost: Boolean = true) {
        val book = activeBook
        val duration = (state.durationSeconds * 1000).toLong().coerceAtLeast(0)
        val position = clampMediaSeek((state.positionSeconds * 1000).toLong(), duration)
        val safe = safeMediaMetadata(book?.title.orEmpty(), book?.author.orEmpty())
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, safe.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, safe.subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Bard")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
        val playbackState = when {
            book == null -> PlaybackStateCompat.STATE_NONE
            state.readiness == PlayerReadiness.Error -> PlaybackStateCompat.STATE_ERROR
            state.readiness == PlayerReadiness.Unavailable -> PlaybackStateCompat.STATE_STOPPED
            state.readiness == PlayerReadiness.Preparing -> PlaybackStateCompat.STATE_CONNECTING
            state.readiness == PlayerReadiness.Buffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = if (book == null) 0L else {
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playbackState, position, state.playbackSpeed.toFloat())
                .setBufferedPosition(position)
                .apply {
                    if (state.readiness == PlayerReadiness.Error) {
                        setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, "Playback unavailable")
                    }
                }
                .build(),
        )
        session.isActive = book != null
        if (updatePlaybackHost) {
            LibbyPlaybackForegroundService.update(appContext, state.isPlaying)
        }
    }

    private fun dispatch(key: String, action: () -> Unit) {
        if (!deduplicator.shouldDispatch(key)) {
            Log.d(TAG, "duplicate media command ignored action=$key")
            return
        }
        Log.d(TAG, "media command action=$key source=${activeBook?.source?.name ?: "none"}")
        action()
    }

    private fun seekTo(positionMilliseconds: Long) {
        val duration = (state.durationSeconds * 1000).toLong()
        val target = clampMediaSeek(positionMilliseconds, duration)
        state = state.copy(positionSeconds = target / 1000.0)
        publish()
        when (activeBook?.source) {
            AudiobookSource.Libby -> LibbyBridge.seekTo(target)
            AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.seekTo(target)
            null -> Unit
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = dispatch("play") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> {
                    LocalPlaybackController.pause()
                    LibbyBridge.play()
                }
                AudiobookSource.Local, AudiobookSource.Rss -> {
                    LibbyBridge.pause()
                    LocalPlaybackController.play()
                }
                null -> Unit
            }
            state = state.copy(isPlaying = true)
            publish()
        }

        override fun onPause() = dispatch("pause") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> LibbyBridge.pause()
                AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.pause()
                null -> Unit
            }
            state = state.copy(isPlaying = false)
            publish()
        }

        override fun onStop() = dispatch("stop") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> LibbyBridge.pause()
                AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.pause()
                null -> Unit
            }
            state = state.copy(isPlaying = false, readiness = PlayerReadiness.Unavailable)
            publish(updatePlaybackHost = false)
            session.isActive = false
            LibbyPlaybackForegroundService.stopAndRemove(appContext)
        }

        override fun onSeekTo(pos: Long) = dispatch("seek:$pos") { seekTo(pos) }

        override fun onFastForward() = dispatch("fast-forward") {
            seekTo(
                mediaFastForwardTarget(
                    (state.positionSeconds * 1000).toLong(),
                    (state.durationSeconds * 1000).toLong(),
                ),
            )
        }

        override fun onRewind() = dispatch("rewind") {
            seekTo(
                mediaRewindTarget(
                    (state.positionSeconds * 1000).toLong(),
                    (state.durationSeconds * 1000).toLong(),
                ),
            )
        }

        // Bard has no chapter queue yet. These deterministic audiobook fallbacks keep hardware
        // buttons useful without selecting a different book or inventing source-specific logic.
        override fun onSkipToNext() = dispatch("next") {
            seekTo(
                mediaFastForwardTarget(
                    (state.positionSeconds * 1000).toLong(),
                    (state.durationSeconds * 1000).toLong(),
                ),
            )
        }

        override fun onSkipToPrevious() = dispatch("previous") {
            seekTo(
                mediaPreviousTarget(
                    (state.positionSeconds * 1000).toLong(),
                    (state.durationSeconds * 1000).toLong(),
                ),
            )
        }
    }

    data class NotificationSnapshot(
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean,
    )
}
