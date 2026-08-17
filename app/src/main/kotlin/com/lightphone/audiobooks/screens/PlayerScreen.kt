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
import com.lightphone.audiobooks.AppLightViewModel
import com.lightphone.audiobooks.MediaClient
import com.lightphone.audiobooks.PlayerSession
import com.lightphone.audiobooks.chapterIndexAt
import com.lightphone.audiobooks.chapterLabel
import com.lightphone.audiobooks.embeddedChapters
import com.lightphone.audiobooks.formatSpeed
import com.lightphone.audiobooks.formatTime
import com.lightphone.audiobooks.partStartMs
import com.lightphone.audiobooks.settleReopenToPlayer
import com.lightphone.audiobooks.VolumePanelOverlay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the player screen renders, live or preview. */
data class PlayerUiState(
    /** Whether the player is showing this book's live playback (vs a static preview). */
    val live: Boolean,
    val title: String,
    val author: String?,
    val partIndex: Int,
    val partCount: Int,
    /** Position on the book's global timeline. */
    val positionMs: Long,
    /** The book's total duration (metadata, refreshed from saved progress). */
    val durationMs: Long,
    val playing: Boolean,
    val speed: Float,
    val error: String?,
)

class PlayerViewModel(
    private val book: LightServiceMethod.GetBooks.Book,
    private val audio: LightAudio,
) : AppLightViewModel<Unit>() {

    /** This screen is the (possibly stale) Player for [book] — settles the
     *  reopen itself in onScreenShow instead of letting the base do it. */
    override val reopenBook: LightServiceMethod.GetBooks.Book? = book

    private val _ui = MutableStateFlow(
        PlayerUiState(
            live = false,
            title = book.title,
            author = book.author.takeIf { it.isNotBlank() },
            partIndex = savedPartIndex(book),
            partCount = book.partCount,
            positionMs = book.progressMs,
            durationMs = book.durationMs,
            playing = false,
            speed = 1f,
            error = null,
        ),
    )
    val ui = _ui.asStateFlow()

    /** True once this book has been played at least once in this player
     *  session; the skip controls stay visible from then on, even when paused. */
    val hasPlayed = MutableStateFlow(false)

    private var playerHandle: LightAudioPlayer? = null
    private var observing = false
    private var autoPlayNext = true
    private var lastSavedAt = 0L
    private var lastPartIndex = -1
    private var lastChapterPartIndex = -1
    private var lastChapterIndex = -1
    private var pendingSeekMs: Long? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            // Reopen-to-Player cascade: settle if this is the playing book's
            // Player, pop if it is a stale preview of another book.
            if (settleReopenToPlayer(screen, book)) return@launch
            // Global settings come from the companion; the player applies them.
            autoPlayNext = MediaClient.autoPlayNext() ?: true
            val speed = MediaClient.playbackSpeed() ?: 1f
            _ui.update { it.copy(speed = speed) }
            val p = player() ?: return@launch
            p.speed = speed
            if (PlayerSession.loadedBookId == book.id && p.currentMediaItemIndex.value >= 0) {
                // Reopening the tool while this book is loaded on the detached
                // player reconnects and reuses the live queue — never re-queue.
                adoptLive(p)
            } else if (p.currentMediaItemIndex.value < 0) {
                // The detached service died since (idle stop or process death);
                // the queue it held is gone.
                PlayerSession.loadedBookId = null
            }
        }
    }

    /** The detached handle for this screen, created on first need and released
     *  when the screen is popped (releasing does not stop detached playback). */
    private suspend fun player(): LightAudioPlayer? {
        playerHandle?.let { return if (it.awaitReady()) it else null }
        val p = runCatching {
            audio.newPlayer(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached)
        }.getOrNull() ?: return null
        playerHandle = p
        return if (p.awaitReady()) p else null
    }

    private fun adoptLive(p: LightAudioPlayer) {
        _ui.update { it.copy(live = true) }
        if (p.isPlaying.value) hasPlayed.value = true
        PlayerSession.isPlaying = p.isPlaying.value
        observe(p)
        refreshState(p)
    }

    /** Wires the SDK player's flows to this screen's state. Idempotent. */
    private fun observe(p: LightAudioPlayer) {
        if (observing) return
        observing = true
        viewModelScope.launch {
            p.isPlaying.collect { playing ->
                _ui.update { it.copy(playing = playing) }
                PlayerSession.isPlaying = playing
                if (!playing && _ui.value.live) saveProgress()
            }
        }
        viewModelScope.launch {
            p.currentMediaItemIndex.collect { index ->
                // With Auto-Play off, a queue advance is a chapter boundary:
                // pause at the new part's start instead of flowing into it.
                if (index > lastPartIndex && lastPartIndex >= 0 &&
                    _ui.value.playing && !autoPlayNext
                ) {
                    p.pause()
                }
                lastPartIndex = index
                refreshState(p)
            }
        }
        viewModelScope.launch {
            p.durationMs.collect { duration ->
                // A pending seek applies once its target item's duration
                // resolves (LightAudioPlayer clamps seeks to a known duration).
                if (duration > 0) {
                    val pending = pendingSeekMs
                    if (pending != null) {
                        pendingSeekMs = null
                        p.seekTo(pending)
                        refreshState(p)
                    }
                }
            }
        }
        viewModelScope.launch {
            p.positionMs.collect { refreshState(p) }
        }
        viewModelScope.launch {
            p.error.collect { error ->
                _ui.update { it.copy(error = error?.let { e -> "${e.kind}: ${e.diagnostic}" }) }
            }
        }
        // Time-based work only while playing: embedded-chapter boundary pauses
        // (Auto-Play off) and progress persistence.
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (p.isPlaying.value && _ui.value.live) {
                    checkChapterBoundary(p)
                    if (System.currentTimeMillis() - lastSavedAt >= SAVE_INTERVAL_MS) {
                        saveProgress()
                    }
                }
            }
        }
    }

    private fun refreshState(p: LightAudioPlayer) {
        if (!_ui.value.live) return
        val index = p.currentMediaItemIndex.value.coerceAtLeast(0)
        _ui.update {
            it.copy(
                partIndex = index,
                positionMs = partStartMs(book, index) + p.positionMs.value.coerceAtLeast(0),
                playing = p.isPlaying.value,
            )
        }
    }

    /** Loads this book on the player (replacing whatever was loaded). */
    private suspend fun queueBook(
        p: LightAudioPlayer,
        partIndex: Int = 0,
        positionMs: Long? = null,
        autoPlay: Boolean,
    ) {
        val references = book.parts.map { it.playbackReference }.filter { it.isNotBlank() }
            .ifEmpty { listOf(book.playbackReference) }
        if (references.isEmpty()) return
        val items = references.map { reference ->
            LightAudioItem(
                source = LightAudioSource.UrlSource(MediaClient.proxyUri(reference)),
                metadata = LightMediaMetadata(
                    title = book.title,
                    artist = book.author.takeIf { it.isNotBlank() },
                ),
            )
        }
        // An ended book restarts from the beginning, like the old player did:
        // queueing at the saved end position would play a fraction of a second
        // and stop. Only the explicit play path restarts — opening paused at a
        // chapter keeps the exact position.
        val target = if (autoPlay && book.durationMs > 0 &&
            (positionMs ?: book.progressMs) >= book.durationMs - END_EPSILON_MS
        ) {
            0L
        } else {
            (positionMs ?: book.progressMs).coerceAtLeast(0)
        }
        val startIndex = locatePart(target).coerceIn(0, items.lastIndex)
        val within = target - partStartMs(book, startIndex)
        pendingSeekMs = null
        p.setMediaQueue(items, startIndex)
        p.speed = _ui.value.speed
        PlayerSession.loadedBookId = book.id
        lastPartIndex = startIndex
        lastChapterPartIndex = startIndex
        lastChapterIndex = chapterIndexAt(chaptersFor(startIndex), within)
        _ui.update {
            it.copy(
                live = true,
                partIndex = startIndex,
                positionMs = target,
                durationMs = book.durationMs,
            )
        }
        adoptLive(p)
        pendingSeekMs = within
        applyPendingSeek(p)
        if (autoPlay) p.play()
    }

    fun togglePlay() {
        viewModelScope.launch {
            val p = player() ?: return@launch
            hasPlayed.value = true
            if (_ui.value.live && _ui.value.playing) {
                p.pause()
            } else if (_ui.value.live) {
                maybeRestartEnded(p)
                p.play()
            } else {
                // In preview the play button means "play this book" even if
                // another book is currently playing — never pause the other
                // book from here; queueing this one replaces it.
                queueBook(p, autoPlay = true)
            }
        }
    }

    fun seekBy(deltaMilliseconds: Long) {
        viewModelScope.launch {
            val p = player() ?: return@launch
            if (!_ui.value.live) return@launch
            seekToGlobal(p, _ui.value.positionMs + deltaMilliseconds)
        }
    }

    fun seekToFraction(fraction: Float) {
        viewModelScope.launch {
            val p = player() ?: return@launch
            if (!_ui.value.live) return@launch
            val chapter = chapterTime(book, _ui.value)
            val start = chapter?.startMs ?: 0L
            val duration = chapter?.durationMs ?: _ui.value.durationMs
            if (duration <= 0) return@launch
            seekToGlobal(p, start + (duration * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    /** Seeks on the book's global timeline, crossing part boundaries. */
    private fun seekToGlobal(p: LightAudioPlayer, targetMs: Long) {
        val durations = book.parts.map { it.durationMs.coerceAtLeast(0) }
        val total = durations.takeIf { it.isNotEmpty() }?.sum() ?: book.durationMs
        val target = targetMs.coerceIn(0, total.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val part = locatePart(target)
        val current = p.currentMediaItemIndex.value.coerceAtLeast(0)
        val within = target - partStartMs(book, part)
        if (part != current) {
            repeat((part - current).coerceAtLeast(0)) { p.skipToNext() }
            repeat((current - part).coerceAtLeast(0)) { p.skipToPrevious() }
            // With Auto-Play off, crossing into another chapter pauses there —
            // a manual skip past a chapter end behaves like a natural chapter
            // boundary instead of flowing into the next chapter.
            if (!autoPlayNext) p.pause()
        }
        lastPartIndex = part
        lastChapterPartIndex = part
        lastChapterIndex = chapterIndexAt(chaptersFor(part), within)
        pendingSeekMs = within
        applyPendingSeek(p)
        _ui.update { it.copy(partIndex = part, positionMs = target) }
        // Persist the seek target itself: the target item's duration is still
        // resolving right after a skip, so a quick exit must not resume wrong.
        saveProgress()
    }

    private fun applyPendingSeek(p: LightAudioPlayer) {
        val pending = pendingSeekMs ?: return
        if (p.durationMs.value > 0) {
            pendingSeekMs = null
            p.seekTo(pending)
            refreshState(p)
        }
    }

    /** Jumps to a chapter on this book. From a preview (book not loaded) this
     *  loads the book paused at the chapter — never touching whatever else is
     *  playing; once live it seeks, preserving the play/pause state. */
    fun jumpToChapter(index: Int) {
        viewModelScope.launch {
            val p = player() ?: return@launch
            val chapters = embeddedChapters(book)
            if (!_ui.value.live) {
                if (chapters.isNotEmpty()) {
                    queueBook(p, positionMs = chapters[index].startMs, autoPlay = false)
                } else {
                    queueBook(p, partIndex = index, autoPlay = false)
                }
            } else if (chapters.isNotEmpty()) {
                seekToGlobal(p, chapters[index].startMs)
            } else {
                seekToGlobal(p, partStartMs(book, index))
            }
        }
    }

    fun setSpeed(value: Float) {
        _ui.update { it.copy(speed = value) }
        playerHandle?.speed = value
        viewModelScope.launch { MediaClient.setSpeed(value) }
    }

    private fun maybeRestartEnded(p: LightAudioPlayer) {
        // An ended book restarts from the beginning, like the old player did.
        if (!p.isPlaying.value && p.durationMs.value > 0 &&
            p.currentMediaItemIndex.value == book.partCount - 1 &&
            p.positionMs.value >= p.durationMs.value - END_EPSILON_MS
        ) {
            seekToGlobal(p, 0L)
        }
    }

    private fun saveProgress() {
        if (!_ui.value.live) return
        lastSavedAt = System.currentTimeMillis()
        val state = _ui.value
        viewModelScope.launch {
            MediaClient.saveProgress(book.id, state.positionMs, state.durationMs, state.speed)
        }
    }

    /** Index of the part containing [positionMs] on the global timeline (part 0 for single-file books). */
    private fun locatePart(positionMs: Long): Int {
        val durations = book.parts.map { it.durationMs.coerceAtLeast(0) }
        if (durations.isEmpty()) return 0
        var remaining = positionMs.coerceAtLeast(0)
        durations.forEachIndexed { index, duration ->
            if (remaining < duration) return index
            remaining -= duration
        }
        return durations.lastIndex
    }

    /** Embedded chapters of the given part (single-file books with embedded chapters). */
    private fun chaptersFor(index: Int): List<LightServiceMethod.GetBooks.Chapter> =
        book.parts.getOrNull(index)?.chapters.orEmpty()

    private fun checkChapterBoundary(p: LightAudioPlayer) {
        if (autoPlayNext) return
        val index = p.currentMediaItemIndex.value.coerceAtLeast(0)
        if (index != lastChapterPartIndex) {
            lastChapterPartIndex = index
            lastChapterIndex = chapterIndexAt(chaptersFor(index), p.positionMs.value)
        }
        val chapters = chaptersFor(index)
        if (chapters.isNotEmpty() && lastChapterIndex >= 0) {
            val current = chapterIndexAt(chapters, p.positionMs.value)
            if (current > lastChapterIndex) p.pause()
            lastChapterIndex = current
        }
    }

    override fun onCleared() {
        // Releasing the handle only disconnects: detached playback continues in
        // the SDK's service, and a later screen reconnects to the live queue.
        playerHandle?.release()
        playerHandle = null
        super.onCleared()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 7_000L
        const val END_EPSILON_MS = 1_500L
    }
}

class PlayerScreen(
    private val sealedActivity: SealedLightActivity,
    private val book: LightServiceMethod.GetBooks.Book,
) : LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    override val viewModelClass: Class<PlayerViewModel>
        get() = PlayerViewModel::class.java

    override fun createViewModel(): PlayerViewModel = PlayerViewModel(
        book = book,
        audio = DefaultLightAudio(sealedActivity),
    )

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val hasPlayed by viewModel.hasPlayed.collectAsState()
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
                    )
                    Box(modifier = Modifier.weight(1f)) {
                    val embeddedIndex = if (chapters.isNotEmpty()) {
                        chapterIndexAt(
                            chapters,
                            (ui.positionMs - partStartMs(book, ui.partIndex.coerceAtLeast(0))).coerceAtLeast(0),
                        )
                    } else {
                        ui.partIndex
                    }
                    PlayerContent(
                        state = ui,
                        chapter = chapterTime(book, ui),
                        showSkips = hasPlayed,
                        showChapter = ui.partCount > 1 || chapters.size > 1,
                        chapterIndex = embeddedIndex,
                        chapterCount = if (chapters.isNotEmpty()) chapters.size else ui.partCount,
                        themeColors = themeColors,
                        onBack15 = { viewModel.seekBy(-15_000) },
                        onForward15 = { viewModel.seekBy(15_000) },
                        onTogglePlay = { viewModel.togglePlay() },
                        // Tapping the chapter label opens the same chapters list
                        // as the bottom-bar button.
                        onOpenChapters = { openChapters() },
                        // Seeking a book that isn't loaded would seek whatever
                        // is playing — preview shows the saved position only.
                        onSeekFraction = if (ui.live) viewModel::seekToFraction else { _ -> },
                    )
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { openSettings() },
                            contentDescription = "Settings",
                        ),
                        // Speed is a global setting, so it always shows the
                        // current value and works even while this book is only
                        // a preview — it never needs the book loaded.
                        LightBarButton.Text(
                            text = formatSpeed(ui.speed),
                            onClick = { openSpeedPicker() },
                        ),
                        // Chapters view from the book's own data; a tap from a
                        // preview loads this book paused at the chosen chapter
                        // instead of seeking whatever is playing.
                        if (book.partCount > 1 || chapters.size > 1) LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = { openChapters() },
                            contentDescription = "Chapters",
                        ) else null,
                    ),
                )
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
            viewModel.jumpToChapter(index)
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState,
    chapter: ChapterTime?,
    showSkips: Boolean,
    showChapter: Boolean,
    chapterIndex: Int,
    chapterCount: Int,
    themeColors: com.thelightphone.sdk.ui.LightColors,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenChapters: () -> Unit,
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
            text = state.title,
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
                    .lightClickable(onClick = onOpenChapters)
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

        state.error?.let { error ->
            LightText(
                text = error,
                variant = LightTextVariant.Fine,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
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

/** The part the book's saved position falls in (part 0 for single-file books). */
private fun savedPartIndex(book: LightServiceMethod.GetBooks.Book): Int =
    book.parts.indices.lastOrNull { partStartMs(book, it) <= book.progressMs } ?: 0

/**
 * Maps the book-global playback state onto the current chapter, so the player
 * shows chapter-scoped time and progress. Embedded chapters (single-file
 * books) scope to the chapter containing the position; folder books scope to
 * the current part. Returns null (full-book values) when the book has no part
 * durations — e.g. single-file books without embedded chapters.
 */
private fun chapterTime(
    book: LightServiceMethod.GetBooks.Book,
    state: PlayerUiState,
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
