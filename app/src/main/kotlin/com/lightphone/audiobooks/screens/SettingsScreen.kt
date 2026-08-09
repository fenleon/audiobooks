package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.MediaClient
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
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

class SettingsViewModel : LightViewModel<Unit>() {

    val autoPlayNext = MutableStateFlow(true)
    private var loaded = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            MediaClient.autoPlayNext()?.let { autoPlayNext.value = it }
        }
    }

    fun toggleAutoPlayNext() {
        viewModelScope.launch {
            val next = !autoPlayNext.value
            MediaClient.setAutoPlayNext(next)
            autoPlayNext.value = next
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel()

    @Composable
    override fun Content() {
        val autoPlayNext by viewModel.autoPlayNext.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to Library",
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                )
                Column(modifier = Modifier.weight(1f)) {
                    LightText(
                        text = "Scan Library",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { openScanProgress() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.toggleAutoPlayNext() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Toggle sits immediately left of its action label.
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightIcon(
                                icon = if (autoPlayNext) {
                                    LightIcons.TOGGLE_STATE_ON
                                } else {
                                    LightIcons.TOGGLE_STATE_OFF
                                },
                                size = 1.5f,
                                contentDescription = if (autoPlayNext) {
                                    "Auto-play next chapter on"
                                } else {
                                    "Auto-play next chapter off"
                                },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            LightText(
                                text = "Auto-Play",
                                variant = LightTextVariant.Copy,
                            )
                            LightText(
                                text = "next chapter",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openScanProgress() {
        // Dismissing the scan panel returns straight to the library: the panel
        // pops back to this screen, whose callback pops straight through it.
        navigateTo(screenFactory = { ScanProgressScreen(it) }) { goBack() }
    }
}
