package com.lightphone.audiobooks.server

import android.app.Activity
import android.app.PendingIntent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the system media-delete consent dialog. Android 11+ requires it to
 * delete audiobook files the companion doesn't own (files placed via MTP/adb
 * belong to the media store, not to the app). The dialog must be launched from
 * an activity via startActivityForResult — the companion is a background
 * service and can't start activities, so the tool (the foreground process)
 * starts this activity via `SimpleLightScreen.startServerActivity` after a
 * `DeleteBook` call reports `consentPending`.
 *
 * Once the user consents, the delete access grant persists for the companion's
 * uid, so the actual deletion runs here and the library rescans.
 */
class DeleteConsentActivity : ComponentActivity() {

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val bookId = pendingBookId
        pendingBookId = null
        if (result.resultCode == Activity.RESULT_OK && bookId != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { MediaServiceMethods.completeDelete(bookId) }
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = takePendingConsent() ?: run {
            finish()
            return
        }
        pendingBookId = pending.first
        deleteLauncher.launch(IntentSenderRequest.Builder(pending.second).build())
    }

    private var pendingBookId: String? = null

    companion object {
        private var pendingConsent: Pair<String, PendingIntent>? = null

        /** Registers the consent request awaiting [DeleteConsentActivity] (one at a time). */
        fun register(bookId: String, request: PendingIntent) {
            pendingConsent = bookId to request
        }

        private fun takePendingConsent(): Pair<String, PendingIntent>? =
            pendingConsent.also { pendingConsent = null }
    }
}
