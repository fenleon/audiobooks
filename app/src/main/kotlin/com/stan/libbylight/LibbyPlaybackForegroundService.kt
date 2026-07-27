package com.stan.libbylight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media.session.MediaButtonReceiver
import com.stan.libbylight.player.BardMediaSessionManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts Bard's one playback notification and keeps the active playback backend perceptible.
 * Playback and source routing remain owned by the existing controllers and Bard media session.
 */
class LibbyPlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BardMediaSessionManager.handleMediaButtonIntent(intent)
        if (intent?.action == ACTION_PAUSE_NOTIFICATION ||
            intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            !BardMediaSessionManager.notificationSnapshot().isPlaying
        ) {
            running.set(false)
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(),
            )
            Log.d(TAG, "foreground host released; paused media notification retained")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_NOTIFICATION) {
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            Log.d(TAG, "playback notification removed")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        running.set(true)
        mutableStartFailed.value = false
        Log.d(TAG, "foreground playback host started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        Log.d(TAG, "foreground playback host stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps audiobook playback active"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val snapshot = BardMediaSessionManager.notificationSnapshot()
        val token = BardMediaSessionManager.sessionToken
        val openBard = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val previous = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        )
        val playPause = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            if (snapshot.isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY,
        )
        val next = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackState.ACTION_SKIP_TO_NEXT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio_message_white)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.subtitle ?: "Bard")
            .setContentIntent(openBard)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    previous,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (snapshot.isPlaying) "Pause" else "Play",
                    playPause,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "Next",
                    next,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .apply {
                        (token?.token as? android.media.session.MediaSession.Token)?.let(::setMediaSession)
                    },
            )
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(snapshot.isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bard_playback"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "BardPlaybackService"
        private const val ACTION_PAUSE_NOTIFICATION =
            "com.stan.libbylight.action.PAUSE_PLAYBACK_NOTIFICATION"
        private const val ACTION_STOP_NOTIFICATION =
            "com.stan.libbylight.action.STOP_PLAYBACK_NOTIFICATION"
        private val running = AtomicBoolean(false)
        private val mutableStartFailed = MutableStateFlow(false)
        val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

        fun update(context: Context, isPlaying: Boolean) {
            val appContext = context.applicationContext
            if (isPlaying) {
                if (running.compareAndSet(false, true)) {
                    try {
                        appContext.startForegroundService(
                            Intent(appContext, LibbyPlaybackForegroundService::class.java),
                        )
                    } catch (error: RuntimeException) {
                        running.set(false)
                        mutableStartFailed.value = true
                        Log.e(TAG, "could not start foreground playback host")
                    }
                }
            } else if (running.compareAndSet(true, false)) {
                mutableStartFailed.value = false
                appContext.startService(
                    Intent(appContext, LibbyPlaybackForegroundService::class.java)
                        .setAction(ACTION_PAUSE_NOTIFICATION),
                )
            }
        }

        fun stopAndRemove(context: Context) {
            val appContext = context.applicationContext
            running.set(false)
            appContext.startService(
                Intent(appContext, LibbyPlaybackForegroundService::class.java)
                    .setAction(ACTION_STOP_NOTIFICATION),
            )
        }
    }
}
