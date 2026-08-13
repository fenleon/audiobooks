package com.lightphone.audiobooks.server.library.chapters

import com.lightphone.audiobooks.server.library.EmbeddedChapter
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage for [Mp4ChapterParser] using hand-built MP4 fixtures in
 * ffmpeg's chpl layout: 100 ns → ms conversion, chapter-end derivation,
 * moov-at-start and moov-at-end (skip-based walk), and rejection of files
 * without a chpl box.
 */
class Mp4ChapterParserTest {

    @Test
    fun `parses Nero chpl with moov at the start`() {
        val file = ftyp + atom("moov", atom("udta", chpl(listOf(
            0L to "Chapter One",
            30_000L * 10_000 to "Chapter Two",
            60_000L * 10_000 to "Chapter Three",
        ))))

        val chapters = Mp4ChapterParser.parse(ByteArrayInputStream(file), fileDurationMs = 90_000)

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
    fun `finds moov when it sits after a large mdat`() {
        val mdatPayload = ByteArray(1_000_000) { it.toByte() }
        val file = ftyp + atom("mdat", mdatPayload) + atom("moov", atom("udta", chpl(listOf(
            0L to "Intro",
            5_000L * 10_000 to "Main",
        ))))

        val chapters = Mp4ChapterParser.parse(ByteArrayInputStream(file), fileDurationMs = 90_000)

        assertEquals(
            listOf(
                EmbeddedChapter("Intro", 0, 5_000),
                EmbeddedChapter("Main", 5_000, 90_000),
            ),
            chapters,
        )
    }

    @Test
    fun `no chpl box returns empty`() {
        val file = ftyp + atom("moov", atom("udta", atom("meta", byteArrayOf(1, 2, 3))))
        assertEquals(emptyList<EmbeddedChapter>(), Mp4ChapterParser.parse(ByteArrayInputStream(file), 0))
        assertEquals(emptyList<EmbeddedChapter>(), Mp4ChapterParser.parse(ByteArrayInputStream("not an mp4".toByteArray()), 0))
    }

    // --- fixture builders ---

    private val ftyp: ByteArray = atom("ftyp", "M4B ".toByteArray(Charsets.ISO_8859_1))

    private fun atom(type: String, payload: ByteArray): ByteArray =
        be32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    /** chpl box in ffmpeg's mov_write_chpl_tag layout: version+flags, unknown, 1-byte count, entries. */
    private fun chpl(chapters: List<Pair<Long, String>>): ByteArray {
        val body = ByteArray(0) +
            byteArrayOf(0x01, 0, 0, 0) + // version + flags
            byteArrayOf(0, 0, 0, 0) + // unknown
            byteArrayOf(chapters.size.toByte()) + // 1-byte chapter count
            chapters.fold(ByteArray(0)) { acc, (startNs, title) ->
                val titleBytes = title.toByteArray(Charsets.UTF_8)
                acc + be64(startNs) + byteArrayOf(titleBytes.size.toByte()) + titleBytes
            }
        return atom("chpl", body)
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun be64(value: Long): ByteArray = byteArrayOf(
        (value ushr 56).toByte(),
        (value ushr 48).toByte(),
        (value ushr 40).toByte(),
        (value ushr 32).toByte(),
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
