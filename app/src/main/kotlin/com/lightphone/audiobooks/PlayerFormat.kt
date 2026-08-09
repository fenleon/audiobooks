package com.lightphone.audiobooks

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

/** "Chapter 3 of 7" — only shown for multi-part (folder) books. */
fun chapterLabel(partIndex: Int, partCount: Int): String =
    "Chapter ${partIndex + 1} of $partCount"
