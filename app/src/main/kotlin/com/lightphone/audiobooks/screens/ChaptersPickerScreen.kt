package com.lightphone.audiobooks.screens

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
import com.lightphone.audiobooks.MediaClient
import com.lightphone.audiobooks.chapterIndexAt
import com.lightphone.audiobooks.embeddedChapters
import com.lightphone.audiobooks.formatTime
import com.lightphone.audiobooks.partStartMs
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
) : LightViewModel<Int>() {

    /** The current entry (embedded-chapter or part index), highlighted in the list. */
    val currentIndex = MutableStateFlow(0)

    override fun onScreenShow(screen: SimpleLightScreen<Int>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            val state = MediaClient.playbackState()
            val chapters = embeddedChapters(book)
            currentIndex.value = if (state?.bookId == book.id) {
                // Live: highlight where playback actually is.
                if (chapters.isNotEmpty()) {
                    chapterIndexAt(
                        chapters,
                        (state.positionMs - partStartMs(book, state.partIndex.coerceAtLeast(0))).coerceAtLeast(0),
                    )
                } else {
                    state.partIndex
                }
            } else {
                // Preview (or nothing loaded): highlight the book's own saved
                // position instead of whatever is playing.
                if (chapters.isNotEmpty()) {
                    chapterIndexAt(chapters, book.progressMs.coerceAtLeast(0))
                } else {
                    book.parts.indices.lastOrNull { partStartMs(book, it) <= book.progressMs } ?: 0
                }
            }
        }
    }
}

/**
 * Chapter list for a book. The tapped chapter index is returned as the
 * navigation result — the player applies it (preserving play/pause), so the
 * switch is never dropped by this screen's teardown.
 */
class ChaptersPickerScreen(
    sealedActivity: SealedLightActivity,
    private val book: LightServiceMethod.GetBooks.Book,
) : LightScreen<Int, ChaptersPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChaptersPickerViewModel>
        get() = ChaptersPickerViewModel::class.java

    override fun createViewModel(): ChaptersPickerViewModel = ChaptersPickerViewModel(book)

    @Composable
    override fun Content() {
        val currentIndex by viewModel.currentIndex.collectAsState()
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
                    center = LightTopBarCenter.Text("Chapters"),
                )
                LightScrollView {
                    // Embedded chapters (single-file books) list the file's own
                    // chapters; folder books list their parts. Both return the
                    // tapped index, which the player resolves to a seek.
                    if (chapters.isNotEmpty()) {
                        chapters.forEachIndexed { index, chapter ->
                            val duration = (chapter.endMs - chapter.startMs).coerceAtLeast(0)
                            ChapterRow(
                                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                                durationMs = duration,
                                isCurrent = index == currentIndex,
                                onClick = { goBack(index) },
                            )
                        }
                    } else {
                        book.parts.forEachIndexed { index, part ->
                            ChapterRow(
                                title = part.title.ifBlank { "Chapter ${index + 1}" },
                                durationMs = part.durationMs,
                                isCurrent = index == currentIndex,
                                onClick = { goBack(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    title: String,
    durationMs: Long,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            lighten = !isCurrent,
            modifier = Modifier.weight(1f),
        )
        if (durationMs > 0) {
            LightText(
                text = formatTime(durationMs),
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
    }
}
