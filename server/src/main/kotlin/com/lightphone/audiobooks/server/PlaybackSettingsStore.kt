package com.lightphone.audiobooks.server

import android.content.Context

/**
 * Persistent playback preferences, owned by the companion (playback runs
 * here; the tool reads/writes them over the SDK binder).
 */
object PlaybackSettingsStore {
    private lateinit var appContext: Context
    private const val PREFS_NAME = "playback_settings"
    private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"

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
}
