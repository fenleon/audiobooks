package com.lightphone.audiobooks.library

import org.json.JSONObject
import java.io.File

private const val FILE_NAME = "playback_settings.json"

/** Persistent playback preferences: the Auto-Play "next chapter" toggle and
 *  the global playback speed (one setting shared by every book). */
object PlaybackSettingsStore {
    private lateinit var filesDir: File
    private var cache: Pair<Boolean, Float>? = null

    fun init(filesDir: File) {
        this.filesDir = filesDir
    }

    private fun storeFile() = File(filesDir, FILE_NAME)

    private fun load(): Pair<Boolean, Float> = synchronized(this) {
        cache?.let { return it }
        val json = readJson(storeFile())
        Pair(
            json?.optBoolean("auto_play_next") ?: true,
            json?.optDouble("playback_speed", 1.0)?.toFloat() ?: 1f,
        ).also { cache = it }
    }

    private fun persist(autoPlayNext: Boolean, speed: Float) = synchronized(this) {
        val json = JSONObject()
            .put("auto_play_next", autoPlayNext)
            .put("playback_speed", speed.toDouble())
        writeJsonAtomically(storeFile(), json)
        cache = autoPlayNext to speed
    }

    /** Whether playback continues into the next chapter when the current one ends. */
    var autoPlayNext: Boolean
        get() = load().first
        set(value) {
            persist(value, load().second)
        }

    /** Global playback speed, applied to every book (one setting, not per-book). */
    var playbackSpeed: Float
        get() = load().second
        set(value) {
            persist(load().first, value)
        }
}
