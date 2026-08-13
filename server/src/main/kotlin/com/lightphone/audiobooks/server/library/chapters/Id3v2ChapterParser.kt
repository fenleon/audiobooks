package com.lightphone.audiobooks.server.library.chapters

import com.lightphone.audiobooks.server.library.EmbeddedChapter
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Minimal ID3v2.3/v2.4 chapter parser: collects CHAP frames (element id,
 * start/end ms, nested TIT2 description) and orders them by the CTOC child
 * list when one is present. Pure Kotlin — no Android dependencies, so the
 * parser unit-tests on the JVM.
 *
 * Only the ID3 tag is read (the caller passes the file's stream; the tag
 * header gives its size). Handles synchsafe sizes, the extended header,
 * unsynchronisation (tag- and frame-level), and open-ended v2.4 CHAP ends.
 */
object Id3v2ChapterParser {

    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val OPEN_ENDED = 0xFFFFFFFFL

    /** Returns the file's embedded chapters, or empty when none are parseable. */
    fun parse(input: InputStream, fileDurationMs: Long): List<EmbeddedChapter> {
        val header = readFully(input, 10)
        if (header == null || header.size < 10 ||
            header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()
        ) {
            return emptyList()
        }
        val version = header[3].toInt() and 0xFF
        if (version != 3 && version != 4) return emptyList()
        val flags = header[5].toInt() and 0xFF
        val tagSize = synchsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return emptyList()
        val tagBytes = readFully(input, tagSize) ?: return emptyList()
        // Tag-level unsynchronisation (flag 0x80) rewrites the whole tag; frame
        // sizes are then meaningless until it is undone.
        val tagUnsynced = flags and 0x80 != 0
        val tag = if (tagUnsynced) deUnsynchronise(tagBytes) else tagBytes

        val frames = parseFrames(tag, version, tagUnsynced, if (flags and 0x40 != 0) extendedHeaderSize(tag, version) else 0)
        val chaps = mutableListOf<Chap>()
        val ctocChildren = mutableListOf<String>()
        for (frame in frames) {
            when (frame.id) {
                "CHAP" -> parseChap(frame.data, version)?.let { chaps += it }
                "CTOC" -> ctocChildren += parseCtocChildren(frame.data)
            }
        }
        if (chaps.isEmpty()) return emptyList()

        val byId = chaps.associateBy { it.elementId }
        val ordered = buildList {
            if (ctocChildren.isNotEmpty()) {
                ctocChildren.forEach { id -> byId[id]?.let { add(it) } }
            }
            chaps.forEach { if (it.elementId !in ctocChildren) add(it) }
        }
        return ordered.mapIndexed { index, chap ->
            val end = if (chap.endMs == OPEN_ENDED) {
                ordered.getOrNull(index + 1)?.startMs ?: fileDurationMs.coerceAtLeast(chap.startMs)
            } else {
                chap.endMs
            }
            EmbeddedChapter(chap.title, chap.startMs, end)
        }
    }

    private class Frame(val id: String, val data: ByteArray)

    private class Chap(val elementId: String, val startMs: Long, val endMs: Long, val title: String)

    private fun parseFrames(tag: ByteArray, version: Int, tagUnsynced: Boolean, startOffset: Int): List<Frame> {
        val frames = mutableListOf<Frame>()
        var offset = startOffset
        while (offset + 10 <= tag.size) {
            val id = String(tag, offset, 4, Charsets.ISO_8859_1)
            if (id.firstOrNull() == '\u0000') break // padding
            val size = if (version == 4) synchsafe(tag, offset + 4) else be32(tag, offset + 4)
            val flag1 = tag[offset + 8].toInt() and 0xFF
            val flag2 = tag[offset + 9].toInt() and 0xFF
            val headerEnd = offset + 10
            if (size <= 0 || headerEnd + size > tag.size) break
            val frameBody = tag.copyOfRange(headerEnd, headerEnd + size)
            // Compressed/encrypted frames are not parseable — skip the payload.
            val compressed = if (version == 4) {
                flag2 and 0x20 != 0 || flag2 and 0x10 != 0
            } else {
                flag1 and 0x80 != 0 || flag1 and 0x40 != 0
            }
            if (compressed) {
                frames += Frame(id, ByteArray(0))
            } else {
                var body = frameBody
                // Frame-level unsynchronisation (v2.4 format flag 0x08). Skip when
                // the whole tag was already de-unsynchronised — applying it twice
                // would eat literal 0x00 bytes.
                if (version == 4 && !tagUnsynced && flag2 and 0x08 != 0) {
                    body = deUnsynchronise(frameBody)
                }
                // Grouping id (1 byte) and the v2.4 data-length indicator (4 bytes)
                // precede the frame data proper.
                var skip = 0
                if (version == 4) {
                    if (flag2 and 0x40 != 0) skip += 1
                    if (flag2 and 0x04 != 0) skip += 4
                } else {
                    if (flag1 and 0x20 != 0) skip += 1
                }
                val data = if (skip <= body.size) body.copyOfRange(skip, body.size) else ByteArray(0)
                frames += Frame(id, data)
            }
            offset = headerEnd + size
        }
        return frames
    }

    private fun parseChap(data: ByteArray, version: Int): Chap? {
        val elementId = cString(data, 0) ?: return null
        var offset = elementId.length + 1
        // Element id, start/end times (ms), then start/end byte offsets —
        // 0xFFFFFFFF when unknown (ffmpeg always writes them as such).
        if (offset + 16 > data.size) return null
        val start = be32(data, offset).toLong() and 0xFFFFFFFFL
        val end = be32(data, offset + 4).toLong() and 0xFFFFFFFFL
        offset += 16
        var title = ""
        while (offset + 10 <= data.size) {
            val subId = String(data, offset, 4, Charsets.ISO_8859_1)
            val remaining = data.size - (offset + 10)
            // Subframes follow the parent tag's size convention, but some v2.3
            // encoders backport CHAP with plain sizes — pick whichever
            // interpretation fits the remaining region.
            val synchsafeSize = synchsafe(data, offset + 4)
            val plainSize = be32(data, offset + 4)
            val size = when {
                synchsafeSize in 0..remaining && plainSize in 0..remaining ->
                    if (version == 4) synchsafeSize else plainSize
                synchsafeSize in 0..remaining -> synchsafeSize
                plainSize in 0..remaining -> plainSize
                else -> break
            }
            val subData = data.copyOfRange(offset + 10, offset + 10 + size)
            if (subId == "TIT2") title = decodeText(subData)
            offset += 10 + size
        }
        return Chap(elementId, start, end, title)
    }

    private fun parseCtocChildren(data: ByteArray): List<String> {
        val elementId = cString(data, 0) ?: return emptyList()
        var offset = elementId.length + 1
        if (offset + 2 > data.size) return emptyList()
        offset += 2 // flags + entry count
        val children = mutableListOf<String>()
        while (offset < data.size) {
            val child = cString(data, offset) ?: break
            children += child
            offset += child.length + 1
        }
        return children
    }

    /** v2.4: size includes the size field itself; v2.3: treated the same (the "6 or 10 bytes" reading). */
    private fun extendedHeaderSize(tag: ByteArray, version: Int): Int {
        if (tag.size < 4) return 0
        val size = if (version == 4) synchsafe(tag, 0) else be32(tag, 0)
        return size.coerceIn(0, tag.size)
    }

    private fun decodeText(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val encoding = data[0].toInt() and 0xFF
        val bytes = data.copyOfRange(1, data.size)
        val text = when (encoding) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            2 -> String(bytes, Charsets.UTF_16BE)
            3 -> String(bytes, Charsets.UTF_8)
            else -> { // 1 = UTF-16 with BOM
                when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                        String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
                    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                        String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
                    else -> String(bytes, Charsets.UTF_16BE)
                }
            }
        }
        return text.trim { it == '\u0000' || it == ' ' }
    }

    private fun cString(data: ByteArray, start: Int): String? {
        var end = start
        while (end < data.size && data[end].toInt() != 0) end++
        if (end >= data.size) return null
        return String(data, start, end - start, Charsets.ISO_8859_1)
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

    private fun synchsafe(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)

    private fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun deUnsynchronise(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i].toInt() == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0x00) i++
            i++
        }
        return out.toByteArray()
    }
}
