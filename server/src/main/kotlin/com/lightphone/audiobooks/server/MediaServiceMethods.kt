package com.lightphone.audiobooks.server

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lightphone.audiobooks.server.library.AudiobookProgressStore
import com.lightphone.audiobooks.server.library.LocalBookRepository
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Implements the Audiobooks media methods on the companion's LightSdkService.
 * These are the server-side half of the tool model: the tool is a thin UI that
 * calls these over the SDK binder; everything privileged lives here.
 *
 * Playback itself is tool-side (SDK detached audio) — the companion's job is
 * the library (scan, progress store, settings) and serving the audio files via
 * [AudiobookMediaProvider]. The resolver runs on a binder thread; nothing here
 * touches a player anymore, so no main-thread marshalling is needed.
 */
object MediaServiceMethods {

    fun dispatch(methodId: String, payload: String?): LightResult<String> {
        if (methodId == LightServiceMethod.ScanLibrary.id) {
            // Await the scan so the tool's scanning indicator tracks the real
            // duration and the returned list is fresh. Runs off the binder
            // thread directly: scans are pure I/O.
            runBlocking(Dispatchers.IO) { LocalBookRepository.scan() }
            return booksResult()
        }
        if (methodId == LightServiceMethod.DeleteBook.id) {
            val request = LightServiceMethod.DeleteBook.decodeRequest(payload!!)
            return deleteBook(request.bookId)
        }
        return when (methodId) {
            LightServiceMethod.GetBooks.id -> booksResult()

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

            LightServiceMethod.GetPlaybackSpeed.id -> {
                val response = LightServiceMethod.GetPlaybackSpeed.Response(
                    speed = PlaybackSettingsStore.playbackSpeed,
                )
                LightResult.Success(LightServiceMethod.GetPlaybackSpeed.encodeResponse(response))
            }

            LightServiceMethod.SetPlaybackSpeed.id -> {
                val request = LightServiceMethod.SetPlaybackSpeed.decodeRequest(payload!!)
                PlaybackSettingsStore.playbackSpeed = request.speed
                LightResult.Success(LightServiceMethod.SetPlaybackSpeed.encodeResponse(Unit))
            }

            LightServiceMethod.GetVolumeLevel.id -> {
                val audio = LocalBookRepository.applicationContext
                    .getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val response = LightServiceMethod.GetVolumeLevel.Response(
                    level = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                )
                LightResult.Success(LightServiceMethod.GetVolumeLevel.encodeResponse(response))
            }

            LightServiceMethod.GetBluetoothConnected.id -> {
                val audio = LocalBookRepository.applicationContext
                    .getSystemService(Context.AUDIO_SERVICE) as AudioManager
                // Without BLUETOOTH_CONNECT (API 31+) BT devices are filtered
                // out of getDevices, so this reads false until the permission
                // is granted (adb pm grant, like passes' CAMERA).
                val connected = runCatching {
                    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                }.getOrDefault(false)
                val response = LightServiceMethod.GetBluetoothConnected.Response(connected)
                LightResult.Success(LightServiceMethod.GetBluetoothConnected.encodeResponse(response))
            }

            LightServiceMethod.WaitForVolumeChange.id -> {
                val request = LightServiceMethod.WaitForVolumeChange.decodeRequest(payload!!)
                VolumeChangeMonitor.ensureRegistered(LocalBookRepository.applicationContext)
                // Blocks a binder thread up to the timeout — the SDK's service
                // has a thread pool, and one long-poll at a time is the point.
                val (level, max) = runBlocking {
                    VolumeChangeMonitor.awaitChange(
                        LightServiceMethod.WaitForVolumeChange.WAIT_TIMEOUT_MS,
                        request.knownLevel,
                    )
                }
                val response = LightServiceMethod.WaitForVolumeChange.Response(level, max)
                LightResult.Success(LightServiceMethod.WaitForVolumeChange.encodeResponse(response))
            }

            LightServiceMethod.SaveProgress.id -> {
                val request = LightServiceMethod.SaveProgress.decodeRequest(payload!!)
                // The tool owns playback and reports positions; the library's
                // percent reads them from the store. Unknown books are ignored
                // (a stale report after a rescan), not errors.
                LocalBookRepository.books.value.firstOrNull { it.id == request.bookId }
                    ?.let { book ->
                        AudiobookProgressStore.saveLocal(
                            book,
                            request.positionMs,
                            request.durationMs,
                            request.speed,
                        )
                    }
                LightResult.Success(LightServiceMethod.SaveProgress.encodeResponse(Unit))
            }

            else -> LightResult.Error(
                LightResult.ErrorCode.Unknown,
                "unknown method: $methodId",
            )
        }
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
        runBlocking(Dispatchers.IO) {
            LocalBookRepository.deleteBook(book)
            LocalBookRepository.scan()
        }
        AudiobookProgressStore.clear(book.source, book.id)
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
                playbackReference = book.playbackReference,
                parts = book.parts.map { part ->
                    LightServiceMethod.GetBooks.Part(
                        title = part.title,
                        durationMs = part.durationMilliseconds,
                        playbackReference = part.playbackReference,
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
