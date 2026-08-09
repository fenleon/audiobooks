package com.stan.libbylight.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.stan.libbylight.chapterLabel
import com.stan.libbylight.formatSpeed
import com.stan.libbylight.formatTime
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
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
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val book: LightServiceMethod.GetBooks.Book,
) : LightViewModel<Unit>() {

    val state = MutableStateFlow<LightServiceMethod.GetPlaybackState.Response?>(null)
    private var pollJob: Job? = null
    private var started = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!started) {
            started = true
            viewModelScope.launch { MediaClient.play(book.id) }
        }
        startPolling()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopPolling()
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (isActive) {
                state.value = MediaClient.playbackState()
                delay(500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun togglePlay() {
        val playing = state.value?.playing == true
        viewModelScope.launch {
            if (playing) MediaClient.pause() else MediaClient.play(book.id)
        }
    }

    fun seekBy(deltaMilliseconds: Long) {
        val current = state.value?.positionMs ?: return
        viewModelScope.launch { MediaClient.seekTo(current + deltaMilliseconds) }
    }

    fun seekToFraction(fraction: Float) {
        val duration = state.value?.durationMs ?: return
        if (duration <= 0) return
        viewModelScope.launch {
            MediaClient.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { MediaClient.setSpeed(speed) }
    }

    fun seekToPart(index: Int) {
        viewModelScope.launch { MediaClient.play(book.id, partIndex = index) }
    }
}

class PlayerScreen(
    sealedActivity: SealedLightActivity,
    private val book: LightServiceMethod.GetBooks.Book,
) : LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel(): PlayerViewModel = PlayerViewModel(book)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
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
                    center = LightTopBarCenter.Text(book.title),
                )
                val playback = state
                if (playback == null) {
                    LightText(
                        text = "Connecting to the Audiobooks server…",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    PlayerContent(
                        state = playback,
                        themeColors = themeColors,
                        onBack15 = { viewModel.seekBy(-15_000) },
                        onForward15 = { viewModel.seekBy(15_000) },
                        onTogglePlay = { viewModel.togglePlay() },
                        onSeekFraction = viewModel::seekToFraction,
                        onOpenChapters = { navigateTo(screenFactory = { ChaptersPickerScreen(it, book) }) },
                        onOpenSpeed = { navigateTo(screenFactory = { SpeedPickerScreen(it) }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerContent(
    state: LightServiceMethod.GetPlaybackState.Response,
    themeColors: com.thelightphone.sdk.ui.LightColors,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSpeed: () -> Unit,
) {
    val progress = if (state.durationMs > 0) {
        state.positionMs.toFloat() / state.durationMs
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        LightText(
            text = state.title.orEmpty(),
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(top = 16.dp),
        )
        state.author?.takeIf { it.isNotBlank() }?.let { author ->
            LightText(
                text = author,
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (state.partCount > 1) {
            LightText(
                text = chapterLabel(state.partIndex, state.partCount),
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .lightClickable(onClick = onOpenChapters),
            )
        }

        LightTouchableProgressBar(
            colors = themeColors,
            progress = progress,
            onValueChange = onSeekFraction,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(
                text = formatTime(state.positionMs),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
            LightText(
                text = formatTime(state.durationMs),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(LightIcons.SKIP_BACKWARD_FIFTEEN, "Back 15", onBack15)
            TransportButton(
                if (state.playing) LightIcons.PAUSE else LightIcons.PLAY,
                if (state.playing) "Pause" else "Play",
                onTogglePlay,
            )
            TransportButton(LightIcons.SKIP_FORWARD_FIFTEEN, "Forward 15", onForward15)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            LightText(
                text = formatSpeed(state.speed),
                variant = LightTextVariant.Copy,
                modifier = Modifier.lightClickable(onClick = onOpenSpeed),
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: com.thelightphone.sdk.ui.LightIconConfiguration,
    description: String,
    onClick: () -> Unit,
) {
    LightIcon(
        icon = icon,
        modifier = Modifier.lightClickable(onClick = onClick),
        contentDescription = description,
    )
}
