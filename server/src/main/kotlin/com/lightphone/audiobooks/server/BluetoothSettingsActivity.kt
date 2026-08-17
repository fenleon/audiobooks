package com.lightphone.audiobooks.server

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Opens the system Bluetooth settings. The companion can't launch activities
 * from the background, so the tool (the foreground process) starts this
 * transparent activity via `SimpleLightScreen.startServerActivity`, which then
 * opens the phone's Bluetooth settings and finishes.
 */
class BluetoothSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
