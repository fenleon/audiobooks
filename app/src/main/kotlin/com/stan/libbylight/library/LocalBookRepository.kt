package com.stan.libbylight.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "LocalBooks"
private const val AUDIOBOOKS_DIRECTORY = "Audiobooks"

sealed interface LocalScanResult {
    data object PermissionRequired : LocalScanResult
    data object FolderMissing : LocalScanResult
    data object Empty : LocalScanResult
    data class Success(val books: List<Audiobook>) : LocalScanResult
}

object LocalBookRepository {
    private lateinit var appContext: Context
    private val scanMutex = Mutex()
    private val mutableBooks = MutableStateFlow<List<Audiobook>>(emptyList())
    private val mutableScanResult = MutableStateFlow<LocalScanResult?>(null)
    private val mutableScanning = MutableStateFlow(false)
    private var scanGeneration = 0

    val books: StateFlow<List<Audiobook>> = mutableBooks.asStateFlow()
    val scanResult: StateFlow<LocalScanResult?> = mutableScanResult.asStateFlow()
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasReadPermission(): Boolean =
        appContext.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun updateBook(book: Audiobook) {
        mutableBooks.update { current ->
            current.map { existing -> if (existing.id == book.id) book else existing }
        }
    }

    fun publishPermissionRequired() {
        mutableScanResult.value = LocalScanResult.PermissionRequired
    }

    suspend fun deleteBook(book: Audiobook): Boolean = withContext(Dispatchers.IO) {
        if (book.source != AudiobookSource.Local) return@withContext false
        val references = book.parts.map { it.playbackReference }
            .ifEmpty { listOf(book.playbackReference) }
        val deleted = references.all { reference ->
            val uri = runCatching { Uri.parse(reference) }.getOrNull()
                ?: return@all false
            runCatching { appContext.contentResolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
        }
        if (deleted) scan()
        deleted
    }

    suspend fun scan(): LocalScanResult {
        if (!scanMutex.tryLock()) return mutableScanResult.value ?: LocalScanResult.Empty
        mutableScanning.value = true
        val generation = ++scanGeneration
        Log.d(TAG, "scan generation=$generation started")
        return try {
            val result = scanFresh(generation)
            val snapshot = (result as? LocalScanResult.Success)?.books.orEmpty()
            mutableBooks.value = snapshot
            mutableScanResult.value = result
            Log.d(TAG, "repository snapshot count=${snapshot.size}")
            result
        } finally {
            mutableScanning.value = false
            scanMutex.unlock()
        }
    }

    private suspend fun scanFresh(generation: Int): LocalScanResult = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext LocalScanResult.PermissionRequired

        // MediaStore does not represent empty directories. This existence-only probe lets Bard
        // distinguish a missing top-level folder while all file discovery remains in MediaStore.
        val rootDirectory = File(
            Environment.getExternalStorageDirectory(),
            AUDIOBOOKS_DIRECTORY,
        )
        val folderExists = rootDirectory.isDirectory
        val directCandidates = if (folderExists) {
            rootDirectory
                .listFiles()
                ?.flatMap { entry ->
                    when {
                        entry.isFile -> listOf(entry)
                        entry.isDirectory && !entry.name.startsWith('.') ->
                            entry.walkTopDown()
                                .filter { it.isFile && !it.name.startsWith('.') }
                                .toList()
                        else -> emptyList()
                    }
                }
                ?.filter { file ->
                    !file.name.startsWith('.') && file.name.hasSupportedExtension()
                }
        } else {
            emptyList()
        }
        val directRelativeKeys = directCandidates.orEmpty().associateBy {
            it.relativeTo(rootDirectory).invariantSeparatorsPath.lowercase()
        }
        val scannedUris = withTimeoutOrNull(10_000) {
            directCandidates?.let { requestMediaIndexing(rootDirectory, it) }.orEmpty()
        }.orEmpty()
        Log.d(TAG, "scan generation=$generation strategy=direct-index-and-mediastore")

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT < 29) add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()
        val selection: String
        val arguments: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            arguments = arrayOf("$AUDIOBOOKS_DIRECTORY/%")
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            arguments = arrayOf("$root/$AUDIOBOOKS_DIRECTORY/%")
        }

        var mp3Count = 0
        var m4bCount = 0
        val books = try {
            val accepted = linkedMapOf<String, Audiobook>()
            val folderParts = linkedMapOf<String, LinkedHashMap<String, LocalPartCandidate>>()
            buildList {
                appContext.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    arguments,
                    "${MediaStore.Audio.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val relativePathColumn = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameColumn).orEmpty()
                        val mimeType = cursor.getString(mimeColumn).orEmpty()
                        val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                        if (displayName.startsWith('.') || dataPath.substringAfterLast('/').startsWith('.')) continue
                        val isMp3 = displayName.endsWith(".mp3", ignoreCase = true) || mimeType == "audio/mpeg"
                        val isM4b = displayName.endsWith(".m4b", ignoreCase = true)
                        if (!isMp3 && !isM4b) continue
                        if (isM4b) m4bCount++ else mp3Count++
                        val relativePath = if (relativePathColumn >= 0) {
                            cursor.getString(relativePathColumn).orEmpty()
                        } else {
                            val parent = File(dataPath).parentFile ?: continue
                            val root = File(Environment.getExternalStorageDirectory(), AUDIOBOOKS_DIRECTORY)
                            val relative = runCatching { parent.relativeTo(root).path }.getOrDefault("")
                            if (relative.isBlank() || relative == ".") "$AUDIOBOOKS_DIRECTORY/"
                            else "$AUDIOBOOKS_DIRECTORY/$relative/"
                        }
                        val folderName = relativePath.localFolderNameOrNull()
                        if (relativePath != "$AUDIOBOOKS_DIRECTORY/" && folderName == null) continue
                        val relativeKey = (
                            relativePath.removePrefix("$AUDIOBOOKS_DIRECTORY/") + displayName
                        ).lowercase()
                        if (relativeKey !in directRelativeKeys) continue

                        val mediaId = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collection, mediaId)
                        val embedded = readMetadata(uri)
                        val size = cursor.getLong(sizeColumn).coerceAtLeast(0)
                        if (folderName == null) {
                            val stableId = "external:$mediaId"
                            val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                            val duration = embedded.durationMilliseconds.takeIf { it > 0 }
                                ?: stored.durationMilliseconds.takeIf { it > 0 }
                                ?: 0L
                            accepted[stableId] = audiobookFrom(
                                stableId = stableId,
                                displayName = displayName,
                                uri = uri,
                                embedded = embedded,
                                stored = stored,
                                duration = duration,
                                fileSizeBytes = size,
                            )
                        } else {
                            val sortKey = relativePath.removePrefix(
                                "$AUDIOBOOKS_DIRECTORY/$folderName/",
                            ) + displayName
                            folderParts.getOrPut(folderName) { linkedMapOf() }[
                                sortKey.lowercase()
                            ] = LocalPartCandidate(
                                    displayName = displayName,
                                    sortKey = sortKey,
                                    uri = uri,
                                    embedded = embedded,
                                    fileSizeBytes = size,
                                )
                        }
                    }
                }
                scannedUris.forEach { (relativeKey, uri) ->
                    if (relativeKey.lowercase() !in directRelativeKeys) return@forEach
                    if (accepted.values.any { book ->
                            book.playbackReference == uri.toString() ||
                                book.parts.any { it.playbackReference == uri.toString() }
                        }
                    ) return@forEach
                    if (folderParts.values.any { parts -> parts.values.any { it.uri == uri } }) {
                        return@forEach
                    }
                    val displayName = relativeKey.substringAfterLast('/')
                    val folderName = relativeKey.substringBefore('/').takeIf { '/' in relativeKey }
                    val embedded = readMetadata(uri)
                    if (folderName != null) {
                        if (displayName.endsWith(".m4b", true)) m4bCount++ else mp3Count++
                        val sortKey = relativeKey.removePrefix("$folderName/")
                        folderParts.getOrPut(folderName) { linkedMapOf() }[
                            sortKey.lowercase()
                        ] = LocalPartCandidate(
                                displayName = displayName,
                                sortKey = sortKey,
                                uri = uri,
                                embedded = embedded,
                                fileSizeBytes = querySize(uri),
                            )
                        return@forEach
                    }
                    val mediaId = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return@forEach
                    val stableId = "external:$mediaId"
                    if (stableId in accepted) return@forEach
                    val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                    val duration = embedded.durationMilliseconds.takeIf { it > 0 }
                        ?: stored.durationMilliseconds.takeIf { it > 0 }
                        ?: 0L
                    if (displayName.endsWith(".m4b", true)) m4bCount++ else mp3Count++
                    accepted[stableId] = audiobookFrom(
                        stableId = stableId,
                        displayName = displayName,
                        uri = uri,
                        embedded = embedded,
                        stored = stored,
                        duration = duration,
                        fileSizeBytes = querySize(uri),
                    )
                }
                folderParts.forEach { (folderName, candidatesByPath) ->
                    val ordered = candidatesByPath.values.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortKey },
                    )
                    val stableId = "folder:${folderName.normalizedFolderHash()}"
                    val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                    val duration = ordered.sumOf { it.embedded.durationMilliseconds.coerceAtLeast(0) }
                        .takeIf { it > 0 } ?: stored.durationMilliseconds
                    accepted[stableId] = Audiobook(
                        id = stableId,
                        source = AudiobookSource.Local,
                        title = folderName,
                        author = ordered.firstNotNullOfOrNull {
                            it.embedded.author.takeIf(String::isNotBlank)
                        }.orEmpty(),
                        playbackReference = ordered.first().uri.toString(),
                        durationMilliseconds = duration,
                        positionMilliseconds = stored.positionMilliseconds.coerceAtMost(
                            duration.takeIf { it > 0 } ?: Long.MAX_VALUE,
                        ),
                        playbackSpeed = stored.playbackSpeed,
                        completed = stored.completed,
                        lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
                        lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
                        fileSizeBytes = ordered.sumOf { it.fileSizeBytes },
                        parts = ordered.map {
                            AudiobookPart(
                                playbackReference = it.uri.toString(),
                                durationMilliseconds = it.embedded.durationMilliseconds,
                            )
                        },
                    )
                }
                addAll(accepted.values)
            }.sortedBy { it.title.lowercase() }
        } catch (_: SecurityException) {
            return@withContext LocalScanResult.PermissionRequired
        } catch (_: Exception) {
            return@withContext if (folderExists) LocalScanResult.Empty else LocalScanResult.FolderMissing
        }

        Log.d(TAG, "audiobooks folder found=$folderExists")
        Log.d(TAG, "raw candidate count=${directCandidates?.size ?: books.size}")
        Log.d(TAG, "local book count=${books.size}")
        Log.d(TAG, "mp3 count=$mp3Count")
        Log.d(TAG, "m4b count=$m4bCount")
        when {
            books.isNotEmpty() -> LocalScanResult.Success(books)
            !folderExists -> LocalScanResult.FolderMissing
            else -> LocalScanResult.Empty
        }
    }

    private suspend fun requestMediaIndexing(
        rootDirectory: File,
        files: List<File>,
    ): Map<String, Uri> {
        if (files.isEmpty()) return emptyMap()
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(files.size)
            val indexed = ConcurrentHashMap<String, Uri>()
            MediaScannerConnection.scanFile(
                appContext,
                files.map { it.absolutePath }.toTypedArray(),
                files.map { if (it.name.endsWith(".m4b", true)) "audio/mp4" else "audio/mpeg" }
                    .toTypedArray(),
            ) { path, uri ->
                if (uri != null) {
                    val relativeKey = runCatching {
                        File(path).relativeTo(rootDirectory).invariantSeparatorsPath
                    }.getOrDefault(File(path).name)
                    indexed[relativeKey] = uri
                }
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    continuation.resume(indexed.toMap())
                }
            }
        }
    }

    private fun audiobookFrom(
        stableId: String,
        displayName: String,
        uri: Uri,
        embedded: EmbeddedMetadata,
        stored: AudiobookProgress,
        duration: Long,
        fileSizeBytes: Long,
    ): Audiobook = Audiobook(
        id = stableId,
        source = AudiobookSource.Local,
        title = embedded.title.ifBlank { displayName.removeSupportedSuffix() },
        author = embedded.author,
        playbackReference = uri.toString(),
        durationMilliseconds = duration,
        positionMilliseconds = stored.positionMilliseconds.coerceAtMost(
            duration.takeIf { it > 0 } ?: Long.MAX_VALUE,
        ),
        playbackSpeed = stored.playbackSpeed,
        completed = stored.completed,
        lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
        lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
        fileSizeBytes = fileSizeBytes,
    )

    private fun querySize(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)).coerceAtLeast(0)
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    private fun readMetadata(uri: Uri): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Log.d(TAG, "metadata available=${title.isNotBlank() || author.isNotBlank()}")
            EmbeddedMetadata(title, author, duration)
        } catch (_: Exception) {
            EmbeddedMetadata()
        } finally {
            retriever.release()
        }
    }

    private fun String.removeSupportedSuffix(): String = when {
        endsWith(".mp3", ignoreCase = true) -> dropLast(4)
        endsWith(".m4b", ignoreCase = true) -> dropLast(4)
        else -> this
    }

    private fun String.hasSupportedExtension(): Boolean =
        endsWith(".mp3", ignoreCase = true) || endsWith(".m4b", ignoreCase = true)

    private fun String.localFolderNameOrNull(): String? {
        val withoutRoot = removePrefix("$AUDIOBOOKS_DIRECTORY/").trimEnd('/')
        if (withoutRoot.isBlank()) return null
        return withoutRoot.substringBefore('/').takeIf(String::isNotBlank)
    }

    private fun String.normalizedFolderHash(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(lowercase().trim().toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private data class LocalPartCandidate(
        val displayName: String,
        val sortKey: String,
        val uri: Uri,
        val embedded: EmbeddedMetadata,
        val fileSizeBytes: Long,
    )

    private data class EmbeddedMetadata(
        val title: String = "",
        val author: String = "",
        val durationMilliseconds: Long = 0,
    )
}
