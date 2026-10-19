package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.AudiobookLibrary
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.chapterIndexAt
import com.lightphone.audiobooks.embeddedChapters
import com.lightphone.audiobooks.formatTime
import com.lightphone.audiobooks.library.Audiobook
import com.lightphone.audiobooks.library.LocalBookRepository
import com.lightphone.audiobooks.partStartMs
import com.thelightphone.sdk.LightScreen
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ChaptersPickerViewModel(
    private val book: Audiobook,
) : AppLightViewModel<Int>() {

    /** The current entry (embedded-chapter or part index), highlighted in the list. */
    val currentIndex = MutableStateFlow(0)

    override fun onScreenShow(screen: SimpleLightScreen<Int>) {
        super.onScreenShow(screen)
        // Highlight from the book's saved position. The snapshot passed at
        // navigation time goes stale the moment playback advances, so re-read
        // the book (the Player saves progress on pause and every few seconds
        // while playing) and re-derive.
        currentIndex.value = indexFor(book.positionMilliseconds)
        viewModelScope.launch {
            val fresh = LocalBookRepository.refreshedBooks().firstOrNull { it.id == book.id } ?: return@launch
            currentIndex.value = indexFor(fresh.positionMilliseconds)
        }
    }

    private fun indexFor(progressMs: Long): Int {
        val chapters = embeddedChapters(book)
        return if (chapters.isNotEmpty()) {
            chapterIndexAt(chapters, progressMs.coerceAtLeast(0))
        } else {
            book.parts.indices.lastOrNull { partStartMs(book, it) <= progressMs } ?: 0
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
    private val book: Audiobook,
) : LightScreen<Int, ChaptersPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChaptersPickerViewModel>
        get() = ChaptersPickerViewModel::class.java

    override fun createViewModel(): ChaptersPickerViewModel {
        AudiobookLibrary.init(lightContext.filesDir)
        return ChaptersPickerViewModel(book)
    }

    @Composable
    override fun Content() {
        val currentIndex by viewModel.currentIndex.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val chapters = embeddedChapters(book)
        // Opens with the current chapter at the top of the viewport: each row
        // records its y offset, and once the current row's offset resolves the
        // scroll lands on it (one frame later, so maxValue reflects layout).
        val scrollState = rememberScrollState()
        val rowTops = remember { mutableStateMapOf<Int, Int>() }
        LaunchedEffect(currentIndex) {
            val y = snapshotFlow { rowTops[currentIndex] }.filterNotNull().first()
            withFrameNanos { }
            scrollState.scrollTo(y.coerceAtMost(scrollState.maxValue))
        }

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
                    LightScrollView(scrollState = scrollState) {
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
                                modifier = Modifier.onGloballyPositioned {
                                    rowTops[index] = it.positionInParent().y.roundToInt()
                                },
                            )
                        }
                    } else {
                        book.parts.forEachIndexed { index, part ->
                            ChapterRow(
                                title = part.title.ifBlank { "Chapter ${index + 1}" },
                                durationMs = part.durationMilliseconds,
                                isCurrent = index == currentIndex,
                                onClick = { goBack(index) },
                                modifier = Modifier.onGloballyPositioned {
                                    rowTops[index] = it.positionInParent().y.roundToInt()
                                },
                            )
                        }
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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
            underline = isCurrent,
            modifier = Modifier.weight(1f),
        )
        if (durationMs > 0) {
            LightText(
                text = formatTime(durationMs),
                variant = LightTextVariant.Fine,
            )
        }
    }
}
