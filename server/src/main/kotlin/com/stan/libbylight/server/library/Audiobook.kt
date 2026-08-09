package com.stan.libbylight.server.library

enum class AudiobookSource {
    Local,
}

/** One physical audio file within a multi-file audiobook (a folder book). */
data class AudiobookPart(
    val playbackReference: String,
    val durationMilliseconds: Long,
    val title: String = "",
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
    val progressPercentOverride: Int? = null,
    val dueText: String = "",
    val fileSizeBytes: Long = 0,
    val parts: List<AudiobookPart> = emptyList(),
) {
    val progressPercent: Int
        get() = progressPercentOverride ?: if (durationMilliseconds > 0) {
            ((positionMilliseconds * 100) / durationMilliseconds).toInt().coerceIn(0, 100)
        } else {
            0
        }
}
