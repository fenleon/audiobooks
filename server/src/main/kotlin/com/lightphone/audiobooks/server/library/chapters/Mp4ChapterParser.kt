package com.lightphone.audiobooks.server.library.chapters

import com.lightphone.audiobooks.server.library.EmbeddedChapter
import java.io.InputStream

/**
 * Minimal MP4 chapter parser for Nero-style bookmarks (moov/udta/chpl), the
 * layout ffmpeg and audiobook muxers write for .m4b files. Pure Kotlin — no
 * Android dependencies, so the parser unit-tests on the JVM.
 *
 * The stream is walked atom by atom (only 8-byte headers are read, everything
 * else is skipped), so a `moov` box sitting at the end of the file — common
 * for m4b — is found without reading the whole file. chpl start times are in
 * 100 ns units; the format has no end times, so each chapter ends at the next
 * chapter's start (or the file duration for the last).
 */
object Mp4ChapterParser {

    private const val MAX_BOX_BYTES = 64 * 1024 * 1024

    /** Returns the file's embedded chapters, or empty when the stream has no parseable chpl box. */
    fun parse(input: InputStream, fileDurationMs: Long): List<EmbeddedChapter> {
        val chpl = findChpl(input) ?: return emptyList()
        return parseChpl(chpl, fileDurationMs)
    }

    /** Walks top-level atoms (skipping payloads) and returns the chpl box's payload, if any. */
    private fun findChpl(input: InputStream): ByteArray? {
        while (true) {
            val header = readFully(input, 8) ?: return null
            if (header.size < 8) return null
            var size = be32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 0L) return null // extends to EOF — no further atoms
            if (size == 1L) {
                // 64-bit largesize follows the type.
                val large = readFully(input, 8) ?: return null
                if (large.size < 8) return null
                size = be64(large, 0)
            }
            if (size < 8) return null
            val payloadSize = size - 8
            if (type == "moov") {
                if (payloadSize > MAX_BOX_BYTES) return null
                val moov = readFully(input, payloadSize.toInt()) ?: return null
                findChild(moov, "udta")?.let { udta ->
                    findChild(udta, "chpl")?.let { return it }
                }
                continue // moov payload already consumed — do not skip it again
            }
            if (!skipFully(input, payloadSize)) return null
        }
    }

    /** Returns the payload of the first [type] child atom within a container's payload. */
    private fun findChild(container: ByteArray, type: String): ByteArray? {
        var offset = 0
        while (offset + 8 <= container.size) {
            val size = be32(container, offset)
            val childType = String(container, offset + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || offset + size > container.size) return null
            if (childType == type) {
                return container.copyOfRange(offset + 8, offset + size.toInt())
            }
            offset += size.toInt()
        }
        return null
    }

    private fun parseChpl(data: ByteArray, fileDurationMs: Long): List<EmbeddedChapter> {
        // ffmpeg's mov_write_chpl_tag layout (verified against its source):
        // version+flags (4 bytes), unknown (4 bytes), then a ONE-BYTE chapter
        // count, then entries: start time (8 bytes, 100 ns units) + 1-byte
        // title length + UTF-8 title.
        if (data.size < 13) return emptyList()
        val count = data[8].toInt() and 0xFF
        var offset = 9
        val chapters = mutableListOf<EmbeddedChapter>()
        repeat(count) {
            if (offset + 9 > data.size) return@repeat
            val startMs = be64(data, offset) / 10_000
            offset += 8
            val length = data[offset].toInt() and 0xFF
            offset += 1
            if (offset + length > data.size) return@repeat
            val title = String(data, offset, length, Charsets.UTF_8)
            offset += length
            chapters += EmbeddedChapter(title, startMs, startMs)
        }
        return chapters.mapIndexed { index, chapter ->
            val end = chapters.getOrNull(index + 1)?.startMs
                ?: fileDurationMs.coerceAtLeast(chapter.startMs)
            chapter.copy(endMs = end)
        }
    }

    private fun readFully(input: InputStream, n: Long): ByteArray? {
        if (n > Int.MAX_VALUE) return null
        return readFully(input, n.toInt())
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val bytes = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(bytes, offset, n - offset)
            if (read < 0) return bytes.copyOf(offset)
            offset += read
        }
        return bytes
    }

    private fun skipFully(input: InputStream, n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun be32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun be64(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 56) or
            ((data[offset + 1].toLong() and 0xFF) shl 48) or
            ((data[offset + 2].toLong() and 0xFF) shl 40) or
            ((data[offset + 3].toLong() and 0xFF) shl 32) or
            ((data[offset + 4].toLong() and 0xFF) shl 24) or
            ((data[offset + 5].toLong() and 0xFF) shl 16) or
            ((data[offset + 6].toLong() and 0xFF) shl 8) or
            (data[offset + 7].toLong() and 0xFF)
}
