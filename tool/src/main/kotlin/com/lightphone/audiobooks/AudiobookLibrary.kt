package com.lightphone.audiobooks

import com.lightphone.audiobooks.library.AudiobookProgressStore
import com.lightphone.audiobooks.library.LocalBookRepository
import com.lightphone.audiobooks.library.PlaybackSettingsStore
import java.io.File

/**
 * One-time wiring for the tool's in-process library (Rung-1 fold: the scan,
 * stores, and playback file access run in this process — there is no embedded
 * LightSdkService). Light screens hold `lightContext`, so the first screen
 * initializes this with the tool's own `lightContext.filesDir`; the stores and
 * repository keep their state there. No Application class is possible (the
 * tool plugin forbids user manifests), so this is the process singleton.
 */
object AudiobookLibrary {
    @Volatile
    private var initialized = false

    fun init(filesDir: File) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PlaybackSettingsStore.init(filesDir)
            AudiobookProgressStore.init(filesDir)
            LocalBookRepository.init(filesDir)
            initialized = true
        }
    }
}
