package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.MediaClient
import com.lightphone.audiobooks.VolumePanelOverlay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ScanProgressViewModel : AppLightViewModel<Unit>() {

    val done = MutableStateFlow(false)
    private var started = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (started) return
        started = true
        viewModelScope.launch {
            MediaClient.scanLibrary()
            done.value = true
        }
    }
}

/**
 * Scan progress panel, mirroring the sync workflow of the podcast/calendar
 * tools: a status line while the scan runs, and an X to dismiss straight back
 * to the library. The companion scans in the background and the library
 * refreshes from its result when this panel is dismissed.
 */
class ScanProgressScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ScanProgressViewModel>(sealedActivity) {

    override val viewModelClass: Class<ScanProgressViewModel>
        get() = ScanProgressViewModel::class.java

    override fun createViewModel(): ScanProgressViewModel = ScanProgressViewModel()

    @Composable
    override fun Content() {
        val done by viewModel.done.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val volumePanel by viewModel.volumePanel.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                    LightText(
                        text = "Scanning Library",
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LightText(
                        text = if (done) {
                            "Scan complete."
                        } else {
                            "Audiobooks are being scanned in the background."
                        },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        null,
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            // Deliver a result so the settings screen (which
                            // pushed this panel) pops through to the library.
                            onClick = { goBack(Unit) },
                            contentDescription = "Dismiss",
                        ),
                        null,
                    ),
                )
                }
                // Full-screen overlay on top of everything (the panel is a
                // visual replica — not interactive).
                VolumePanelOverlay(
                    state = volumePanel,
                    onDismiss = { viewModel.dismissVolumePanel() },
                )
            }
        }
    }
}
