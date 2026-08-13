package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.MediaClient
import com.lightphone.audiobooks.chapterIndexAt
import com.lightphone.audiobooks.chapterLabel
import com.lightphone.audiobooks.embeddedChapters
import com.lightphone.audiobooks.formatSpeed
import com.lightphone.audiobooks.formatTime
import com.lightphone.audiobooks.partStartMs
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
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
    /** True once this book has been played at least once in this player
     *  session; the skip controls stay visible from then on, even when paused. */
    val hasPlayed = MutableStateFlow(false)
    private var pollJob: Job? = null
    private var started = false

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!started) {
            started = true
            // Show a loading state instead of the previously-loaded book while
            // the new book opens (open() blocks the server's main thread), and
            // only start polling once it has — a poll racing ahead of open()
            // returns the previous book's state.
            state.value = null
            viewModelScope.launch {
                MediaClient.open(book.id)
                startPolling()
            }
        } else {
            startPolling()
        }
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
                // A book reopened mid-playback (e.g. via the notification) counts
                // as played too.
                if (state.value?.playing == true) hasPlayed.value = true
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
            if (playing) {
                MediaClient.pause()
            } else {
                hasPlayed.value = true
                MediaClient.play(book.id)
            }
        }
    }

    fun seekBy(deltaMilliseconds: Long) {
        val current = state.value?.positionMs ?: return
        viewModelScope.launch { MediaClient.seekTo(current + deltaMilliseconds) }
    }

    /** Seeks on the book's global timeline (embedded-chapter jumps land here). */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch { MediaClient.seekTo(positionMs) }
    }

    fun seekToFraction(fraction: Float) {
        val state = state.value ?: return
        val chapter = chapterTime(book, state)
        val start = chapter?.startMs ?: 0L
        val duration = chapter?.durationMs ?: state.durationMs
        if (duration <= 0) return
        viewModelScope.launch {
            MediaClient.seekTo(start + (duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    /** Jumps to a chapter on the loaded book, preserving the play/pause state. */
    fun seekToPart(index: Int) {
        viewModelScope.launch { MediaClient.seekToPart(index) }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { MediaClient.setSpeed(speed) }
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
        val hasPlayed by viewModel.hasPlayed.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val chapters = embeddedChapters(book)

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
                )
                Box(modifier = Modifier.weight(1f)) {
                    val playback = state
                    if (playback == null) {
                        LightText(
                            text = "Loading…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        val embeddedIndex = if (chapters.isNotEmpty()) {
                            chapterIndexAt(
                                chapters,
                                (playback.positionMs - partStartMs(book, playback.partIndex.coerceAtLeast(0))).coerceAtLeast(0),
                            )
                        } else {
                            playback.partIndex
                        }
                        PlayerContent(
                            state = playback,
                            chapter = chapterTime(book, playback),
                            showSkips = hasPlayed,
                            showChapter = playback.partCount > 1 || chapters.size > 1,
                            chapterIndex = embeddedIndex,
                            chapterCount = if (chapters.isNotEmpty()) chapters.size else playback.partCount,
                            themeColors = themeColors,
                            onBack15 = { viewModel.seekBy(-15_000) },
                            onForward15 = { viewModel.seekBy(15_000) },
                            onTogglePlay = { viewModel.togglePlay() },
                            onSeekFraction = viewModel::seekToFraction,
                        )
                    }
                }
                if (state != null) {
                    LightBottomBar(
                        modifier = Modifier.navigationBarsPadding(),
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.SETTINGS,
                                onClick = { openSettings() },
                                contentDescription = "Settings",
                            ),
                            LightBarButton.Text(
                                text = formatSpeed(state!!.speed),
                                onClick = { openSpeedPicker() },
                            ),
                            // Chapters exist for multi-part (folder) books, or
                            // single-file books with embedded chapters.
                            if (state!!.partCount > 1 || chapters.size > 1) LightBarButton.LightIcon(
                                icon = LightIcons.LIST,
                                onClick = { openChapters() },
                                contentDescription = "Chapters",
                            ) else null,
                        ),
                    )
                }
            }
        }
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }

    /** The chosen speed is returned as the navigation result and applied here,
     *  so the selection survives the picker screen's teardown. */
    private fun openSpeedPicker() {
        navigateTo(screenFactory = { SpeedPickerScreen(it) }) { speed ->
            viewModel.setSpeed(speed)
        }
    }

    private fun openChapters() {
        navigateTo(screenFactory = { ChaptersPickerScreen(it, book) }) { index ->
            val chapters = embeddedChapters(book)
            if (chapters.isNotEmpty()) {
                // Embedded-chapter books are single-part today, so the flat
                // chapter index maps straight to a start offset that is already
                // the global seek target.
                viewModel.seekTo(chapters[index].startMs)
            } else {
                viewModel.seekToPart(index)
            }
        }
    }
}

@Composable
private fun PlayerContent(
    state: LightServiceMethod.GetPlaybackState.Response,
    chapter: ChapterTime?,
    showSkips: Boolean,
    showChapter: Boolean,
    chapterIndex: Int,
    chapterCount: Int,
    themeColors: com.thelightphone.sdk.ui.LightColors,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    val position = chapter?.positionMs ?: state.positionMs
    val duration = chapter?.durationMs ?: state.durationMs
    val progress = if (duration > 0) {
        position.toFloat() / duration
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
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
        state.author?.takeIf { it.isNotBlank() }?.let { author ->
            LightText(
                text = author,
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
        if (showChapter) {
            LightText(
                text = chapterLabel(chapterIndex, chapterCount),
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
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
                text = formatTime(position),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
            LightText(
                text = formatTime(duration),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }

        // Skip controls are hidden until this book has been played once in this
        // session; after that they stay visible, paused or not.
        if (showSkips) {
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
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(LightIcons.PLAY, "Play", onTogglePlay)
            }
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
        size = 3f,
        modifier = Modifier.lightClickable(onClick = onClick),
        contentDescription = description,
    )
}

/** The current chapter's start, duration, and position within it. */
private data class ChapterTime(val startMs: Long, val durationMs: Long, val positionMs: Long)

/**
 * Maps the book-global playback state onto the current chapter, so the player
 * shows chapter-scoped time and progress. Embedded chapters (single-file
 * books) scope to the chapter containing the position; folder books scope to
 * the current part. Returns null (full-book values) when the book has no part
 * durations — e.g. single-file books without embedded chapters.
 */
private fun chapterTime(
    book: LightServiceMethod.GetBooks.Book,
    state: LightServiceMethod.GetPlaybackState.Response,
): ChapterTime? {
    val index = state.partIndex.coerceAtLeast(0)
    val part = book.parts.getOrNull(index) ?: return null
    val start = partStartMs(book, index)
    val chapters = part.chapters
    if (chapters.isNotEmpty()) {
        val positionInPart = (state.positionMs - start).coerceAtLeast(0)
        val current = chapters.firstOrNull { positionInPart < it.endMs } ?: chapters.last()
        val duration = (current.endMs - current.startMs).coerceAtLeast(0)
        return ChapterTime(
            startMs = start + current.startMs,
            durationMs = duration,
            positionMs = (positionInPart - current.startMs).coerceIn(0, duration),
        )
    }
    val duration = part.durationMs
    if (duration <= 0) return null
    return ChapterTime(
        startMs = start,
        durationMs = duration,
        positionMs = (state.positionMs - start).coerceIn(0, duration),
    )
}
