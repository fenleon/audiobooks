package com.lightphone.audiobooks.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.lightphone.audiobooks.server.library.LocalBookRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Backs the [com.thelightphone.sdk.shared.LightServiceMethod.WaitForVolumeChange]
 * long-poll: the companion listens for `VOLUME_CHANGED_ACTION` (registered
 * lazily, on first use) and lets the tool's request block until the
 * media-stream volume actually changes — so the in-app volume panel reacts
 * instantly to a connected BT device's own volume buttons (AVRCP), with no
 * polling cadence.
 */
object VolumeChangeMonitor {

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var registered = false

    @Synchronized
    fun ensureRegistered(context: Context) {
        if (registered) return
        registered = true
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        changes.tryEmit(Unit)
                    }
                },
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            )
        }
    }

    /**
     * The current media-stream level/max, waiting up to [timeoutMs] for the
     * next volume change when the level still equals [knownLevel].
     */
    suspend fun awaitChange(timeoutMs: Long, knownLevel: Int): Pair<Int, Int> {
        val audio = LocalBookRepository.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun current() =
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) to
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = current()
        if (now.first != knownLevel) return now
        withTimeoutOrNull(timeoutMs) { changes.first() }
        return current()
    }
}
