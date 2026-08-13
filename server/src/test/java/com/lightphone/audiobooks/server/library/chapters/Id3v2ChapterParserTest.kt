package com.lightphone.audiobooks.server.library.chapters

import com.lightphone.audiobooks.server.library.EmbeddedChapter
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage for [Id3v2ChapterParser] using hand-built ID3v2.3/v2.4 byte
 * fixtures: CTOC ordering, open-ended ends, plain vs synchsafe frame sizes,
 * unsynchronisation, text encodings, and rejection of non-ID3 input.
 */
class Id3v2ChapterParserTest {

    @Test
    fun `v2_4 orders by CTOC and resolves open-ended ends`() {
        val tag = id3Tag(version = 4, frames = listOf(
            chapFrame(version = 4, elementId = "ch2", start = 60_000, end = OPEN_ENDED, title = "Chapter Two"),
            chapFrame(version = 4, elementId = "ch1", start = 0, end = 60_000, title = "Chapter One"),
            ctocFrame(version = 4, elementId = "toc", children = listOf("ch1", "ch2")),
        ))

        val chapters = Id3v2ChapterParser.parse(ByteArrayInputStream(tag), fileDurationMs = 90_000)

        assertEquals(
            listOf(
                EmbeddedChapter("Chapter One", 0, 60_000),
                // ch2's open-ended end falls back to the file duration.
                EmbeddedChapter("Chapter Two", 60_000, 90_000),
            ),
            chapters,
        )
    }

    @Test
    fun `v2_3 plain sizes keep file order without CTOC`() {
        val tag = id3Tag(version = 3, frames = listOf(
            chapFrame(version = 3, elementId = "c1", start = 0, end = 30_000, title = "A".repeat(300)),
            chapFrame(version = 3, elementId = "c2", start = 30_000, end = 60_000, title = "Short"),
        ))

        val chapters = Id3v2ChapterParser.parse(ByteArrayInputStream(tag), fileDurationMs = 60_000)

        assertEquals(
            listOf(
                EmbeddedChapter("A".repeat(300), 0, 30_000),
                EmbeddedChapter("Short", 30_000, 60_000),
            ),
            chapters,
        )
    }

    @Test
    fun `tag-level unsynchronisation is undone before parsing`() {
        // Latin-1 title containing 0xFF 0x00; the tag-level unsync flag makes the
        // encoder insert 0x00 after every 0xFF in the whole tag.
        val tag = id3Tag(
            version = 4,
            frames = listOf(
                chapFrame(version = 4, elementId = "c1", start = 0, end = 10_000, titleBytes = byteArrayOf(0, 0x41, 0xFF.toByte(), 0x00, 0x42)),
            ),
            flags = 0x80, // tag-level unsynchronisation
        )

        val chapters = Id3v2ChapterParser.parse(ByteArrayInputStream(tag), fileDurationMs = 10_000)

        assertEquals(listOf(EmbeddedChapter("A\u00FF\u0000B", 0, 10_000)), chapters)
    }

    @Test
    fun `utf-16 title with BOM is decoded`() {
        val utf16Le = "Kapitel Eins".toByteArray(Charsets.UTF_16LE)
        val bomTitle = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16Le
        val tag = id3Tag(version = 4, frames = listOf(
            chapFrame(version = 4, elementId = "c1", start = 0, end = 10_000, titleBytes = byteArrayOf(1) + bomTitle),
        ))

        val chapters = Id3v2ChapterParser.parse(ByteArrayInputStream(tag), fileDurationMs = 10_000)

        assertEquals(listOf(EmbeddedChapter("Kapitel Eins", 0, 10_000)), chapters)
    }

    @Test
    fun `non-ID3 input returns empty`() {
        assertEquals(emptyList<EmbeddedChapter>(), Id3v2ChapterParser.parse(ByteArrayInputStream("not an id3 tag".toByteArray()), 0))
        assertEquals(emptyList<EmbeddedChapter>(), Id3v2ChapterParser.parse(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 0))
    }

    // --- fixture builders ---

    private companion object {
        const val OPEN_ENDED = 0xFFFFFFFFL
    }

    private fun id3Tag(version: Int, frames: List<ByteArray>, flags: Int = 0): ByteArray {
        // Frame sizes describe the de-unsynchronised lengths (as encoders
        // write them); the whole tag body is unsynchronised only after sizing.
        var body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        if (flags and 0x80 != 0) body = unsynchronise(body)
        val size = synchsafe(body.size)
        return "ID3".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(version.toByte(), 0) + byteArrayOf(flags.toByte()) + size + body
    }

    /** Inserts 0x00 after every 0xFF — the tag-level unsynchronisation transform. */
    private fun unsynchronise(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        data.forEach { byte ->
            out.write(byte.toInt())
            if (byte.toInt() == 0xFF) out.write(0)
        }
        return out.toByteArray()
    }

    private fun chapFrame(
        version: Int,
        elementId: String,
        start: Int,
        end: Long,
        title: String,
    ): ByteArray = chapFrame(version, elementId, start, end, titleBytes = byteArrayOf(3) + title.toByteArray(Charsets.UTF_8))

    private fun chapFrame(version: Int, elementId: String, start: Int, end: Long, titleBytes: ByteArray): ByteArray {
        val subframe = frame(version, "TIT2", titleBytes)
        // Byte offsets follow the end time (0xFFFFFFFF = unknown, as ffmpeg writes).
        val data = elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            be32(start) + be32(end.toInt()) + ByteArray(8) { -1 } + subframe
        return frame(version, "CHAP", data)
    }

    private fun ctocFrame(version: Int, elementId: String, children: List<String>): ByteArray {
        val childrenBytes = children.fold(ByteArray(0)) { acc, child ->
            acc + child.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        }
        val data = elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            byteArrayOf(0x03, children.size.toByte()) + childrenBytes
        return frame(version, "CTOC", data)
    }

    private fun frame(version: Int, id: String, data: ByteArray): ByteArray {
        val size = if (version == 4) synchsafe(data.size) else be32(data.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + data
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
