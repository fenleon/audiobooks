package com.stan.libbylight

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.stan.libbylight.player.PlaybackMediaSession

/**
 * Receives ACTION_MEDIA_BUTTON broadcasts — from the notification's transport
 * actions and from headset/media-key sources — and hands them to the media
 * session, which dispatches to [LocalPlaybackController].
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackMediaSession.handleMediaButtonEvent(intent)
    }

    companion object {
        /** A PendingIntent that delivers [keyCode] to [MediaButtonReceiver] as a media-button press. */
        fun buildMediaButtonPendingIntent(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(context, MediaButtonReceiver::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(
                context,
                keyCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
