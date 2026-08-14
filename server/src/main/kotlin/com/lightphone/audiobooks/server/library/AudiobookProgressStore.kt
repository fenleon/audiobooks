package com.lightphone.audiobooks.server.library

import android.content.Context

private const val PREFERENCES_NAME = "local_audiobook_progress"

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
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun qualifiedId(source: AudiobookSource, id: String): String = "${source.name}:$id"

    fun read(source: AudiobookSource, id: String): AudiobookProgress {
        val preferences = preferences()
        val key = qualifiedId(source, id)
        val legacyPrefix = if (source == AudiobookSource.Local && !preferences.contains("$key.position")) {
            id
        } else {
            key
        }
        return AudiobookProgress(
            positionMilliseconds = preferences.getLong("$legacyPrefix.position", 0L),
            durationMilliseconds = preferences.getLong("$legacyPrefix.duration", 0L),
            playbackSpeed = preferences.getFloat("$legacyPrefix.speed", 1f),
            completed = preferences.getBoolean("$legacyPrefix.completed", false),
            lastPlayedAtMilliseconds = preferences.getLong("$key.lastPlayed", 0L),
            lastUpdatedAtMilliseconds = preferences.getLong(
                "$key.updated",
                preferences.getLong("$legacyPrefix.updated", 0L),
            ),
            playbackReference = preferences.getString("$key.reference", "").orEmpty(),
            title = preferences.getString("$key.title", "").orEmpty(),
            author = preferences.getString("$key.author", "").orEmpty(),
        )
    }

    /** Forgets all stored progress/metadata for a book (used when it is deleted). */
    fun clear(source: AudiobookSource, id: String) {
        val key = qualifiedId(source, id)
        val editor = preferences().edit()
            .remove("$key.position")
            .remove("$key.duration")
            .remove("$key.speed")
            .remove("$key.completed")
            .remove("$key.lastPlayed")
            .remove("$key.updated")
            .remove("$key.reference")
            .remove("$key.title")
            .remove("$key.author")
        // Legacy key layout used pre-qualified-id migration for local books.
        if (source == AudiobookSource.Local) {
            editor
                .remove("$id.position")
                .remove("$id.duration")
                .remove("$id.speed")
                .remove("$id.completed")
                .remove("$id.updated")
        }
        editor.apply()
    }

    fun saveLocal(
        book: Audiobook,
        positionMilliseconds: Long,
        durationMilliseconds: Long,
        playbackSpeed: Float,
    ) {
        val existing = read(book.source, book.id)
        val duration = durationMilliseconds.coerceAtLeast(0)
        var position = positionMilliseconds.coerceAtLeast(0)
        val completed = duration > 0 && duration - position.coerceAtMost(duration) <= 30_000L
        // A finished book stores exactly its full duration, so the library's
        // percent reads 100% (the player-derived end position can otherwise
        // land a hair short of the resolved duration).
        if (completed) position = duration
        write(
            book.source,
            book.id,
            existing.copy(
                positionMilliseconds = position,
                durationMilliseconds = duration.takeIf { it > 0 } ?: existing.durationMilliseconds,
                playbackSpeed = playbackSpeed.takeIf { it > 0 } ?: existing.playbackSpeed,
                completed = completed,
                lastUpdatedAtMilliseconds = System.currentTimeMillis(),
                playbackReference = book.playbackReference,
                title = book.title,
                author = book.author,
            ),
        )
    }

    private fun write(source: AudiobookSource, id: String, progress: AudiobookProgress) {
        val key = qualifiedId(source, id)
        preferences().edit()
            .putLong("$key.position", progress.positionMilliseconds)
            .putLong("$key.duration", progress.durationMilliseconds)
            .putFloat("$key.speed", progress.playbackSpeed)
            .putBoolean("$key.completed", progress.completed)
            .putLong("$key.lastPlayed", progress.lastPlayedAtMilliseconds)
            .putLong("$key.updated", progress.lastUpdatedAtMilliseconds)
            .putString("$key.reference", progress.playbackReference)
            .putString("$key.title", progress.title)
            .putString("$key.author", progress.author)
            .apply()
    }

    private fun preferences() =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
