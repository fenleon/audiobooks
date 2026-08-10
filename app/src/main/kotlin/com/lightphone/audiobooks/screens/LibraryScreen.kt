package com.lightphone.audiobooks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel : LightViewModel<Unit>() {

    val books = MutableStateFlow<List<LightServiceMethod.GetBooks.Book>>(emptyList())
    val loading = MutableStateFlow(true)
    val editing = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            var result = MediaClient.getBooks()
            // A cold start can bind before the companion finishes its initial
            // scan; retry briefly so the library doesn't flash "No books found".
            repeat(EMPTY_REFRESH_RETRIES) {
                if (result.isNotEmpty()) return@repeat
                delay(EMPTY_REFRESH_DELAY_MS)
                result = MediaClient.getBooks()
            }
            books.value = result
            loading.value = false
            // Deleting the last book empties the library; edit mode has nothing
            // to act on and its toggle disappears with the book list.
            if (books.value.isEmpty()) editing.value = false
        }
    }

    fun toggleEditing() {
        editing.value = !editing.value
    }

    private companion object {
        const val EMPTY_REFRESH_RETRIES = 6
        const val EMPTY_REFRESH_DELAY_MS = 1_500L
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
        val editing by viewModel.editing.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    rightButton = if (books.isNotEmpty()) {
                        LightBarButton.Text(
                            text = if (editing) "DONE" else "EDIT",
                            onClick = { viewModel.toggleEditing() },
                        )
                    } else {
                        null
                    },
                )
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading && books.isEmpty() -> StatusText("Scanning your library…")
                        books.isEmpty() -> StatusText(
                            "No books found. Copy audiobooks into the Audiobooks folder on your device.",
                        )
                        else -> LightScrollView {
                            books.forEach { book ->
                                BookRow(
                                    book = book,
                                    editing = editing,
                                    onOpen = { openPlayer(book) },
                                    onRemove = { removeBook(book) },
                                )
                            }
                        }
                    }
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        // No settings or bluetooth while choosing books to remove.
                        if (editing) null else LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { openSettings() },
                            contentDescription = "Settings",
                        ),
                        null,
                        if (editing) null else LightBarButton.LightIcon(
                            icon = LightIcons.BLUETOOTH,
                            onClick = { openBluetoothSettings() },
                            contentDescription = "Bluetooth settings",
                        ),
                    ),
                )
            }
        }
    }

    private fun openPlayer(book: LightServiceMethod.GetBooks.Book) {
        navigateTo(screenFactory = { PlayerScreen(it, book) })
    }

    private fun removeBook(book: LightServiceMethod.GetBooks.Book) {
        navigateTo(screenFactory = { RemoveBookScreen(it, book) })
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }

    /** The companion (which can't launch activities from the background) hosts
     *  a transparent activity that opens the system Bluetooth settings. */
    private fun openBluetoothSettings() {
        startServerActivity(
            "com.lightphone.audiobooks.server/com.lightphone.audiobooks.server.BluetoothSettingsActivity",
        )
    }
}

@Composable
private fun BookRow(
    book: LightServiceMethod.GetBooks.Book,
    editing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    val progress = if (book.durationMs > 0) {
        book.progressMs.toFloat() / book.durationMs
    } else {
        0f
    }
    val rowModifier = if (editing) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
    }
    Column(
        modifier = rowModifier.padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .lightClickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    LightIcon(
                        icon = LightIcons.CLOSE,
                        size = 1.5f,
                        contentDescription = "Remove ${book.title}",
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = book.title,
                    variant = LightTextVariant.Copy,
                )
                if (book.author.isNotBlank()) {
                    LightText(
                        text = book.author,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // Progress is noise while choosing books to remove.
            if (!editing) {
                LightText(
                    text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
            }
        }
        // Progress is noise while choosing books to remove.
        if (!editing) {
            LightProgressBar(
                colors = themeColors,
                progress = progress,
            )
        }
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
