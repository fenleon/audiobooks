package com.lightphone.audiobooks.server.library.chapters

import com.lightphone.audiobooks.server.library.EmbeddedChapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parses real ffmpeg-generated files (the same ones pushed to the emulator for
 * verification): an M4B with Nero chpl bookmarks and an MP3 with ID3v2.4 CHAP
 * frames — the layouts the parsers must handle in the wild.
 */
class RealChapterFilesTest {

    @Test
    fun `real ffmpeg m4b yields three named chapters`() {
        val input = javaClass.getResourceAsStream("/chapters/chapters.m4b")!!
        val chapters = Mp4ChapterParser.parse(input, fileDurationMs = 90_000)

        assertEquals(
            listOf(
                EmbeddedChapter("Chapter One", 0, 30_000),
                EmbeddedChapter("Chapter Two", 30_000, 60_000),
                EmbeddedChapter("Chapter Three", 60_000, 90_000),
            ),
            chapters,
        )
    }

    @Test
    fun `real ffmpeg mp3 yields three named chapters`() {
        val input = javaClass.getResourceAsStream("/chapters/chapters.mp3")!!
        val chapters = Id3v2ChapterParser.parse(input, fileDurationMs = 90_000)

        assertEquals(
            listOf(
                EmbeddedChapter("Chapter One", 0, 30_000),
                EmbeddedChapter("Chapter Two", 30_000, 60_000),
                EmbeddedChapter("Chapter Three", 60_000, 90_000),
            ),
            chapters,
        )
    }
}
