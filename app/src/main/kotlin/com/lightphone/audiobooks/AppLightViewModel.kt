package com.lightphone.audiobooks

import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The tool's last-known media volume — seeded from the server and updated on
 *  every rocker press, so the volume panel bar moves instantly (the server's
 *  adjust lands a binder round-trip later). */
object MediaVolumeState {
    var level: Int? = null
    var max: Int = 0
}

/**
 * Base view model for every Audiobooks screen. One definition of the shared
 * behaviors:
 * - **reopen-to-Player**: the SDK only offers per-screen lifecycle hooks
 *   ([LightViewModel.onScreenShow] fires on the top screen), so the trigger
 *   must run per screen — but the logic lives here once ([settleReopenToPlayer]);
 * - **volume panel**: the SDK delivers the LP3's volume rocker to the top
 *   screen's view model first ([LightKeyHandler]); show the in-app volume
 *   panel instantly (level computed locally from [MediaVolumeState] — no
 *   binder round-trip), then let the key fall through to the companion, which
 *   adjusts the media stream ([VolumePanelOverlay]).
 *
 * Non-Player screens settle the reopen automatically. The Player screen
 * settles itself (it must stop its own setup when it gets popped), so it
 * overrides [reopenBook] and skips the base settle via [handlesReopenItself].
 */
abstract class AppLightViewModel<T> : LightViewModel<T>() {

    /** The book this screen's Player is bound to (Player screen only). */
    open val reopenBook: LightServiceMethod.GetBooks.Book? = null

    private val handlesReopenItself: Boolean get() = reopenBook != null

    /** The volume panel's state (null = hidden). Hosted by every screen's root. */
    val volumePanel = MutableStateFlow<VolumePanelState?>(null)

    fun dismissVolumePanel() {
        volumePanel.value = null
    }

    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        super.onScreenShow(screen)
        // Keep the volume cache fresh (cheap; lets the panel bar move without
        // a round-trip on the next press).
        refreshVolumeLevel()
        if (handlesReopenItself) return
        viewModelScope.launch {
            if (settleReopenToPlayer(screen)) return@launch
        }
    }

    override fun onAppPause() {
        if (PlayerSession.isPlaying) PlayerSession.reopenPending = true
        super.onAppPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            if (MediaVolumeState.level == null) {
                // Cold start: seed the cache first, then show the new level.
                viewModelScope.launch {
                    MediaClient.volumeLevel()?.let { level ->
                        MediaVolumeState.level = level.level
                        MediaVolumeState.max = level.max
                        showVolumePanel(keyCode)
                    }
                }
            } else {
                showVolumePanel(keyCode)
            }
            // Not handled here: the SDK forwards the rocker to the companion,
            // which adjusts the media stream (one step per press — repeats are
            // filtered above, and the server ignores them too).
            return false
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showVolumePanel(keyCode: Int) {
        val current = MediaVolumeState.level ?: return
        val newLevel = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> (current + 1).coerceAtMost(MediaVolumeState.max.coerceAtLeast(1))
            else -> (current - 1).coerceAtLeast(0)
        }
        MediaVolumeState.level = newLevel
        volumePanel.value = VolumePanelState.Media(newLevel, MediaVolumeState.max)
    }

    private fun refreshVolumeLevel() {
        viewModelScope.launch {
            MediaClient.volumeLevel()?.let { level ->
                MediaVolumeState.level = level.level
                MediaVolumeState.max = level.max
            }
        }
    }
}
