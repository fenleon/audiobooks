package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.chapterIndexAt
import com.lightphone.audiobooks.embeddedChapters
import com.lightphone.audiobooks.formatTime
import com.lightphone.audiobooks.partStartMs
import com.lightphone.audiobooks.VolumePanelOverlay
import com.thelightphone.sdk.LightScreen
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

class ChaptersPickerViewModel(
    private val book: LightServiceMethod.GetBooks.Book,
) : AppLightViewModel<Int>() {

    /** The current entry (embedded-chapter or part index), highlighted in the list. */
    val currentIndex = MutableStateFlow(0)

    override fun onScreenShow(screen: SimpleLightScreen<Int>) {
        super.onScreenShow(screen)
        // Highlight from the book's saved position — the Player screen keeps it
        // fresh via SaveProgress, so the current chapter is right up to the
        // last save. (The detached player's live position is not readable here:
        // only the Player screen holds a handle, and only one may exist.)
        val chapters = embeddedChapters(book)
        currentIndex.value = if (chapters.isNotEmpty()) {
            chapterIndexAt(chapters, book.progressMs.coerceAtLeast(0))
        } else {
            book.parts.indices.lastOrNull { partStartMs(book, it) <= book.progressMs } ?: 0
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
        val volumePanel by viewModel.volumePanel.collectAsState()
        val chapters = embeddedChapters(book)

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
            // Left margin only: the scroll view already reserves the right
            // gutter, so a symmetric padding would double-inset the row and
            // leave the duration floating off the right edge.
            .padding(start = 24.dp, top = 14.dp, bottom = 14.dp),
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
