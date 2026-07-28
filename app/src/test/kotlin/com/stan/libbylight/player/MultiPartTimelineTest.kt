package com.stan.libbylight.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiPartTimelineTest {
    private val parts = longArrayOf(10_000L, 20_000L, 30_000L)

    @Test fun globalPositionsResolveAcrossPartBoundaries() {
        assertEquals(PartPosition(0, 9_999L), locatePart(9_999L, parts))
        assertEquals(PartPosition(1, 0L), locatePart(10_000L, parts))
        assertEquals(PartPosition(2, 5_000L), locatePart(35_000L, parts))
    }

    @Test fun positionsClampToWholeBook() {
        assertEquals(PartPosition(0, 0L), locatePart(-1L, parts))
        assertEquals(PartPosition(2, 30_000L), locatePart(80_000L, parts))
    }

    @Test fun partPositionMapsBackToGlobalTimeline() {
        assertEquals(35_000L, globalPartPosition(2, 5_000L, parts))
    }
}
