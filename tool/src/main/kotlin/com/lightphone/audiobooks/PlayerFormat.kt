package com.lightphone.audiobooks

import com.thelightphone.sdk.shared.LightServiceMethod

/** Formats a duration in milliseconds as m:ss or h:mm:ss. */
fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Formats a speed as "1x", "1.25x", ... (lowercase x). */
fun formatSpeed(speed: Float): String = "${speed.toString().trimEnd('0').trimEnd('.')}x"

/** "Chapter 3 of 7" — used for parts (folder books) and embedded chapters. */
fun chapterLabel(index: Int, count: Int): String =
    "Chapter ${index + 1} of $count"

/**
 * The book's embedded chapters, flattened across parts. Only single-file books
 * carry embedded chapters today (they get exactly one part), so the flattened
 * list is that part's chapters.
 */
fun embeddedChapters(book: LightServiceMethod.GetBooks.Book): List<LightServiceMethod.GetBooks.Chapter> =
    book.parts.flatMap { it.chapters }

/** Global start of a part: the sum of the durations of all earlier parts. */
fun partStartMs(book: LightServiceMethod.GetBooks.Book, partIndex: Int): Long =
    book.parts.take(partIndex).sumOf { it.durationMs.coerceAtLeast(0) }

/** Index of the chapter containing [positionMs] (an offset within the part), clamped to the last chapter. */
fun chapterIndexAt(chapters: List<LightServiceMethod.GetBooks.Chapter>, positionMs: Long): Int {
    if (chapters.isEmpty()) return 0
    val index = chapters.indexOfFirst { positionMs < it.endMs }
    return if (index >= 0) index else chapters.lastIndex
}
