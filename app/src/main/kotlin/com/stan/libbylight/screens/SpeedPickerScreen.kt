package com.stan.libbylight.screens

import androidx.compose.foundation.background
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
import com.stan.libbylight.MediaClient
import com.stan.libbylight.formatSpeed
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

class SpeedPickerViewModel : LightViewModel<Unit>() {

    val speeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    val current = MutableStateFlow(1.0f)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            current.value = MediaClient.playbackState()?.speed ?: 1.0f
        }
    }

    fun select(speed: Float) {
        viewModelScope.launch {
            MediaClient.setSpeed(speed)
            current.value = speed
        }
    }
}

class SpeedPickerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SpeedPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<SpeedPickerViewModel>
        get() = SpeedPickerViewModel::class.java

    override fun createViewModel(): SpeedPickerViewModel = SpeedPickerViewModel()

    @Composable
    override fun Content() {
        val current by viewModel.current.collectAsState()
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
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Speed"),
                )
                LightScrollView {
                    viewModel.speeds.forEach { speed ->
                        LightText(
                            text = formatSpeed(speed),
                            variant = LightTextVariant.Copy,
                            lighten = speed != current,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    viewModel.select(speed)
                                    goBack()
                                }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
