package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.MediaClient
import com.lightphone.audiobooks.formatSpeed
import com.lightphone.audiobooks.VolumePanelOverlay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SpeedPickerViewModel : AppLightViewModel<Float>() {

    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    val current = MutableStateFlow(1.0f)

    override fun onScreenShow(screen: SimpleLightScreen<Float>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            current.value = MediaClient.playbackSpeed() ?: 1.0f
        }
    }
}

/**
 * Speed selection screen. The chosen speed is returned as the navigation
 * result — the player applies it (its viewmodel outlives this screen), so a
 * quick selection is never dropped by this screen's teardown.
 */
class SpeedPickerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Float, SpeedPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<SpeedPickerViewModel>
        get() = SpeedPickerViewModel::class.java

    override fun createViewModel(): SpeedPickerViewModel = SpeedPickerViewModel()

    @Composable
    override fun Content() {
        val current by viewModel.current.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val volumePanel by viewModel.volumePanel.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Playback Speed"),
                    )
                    LightScrollView {
                    viewModel.speeds.forEach { speed ->
                        LightText(
                            text = formatSpeed(speed),
                            // Heading = the settings row size; matches the
                            // podcast player's speed panel text.
                            variant = LightTextVariant.Heading,
                            lighten = speed != current,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { goBack(speed) }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
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
}
