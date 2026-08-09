package com.stan.libbylight.screens

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.stan.libbylight.BuildConfig
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookPart
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.library.AudiobookSource
import com.stan.libbylight.library.LocalBookRepository
import com.stan.libbylight.library.LocalScanResult
import com.stan.libbylight.library.PersistedActiveAudiobook
import com.stan.libbylight.player.LocalPlaybackController
import com.stan.libbylight.player.PlayerReadiness
import com.stan.libbylight.player.PlayerState
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun PlayerDebugScreen() {
    val persistedActiveAtStartup = remember { AudiobookProgressStore.lastActiveAudiobook() }
    val persistedActiveRecord by AudiobookProgressStore.activeAudiobook.collectAsState()
    val localPlayerState by LocalPlaybackController.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showBooks by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showLocalBooks by remember { mutableStateOf(false) }
    var showVersion by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var downloadsEditMode by remember { mutableStateOf(false) }
    var pendingDownloadRemoval by remember { mutableStateOf<Audiobook?>(null) }
    var downloadRemovalMessage by remember { mutableStateOf("") }
    var showNowPlayingLoading by remember { mutableStateOf(false) }
    var showCurrentBookUnavailable by remember { mutableStateOf(false) }
    var pendingNowPlaying by remember { mutableStateOf<PersistedActiveAudiobook?>(null) }

    var activeBook by remember { mutableStateOf(persistedActiveAtStartup?.toAudiobook()) }
    val localBooks by LocalBookRepository.books.collectAsState()
    val localScanResult by LocalBookRepository.scanResult.collectAsState()
    val localScanning by LocalBookRepository.scanning.collectAsState()
    val context = LocalContext.current

    val displayedLocalBooks = localBooks.map { book ->
        if (activeBook?.id == book.id) {
            book.copy(
                positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                    .takeIf { it > 0 } ?: book.durationMilliseconds,
            )
        } else {
            book
        }
    }
    val allBooks = displayedLocalBooks.sortedWith(
        compareByDescending<Audiobook> { it.lastPlayedAtMilliseconds }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    val downloadedBooks = localBooks.sortedBy { it.title.lowercase() }

    val localDeleteConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val book = pendingDownloadRemoval
        if (result.resultCode == Activity.RESULT_OK && book?.source == AudiobookSource.Local) {
            scope.launch { LocalBookRepository.scan() }
            if (activeBook?.id == book.id) {
                LocalPlaybackController.close()
                activeBook = null
            }
            pendingDownloadRemoval = null
            downloadRemovalMessage = ""
        } else if (book != null) {
            downloadRemovalMessage = "Could not remove audiobook."
        }
    }

    fun performLocalScan() {
        scope.launch {
            LocalBookRepository.scan()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) performLocalScan() else LocalBookRepository.publishPermissionRequired()
    }

    fun scanLocalBooks() {
        if (LocalBookRepository.hasReadPermission()) {
            performLocalScan()
        } else {
            permissionLauncher.launch(LocalBookRepository.requiredPermission())
        }
    }

    fun openBook(book: Audiobook, autoPlay: Boolean = false) {
        val previous = activeBook
        val requestedId = AudiobookProgressStore.qualifiedId(book.source, book.id)
        val activeId = previous?.let { AudiobookProgressStore.qualifiedId(it.source, it.id) }
        // Shortcut only when the controller has actually loaded this book. The
        // UI's activeBook is pre-seeded from persisted data at startup (a book
        // without parts), so an id match alone must not skip LocalPlaybackController.open().
        if (requestedId == activeId && localPlayerState.title == book.title) {
            AudiobookProgressStore.markOpened(book)
            showBooks = false
            return
        }
        if (previous != null) {
            LocalPlaybackController.persistProgress()
            LocalBookRepository.updateBook(
                previous.copy(
                    positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                    durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                        .takeIf { it > 0 } ?: previous.durationMilliseconds,
                ),
            )
        }
        val opened = AudiobookProgressStore.markOpened(book)
        val openedBook = book.copy(
            lastPlayedAtMilliseconds = opened.lastPlayedAtMilliseconds,
            lastUpdatedAtMilliseconds = opened.lastUpdatedAtMilliseconds,
        )
        activeBook = openedBook
        showBooks = false
        LocalBookRepository.updateBook(openedBook)
        LocalPlaybackController.open(openedBook, autoPlay = autoPlay)
    }

    fun restoreLastPlayer() {
        val persisted = AudiobookProgressStore.lastActiveAudiobook()
        if (persisted == null) {
            showCurrentBookUnavailable = true
            return
        }
        val current = activeBook
        // Only trust the seeded activeBook when the controller really loaded it
        // (title check); otherwise fall through and open the persisted book.
        if (current != null &&
            AudiobookProgressStore.qualifiedId(current.source, current.id) == persisted.qualifiedId &&
            localPlayerState.title == current.title
        ) {
            showBooks = false
            return
        }
        pendingNowPlaying = persisted
        showNowPlayingLoading = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            pendingDownloadRemoval != null -> {
                val book = pendingDownloadRemoval!!
                DownloadRemovalConfirmationScreen(
                    message = downloadRemovalMessage,
                    onBack = {
                        pendingDownloadRemoval = null
                        downloadRemovalMessage = ""
                    },
                    onConfirm = {
                        downloadRemovalMessage = ""
                        scope.launch {
                            val removed = LocalBookRepository.deleteBook(book)
                            if (removed) {
                                if (activeBook?.id == book.id) {
                                    LocalPlaybackController.close()
                                    activeBook = null
                                }
                                pendingDownloadRemoval = null
                            } else {
                                val request = runCatching {
                                    val references = book.parts.map { Uri.parse(it.playbackReference) }
                                        .ifEmpty { listOf(Uri.parse(book.playbackReference)) }
                                    MediaStore.createDeleteRequest(
                                        context.contentResolver,
                                        references,
                                    )
                                }.getOrNull()
                                if (request != null) {
                                    localDeleteConsentLauncher.launch(
                                        IntentSenderRequest.Builder(request.intentSender).build(),
                                    )
                                } else {
                                    downloadRemovalMessage = "Could not remove audiobook."
                                }
                            }
                        }
                    },
                )
            }

            showCurrentBookUnavailable -> {
                CurrentBookStatusScreen(
                    message = "No current open book",
                    onBack = {
                        showCurrentBookUnavailable = false
                        showBooks = true
                    },
                )
            }

            showNowPlayingLoading -> {
                CurrentBookStatusScreen(
                    message = "Opening current book…",
                    onBack = {
                        pendingNowPlaying = null
                        showNowPlayingLoading = false
                        showBooks = true
                    },
                )
            }

            showLocalBooks -> {
                LocalBooksScreen(
                    result = localScanResult,
                    scanning = localScanning,
                    onBack = { showLocalBooks = false },
                    onScan = ::scanLocalBooks,
                )
            }

            showVersion -> {
                VersionScreen(onBack = { showVersion = false })
            }

            showDownloads -> {
                DownloadsScreen(
                    books = downloadedBooks,
                    editMode = downloadsEditMode,
                    onBack = {
                        downloadsEditMode = false
                        showDownloads = false
                    },
                    onToggleEdit = { downloadsEditMode = !downloadsEditMode },
                    onOpenBook = ::openBook,
                    onRemoveBook = { pendingDownloadRemoval = it },
                )
            }

            showSettings -> {
                SettingsScreen(
                    onBack = { showSettings = false },
                    onOpenLocalBooks = { showLocalBooks = true },
                    onOpenVersion = { showVersion = true },
                )
            }

            showBooks -> {
                BooksScreen(
                    books = allBooks,
                    loading = false,
                    message = "",
                    onSettings = { showSettings = true },
                    onDownloads = { showDownloads = true },
                    onPlayer = persistedActiveRecord?.let { ::restoreLastPlayer },
                    onOpenBook = ::openBook,
                )
            }

            else -> {
                PlayerScreen(
                    state = localPlayerState,
                    book = activeBook,
                    onPlay = { LocalPlaybackController.play() },
                    onPause = LocalPlaybackController::pause,
                    onBack15 = { LocalPlaybackController.seekBy(-15_000) },
                    onForward15 = { LocalPlaybackController.seekBy(15_000) },
                    onSeekTo = LocalPlaybackController::seekTo,
                    onSeekToPart = LocalPlaybackController::seekToPart,
                    onSetSpeed = LocalPlaybackController::setSpeed,
                    statusText = if (localPlayerState.readiness == PlayerReadiness.Error) {
                        localPlayerState.diagnostic
                    } else {
                        ""
                    },
                    onBooks = { showBooks = true },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (LocalBookRepository.hasReadPermission()) performLocalScan()
    }

    LaunchedEffect(pendingNowPlaying, allBooks, localScanResult, localScanning) {
        val persisted = pendingNowPlaying ?: return@LaunchedEffect
        val resolved = allBooks.firstOrNull {
            AudiobookProgressStore.qualifiedId(it.source, it.id) == persisted.qualifiedId
        }
        if (resolved != null) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            openBook(resolved, autoPlay = false)
            return@LaunchedEffect
        }

        val resolutionFinished = !LocalBookRepository.hasReadPermission() ||
            (!localScanning && localScanResult != null)
        if (resolutionFinished) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            showCurrentBookUnavailable = true
        }
    }
}

@Composable
private fun CurrentBookStatusScreen(message: String, onBack: () -> Unit) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BardSurface(content: @Composable () -> Unit) {
    LightTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

@Composable
private fun BooksScreen(
    books: List<Audiobook>,
    loading: Boolean,
    message: String,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onPlayer: (() -> Unit)?,
    onOpenBook: (Audiobook) -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                loading && books.isEmpty() -> BooksStatus("Loading books…", Modifier.weight(1f))
                message.isNotBlank() && books.isEmpty() -> BooksStatus(message, Modifier.weight(1f))
                books.isEmpty() -> BooksStatus("No audiobooks", Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 6.5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(books, key = { "${it.source}:${it.id}" }) { book ->
                            BookRow(
                                book = book,
                                onClick = { onOpenBook(book) },
                            )
                        }
                    }
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = onSettings,
                        contentDescription = "Settings",
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.DOWNLOADED_ARROW,
                        onClick = onDownloads,
                        contentDescription = "Downloads",
                    ),
                    onPlayer?.let {
                        LightBarButton.LightIcon(
                            icon = LightIcons.AUDIO_MESSAGE,
                            onClick = it,
                            contentDescription = "Now Playing",
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun BooksStatus(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Center,
            lighten = true,
        )
    }
}

@Composable
private fun DownloadsScreen(
    books: List<Audiobook>,
    editMode: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpenBook: (Audiobook) -> Unit,
    onRemoveBook: (Audiobook) -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
                center = LightTopBarCenter.Text("Downloads"),
                rightButton = LightBarButton.Text(
                    text = if (editMode) "DONE" else "EDIT",
                    onClick = onToggleEdit,
                ),
            )
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "There are no audiobooks downloaded",
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                    )
                }
            } else {
                LightLazyScrollView(
                    uniformItemHeightGridUnits = 7f,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = if (editMode) 0.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                            top = 0.75f.gridUnitsAsDp(),
                        ),
                ) {
                    items(books, key = { "download:${it.source}:${it.id}" }) { book ->
                        DownloadRow(
                            book = book,
                            editMode = editMode,
                            onOpen = { onOpenBook(book) },
                            onRemove = { onRemoveBook(book) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    book: Audiobook,
    editMode: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(7f.gridUnitsAsDp())
            .then(
                if (editMode) Modifier else Modifier.lightClickable(
                    onClickLabel = "Open ${book.title}",
                    role = Role.Button,
                    onClick = onOpen,
                ),
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editMode) {
            Box(
                modifier = Modifier
                    .size(4f.gridUnitsAsDp())
                    .lightClickable(
                        onClickLabel = "Remove ${book.title}",
                        role = Role.Button,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.CLOSE,
                    width = 1.5f,
                    height = 1.5f,
                    contentDescription = "Remove download",
                )
            }
            Spacer(Modifier.size(0.25f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = book.author.ifBlank { "Unknown Author" },
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val size = formatDownloadSize(book.fileSizeBytes)
            LightText(
                text = listOf("Local storage", size).filter { it.isNotBlank() }.joinToString(" · "),
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = true,
            )
        }
    }
}

private fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (bytes >= gibibyte) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / gibibyte)
    } else if (bytes < mebibyte) {
        "<1 MB"
    } else {
        "${(bytes / mebibyte).roundToInt()} MB"
    }
}

@Composable
private fun DownloadRemovalConfirmationScreen(
    message: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Downloads",
                ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = "Remove this audiobook file from the device?",
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        lighten = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("REMOVE", onClick = onConfirm),
                    LightBarButton.Text("CANCEL", onClick = onBack),
                ),
            )
        }
    }
}

@Composable
private fun BookRow(book: Audiobook, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Play ${book.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        LightText(
            text = book.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = book.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val progress = book.progressPercent.takeIf { it > 0 }?.let { "$it%" } ?: "Not started"
        val status = listOf(progress, "Local storage")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        LightText(
            text = status,
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

@Composable
private fun PlayerScreen(
    state: PlayerState,
    book: Audiobook?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onSeekTo: (positionMilliseconds: Long) -> Unit,
    onSeekToPart: (partIndex: Int) -> Unit,
    onSetSpeed: (Double) -> Unit,
    statusText: String,
    onBooks: () -> Unit,
) {
    var showChaptersPicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMilliseconds by remember { mutableStateOf<Long?>(null) }
    val title = book?.title?.takeIf { it.isNotBlank() }
        ?: state.title.takeIf { it.isNotBlank() }
        ?: "Audiobook"
    val partCount = book?.parts?.size ?: 0
    val hasChapters = partCount > 1
    val authoritativePositionSeconds = state.positionSeconds
    val liveProgress = if (state.durationSeconds > 0) {
        (authoritativePositionSeconds / state.durationSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val hasTiming = state.durationSeconds > 0
    val pendingProgress = pendingSeekMilliseconds?.let { pending ->
        if (hasTiming) (pending / (state.durationSeconds * 1000.0)).toFloat().coerceIn(0f, 1f)
        else null
    }
    val displayedProgress = scrubProgress ?: pendingProgress ?: liveProgress
    val displayedPositionSeconds = when {
        scrubProgress != null -> scrubProgress!!.toDouble() * state.durationSeconds
        pendingSeekMilliseconds != null -> pendingSeekMilliseconds!! / 1000.0
        else -> authoritativePositionSeconds
    }

    LaunchedEffect(state.positionSeconds, pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        val actualMilliseconds = (state.positionSeconds * 1000.0).roundToLong()
        if (kotlin.math.abs(actualMilliseconds - pending) <= 2_000L) {
            pendingSeekMilliseconds = null
        }
    }

    LaunchedEffect(pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        delay(3_000)
        if (pendingSeekMilliseconds == pending) pendingSeekMilliseconds = null
    }

    BardSurface {
        if (showChaptersPicker) {
            ChaptersPicker(
                parts = book?.parts.orEmpty(),
                currentPartIndex = state.currentPartIndex,
                onSelect = { partIndex ->
                    onSeekToPart(partIndex)
                    showChaptersPicker = false
                },
                onClose = { showChaptersPicker = false },
            )
            return@BardSurface
        }
        if (showSpeedPicker) {
            SpeedPicker(
                currentSpeed = state.playbackSpeed,
                onSelect = { speed ->
                    onSetSpeed(speed)
                    showSpeedPicker = false
                },
                onClose = { showSpeedPicker = false },
            )
            return@BardSurface
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBooks,
                    contentDescription = "Back to Books",
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                val author = book?.author.orEmpty()
                if (author.isNotBlank()) {
                    LightText(
                        text = author,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                }
                LightText(
                    text = title,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                if (hasChapters) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.5f.gridUnitsAsDp())
                            .lightClickable(
                                onClickLabel = "Open chapters",
                                role = Role.Button,
                                onClick = { showChaptersPicker = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = buildString {
                                append(
                                    "Chapter ${(state.currentPartIndex + 1).coerceIn(1, partCount)} of $partCount",
                                )
                                if (hasTiming) {
                                    append(" · ${formatPlaybackTime(state.durationSeconds)}")
                                }
                            },
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                } else {
                    LightText(
                        text = when {
                            hasTiming -> formatPlaybackTime(state.durationSeconds)
                            state.diagnostic == "This audiobook could not be played." -> state.diagnostic
                            else -> "--:--"
                        },
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                Log.d("TextSize", "DURATION(Detail) size=$size")
                            },
                    )
                    Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                }
                if (statusText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.25f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = statusText,
                            variant = LightTextVariant.Superfine,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            lighten = true,
                        )
                    }
                }

                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                PlaybackProgress(
                    progress = displayedProgress,
                    enabled = hasTiming && state.readiness == PlayerReadiness.Ready,
                    onScrub = { scrubProgress = it },
                    onScrubCancelled = { scrubProgress = null },
                    onSeek = { fraction ->
                        val targetMilliseconds =
                            (state.durationSeconds * 1000.0 * fraction).roundToLong()
                                .coerceIn(0L, (state.durationSeconds * 1000.0).roundToLong())
                        scrubProgress = null
                        pendingSeekMilliseconds = targetMilliseconds
                        onSeekTo(targetMilliseconds)
                    },
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconAction(
                        icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                        description = "Rewind 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onBack15,
                    )
                    PlayerIconAction(
                        icon = if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                        description = if (state.isPlaying) "Pause" else "Play",
                        iconWidth = 2.5f,
                        iconHeight = 2.5f,
                        touchSize = 6f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = {
                            if (state.isPlaying) onPause() else onPlay()
                        },
                    )
                    PlayerIconAction(
                        icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                        description = "Forward 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onForward15,
                    )
                }
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = if (hasTiming || authoritativePositionSeconds > 0) {
                        formatPlaybackTime(displayedPositionSeconds)
                    } else {
                        "--:--"
                    },
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            Log.d("TextSize", "POSITION(Fine) size=$size")
                        },
                )
                Spacer(Modifier.weight(1f))
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        "${trimSpeed(state.playbackSpeed)}×",
                        onClick = { showSpeedPicker = true },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubCancelled: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var trackWidthPixels by remember { mutableStateOf(0) }
    fun progressAt(offset: Offset): Float =
        if (trackWidthPixels > 0) (offset.x / trackWidthPixels).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .onSizeChanged { trackWidthPixels = it.width }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek(progressAt(offset)) }
            }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                var proposedProgress = progress
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        proposedProgress = progressAt(offset)
                        onScrub(proposedProgress)
                    },
                    onDragCancel = onScrubCancelled,
                    onDragEnd = { onSeek(proposedProgress) },
                ) { change, _ ->
                    proposedProgress = progressAt(change.position)
                    onScrub(proposedProgress)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .height(0.08f.gridUnitsAsDp())
                .fillMaxWidth()
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(0.35f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
    }
}

@Composable
private fun PlayerIconAction(
    icon: com.thelightphone.sdk.ui.LightIconConfiguration,
    description: String,
    iconWidth: Float = 2f,
    iconHeight: Float = 2f,
    touchSize: Float = 5f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(touchSize.gridUnitsAsDp())
            .then(
                if (enabled) {
                    Modifier.lightClickable(
                        onClickLabel = description,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            width = iconWidth,
            height = iconHeight,
            contentDescription = description,
        )
    }
}

@Composable
private fun ChaptersPicker(
    parts: List<AudiobookPart>,
    currentPartIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back to Player",
            ),
            center = LightTopBarCenter.Text("Chapters"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
        ) {
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            parts.forEachIndexed { index, part ->
                val isSelected = index == currentPartIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.5f.gridUnitsAsDp())
                        .semantics { selected = isSelected }
                        .lightClickable(
                            onClickLabel = "Play chapter ${index + 1}",
                            role = Role.RadioButton,
                        ) { onSelect(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = part.title.ifBlank { "Chapter ${index + 1}" },
                        variant = LightTextVariant.Paragraph,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (part.durationMilliseconds > 0) {
                        Spacer(Modifier.width(1f.gridUnitsAsDp()))
                        LightText(
                            text = formatPlaybackTime(part.durationMilliseconds / 1000.0),
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedPicker(
    currentSpeed: Double,
    onSelect: (Double) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back to Player",
            ),
            center = LightTopBarCenter.Text("Speed"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
        ) {
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            speedChoices.forEach { speed ->
                val isSelected = kotlin.math.abs(speed - currentSpeed) < 0.01
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.5f.gridUnitsAsDp())
                        .semantics { selected = isSelected }
                        .lightClickable(
                            onClickLabel = "Playback speed ${trimSpeed(speed)}×",
                            role = Role.RadioButton,
                        ) { onSelect(speed) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "${trimSpeed(speed)}×",
                        variant = LightTextVariant.Paragraph,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalBooksScreen(
    result: LocalScanResult?,
    scanning: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Local Books"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "Place your audiobook files directly into the Light Phone III/Audiobooks folder (create one if none exists).",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "MP3 and M4B files supported.",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                val status = if (scanning) {
                    "Scanning…"
                } else when (result) {
                    null -> ""
                    LocalScanResult.PermissionRequired -> "Audiobooks needs permission to read audio files."
                    LocalScanResult.FolderMissing -> "No Audiobooks folder found."
                    LocalScanResult.Empty -> "No audiobook files found."
                    is LocalScanResult.Success -> "${result.books.size} books found"
                }
                if (status.isNotBlank()) {
                    LightText(status, variant = LightTextVariant.Detail, lighten = true)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                }
                SettingsRow(
                    title = if (result == null) "Scan for Books" else "Scan Again",
                    onClick = if (scanning) ({}) else onScan,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLocalBooks: () -> Unit,
    onOpenVersion: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Settings"),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Local Books", onClick = onOpenLocalBooks)
                SettingsRow(title = "Version", onClick = onOpenVersion)
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Open $title settings",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VersionScreen(onBack: () -> Unit) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Version"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Local audiobooks stay on your device and play entirely offline.",
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = true,
                )
            }
        }
    }
}

private fun formatPlaybackTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    return if (total >= 3600) {
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val remainingSeconds = total % 60
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        val minutes = total / 60
        val remainingSeconds = total % 60
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

private fun PersistedActiveAudiobook.toAudiobook(): Audiobook = Audiobook(
    id = id,
    source = source,
    title = title.ifBlank { progress.title },
    author = author.ifBlank { progress.author },
    playbackReference = playbackReference.ifBlank { progress.playbackReference },
    durationMilliseconds = progress.durationMilliseconds,
    positionMilliseconds = progress.positionMilliseconds,
    playbackSpeed = progress.playbackSpeed,
    completed = progress.completed,
    lastPlayedAtMilliseconds = progress.lastPlayedAtMilliseconds,
    lastUpdatedAtMilliseconds = progress.lastUpdatedAtMilliseconds,
    progressPercentOverride = progress.progressPercentOverride,
    dueText = progress.dueText,
)

private val speedChoices = listOf(1.0, 1.25, 1.5, 1.75, 2.0)

private fun trimSpeed(speed: Double): String =
    if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString()
