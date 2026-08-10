package com.lightphone.audiobooks.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightphone.audiobooks.server.library.LocalBookRepository
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal status screen for the companion. Its real job is the SDK service +
 * playback; this activity exists to request the audio permission and to give
 * the user a place to trigger a rescan.
 */
class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAudioPermissionIfNeeded()
        setContent { StatusScreen(onScan = {
            activityScope.launch { LocalBookRepository.scan() }
        }) }
    }

    private fun requestAudioPermissionIfNeeded() {
        val permissions = mutableListOf(
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun StatusScreen(onScan: () -> Unit) {
    val books by LocalBookRepository.books.collectAsState()
    val scanning by LocalBookRepository.scanning.collectAsState()
    val themeColors by LightThemeController.colors.collectAsState()

    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
                .padding(32.dp),
        ) {
            LightText(text = "Audiobooks Server", variant = LightTextVariant.Heading)
            LightText(
                text = "Companion for the Audiobooks tool",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            LightText(
                text = if (scanning) "Scanning…" else "${books.size} book(s) in the library",
                variant = LightTextVariant.Copy,
            )
            LightText(
                text = "Scan for books",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .lightClickable(onClick = onScan),
            )
        }
    }
}
