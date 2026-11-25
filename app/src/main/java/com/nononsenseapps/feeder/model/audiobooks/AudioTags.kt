package com.nononsenseapps.feeder.model.audiobooks

import kotlinx.serialization.Serializable
import java.nio.charset.Charset

/**
 * Reads what an audiobook file says about itself: length, title, author, cover and chapters.
 * The same rules as the web app's web/src/lib/audiobooks/tags.ts.
 *
 * MP4 (m4b, m4a, mp4): the length from `mvhd`, tags from `udta/meta/ilst`, and chapters from the
 * Nero `udta/chpl` atom or, failing that, a QuickTime chapter text track (`tref/chap`). This
 * follows the Voice audiobook player's MP4 chapter reader (github.com/PaulWoitaschek/Voice,
 * GPL-3.0). MP3: ID3v2 tags (TIT2, TALB, TPE1/TPE2, APIC) and ID3 chapters (CHAP frames).
 *
 * Everything reads through a [RangeReader], so only the metadata is fetched from Dropbox.
 */
interface RangeReader {
    val size: Long

    suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray
}

/** A chapter mark inside one file: start in ms from the start of the file. */
@Serializable
data class Mark(
    val startMs: Long,
    val title: String,
)

class FileTags(
    /** Length in seconds, when the file says (MP4 does; for MP3 it is measured) */
    val duration: Double? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val cover: ByteArray? = null,
    val chapters: List<Mark> = emptyList(),
)

class BytesReader(
    private val bytes: ByteArray,
) : RangeReader {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray = bytes.copyOfRange(offset.coerceIn(0, size).toInt(), (offset + length).coerceIn(0, size).toInt())
}

object AudioTags {
    private val latin1 = Charsets.ISO_8859_1
    private val utf8 = Charsets.UTF_8

    fun isMp4Name(name: String) = Regex("\\.(m4b|m4a|mp4|aac)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** Reads the tags of a file, by its name's extension (unknown types give empty tags). */
    suspend fun read(
        name: String,
        r: RangeReader,
    ): FileTags =
        try {
            when {
                isMp4Name(name) -> readMp4(r)
                name.endsWith(".mp3", ignoreCase = true) -> readId3(r)
                else -> FileTags()
            }
        } catch (e: Exception) {
            FileTags()
        }

    private fun fourcc(
        b: ByteArray,
        at: Int,
    ) = String(b, at, 4, latin1)

    private fun u8(
        b: ByteArray,
        at: Int,
    ) = b[at].toInt() and 0xff

    private fun u16(
        b: ByteArray,
        at: Int,
    ) = (u8(b, at) shl 8) or u8(b, at + 1)

    private fun u32(
        b: ByteArray,
        at: Int,
    ): Long = (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()

    private fun u64(
        b: ByteArray,
        at: Int,
    ): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun clean(s: String) = s.trimEnd('\u0000').trim()

    // ---------------------------------------------------------------------------------------
    // MP4

    private data class Box(
        val type: String,
        val start: Int,
        val end: Int,
    )

    private fun boxes(
        b: ByteArray,
        from: Int,
        to: Int,
    ): List<Box> {
        val out = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            val type = fourcc(b, at + 4)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = u64(b, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || at + size > to) break
            out += Box(type, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun child(
        b: ByteArray,
        box: Box?,
        type: String,
        skip: Int = 0,
    ): Box? = box?.let { p -> boxes(b, p.start + skip, p.end).firstOrNull { it.type == type } }

    private fun path(
        b: ByteArray,
        box: Box?,
        vararg types: String,
    ): Box? = types.fold(box) { at, t -> child(b, at, t) }

    /** Finds the top-level `moov` by reading only box headers, wherever it is in the file. */
    private suspend fun findMoov(r: RangeReader): ByteArray? {
        var at = 0L
        repeat(64) {
            if (at + 8 > r.size) return null
            val h = r.read(at, 16)
            if (h.size < 8) return null
            var size = u32(h, 0)
            val type = fourcc(h, 4)
            if (size == 1L && h.size >= 16) {
                size = u64(h, 8)
            } else if (size == 0L) {
                size = r.size - at
            }
            if (size < 8) return null
            if (type == "moov") {
                if (size > 64L * 1024 * 1024) return null
                val moov = r.read(at, size.toInt())
                return if (moov.size.toLong() == size) moov else null
            }
            at += size
        }
        return null
    }

    suspend fun readMp4(r: RangeReader): FileTags {
        val b = findMoov(r) ?: return FileTags()
        val moov = boxes(b, 0, b.size).firstOrNull()?.takeIf { it.type == "moov" } ?: return FileTags()
        var duration: Double? = null
        child(b, moov, "mvhd")?.let { mvhd ->
            val v = u8(b, mvhd.start)
            val scale = if (v == 1) u32(b, mvhd.start + 20) else u32(b, mvhd.start + 12)
            val dur = if (v == 1) u64(b, mvhd.start + 24) else u32(b, mvhd.start + 16)
            if (scale > 0 && dur > 0) duration = dur.toDouble() / scale
        }
        val tags = Ilst()
        val udta = child(b, moov, "udta")
        readIlst(b, udta, tags)
        if (tags.title == null && tags.album == null) readIlst(b, moov, tags)
        var chapters = child(b, udta, "chpl")?.let { readChpl(b, it) }.orEmpty()
        if (chapters.isEmpty()) chapters = readChapterTrack(b, moov, r)
        return FileTags(duration, tags.title, tags.album, tags.artist, tags.cover, tidyMarks(chapters, duration))
    }

    private class Ilst {
        var title: String? = null
        var album: String? = null
        var artist: String? = null
        var cover: ByteArray? = null
    }

    private fun readIlst(
        b: ByteArray,
        parent: Box?,
        out: Ilst,
    ) {
        val meta = child(b, parent, "meta") ?: return
        // iTunes' meta is a full box (4 bytes of version and flags); QuickTime's is not
        val full = meta.start + 8 <= meta.end && fourcc(b, meta.start + 4) != "hdlr"
        val ilst = child(b, meta, "ilst", if (full) 4 else 0) ?: return
        for (item in boxes(b, ilst.start, ilst.end)) {
            val data = child(b, item, "data") ?: continue
            if (data.end - data.start < 8) continue
            val payload = b.copyOfRange(data.start + 8, data.end)

            fun text() = clean(String(payload, utf8)).ifEmpty { null }
            when (item.type) {
                "©nam" -> out.title = out.title ?: text()
                "©alb" -> out.album = out.album ?: text()
                "©ART", "aART", "©wrt" -> out.artist = out.artist ?: text()
                "covr" -> if (out.cover == null && payload.isNotEmpty()) out.cover = payload
            }
        }
    }

    /** Nero chapters: times in 100 ns units. */
    private fun readChpl(
        b: ByteArray,
        chpl: Box,
    ): List<Mark> {
        val v = u8(b, chpl.start)
        if (v != 0 && v != 1) return emptyList()
        var at = chpl.start + 4 + if (v == 1) 4 else 0
        val count = u8(b, at++)
        val out = mutableListOf<Mark>()
        for (i in 0 until count) {
            if (at >= chpl.end) break
            val t = if (v == 1) u64(b, at) else u32(b, at)
            at += if (v == 1) 8 else 4
            val len = u8(b, at++)
            out += Mark(t / 10_000, clean(String(b, at, len.coerceAtMost(chpl.end - at), utf8)))
            at += len
        }
        return out
    }

    /** A QuickTime chapter track: an audio track's `tref/chap` names a text track of chapter titles. */
    private suspend fun readChapterTrack(
        b: ByteArray,
        moov: Box,
        r: RangeReader,
    ): List<Mark> {
        val tracks =
            boxes(b, moov.start, moov.end).filter { it.type == "trak" }.map { box ->
                val tkhd = child(b, box, "tkhd")
                (tkhd?.let { u32(b, it.start + if (u8(b, it.start) == 1) 20 else 12) } ?: 0L) to box
            }
        val chapId =
            tracks.firstNotNullOfOrNull { (_, box) ->
                path(b, box, "tref", "chap")?.takeIf { it.end - it.start >= 4 }?.let { u32(b, it.start) }
            } ?: return emptyList()
        val track = tracks.firstOrNull { it.first == chapId }?.second ?: return emptyList()
        val mdia = child(b, track, "mdia")
        val mdhd = child(b, mdia, "mdhd") ?: return emptyList()
        val stbl = path(b, mdia, "minf", "stbl") ?: return emptyList()
        val scale = if (u8(b, mdhd.start) == 1) u32(b, mdhd.start + 20) else u32(b, mdhd.start + 12)
        if (scale == 0L) return emptyList()

        val durations = mutableListOf<Long>()
        child(b, stbl, "stts")?.let { stts ->
            val n = u32(b, stts.start + 4)
            for (i in 0 until n) {
                val count = u32(b, stts.start + 8 + (i * 8).toInt())
                val delta = u32(b, stts.start + 12 + (i * 8).toInt())
                var k = 0L
                while (k < count && durations.size < 10_000) {
                    durations += delta
                    k++
                }
            }
        }
        val sizes = mutableListOf<Int>()
        child(b, stbl, "stsz")?.let { stsz ->
            val fixed = u32(b, stsz.start + 4)
            val n = u32(b, stsz.start + 8)
            for (i in 0 until minOf(n, 10_000L)) sizes += (if (fixed > 0) fixed else u32(b, stsz.start + 12 + (i * 4).toInt())).toInt()
        }
        val offsets = mutableListOf<Long>()
        val stco = child(b, stbl, "stco")
        val co64 = child(b, stbl, "co64")
        if (stco != null) {
            for (i in 0 until u32(b, stco.start + 4)) offsets += u32(b, stco.start + 8 + (i * 4).toInt())
        } else if (co64 != null) {
            for (i in 0 until u32(b, co64.start + 4)) offsets += u64(b, co64.start + 8 + (i * 8).toInt())
        }
        val runs = mutableListOf<Pair<Long, Long>>()
        child(b, stbl, "stsc")?.let { stsc ->
            for (i in 0 until u32(b, stsc.start + 4)) runs += u32(b, stsc.start + 8 + (i * 12).toInt()) to u32(b, stsc.start + 12 + (i * 12).toInt())
        }
        if (runs.isEmpty()) runs += 1L to 1L

        val where = mutableListOf<Long>()
        offsets.forEachIndexed { c, off ->
            var per = 1L
            for ((first, p) in runs) if (c + 1 >= first) per = p
            var at = off
            var k = 0L
            while (k < per && where.size < sizes.size) {
                where += at
                at += sizes.getOrElse(where.size - 1) { 0 }
                k++
            }
        }
        val n = minOf(where.size, if (sizes.isEmpty()) where.size else sizes.size)
        if (n == 0) return emptyList()
        val lo = where.take(n).min()
        val hi = (0 until n).maxOf { where[it] + sizes.getOrElse(it) { 256 } }
        val block = if (hi - lo <= 4L * 1024 * 1024) r.read(lo, (hi - lo).toInt()) else null
        val out = mutableListOf<Mark>()
        var t = 0L
        for (i in 0 until n) {
            val len = sizes.getOrElse(i) { 256 }
            val s =
                if (block != null) {
                    val from = (where[i] - lo).toInt().coerceIn(0, block.size)
                    block.copyOfRange(from, (from + len).coerceAtMost(block.size))
                } else {
                    r.read(where[i], len)
                }
            out += Mark(t * 1000 / scale, textSample(s))
            t += durations.getOrElse(i) { 0 }
        }
        return out
    }

    /** A 3GPP/QuickTime text sample: 16-bit length, then UTF-8 or UTF-16 (with BOM) text. */
    private fun textSample(s: ByteArray): String {
        if (s.size < 2) return ""
        val len = minOf(u16(s, 0), s.size - 2)
        val t = s.copyOfRange(2, 2 + len)
        return when {
            t.size >= 2 && u8(t, 0) == 0xfe && u8(t, 1) == 0xff -> clean(String(t, 2, t.size - 2, Charsets.UTF_16BE))
            t.size >= 2 && u8(t, 0) == 0xff && u8(t, 1) == 0xfe -> clean(String(t, 2, t.size - 2, Charsets.UTF_16LE))
            else -> clean(String(t, utf8))
        }
    }

    /** Sorted, de-duplicated, inside the file, and named ("Chapter 3" for blank ones). */
    fun tidyMarks(
        marks: List<Mark>,
        duration: Double?,
    ): List<Mark> {
        val max = duration?.let { it * 1000 } ?: Double.MAX_VALUE
        val out = mutableListOf<Mark>()
        for (m in marks.sortedBy { it.startMs }) {
            if (m.startMs < 0 || m.startMs >= max) continue
            if (out.isNotEmpty() && m.startMs - out.last().startMs < 500) continue
            out += Mark(m.startMs, m.title.ifBlank { "Chapter ${out.size + 1}" })
        }
        if (out.size == 1) return emptyList()
        if (out.isNotEmpty() && out[0].startMs > 0) out[0] = out[0].copy(startMs = 0)
        return out
    }

    // ---------------------------------------------------------------------------------------
    // ID3v2 (MP3)

    private fun syncsafe(
        b: ByteArray,
        at: Int,
    ) = (u8(b, at) shl 21) or (u8(b, at + 1) shl 14) or (u8(b, at + 2) shl 7) or u8(b, at + 3)

    private fun id3Text(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val cs: Charset =
            when (u8(b, 0)) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> utf8
                else -> latin1
            }
        return clean(String(b, 1, b.size - 1, cs))
    }

    private fun skipTerminated(
        b: ByteArray,
        at: Int,
        enc: Int,
    ): Int {
        if (enc == 1 || enc == 2) {
            var i = at
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return b.size
        }
        for (i in at until b.size) if (b[i].toInt() == 0) return i + 1
        return b.size
    }

    private class Frame(
        val id: String,
        val data: ByteArray,
    )

    private fun frames(
        b: ByteArray,
        from: Int,
        to: Int,
        major: Int,
    ): List<Frame> {
        val out = mutableListOf<Frame>()
        var at = from
        while (at + 10 <= to) {
            val id = String(b, at, 4, latin1)
            if (!Regex("^[A-Z0-9]{4}$").matches(id)) break
            val size = if (major >= 4) syncsafe(b, at + 4) else u32(b, at + 4).toInt()
            if (size <= 0 || at + 10 + size > to) break
            out += Frame(id, b.copyOfRange(at + 10, at + 10 + size))
            at += 10 + size
        }
        return out
    }

    suspend fun readId3(r: RangeReader): FileTags {
        val head = r.read(0, 10)
        if (head.size < 10 || String(head, 0, 3, latin1) != "ID3") return FileTags()
        val major = u8(head, 3)
        if (major < 3) return FileTags()
        val size = syncsafe(head, 6)
        if (size > 32 * 1024 * 1024) return FileTags()
        val b = r.read(0, 10 + size)
        var from = 10
        if (u8(head, 5) and 0x40 != 0) from += if (major >= 4) syncsafe(b, 10) else u32(b, 10).toInt() + 4
        var title: String? = null
        var album: String? = null
        var artist: String? = null
        var cover: ByteArray? = null
        val chapters = mutableListOf<Mark>()
        for (f in frames(b, from, b.size, major)) {
            when (f.id) {
                "TIT2" -> title = title ?: id3Text(f.data).ifEmpty { null }
                "TALB" -> album = album ?: id3Text(f.data).ifEmpty { null }
                "TPE1", "TPE2" -> artist = artist ?: id3Text(f.data).ifEmpty { null }
                "APIC" ->
                    if (cover == null && f.data.size > 4) {
                        val enc = u8(f.data, 0)
                        val mimeEnd = f.data.indexOfFirstFrom(1) { it.toInt() == 0 }
                        if (mimeEnd > 0) {
                            val descEnd = skipTerminated(f.data, mimeEnd + 2, enc)
                            if (descEnd < f.data.size) cover = f.data.copyOfRange(descEnd, f.data.size)
                        }
                    }
                "CHAP" -> {
                    val idEnd = f.data.indexOfFirstFrom(0) { it.toInt() == 0 }
                    if (idEnd >= 0 && idEnd + 17 <= f.data.size) {
                        val at = idEnd + 1
                        val startMs = u32(f.data, at)
                        val tit = frames(f.data, at + 16, f.data.size, major).firstOrNull { it.id == "TIT2" }
                        chapters += Mark(startMs, tit?.let { id3Text(it.data) }.orEmpty())
                    }
                }
            }
        }
        return FileTags(null, title, album, artist, cover, tidyMarks(chapters, null))
    }

    private inline fun ByteArray.indexOfFirstFrom(
        from: Int,
        pred: (Byte) -> Boolean,
    ): Int {
        for (i in from until size) if (pred(this[i])) return i
        return -1
    }
}
