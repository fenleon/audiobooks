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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
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

class LibraryViewModel : LightViewModel<Unit>() {

    val books = MutableStateFlow<List<LightServiceMethod.GetBooks.Book>>(emptyList())
    val loading = MutableStateFlow(true)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            books.value = MediaClient.getBooks()
            loading.value = false
        }
    }

    fun rescan() {
        viewModelScope.launch {
            loading.value = true
            books.value = MediaClient.scanLibrary()
            loading.value = false
        }
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel>
        get() = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel = LibraryViewModel()

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Audiobooks"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { viewModel.rescan() },
                        contentDescription = "Scan for books",
                    ),
                )
                when {
                    loading && books.isEmpty() -> StatusText("Scanning your library…")
                    books.isEmpty() -> StatusText(
                        "No books found. Copy audiobooks into the Audiobooks folder on your device.",
                    )
                    else -> LightScrollView {
                        books.forEach { book ->
                            BookRow(book = book) { openPlayer(book) }
                        }
                    }
                }
            }
        }
    }

    private fun openPlayer(book: LightServiceMethod.GetBooks.Book) {
        navigateTo(screenFactory = { PlayerScreen(it, book) })
    }
}

@Composable
private fun BookRow(book: LightServiceMethod.GetBooks.Book, onClick: () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    val progress = if (book.durationMs > 0) {
        book.progressMs.toFloat() / book.durationMs
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
        if (book.author.isNotBlank()) {
            LightText(
                text = book.author,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        LightProgressBar(
            colors = themeColors,
            progress = progress,
        )
    }
}

@Composable
private fun StatusText(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(24.dp),
    )
}
