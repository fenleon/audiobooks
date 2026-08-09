package com.stan.libbylight.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.stan.libbylight.MediaClient
import com.stan.libbylight.formatTime
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
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

class ChaptersPickerViewModel(
    private val book: LightServiceMethod.GetBooks.Book,
) : LightViewModel<Unit>() {

    val currentPart = MutableStateFlow(0)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            currentPart.value = MediaClient.playbackState()?.partIndex ?: 0
        }
    }

    fun jumpToPart(index: Int) {
        viewModelScope.launch { MediaClient.play(book.id, partIndex = index) }
    }
}

class ChaptersPickerScreen(
    sealedActivity: SealedLightActivity,
    private val book: LightServiceMethod.GetBooks.Book,
) : LightScreen<Unit, ChaptersPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChaptersPickerViewModel>
        get() = ChaptersPickerViewModel::class.java

    override fun createViewModel(): ChaptersPickerViewModel = ChaptersPickerViewModel(book)

    @Composable
    override fun Content() {
        val currentPart by viewModel.currentPart.collectAsState()
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
                    center = LightTopBarCenter.Text("Chapters"),
                )
                LightScrollView {
                    book.parts.forEachIndexed { index, part ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    viewModel.jumpToPart(index)
                                    goBack()
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = part.title.ifBlank { "Chapter ${index + 1}" },
                                variant = LightTextVariant.Copy,
                                lighten = index != currentPart,
                                modifier = Modifier.weight(1f),
                            )
                            if (part.durationMs > 0) {
                                LightText(
                                    text = formatTime(part.durationMs),
                                    variant = LightTextVariant.Fine,
                                    lighten = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
