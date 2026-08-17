package com.lightphone.audiobooks.server.library

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
import android.util.Log
import com.lightphone.audiobooks.server.library.chapters.Id3v2ChapterParser
import com.lightphone.audiobooks.server.library.chapters.Mp4ChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

    // Scan metadata cache: absolute path -> metadata for that file, keyed by
    // (size, mtime). Unchanged files are never re-read, so scans after the
    // first one are a cheap walk + MediaStore query (the Bard "re-reads
    // everything every time" problem).
    private var metadataCache: MutableMap<String, CachedMetadata>? = null

    val books: StateFlow<List<Audiobook>> = mutableBooks.asStateFlow()
    val scanResult: StateFlow<LocalScanResult?> = mutableScanResult.asStateFlow()
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Application context for launching the system delete-consent flow. */
    val applicationContext: Context
        get() = appContext

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

        // MediaStore does not represent empty directories. This existence-only probe lets the companion
        // distinguish a missing top-level folder while all file discovery remains in the walk + MediaStore.
        val rootDirectory = File(
            Environment.getExternalStorageDirectory(),
            AUDIOBOOKS_DIRECTORY,
        )
        val folderExists = rootDirectory.isDirectory
        // Discover every audio file under Audiobooks/ (any depth), so books kept in
        // subfolders are indexed directly instead of waiting for a MediaStore rescan.
        val directCandidates = if (folderExists) {
            runCatching {
                rootDirectory.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            !file.name.startsWith('.') &&
                            file.name.hasSupportedExtension()
                    }
                    .toList()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // MediaStore supplies content URIs (stable ids, playback references) for
        // indexed files. Only files it has never seen are handed to the media
        // scanner — on a cached run that's none, so the 10s indexing window is
        // never hit and the scan stays a cheap walk + query.
        val rows = queryMediaStoreUris(rootDirectory)
        val missing = directCandidates.filter { it.absolutePath !in rows }
        val scannedUris = withTimeoutOrNull(10_000) {
            requestMediaIndexing(missing)
        }.orEmpty()
        Log.d(TAG, "scan generation=$generation strategy=walk-index-mediastore (${missing.size} to index)")

        val cache = metadataCache()
        var cacheDirty = false
        var cacheHits = 0
        var cacheMisses = 0
        // Reads the file's metadata unless an unchanged (size, mtime) entry is cached.
        fun metadataFor(file: File, uri: Uri): CachedMetadata {
            val size = file.length().coerceAtLeast(0)
            val mtime = file.lastModified()
            val cached = cache[file.absolutePath]
            // A cached zero duration means the metadata read never succeeded
            // (e.g. the first scan raced MediaStore indexing). Treat it as a
            // miss so a later successful read heals the entry instead of
            // pinning "00:00" for as long as the file is unchanged.
            if (cached != null && cached.size == size && cached.mtimeMillis == mtime &&
                cached.durationMilliseconds > 0
            ) {
                cacheHits++
                return cached
            }
            cacheMisses++
            val embedded = readMetadata(uri)
            val fresh = CachedMetadata(
                size = size,
                mtimeMillis = mtime,
                title = embedded.title,
                author = embedded.author,
                album = embedded.album,
                durationMilliseconds = embedded.durationMilliseconds,
                trackNumber = embedded.trackNumber,
                discNumber = embedded.discNumber,
            )
            cache[file.absolutePath] = fresh
            cacheDirty = true
            return fresh
        }

        // Drop entries for files that no longer exist so the cache stays bounded.
        val candidatePaths = directCandidates.mapTo(HashSet()) { it.absolutePath }
        if (cache.keys.removeAll { it !in candidatePaths }) cacheDirty = true

        val formatCounts = linkedMapOf<String, Int>()
        val accepted = linkedMapOf<String, Audiobook>()
        val folderParts = linkedMapOf<String, LinkedHashMap<String, LocalPartCandidate>>()
        val seenUris = HashSet<String>()
        for (file in directCandidates) {
            val path = file.absolutePath
            val uri = rows[path] ?: scannedUris[path] ?: continue
            if (!seenUris.add(uri.toString())) continue
            val displayName = file.name
            val size = file.length().coerceAtLeast(0)
            val cached = metadataFor(file, uri)
            val isMp4Container = displayName.endsWith(".m4b", ignoreCase = true) ||
                displayName.endsWith(".m4a", ignoreCase = true)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            formatCounts[ext] = (formatCounts[ext] ?: 0) + 1
            val folderPath = folderPathOf(path, rootDirectory)
            val mediaId = ContentUris.parseId(uri)
            val stableId = "external:$mediaId"
            if (folderPath == null) {
                val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
                val duration = cached.durationMilliseconds.takeIf { it > 0 }
                    ?: stored.durationMilliseconds.takeIf { it > 0 }
                    ?: 0L
                // Chapters are parsed only when this file is a standalone book;
                // the cache records whether they were, so a move between folder
                // and standalone re-parses exactly when needed.
                val chapters = if (cached.chaptersParsed) {
                    cached.chapters
                } else {
                    readEmbeddedChapters(uri, isMp4Container, duration).also {
                        cache[path] = cached.copy(chapters = it, chaptersParsed = true)
                        cacheDirty = true
                    }
                }
                accepted[stableId] = audiobookFrom(
                    stableId = stableId,
                    displayName = displayName,
                    uri = uri,
                    embedded = cached.toEmbeddedMetadata(),
                    stored = stored,
                    duration = duration,
                    fileSizeBytes = size,
                    chapters = chapters,
                )
            } else {
                folderParts.getOrPut(folderPath) { linkedMapOf() }[displayName.lowercase()] =
                    LocalPartCandidate(
                        displayName = displayName,
                        uri = uri,
                        embedded = cached.toEmbeddedMetadata(),
                        fileSizeBytes = size,
                    )
            }
        }
        folderParts.forEach { (folderPath, candidatesByPath) ->
            // Part order: disc/track tags when present **uniformly** (Librivox-
            // style sets tag only some files — e.g. ch09 + ch13 tagged, the
            // rest not — and "tagged first, untagged last" would split the
            // book). With mixed/absent tags, fall back to natural filename
            // order ("ch2" < "ch10"), which matches how these sets are named.
            val taggedUniformly = candidatesByPath.values.all {
                it.embedded.trackNumber > 0 && it.embedded.discNumber > 0
            }
            val ordered = candidatesByPath.values.sortedWith(
                if (taggedUniformly) {
                    compareBy<LocalPartCandidate> { it.embedded.discNumber }
                        .thenBy { it.embedded.trackNumber }
                } else {
                    compareBy<LocalPartCandidate> { it.displayName.lowercase() }
                        .thenComparator { a, b -> naturalOrder(a.displayName, b.displayName) }
                },
            )
            val stableId = "folder:${folderPath.normalizedFolderHash()}"
            val stored = AudiobookProgressStore.read(AudiobookSource.Local, stableId)
            val duration = ordered.sumOf { it.embedded.durationMilliseconds.coerceAtLeast(0) }
                .takeIf { it > 0 } ?: stored.durationMilliseconds
            accepted[stableId] = Audiobook(
                id = stableId,
                source = AudiobookSource.Local,
                // The embedded album tag carries the actual book title
                // (chapter files title themselves per chapter); fall back
                // to the folder name when it's absent.
                title = ordered.firstNotNullOfOrNull {
                    it.embedded.album.takeIf(String::isNotBlank)
                } ?: folderPath.substringAfterLast('/'),
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
                        title = it.embedded.title.ifBlank {
                            it.displayName.removeSupportedSuffix()
                        },
                    )
                },
            )
        }

        if (cacheDirty) {
            cachePrefs().edit()
                .putString(SCAN_CACHE_KEY, encodeCache(cache))
                .apply()
        }

        val books = accepted.values.sortedBy { it.title.lowercase() }
        Log.d(TAG, "audiobooks folder found=$folderExists")
        Log.d(TAG, "candidate count=${directCandidates.size}")
        Log.d(TAG, "local book count=${books.size} (metadata cache hits=$cacheHits, misses=$cacheMisses)")
        Log.d(TAG, "format counts=$formatCounts")
        when {
            books.isNotEmpty() -> LocalScanResult.Success(books)
            !folderExists -> LocalScanResult.FolderMissing
            else -> LocalScanResult.Empty
        }
    }

    /** Maps each indexed file's absolute path to its content URI, for files under Audiobooks/. */
    private fun queryMediaStoreUris(rootDirectory: File): Map<String, Uri> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
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
        val rows = HashMap<String, Uri>()
        runCatching {
            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                arguments,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val relativePathColumn = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val absolutePath = if (Build.VERSION.SDK_INT >= 29) {
                        val relativePath = cursor.getString(relativePathColumn).orEmpty()
                        val displayName = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
                        Environment.getExternalStorageDirectory().absolutePath +
                            "/$relativePath$displayName"
                    } else {
                        if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                    }
                    if (absolutePath.isBlank()) continue
                    val mediaId = cursor.getLong(idColumn)
                    rows[absolutePath] = ContentUris.withAppendedId(collection, mediaId)
                }
            }
        }
        return rows
    }

    private suspend fun requestMediaIndexing(files: List<File>): Map<String, Uri> {
        if (files.isEmpty()) return emptyMap()
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(files.size)
            val indexed = ConcurrentHashMap<String, Uri>()
            MediaScannerConnection.scanFile(
                appContext,
                files.map { it.absolutePath }.toTypedArray(),
                files.map { mimeTypeFor(it.name) }.toTypedArray(),
            ) { path, uri ->
                if (uri != null) indexed[path] = uri
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
        chapters: List<EmbeddedChapter> = emptyList(),
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
        // A single-file book with embedded chapters gets one part carrying them,
        // so chapter navigation, "Chapter N of M", chapter-scoped time, and the
        // auto-play-off boundary pause work off the file's own chapters.
        parts = if (chapters.isEmpty()) emptyList() else listOf(
            AudiobookPart(
                playbackReference = uri.toString(),
                durationMilliseconds = duration,
                chapters = chapters,
            ),
        ),
    )

    /** Parses embedded chapters (MP3 CHAP / MP4 bookmarks) for a single-file book. */
    private fun readEmbeddedChapters(uri: Uri, isMp4Container: Boolean, durationMs: Long): List<EmbeddedChapter> =
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                if (isMp4Container) {
                    Mp4ChapterParser.parse(input, durationMs)
                } else {
                    Id3v2ChapterParser.parse(input, durationMs)
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())

    private fun readMetadata(uri: Uri): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Track/disc tags ("3" or "3/12") order folder-book parts.
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            val discNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            EmbeddedMetadata(title, author, album, duration, trackNumber, discNumber)
        } catch (_: Exception) {
            EmbeddedMetadata()
        } finally {
            retriever.release()
        }
    }

    /** Audio extensions the library accepts (all natively decoded by the platform on API 34). */
    private val SUPPORTED_EXTENSIONS = listOf(
        ".mp3", ".m4b", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav",
    )

    private fun String.removeSupportedSuffix(): String {
        val suffix = SUPPORTED_EXTENSIONS.firstOrNull { endsWith(it, ignoreCase = true) } ?: return this
        return dropLast(suffix.length)
    }

    private fun String.hasSupportedExtension(): Boolean =
        SUPPORTED_EXTENSIONS.any { endsWith(it, ignoreCase = true) }

    /** MIME the media scanner should record for a file (sniffing would also work, this keeps rows exact). */
    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".m4b", true) || name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".aac", true) -> "audio/aac"
        name.endsWith(".ogg", true) || name.endsWith(".oga", true) -> "audio/ogg"
        name.endsWith(".opus", true) -> "audio/opus"
        name.endsWith(".flac", true) -> "audio/flac"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> "audio/mpeg"
    }

    /**
     * Resolves the folder a file belongs to, relative to Audiobooks/ (e.g. "MyTestBook" or
     * "Series/Hemingway/ForWhomTheBellTolls"). Files directly inside Audiobooks/ return null
     * and stay standalone single-file books.
     */
    private fun folderPathOf(absolutePath: String, rootDirectory: File): String? {
        val parent = File(absolutePath).parentFile ?: return null
        val relative = runCatching { parent.relativeTo(rootDirectory).invariantSeparatorsPath }
            .getOrDefault("")
        return relative.takeIf { it.isNotBlank() && it != "." }
    }

    private fun String.normalizedFolderHash(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(lowercase().trim().toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    // --- scan metadata cache ------------------------------------------------

    private const val SCAN_CACHE_PREFS = "scan_metadata_cache"
    private const val SCAN_CACHE_KEY = "cache_v1"

    private data class CachedMetadata(
        val size: Long,
        val mtimeMillis: Long,
        val title: String = "",
        val author: String = "",
        val album: String = "",
        val durationMilliseconds: Long = 0,
        val trackNumber: Int = 0,
        val discNumber: Int = 0,
        val chapters: List<EmbeddedChapter> = emptyList(),
        val chaptersParsed: Boolean = false,
    ) {
        fun toEmbeddedMetadata() = EmbeddedMetadata(
            title = title,
            author = author,
            album = album,
            durationMilliseconds = durationMilliseconds,
            trackNumber = trackNumber,
            discNumber = discNumber,
        )
    }

    private fun cachePrefs() =
        appContext.getSharedPreferences(SCAN_CACHE_PREFS, Context.MODE_PRIVATE)

    private fun metadataCache(): MutableMap<String, CachedMetadata> {
        metadataCache?.let { return it }
        return decodeCache(cachePrefs().getString(SCAN_CACHE_KEY, null)).also { metadataCache = it }
    }

    private fun encodeCache(cache: Map<String, CachedMetadata>): String {
        val root = JSONObject()
        cache.forEach { (path, meta) ->
            val chapters = JSONArray()
            meta.chapters.forEach { chapter ->
                chapters.put(JSONArray().put(chapter.title).put(chapter.startMs).put(chapter.endMs))
            }
            root.put(
                path,
                JSONObject()
                    .put("size", meta.size)
                    .put("mtime", meta.mtimeMillis)
                    .put("title", meta.title)
                    .put("author", meta.author)
                    .put("album", meta.album)
                    .put("duration", meta.durationMilliseconds)
                    .put("track", meta.trackNumber)
                    .put("disc", meta.discNumber)
                    .put("chapters", chapters)
                    .put("chaptersParsed", meta.chaptersParsed),
            )
        }
        return root.toString()
    }

    private fun decodeCache(encoded: String?): MutableMap<String, CachedMetadata> {
        if (encoded.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            val root = JSONObject(encoded)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val o = root.getJSONObject(path)
                    val chapters = buildList {
                        val arr = o.getJSONArray("chapters")
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONArray(i)
                            add(EmbeddedChapter(c.getString(0), c.getLong(1), c.getLong(2)))
                        }
                    }
                    put(
                        path,
                        CachedMetadata(
                            size = o.getLong("size"),
                            mtimeMillis = o.getLong("mtime"),
                            title = o.optString("title"),
                            author = o.optString("author"),
                            album = o.optString("album"),
                            durationMilliseconds = o.optLong("duration"),
                            trackNumber = o.optInt("track"),
                            discNumber = o.optInt("disc"),
                            chapters = chapters,
                            chaptersParsed = o.optBoolean("chaptersParsed"),
                        ),
                    )
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private data class LocalPartCandidate(
        val displayName: String,
        val uri: Uri,
        val embedded: EmbeddedMetadata,
        val fileSizeBytes: Long,
    )

    private data class EmbeddedMetadata(
        val title: String = "",
        val author: String = "",
        val album: String = "",
        val durationMilliseconds: Long = 0,
        /** CD track number (0 when absent) — folder-book part ordering. */
        val trackNumber: Int = 0,
        /** CD disc number (0 when absent) — folder-book part ordering. */
        val discNumber: Int = 0,
    )
}

/**
 * Case-insensitive natural order: numeric runs compare by value, so
 * "ch2" < "ch10" and "part 1" < "part 2" regardless of zero-padding.
 */
internal fun naturalOrder(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i].lowercaseChar()
        val cb = b[j].lowercaseChar()
        if (ca.isDigit() && cb.isDigit()) {
            var endA = i
            while (endA < a.length && a[endA].isDigit()) endA++
            var endB = j
            while (endB < b.length && b[endB].isDigit()) endB++
            val digitsA = a.substring(i, endA).trimStart('0')
            val digitsB = b.substring(j, endB).trimStart('0')
            val byLength = digitsA.length.compareTo(digitsB.length)
            if (byLength != 0) return byLength
            val byDigits = digitsA.compareTo(digitsB)
            if (byDigits != 0) return byDigits
            i = endA
            j = endB
        } else {
            val cmp = ca.compareTo(cb)
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
