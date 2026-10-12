package com.lightphone.audiobooks.library

import android.media.MediaMetadataRetriever
import android.os.Environment
import com.lightphone.audiobooks.library.chapters.Id3v2ChapterParser
import com.lightphone.audiobooks.library.chapters.Mp4ChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private const val AUDIOBOOKS_DIRECTORY = "Audiobooks"

sealed interface LocalScanResult {
    data object FolderMissing : LocalScanResult
    data object Empty : LocalScanResult
    data class Success(val books: List<Audiobook>) : LocalScanResult
}

/**
 * Scans the shared `Audiobooks/` folder on external storage into books, in
 * process (Rung-1 fold: formerly behind the tool's LightSdkService). Files are
 * read directly by absolute path — metadata via MediaMetadataRetriever, embedded
 * chapters via FileInputStream — and playback references are absolute paths the
 * tool's detached player feeds to LightAudioSource.FileSource.
 *
 * Scans are incremental: a persistent `(path, size, mtime)` metadata cache
 * (scan_cache.json in the tool's filesDir) means unchanged files are never
 * re-read — only new/changed files hit MediaMetadataRetriever.
 */
object LocalBookRepository {
    private val scanMutex = Mutex()
    private val mutableBooks = MutableStateFlow<List<Audiobook>>(emptyList())
    private val mutableScanResult = MutableStateFlow<LocalScanResult?>(null)
    private val mutableScanning = MutableStateFlow(false)
    private var scanGeneration = 0

    // Scan metadata cache: absolute path -> metadata for that file, keyed by
    // (size, mtime). Unchanged files are never re-read, so scans after the
    // first one are a cheap walk.
    private var metadataCache: MutableMap<String, CachedMetadata>? = null

    private lateinit var filesDir: File

    val books: StateFlow<List<Audiobook>> = mutableBooks.asStateFlow()
    val scanResult: StateFlow<LocalScanResult?> = mutableScanResult.asStateFlow()
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    /** Whether any scan has completed (the first Library show kicks it). */
    val hasScanned: Boolean
        get() = mutableScanResult.value != null

    fun init(filesDir: File) {
        this.filesDir = filesDir
    }

    suspend fun scan(): LocalScanResult {
        if (!scanMutex.tryLock()) return mutableScanResult.value ?: LocalScanResult.Empty
        mutableScanning.value = true
        val generation = ++scanGeneration
        return try {
            val result = scanFresh(generation)
            val snapshot = (result as? LocalScanResult.Success)?.books.orEmpty()
            mutableBooks.value = snapshot
            mutableScanResult.value = result
            result
        } finally {
            mutableScanning.value = false
            scanMutex.unlock()
        }
    }

    /**
     * The scanned books with their persisted progress re-applied — the library
     * reads positions through this so rows show what the player last saved.
     */
    suspend fun refreshedBooks(): List<Audiobook> = withContext(Dispatchers.IO) {
        val updated = mutableBooks.value.map { book ->
            val stored = AudiobookProgressStore.read(book.source, book.id)
            book.copy(
                positionMilliseconds = stored.positionMilliseconds.coerceAtMost(
                    book.durationMilliseconds.takeIf { it > 0 } ?: Long.MAX_VALUE,
                ),
                playbackSpeed = stored.playbackSpeed,
                completed = stored.completed,
                lastPlayedAtMilliseconds = stored.lastPlayedAtMilliseconds,
                lastUpdatedAtMilliseconds = stored.lastUpdatedAtMilliseconds,
            )
        }
        mutableBooks.value = updated
        updated
    }

    private suspend fun scanFresh(generation: Int): LocalScanResult = withContext(Dispatchers.IO) {
        // The existence-only probe distinguishes a missing top-level folder
        // from an empty one; all file discovery is the walk itself.
        val rootDirectory = File(
            Environment.getExternalStorageDirectory(),
            AUDIOBOOKS_DIRECTORY,
        )
        val folderExists = rootDirectory.isDirectory
        // Discover every audio file under Audiobooks/ (any depth), so books kept in
        // subfolders are indexed directly.
        val candidates = if (folderExists) {
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

        val cache = metadataCache()
        var cacheDirty = false
        var cacheHits = 0
        var cacheMisses = 0
        // Reads the file's metadata unless an unchanged (size, mtime) entry is cached.
        fun metadataFor(file: File): CachedMetadata {
            val size = file.length().coerceAtLeast(0)
            val mtime = file.lastModified()
            val cached = cache[file.absolutePath]
            // A cached zero duration means the metadata read never succeeded
            // (e.g. the first scan raced the file copy). Treat it as a miss so
            // a later successful read heals the entry instead of pinning
            // "00:00" for as long as the file is unchanged.
            if (cached != null && cached.size == size && cached.mtimeMillis == mtime &&
                cached.durationMilliseconds > 0
            ) {
                cacheHits++
                return cached
            }
            cacheMisses++
            val embedded = readMetadata(file)
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
        val candidatePaths = candidates.mapTo(HashSet()) { it.absolutePath }
        if (cache.keys.removeAll { it !in candidatePaths }) cacheDirty = true

        val formatCounts = linkedMapOf<String, Int>()
        val accepted = linkedMapOf<String, Audiobook>()
        val folderParts = linkedMapOf<String, LinkedHashMap<String, LocalPartCandidate>>()
        val seenPaths = HashSet<String>()
        for (file in candidates) {
            val path = file.absolutePath
            if (!seenPaths.add(path)) continue
            val displayName = file.name
            val size = file.length().coerceAtLeast(0)
            val cached = metadataFor(file)
            val isMp4Container = displayName.endsWith(".m4b", ignoreCase = true) ||
                displayName.endsWith(".m4a", ignoreCase = true)
            val ext = displayName.substringAfterLast('.', "").lowercase()
            formatCounts[ext] = (formatCounts[ext] ?: 0) + 1
            val folderPath = folderPathOf(path, rootDirectory)
            val stableId = "file:${path.normalizedHash()}"
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
                    readEmbeddedChapters(file, isMp4Container, duration).also {
                        cache[path] = cached.copy(chapters = it, chaptersParsed = true)
                        cacheDirty = true
                    }
                }
                accepted[stableId] = audiobookFrom(
                    stableId = stableId,
                    displayName = displayName,
                    filePath = path,
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
                        filePath = path,
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
            val stableId = "folder:${folderPath.normalizedHash()}"
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
                playbackReference = ordered.first().filePath,
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
                        playbackReference = it.filePath,
                        durationMilliseconds = it.embedded.durationMilliseconds,
                        title = it.embedded.title.ifBlank {
                            it.displayName.removeSupportedSuffix()
                        },
                    )
                },
            )
        }

        if (cacheDirty) persistCache(cache)

        val books = accepted.values.sortedBy { it.title.lowercase() }
        when {
            books.isNotEmpty() -> LocalScanResult.Success(books)
            !folderExists -> LocalScanResult.FolderMissing
            else -> LocalScanResult.Empty
        }
    }

    private fun audiobookFrom(
        stableId: String,
        displayName: String,
        filePath: String,
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
        playbackReference = filePath,
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
                playbackReference = filePath,
                durationMilliseconds = duration,
                chapters = chapters,
            ),
        ),
    )

    /** Parses embedded chapters (MP3 CHAP / MP4 bookmarks) for a single-file book. */
    private fun readEmbeddedChapters(file: File, isMp4Container: Boolean, durationMs: Long): List<EmbeddedChapter> =
        runCatching {
            FileInputStream(file).use { input ->
                if (isMp4Container) {
                    Mp4ChapterParser.parse(input, durationMs)
                } else {
                    Id3v2ChapterParser.parse(input, durationMs)
                }
            }
        }.getOrDefault(emptyList())

    private fun readMetadata(file: File): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
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

    private fun String.normalizedHash(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(lowercase().trim().toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    // --- scan metadata cache ------------------------------------------------

    private const val SCAN_CACHE_FILE = "scan_cache.json"

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

    private fun cacheFile() = File(filesDir, SCAN_CACHE_FILE)

    private fun metadataCache(): MutableMap<String, CachedMetadata> {
        metadataCache?.let { return it }
        val loaded = readJson(cacheFile())?.let { root -> decodeCache(root) }
            ?.toMutableMap()
            ?: mutableMapOf()
        metadataCache = loaded
        return loaded
    }

    private fun persistCache(cache: Map<String, CachedMetadata>) {
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
        writeJsonAtomically(cacheFile(), root)
    }

    private fun decodeCache(root: JSONObject): Map<String, CachedMetadata> =
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
        }

    private data class LocalPartCandidate(
        val displayName: String,
        val filePath: String,
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
