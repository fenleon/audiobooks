package com.stan.libbylight

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

    suspend fun play(
        bookId: String,
        partIndex: Int = 0,
        positionMs: Long = 0,
    ): Boolean = callRemoteServiceMethod(
        LightServiceMethod.PlayBook,
        LightServiceMethod.PlayBook.Request(bookId, partIndex, positionMs),
    ) is LightResult.Success

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
