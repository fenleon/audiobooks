package com.lightphone.audiobooks.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the natural filename ordering used by folder-book parts. */
class NaturalOrderTest {

    @Test
    fun `numeric runs compare by value, not lexically`() {
        assertTrue(naturalOrder("ch2.mp3", "ch10.mp3") < 0)
        assertTrue(naturalOrder("part 1.mp3", "part 2.mp3") < 0)
        assertTrue(naturalOrder("track10.mp3", "track2.mp3") > 0)
    }

    @Test
    fun `zero-padding does not matter`() {
        assertEquals(0, naturalOrder("ch01.mp3", "ch1.mp3"))
        assertTrue(naturalOrder("ch001.mp3", "ch02.mp3") < 0) // 1 < 2
    }

    @Test
    fun `comparison is case-insensitive`() {
        assertEquals(0, naturalOrder("Chapter.mp3", "chapter.mp3"))
        assertTrue(naturalOrder("a.mp3", "B.mp3") < 0)
    }

    @Test
    fun `suffixes and prefixes order deterministically`() {
        assertTrue(naturalOrder("ch1.mp3", "ch10.mp3") < 0)
        assertTrue(naturalOrder("chapter1a.mp3", "chapter1b.mp3") < 0)
        assertTrue(naturalOrder("book.mp3", "book2.mp3") < 0) // prefix first
    }
}
