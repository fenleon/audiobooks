package com.stan.libbylight.player

internal const val MEDIA_FAST_FORWARD_MILLISECONDS = 30_000L
internal const val MEDIA_REWIND_MILLISECONDS = 15_000L
internal const val MEDIA_PREVIOUS_RESTART_THRESHOLD_MILLISECONDS = 5_000L

internal fun clampMediaSeek(positionMilliseconds: Long, durationMilliseconds: Long): Long {
    val upperBound = durationMilliseconds.takeIf { it > 0 } ?: Long.MAX_VALUE
    return positionMilliseconds.coerceIn(0L, upperBound)
}

internal fun mediaFastForwardTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    clampMediaSeek(positionMilliseconds + MEDIA_FAST_FORWARD_MILLISECONDS, durationMilliseconds)

internal fun mediaRewindTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    clampMediaSeek(positionMilliseconds - MEDIA_REWIND_MILLISECONDS, durationMilliseconds)

internal fun mediaPreviousTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    if (positionMilliseconds > MEDIA_PREVIOUS_RESTART_THRESHOLD_MILLISECONDS) {
        0L
    } else {
        mediaRewindTarget(positionMilliseconds, durationMilliseconds)
    }

internal data class SafeMediaMetadata(
    val title: String,
    val subtitle: String?,
)

internal fun safeMediaMetadata(title: String, author: String): SafeMediaMetadata =
    SafeMediaMetadata(
        title = title.ifBlank { "Bard" },
        subtitle = author.takeIf { it.isNotBlank() },
    )

internal class MediaCommandDeduplicator(
    private val nowMilliseconds: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val windowMilliseconds: Long = 250L,
) {
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE

    fun shouldDispatch(key: String): Boolean {
        val now = nowMilliseconds()
        val duplicate = key == lastKey && now - lastAt in 0..windowMilliseconds
        lastKey = key
        lastAt = now
        return !duplicate
    }
}
