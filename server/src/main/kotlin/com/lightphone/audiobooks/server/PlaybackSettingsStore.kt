package com.lightphone.audiobooks.server

import android.content.Context

/**
 * Persistent playback preferences. Playback runs in the tool (SDK detached
 * audio); the companion stores the shared settings, which the tool reads and
 * writes over the SDK binder.
 */
object PlaybackSettingsStore {
    private lateinit var appContext: Context
    private const val PREFS_NAME = "playback_settings"
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Whether playback continues into the next chapter when the current one ends. */
    var autoPlayNext: Boolean
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_PLAY_NEXT, value)
                .apply()
        }

    /** Global playback speed, applied to every book (one setting, not per-book). */
    var playbackSpeed: Float
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PLAYBACK_SPEED, 1f)
        set(value) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_PLAYBACK_SPEED, value)
                .apply()
        }
}
