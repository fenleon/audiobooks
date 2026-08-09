package com.lightphone.audiobooks

import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull

/**
 * Thin RPC client for the Audiobooks media methods. Everything privileged
 * (scan, playback, media session) lives in the companion (:server); the tool
 * only renders state fetched over the SDK binder.
 */
object MediaClient {

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

    suspend fun play(
        bookId: String,
        partIndex: Int = 0,
        positionMs: Long = 0,
    ): Boolean = callRemoteServiceMethod(
        LightServiceMethod.PlayBook,
        LightServiceMethod.PlayBook.Request(bookId, partIndex, positionMs),
    ) is LightResult.Success

    /** Loads a book paused at its saved position; playback starts only on an explicit play. */
    suspend fun open(
        bookId: String,
        partIndex: Int = 0,
        positionMs: Long = 0,
    ): Boolean = callRemoteServiceMethod(
        LightServiceMethod.OpenBook,
        LightServiceMethod.OpenBook.Request(bookId, partIndex, positionMs),
    ) is LightResult.Success

    /** Jumps to a chapter on the loaded book, preserving the play/pause state. */
    suspend fun seekToPart(partIndex: Int) {
        callRemoteServiceMethod(
            LightServiceMethod.SeekToPart,
            LightServiceMethod.SeekToPart.Request(partIndex),
        )
    }

    suspend fun autoPlayNext(): Boolean? =
        callRemoteServiceMethod(LightServiceMethod.GetAutoPlayNext, Unit)
            .getOrNull()?.enabled

    suspend fun setAutoPlayNext(enabled: Boolean) {
        callRemoteServiceMethod(
            LightServiceMethod.SetAutoPlayNext,
            LightServiceMethod.SetAutoPlayNext.Request(enabled),
        )
    }

    suspend fun pause() {
        callRemoteServiceMethod(LightServiceMethod.PausePlayback, Unit)
    }

    suspend fun seekTo(positionMs: Long) {
        callRemoteServiceMethod(
            LightServiceMethod.SeekTo,
            LightServiceMethod.SeekTo.Request(positionMs),
        )
    }

    suspend fun setSpeed(speed: Float) {
        callRemoteServiceMethod(
            LightServiceMethod.SetPlaybackSpeed,
            LightServiceMethod.SetPlaybackSpeed.Request(speed),
        )
    }

    suspend fun playbackState(): LightServiceMethod.GetPlaybackState.Response? =
        callRemoteServiceMethod(LightServiceMethod.GetPlaybackState, Unit).getOrNull()
}
