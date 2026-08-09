package com.stan.libbylight.server

import android.os.Handler
import android.os.Looper
import com.stan.libbylight.server.library.AudiobookProgressStore
import com.stan.libbylight.server.library.LocalBookRepository
import com.stan.libbylight.server.player.LocalPlaybackController
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Implements the Audiobooks media methods on the companion's LightSdkService.
 * These are the server-side half of the tool model: the tool is a thin UI that
 * calls these over the SDK binder; everything privileged lives here.
 *
 * The resolver runs on a binder thread; playback commands must run on the main
 * thread (ExoPlayer requires it), so every call is marshalled through
 * [onMain].
 */
object MediaServiceMethods {

    private lateinit var scope: CoroutineScope
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(applicationScope: CoroutineScope) {
        scope = applicationScope
    }

    fun dispatch(methodId: String, payload: String?): LightResult<String> = onMain {
        when (methodId) {
            LightServiceMethod.GetBooks.id -> booksResult()

            LightServiceMethod.ScanLibrary.id -> {
                scope.launch { LocalBookRepository.scan() }
                booksResult()
            }

            LightServiceMethod.PlayBook.id -> {
                val request = LightServiceMethod.PlayBook.decodeRequest(payload!!)
                val book = LocalBookRepository.books.value.firstOrNull { it.id == request.bookId }
                    ?: return@onMain LightResult.Error(
                        LightResult.ErrorCode.Unknown,
                        "book not found: ${request.bookId}",
                    )
                LocalPlaybackController.open(book, autoPlay = true)
                if (request.partIndex > 0) {
                    LocalPlaybackController.seekToPart(request.partIndex)
                }
                if (request.positionMs > 0) {
                    LocalPlaybackController.seekTo(request.positionMs)
                }
                LightResult.Success(LightServiceMethod.PlayBook.encodeResponse(Unit))
            }

            LightServiceMethod.PausePlayback.id -> {
                LocalPlaybackController.pause()
                LightResult.Success(LightServiceMethod.PausePlayback.encodeResponse(Unit))
            }

            LightServiceMethod.SeekTo.id -> {
                val request = LightServiceMethod.SeekTo.decodeRequest(payload!!)
                LocalPlaybackController.seekTo(request.positionMs)
                LightResult.Success(LightServiceMethod.SeekTo.encodeResponse(Unit))
            }

            LightServiceMethod.SetPlaybackSpeed.id -> {
                val request = LightServiceMethod.SetPlaybackSpeed.decodeRequest(payload!!)
                LocalPlaybackController.setSpeed(request.speed.toDouble())
                LightResult.Success(LightServiceMethod.SetPlaybackSpeed.encodeResponse(Unit))
            }

            LightServiceMethod.GetPlaybackState.id -> {
                val state = LocalPlaybackController.state.value
                val response = LightServiceMethod.GetPlaybackState.Response(
                    bookId = state.title.takeIf { it.isNotBlank() && it != "Audiobook" }
                        ?.let { activeBookId() },
                    title = state.title,
                    author = state.chapter,
                    partIndex = state.currentPartIndex,
                    partCount = state.partCount,
                    partTitle = state.partTitle,
                    positionMs = (state.positionSeconds * 1000).toLong(),
                    durationMs = (state.durationSeconds * 1000).toLong(),
                    playing = state.isPlaying,
                    speed = state.playbackSpeed.toFloat(),
                )
                LightResult.Success(LightServiceMethod.GetPlaybackState.encodeResponse(response))
            }

            else -> LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "unknown method: $methodId",
            )
        }
    }

    /** Runs [block] on the main thread, blocking the caller until it completes. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun booksResult(): LightResult<String> {
        val books = LocalBookRepository.books.value.map { book ->
            val stored = AudiobookProgressStore.read(book.source, book.id)
            LightServiceMethod.GetBooks.Book(
                id = book.id,
                title = book.title,
                author = book.author,
                durationMs = book.durationMilliseconds,
                progressMs = stored.positionMilliseconds,
                partCount = book.parts.size.coerceAtLeast(1),
                parts = book.parts.map { part ->
                    LightServiceMethod.GetBooks.Part(
                        title = part.title,
                        durationMs = part.durationMilliseconds,
                    )
                },
            )
        }
        return LightResult.Success(
            LightServiceMethod.GetBooks.encodeResponse(
                LightServiceMethod.GetBooks.Response(books),
            ),
        )
    }

    private fun activeBookId(): String? {
        val state = LocalPlaybackController.state.value
        val title = state.title.takeIf { it.isNotBlank() && it != "Audiobook" } ?: return null
        return LocalBookRepository.books.value.firstOrNull { it.title == title }?.id
    }
}
