package com.stan.libbylight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.stan.libbylight.player.LocalPlaybackController
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.screens.PlayerDebugScreen

/**
 * Bard entry point. Shows the local-only audiobook UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlayerDebugScreen()
        }
    }

    override fun onStop() {
        LocalPlaybackController.persistProgress()
        AudiobookProgressStore.flushLatest()
        super.onStop()
    }

    override fun onDestroy() {
        LocalPlaybackController.persistProgress()
        AudiobookProgressStore.flushLatest()
        super.onDestroy()
    }
}
