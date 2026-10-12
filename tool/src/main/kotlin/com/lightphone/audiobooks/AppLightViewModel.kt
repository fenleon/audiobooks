package com.lightphone.audiobooks

import androidx.lifecycle.viewModelScope
import com.lightphone.audiobooks.library.Audiobook
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/**
 * Base view model for every Audiobooks screen. One definition of the shared
 * behavior:
 * **reopen-to-Player** — the SDK only offers per-screen lifecycle hooks
 * ([LightViewModel.onScreenShow] fires on the top screen), so the trigger must
 * run per screen — but the logic lives here once ([settleReopenToPlayer]).
 *
 * Non-Player screens settle the reopen automatically. The Player screen
 * settles itself (it must stop its own setup when it gets popped), so it
 * overrides [reopenBook] and skips the base settle via [handlesReopenItself].
 */
abstract class AppLightViewModel<T> : LightViewModel<T>() {

    /** The book this screen's Player is bound to (Player screen only). */
    open val reopenBook: Audiobook? = null

    private val handlesReopenItself: Boolean get() = reopenBook != null

    override fun onScreenShow(screen: SimpleLightScreen<T>) {
        super.onScreenShow(screen)
        if (handlesReopenItself) return
        viewModelScope.launch {
            if (settleReopenToPlayer(screen)) return@launch
        }
    }

    override fun onAppPause() {
        if (PlayerSession.isPlaying) PlayerSession.reopenPending = true
        super.onAppPause()
    }
}
