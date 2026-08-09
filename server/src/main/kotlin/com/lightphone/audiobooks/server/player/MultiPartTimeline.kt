package com.lightphone.audiobooks.server.player

internal data class PartPosition(
    val index: Int,
    val positionMilliseconds: Long,
)

internal fun locatePart(
    positionMilliseconds: Long,
    partDurations: LongArray,
): PartPosition {
    if (partDurations.isEmpty()) return PartPosition(0, 0)
    val total = partDurations.sum().coerceAtLeast(0)
    val target = positionMilliseconds.coerceIn(0, total)
    var prefix = 0L
    partDurations.forEachIndexed { index, duration ->
        val end = prefix + duration.coerceAtLeast(0)
        if (target < end || index == partDurations.lastIndex) {
            return PartPosition(index, (target - prefix).coerceIn(0, duration.coerceAtLeast(0)))
        }
        prefix = end
    }
    return PartPosition(partDurations.lastIndex, partDurations.last().coerceAtLeast(0))
}

internal fun globalPartPosition(
    partIndex: Int,
    positionMilliseconds: Long,
    partDurations: LongArray,
): Long {
    if (partDurations.isEmpty()) return 0
    val index = partIndex.coerceIn(partDurations.indices)
    return partDurations.take(index).sum() +
        positionMilliseconds.coerceIn(0, partDurations[index].coerceAtLeast(0))
}
