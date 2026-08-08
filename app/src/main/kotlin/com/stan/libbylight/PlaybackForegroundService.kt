package com.stan.libbylight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.stan.libbylight.player.LocalPlaybackController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps Bard's process alive while local audiobook playback continues in the background.
 * The service does not own playback or issue any player command; LocalPlaybackController does.
 */
class PlaybackForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val openBard = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bookTitle = LocalPlaybackController.state.value.title
            .takeIf { it.isNotBlank() && it != "Audiobook" }
            ?: "Audiobook"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio_message_white)
            .setContentTitle("Bard")
            .setContentText("Listening to $bookTitle")
            .setContentIntent(openBard)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bard_playback"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "PlaybackService"
        private val running = AtomicBoolean(false)
        private val mutableStartFailed = MutableStateFlow(false)
        val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

        fun update(context: Context, isPlaying: Boolean) {
            val appContext = context.applicationContext
            if (isPlaying) {
                if (running.compareAndSet(false, true)) {
                    try {
                        appContext.startForegroundService(
                            Intent(appContext, PlaybackForegroundService::class.java),
                        )
                    } catch (error: RuntimeException) {
                        running.set(false)
                        mutableStartFailed.value = true
                        Log.e(TAG, "could not start foreground playback host")
                    }
                }
            } else if (running.compareAndSet(true, false)) {
                mutableStartFailed.value = false
                appContext.stopService(
                    Intent(appContext, PlaybackForegroundService::class.java),
                )
            }
        }
    }
}
