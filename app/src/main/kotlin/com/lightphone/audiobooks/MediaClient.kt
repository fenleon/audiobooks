package com.lightphone.audiobooks

import android.net.Uri
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull

/**
 * Thin RPC client for the Audiobooks media methods. Since the 2026-08-18
 * single-module merge the methods land on the tool APK's own LightSdkService
 * (serverPackage = self); the scan, stores, and the media file provider live
 * in-process via the merged :server library. Playback runs in the tool itself
 * (SDK detached audio), so the transport surface is the library + settings +
 * progress reporting.
 */
object MediaClient {

    /** The media provider authority — the tool's player reads the library
     *  files through it (the tool runtime forbids storage access). Since the
     *  merge the provider lives in the same APK. */
    private const val AUDIOBOOK_MEDIA_AUTHORITY = "content://com.lightphone.audiobooks.server.media"

    /**
     * Maps a MediaStore URI (as served in `GetBooks`) to the companion
     * provider's equivalent, which the tool's process can open.
     */
    fun proxyUri(playbackReference: String): String {
        val mediaId = Uri.parse(playbackReference).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: return playbackReference
        return "$AUDIOBOOK_MEDIA_AUTHORITY/media/$mediaId"
    }

    suspend fun getBooks(): List<LightServiceMethod.GetBooks.Book> =
        callRemoteServiceMethod(LightServiceMethod.GetBooks, Unit)
            .getOrNull()?.books.orEmpty()

    suspend fun scanLibrary(): List<LightServiceMethod.GetBooks.Book> =
        callRemoteServiceMethod(LightServiceMethod.ScanLibrary, Unit)
            .getOrNull()?.books.orEmpty()

    /** Deletes a book's files. On Android 11+ the companion may need the system
     *  consent dialog; [LightServiceMethod.DeleteBook.Response.consentPending]
     *  reports that the dialog was shown and the deletion is in flight. */
    suspend fun deleteBook(bookId: String): LightServiceMethod.DeleteBook.Response? =
        callRemoteServiceMethod(
            LightServiceMethod.DeleteBook,
            LightServiceMethod.DeleteBook.Request(bookId),
        ).getOrNull()

    suspend fun autoPlayNext(): Boolean? =
        callRemoteServiceMethod(LightServiceMethod.GetAutoPlayNext, Unit)
            .getOrNull()?.enabled

    suspend fun setAutoPlayNext(enabled: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetAutoPlayNext,
            LightServiceMethod.SetAutoPlayNext.Request(enabled),
        )
    }

    suspend fun playbackSpeed(): Float? =
        callRemoteServiceMethod(LightServiceMethod.GetPlaybackSpeed, Unit)
            .getOrNull()?.speed

    suspend fun setSpeed(speed: Float) {
        callRemoteServiceMethod(
            LightServiceMethod.SetPlaybackSpeed,
            LightServiceMethod.SetPlaybackSpeed.Request(speed),
        )
    }

    /** Whether a Bluetooth audio device is connected (Library's connected-BT icon). */
    suspend fun bluetoothConnected(): Boolean? =
        callRemoteServiceMethod(LightServiceMethod.GetBluetoothConnected, Unit)
            .getOrNull()?.connected

    /**
     * Long-polls until the media-stream volume changes (or the server's
     * timeout) — the volume panel's instant read for a BT device's own volume
     * buttons, with no polling cadence.
     */
    suspend fun waitForVolumeChange(knownLevel: Int): LightServiceMethod.WaitForVolumeChange.Response? =
        callRemoteServiceMethod(
            LightServiceMethod.WaitForVolumeChange,
            LightServiceMethod.WaitForVolumeChange.Request(knownLevel),
        ).getOrNull()

    /** The current media-stream volume (level of max) — the volume panel's read. */
    suspend fun volumeLevel(): LightServiceMethod.GetVolumeLevel.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetVolumeLevel, Unit).getOrNull()

    /** Reports a listening position so the companion can persist it. */
    suspend fun saveProgress(
        bookId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ) {
        callRemoteServiceMethod(
            LightServiceMethod.SaveProgress,
            LightServiceMethod.SaveProgress.Request(bookId, positionMs, durationMs, speed),
        )
    }
}
