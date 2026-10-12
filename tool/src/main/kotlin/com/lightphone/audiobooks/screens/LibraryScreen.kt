package com.lightphone.audiobooks.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.AudiobookLibrary
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.PlayerSession
import com.lightphone.audiobooks.library.Audiobook
import com.lightphone.audiobooks.library.LocalBookRepository
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel : AppLightViewModel<Unit>() {

    val books = MutableStateFlow<List<Audiobook>>(emptyList())
    val loading = MutableStateFlow(true)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            // The first Library show kicks the initial scan (the old bootstrap
            // provider did it at app start; there is no Application hook here).
            if (!LocalBookRepository.hasScanned) LocalBookRepository.scan()
            books.value = LocalBookRepository.refreshedBooks()
            loading.value = false
        }
    }

    /** Runs a full scan (e.g. right after the audio permission is granted —
     *  the initial scan already ran without it and returned an empty
     *  snapshot, so a plain refresh would stay empty). */
    fun scan() {
        viewModelScope.launch {
            loading.value = true
            LocalBookRepository.scan()
            books.value = LocalBookRepository.refreshedBooks()
            loading.value = false
        }
    }
}

// Polls after launching the READ_MEDIA_AUDIO request until the grant lands
// (or times out), then rescans so the library appears without a manual scan.
// The window is human-scale: the system dialog can sit open while the user
// reads it, so 60 s beats the 5 s that let the first version expire early.
private const val PERMISSION_POLLS = 120
private const val PERMISSION_POLL_DELAY_MS = 500L

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel>
        get() = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel {
        AudiobookLibrary.init(lightContext.filesDir)
        return LibraryViewModel()
    }

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        // The detached player keeps playing after the Player screen pops;
        // PlayerSession records what it's doing. Resolved against the current
        // book list (a deleted book hides the button).
        val playingBook = if (PlayerSession.isPlaying) {
            books.firstOrNull { it.id == PlayerSession.loadedBookId }
        } else {
            null
        }

        // The audio permission (READ_MEDIA_AUDIO) is declared in
        // lighttool.toml; the SDK permission flow asks for it. When the
        // library is empty: if the permission is missing, launch the SDK
        // permission flow (system dialog) and rescan once the grant lands; if
        // it's present, rescan once — the initial scan ran before a fresh
        // grant (e.g. granted in system settings) and left a stale empty
        // snapshot. Guarded so it runs once per screen instance: no dialog
        // spam on every navigation, no scan loop on loading flips.
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.READ_MEDIA_AUDIO)
        var permissionPrompted = remember { false }
        LaunchedEffect(books.isEmpty(), loading) {
            if (books.isEmpty() && !loading && !permissionPrompted) {
                permissionPrompted = true
                val granted = checkPermission(Manifest.permission.READ_MEDIA_AUDIO).getOrNull()
                    ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted
                if (granted) {
                    viewModel.scan()
                } else {
                    permissionLauncher?.launch()
                    // The dialog can sit open for a while — poll past the
                    // human-scale window (60 s), then rescan when it lands.
                    repeat(PERMISSION_POLLS) {
                        delay(PERMISSION_POLL_DELAY_MS)
                        if (checkPermission(Manifest.permission.READ_MEDIA_AUDIO).getOrNull()
                                ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted
                        ) {
                            viewModel.scan()
                            return@LaunchedEffect
                        }
                    }
                }
            }
        }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    // The Library has no top bar; reserve the same height as
                    // the side (scrollbar) buffer so the top gap matches the
                    // gutters around the list.
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
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
                                        onOpen = { openPlayer(book) },
                                    )
                                }
                            }
                        }
                    }
                    LightBottomBar(
                        modifier = Modifier.navigationBarsPadding(),
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.SETTINGS,
                                onClick = { openSettings() },
                                contentDescription = "Settings",
                            ),
                            playingBook?.let { book ->
                                LightBarButton.Text(
                                    text = "NOW PLAYING",
                                    onClick = { openPlayer(book) },
                                )
                            },
                            // Balancing spacer: keeps NOW PLAYING centered
                            // (the bar's mixed icon/text/empty layout).
                            null,
                        ),
                    )
                }
            }
        }
    }

    private fun openPlayer(book: Audiobook) {
        navigateTo(screenFactory = { PlayerScreen(it, book) })
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }
}

@Composable
private fun BookRow(
    book: Audiobook,
    onOpen: () -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    val progress = if (book.durationMilliseconds > 0) {
        book.positionMilliseconds.toFloat() / book.durationMilliseconds
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            // Left margin only: the scroll view already reserves the right
            // gutter, so a symmetric padding would double-inset the row and
            // leave the percent floating off the right edge.
            .padding(start = 24.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = book.title,
                    variant = LightTextVariant.Copy,
                )
                if (book.author.isNotBlank()) {
                    LightText(
                        text = book.author,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            LightText(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                variant = LightTextVariant.Fine,
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
        modifier = Modifier.padding(24.dp),
    )
}
