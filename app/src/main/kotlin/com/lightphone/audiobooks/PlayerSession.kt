package com.lightphone.audiobooks

import com.lightphone.audiobooks.screens.LibraryScreen
import com.lightphone.audiobooks.screens.PlayerScreen
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod

/**
 * Process-level record of what the tool's detached player is doing, surviving
 * Player-screen teardown (the screen's player handle is released when the user
 * leaves, but playback continues in the SDK's service).
 *
 * Died with the process? Then playback died too — detached audio lives in the
 * tool's process. Nothing here is authoritative; it only lets the screens know
 * what the last Player session was doing.
 */
object PlayerSession {
    /** The book whose queue is loaded on the detached player, or null when idle. */
    var loadedBookId: String? = null

    /** Last known play state, kept fresh by the Player screen while it is open. */
    var isPlaying: Boolean = false

    /** Set when the app is backgrounded while a book is playing: the next time
     *  the app is in the foreground, the screens return to the live Player
     *  (see [settleReopenToPlayer]). Cleared once the live Player is on top. */
    var reopenPending: Boolean = false
}

/**
 * Called from every screen's [com.thelightphone.sdk.LightViewModel.onScreenShow]
 * to return to the live Player after the app came back from the background
 * while a book was playing. Each screen applies one step; the cascade settles
 * on the live Player with exactly one Player screen on the stack:
 * - the playing book's Player settles the reopen (clears [PlayerSession.reopenPending]);
 * - a Player showing any other book pops itself (a stale preview);
 * - sub-screens (Settings, pickers) pop toward the Player underneath them;
 * - the Library (stack bottom) pushes the playing book's Player.
 *
 * @return true when this screen was popped or navigated away from — the caller
 *   must stop any further work (its view model is being destroyed).
 */
suspend fun settleReopenToPlayer(
    screen: SimpleLightScreen<*>,
    book: LightServiceMethod.GetBooks.Book? = null,
): Boolean {
    if (!PlayerSession.reopenPending) return false
    if (book != null && book.id == PlayerSession.loadedBookId) {
        // The live book's Player settles the reopen whether it is playing or
        // paused — a paused book's Player popped forever here, and with
        // reopenPending never cleared every later screen-show popped again
        // (the 2026-09-14 LP3 "stuck screen, taps dead" wedge).
        PlayerSession.reopenPending = false
        return false
    }
    if (book == null && screen is LibraryScreen) {
        val playingBookId = PlayerSession.loadedBookId ?: run {
            PlayerSession.reopenPending = false
            return false
        }
        if (!PlayerSession.isPlaying) {
            // Playback stopped in the background: nothing to return to. Consume
            // the flag — leaving it set pops every screen forever.
            PlayerSession.reopenPending = false
            return false
        }
        val playingBook = MediaClient.getBooks().firstOrNull { it.id == playingBookId } ?: run {
            PlayerSession.reopenPending = false
            return false
        }
        // The pushed Player's own show settles the flag.
        screen.navigateTo(screenFactory = { activity -> PlayerScreen(activity, playingBook) })
        return true
    }
    screen.goBack()
    return true
}
