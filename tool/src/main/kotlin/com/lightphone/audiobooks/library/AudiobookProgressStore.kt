package com.lightphone.audiobooks.library

import org.json.JSONObject
import java.io.File

private const val FILE_NAME = "progress.json"

data class AudiobookProgress(
    val positionMilliseconds: Long = 0,
    val durationMilliseconds: Long = 0,
    val playbackSpeed: Float = 1f,
    val completed: Boolean = false,
    val lastPlayedAtMilliseconds: Long = 0,
    val lastUpdatedAtMilliseconds: Long = 0,
    val playbackReference: String = "",
    val title: String = "",
    val author: String = "",
)

/** Durable, source-independent progress and ordering metadata. */
object AudiobookProgressStore {
    private lateinit var filesDir: File
    private var cache: MutableMap<String, AudiobookProgress>? = null

    fun init(filesDir: File) {
        this.filesDir = filesDir
    }

    private fun qualifiedId(source: AudiobookSource, id: String): String = "${source.name}:$id"

    private fun storeFile() = File(filesDir, FILE_NAME)

    /** Loads the store on first use (in-memory cache afterwards). */
    private fun load(): MutableMap<String, AudiobookProgress> = synchronized(this) {
        cache?.let { return it }
        val loaded = buildMap {
            readJson(storeFile())?.let { root ->
                root.keys().forEach { key ->
                    val o = root.getJSONObject(key)
                    put(
                        key,
                        AudiobookProgress(
                            positionMilliseconds = o.optLong("position"),
                            durationMilliseconds = o.optLong("duration"),
                            playbackSpeed = o.optDouble("speed", 1.0).toFloat(),
                            completed = o.optBoolean("completed"),
                            lastPlayedAtMilliseconds = o.optLong("lastPlayed"),
                            lastUpdatedAtMilliseconds = o.optLong("updated"),
                            playbackReference = o.optString("reference"),
                            title = o.optString("title"),
                            author = o.optString("author"),
                        ),
                    )
                }
            }
        }.toMutableMap()
        cache = loaded
        loaded
    }

    private fun persist(entries: Map<String, AudiobookProgress>) = synchronized(this) {
        val root = JSONObject()
        entries.forEach { (key, progress) ->
            root.put(
                key,
                JSONObject()
                    .put("position", progress.positionMilliseconds)
                    .put("duration", progress.durationMilliseconds)
                    .put("speed", progress.playbackSpeed.toDouble())
                    .put("completed", progress.completed)
                    .put("lastPlayed", progress.lastPlayedAtMilliseconds)
                    .put("updated", progress.lastUpdatedAtMilliseconds)
                    .put("reference", progress.playbackReference)
                    .put("title", progress.title)
                    .put("author", progress.author),
            )
        }
        writeJsonAtomically(storeFile(), root)
    }

    fun read(source: AudiobookSource, id: String): AudiobookProgress =
        load()[qualifiedId(source, id)] ?: AudiobookProgress()

    /** Forgets all stored progress/metadata for a book. */
    fun clear(source: AudiobookSource, id: String) {
        val entries = load()
        if (entries.remove(qualifiedId(source, id)) != null) persist(entries)
    }

    fun saveLocal(
        book: Audiobook,
        positionMilliseconds: Long,
        durationMilliseconds: Long,
        playbackSpeed: Float,
    ) {
        val entries = load()
        val existing = entries[qualifiedId(book.source, book.id)] ?: AudiobookProgress()
        val duration = durationMilliseconds.coerceAtLeast(0)
        var position = positionMilliseconds.coerceAtLeast(0)
        val completed = duration > 0 && duration - position.coerceAtMost(duration) <= 30_000L
        // A finished book stores exactly its full duration, so the library's
        // percent reads 100% (the player-derived end position can otherwise
        // land a hair short of the resolved duration).
        if (completed) position = duration
        entries[qualifiedId(book.source, book.id)] = existing.copy(
            positionMilliseconds = position,
            durationMilliseconds = duration.takeIf { it > 0 } ?: existing.durationMilliseconds,
            playbackSpeed = playbackSpeed.takeIf { it > 0 } ?: existing.playbackSpeed,
            completed = completed,
            lastUpdatedAtMilliseconds = System.currentTimeMillis(),
            playbackReference = book.playbackReference,
            title = book.title,
            author = book.author,
        )
        persist(entries)
    }
}
