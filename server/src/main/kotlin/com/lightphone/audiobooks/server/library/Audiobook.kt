package com.lightphone.audiobooks.server.library

enum class AudiobookSource {
    Local,
}

/** One embedded chapter (MP3 CHAP frame / M4B bookmark) within a part. */
data class EmbeddedChapter(
    val title: String,
    /** Start offset within the part, milliseconds. */
    val startMs: Long,
    /** End offset within the part, milliseconds (open-ended chapters are resolved to the next chapter's start or the part duration). */
    val endMs: Long,
)

/** One physical audio file within a multi-file audiobook (a folder book). */
data class AudiobookPart(
    val playbackReference: String,
    val durationMilliseconds: Long,
    val title: String = "",
    /** Embedded chapters parsed from this file (empty for folder-book parts — file-per-chapter stays). */
    val chapters: List<EmbeddedChapter> = emptyList(),
)

data class Audiobook(
    val id: String,
    val source: AudiobookSource,
    val title: String,
    val author: String = "",
    val playbackReference: String,
    val durationMilliseconds: Long = 0,
    val positionMilliseconds: Long = 0,
    val playbackSpeed: Float = 1f,
    val completed: Boolean = false,
    val lastPlayedAtMilliseconds: Long = 0,
    val lastUpdatedAtMilliseconds: Long = 0,
    val fileSizeBytes: Long = 0,
    val parts: List<AudiobookPart> = emptyList(),
) {
    val progressPercent: Int
        get() = if (durationMilliseconds > 0) {
            ((positionMilliseconds * 100) / durationMilliseconds).toInt().coerceIn(0, 100)
        } else {
            0
        }
}
