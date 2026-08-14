package com.lightphone.audiobooks.server

import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.lightphone.audiobooks.server.library.AudiobookProgressStore
import com.lightphone.audiobooks.server.library.LocalBookRepository
import com.lightphone.audiobooks.server.player.LocalPlaybackController
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Implements the Audiobooks media methods on the companion's LightSdkService.
 * These are the server-side half of the tool model: the tool is a thin UI that
 * calls these over the SDK binder; everything privileged lives here.
 *
 * The resolver runs on a binder thread. Playback commands must run on the main
 * thread (ExoPlayer requires it), so they are marshalled through [onMain]. The
 * library scan is pure I/O and never touches the player, so it runs directly
 * on the binder thread to keep the main looper free during scans.
 */
object MediaServiceMethods {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun dispatch(methodId: String, payload: String?): LightResult<String> {
        if (methodId == LightServiceMethod.ScanLibrary.id) {
            // Await the scan so the tool's scanning indicator tracks the real
            // duration and the returned list is fresh. Runs off the main
            // thread: the player's state handling and the foreground service
            // share that looper and must not starve during a long scan.
            runBlocking(Dispatchers.IO) { LocalBookRepository.scan() }
            return booksResult()
        }
        if (methodId == LightServiceMethod.DeleteBook.id) {
            val request = LightServiceMethod.DeleteBook.decodeRequest(payload!!)
            return deleteBook(request.bookId)
        }
        return onMain {
            when (methodId) {
                LightServiceMethod.GetBooks.id -> booksResult()

                LightServiceMethod.SeekToPart.id -> {
                    val request = LightServiceMethod.SeekToPart.decodeRequest(payload!!)
                    LocalPlaybackController.seekToPart(request.partIndex)
                    LightResult.Success(LightServiceMethod.SeekToPart.encodeResponse(Unit))
                }

                LightServiceMethod.GetAutoPlayNext.id -> {
                    val response = LightServiceMethod.GetAutoPlayNext.Response(
                        enabled = PlaybackSettingsStore.autoPlayNext,
                    )
                    LightResult.Success(LightServiceMethod.GetAutoPlayNext.encodeResponse(response))
                }

                LightServiceMethod.SetAutoPlayNext.id -> {
                    val request = LightServiceMethod.SetAutoPlayNext.decodeRequest(payload!!)
                    PlaybackSettingsStore.autoPlayNext = request.enabled
                    LightResult.Success(LightServiceMethod.SetAutoPlayNext.encodeResponse(Unit))
                }

                LightServiceMethod.PlayBook.id -> {
                    val request = LightServiceMethod.PlayBook.decodeRequest(payload!!)
                    openBook(request.bookId, request.partIndex, request.positionMs, autoPlay = true)
                }

                LightServiceMethod.OpenBook.id -> {
                    val request = LightServiceMethod.OpenBook.decodeRequest(payload!!)
                    openBook(request.bookId, request.partIndex, request.positionMs, autoPlay = false)
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
                        bookId = state.bookId.takeIf { it.isNotBlank() },
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
    }

    /** Loads a book on the player; [autoPlay] distinguishes open-paused from open-and-play. */
    private fun openBook(
        bookId: String,
        partIndex: Int,
        positionMs: Long,
        autoPlay: Boolean,
    ): LightResult<String> {
        val book = LocalBookRepository.books.value.firstOrNull { it.id == bookId }
            ?: return LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "book not found: $bookId",
            )
        // Pressing play on an already-open book must not re-open it: a re-open
        // stops + re-queues, which resets the player position to 0 until the
        // pending seek lands (the UI flashes 00:00) and adds needless lag.
        if (LocalPlaybackController.isBookLoaded(bookId)) {
            if (autoPlay) LocalPlaybackController.play()
        } else {
            LocalPlaybackController.open(book, autoPlay = autoPlay)
        }
        if (partIndex > 0) {
            LocalPlaybackController.seekToPart(partIndex)
        }
        if (positionMs > 0) {
            LocalPlaybackController.seekTo(positionMs)
        }
        return LightResult.Success(LightServiceMethod.PlayBook.encodeResponse(Unit))
    }

    /**
     * Deletes a book's files. The companion only owns files it created itself,
     * so on Android 11+ deleting media placed via MTP/adb requires the system
     * consent dialog. A direct delete is attempted first; when that fails, the
     * consent request is registered for [DeleteConsentActivity] and the tool is
     * told a decision is pending — the tool then launches the activity (it is
     * the foreground process; the companion cannot start activities from the
     * background), which shows the system dialog and completes the deletion.
     */
    private fun deleteBook(bookId: String): LightResult<String> {
        val book = LocalBookRepository.books.value.firstOrNull { it.id == bookId }
            ?: return LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "book not found: $bookId",
            )
        // Player close must run on the main thread (ExoPlayer); the delete and
        // its rescan are I/O and run off the binder thread.
        onMain {
            if (LocalPlaybackController.isBookLoaded(bookId)) {
                LocalPlaybackController.close()
            }
        }
        val deleted = runBlocking(Dispatchers.IO) { LocalBookRepository.deleteBook(book) }
        if (deleted) {
            AudiobookProgressStore.clear(book.source, book.id)
            return LightResult.Success(
                LightServiceMethod.DeleteBook.encodeResponse(
                    LightServiceMethod.DeleteBook.Response(deleted = true),
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = book.parts.map { Uri.parse(it.playbackReference) }
                .ifEmpty { listOf(Uri.parse(book.playbackReference)) }
            val consent = runCatching {
                MediaStore.createDeleteRequest(
                    LocalBookRepository.applicationContext.contentResolver,
                    uris,
                )
            }.getOrNull()
            if (consent != null) {
                DeleteConsentActivity.register(bookId, consent)
                return LightResult.Success(
                    LightServiceMethod.DeleteBook.encodeResponse(
                        LightServiceMethod.DeleteBook.Response(
                            consentPending = true,
                            consentComponent = ComponentName(
                                LocalBookRepository.applicationContext,
                                DeleteConsentActivity::class.java,
                            ).flattenToString(),
                        ),
                    ),
                )
            }
        }
        return LightResult.Success(
            LightServiceMethod.DeleteBook.encodeResponse(
                LightServiceMethod.DeleteBook.Response(),
            ),
        )
    }

    /**
     * Finalizes a deletion after the consent dialog was answered. On Android
     * 13+ the dialog's ALLOW deletes the files itself; on 11-12 it grants the
     * companion delete access. Either way the actual delete is attempted (a
     * no-op when the provider already removed the files) and the library is
     * rescanned so the tool's next read reflects the true library. Called by
     * [DeleteConsentActivity] when the user confirms.
     */
    fun completeDelete(bookId: String) {
        val book = LocalBookRepository.books.value.firstOrNull { it.id == bookId } ?: return
        runBlocking {
            // ExoPlayer must be accessed from its creating (main) thread; the
            // rest of the deletion runs on the caller's IO thread.
            withContext(Dispatchers.Main) {
                if (LocalPlaybackController.isBookLoaded(bookId)) {
                    LocalPlaybackController.close()
                }
            }
            LocalBookRepository.deleteBook(book)
            LocalBookRepository.scan()
        }
        AudiobookProgressStore.clear(book.source, book.id)
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
                // The persisted duration is the player-resolved timeline the
                // position is measured against; using it as the denominator
                // keeps percent consistent (a finished book reads 100% even
                // when resolved durations differ slightly from metadata).
                durationMs = stored.durationMilliseconds.takeIf { it > 0 }
                    ?: book.durationMilliseconds,
                progressMs = stored.positionMilliseconds,
                partCount = book.parts.size.coerceAtLeast(1),
                parts = book.parts.map { part ->
                    LightServiceMethod.GetBooks.Part(
                        title = part.title,
                        durationMs = part.durationMilliseconds,
                        chapters = part.chapters.map { chapter ->
                            LightServiceMethod.GetBooks.Chapter(
                                title = chapter.title,
                                startMs = chapter.startMs,
                                endMs = chapter.endMs,
                            )
                        },
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
}
