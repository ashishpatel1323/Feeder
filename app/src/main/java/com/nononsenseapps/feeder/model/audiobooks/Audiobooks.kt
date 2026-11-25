package com.nononsenseapps.feeder.model.audiobooks

import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** One audio file of an audiobook. Same fields as the web app's AudioTrack (web/src/lib/db.ts). */
@Serializable
data class AudioTrack(
    val path: String,
    val name: String,
    /** Dropbox content hash */
    val hash: String,
    val size: Long,
    /** Length in seconds, null until measured */
    val duration: Double? = null,
    /** Title from the file's tags */
    val title: String? = null,
    /** Chapter marks inside the file (m4b chapters, ID3 CHAP) */
    val marks: List<Mark> = emptyList(),
    /** Whether the tags have been read (once, on the first play) */
    val scanned: Boolean = false,
    /** The file on the phone (a document URI from the file picker), read in place */
    val uri: String? = null,
) {
    val lengthMs: Long get() = duration?.let { Math.round(it * 1000) } ?: 0
}

/** An audiobook in the Audiobooks tab: one file, or a folder of files played in name order. */
@Serializable
data class Audiobook(
    /** One file: its Dropbox content hash. A folder: a hash of its files' hashes, in play order. */
    val id: String,
    val title: String,
    val author: String = "",
    /** The file, or the folder, in Dropbox; null when the book is only on the phone */
    val dropboxPath: String?,
    val folder: Boolean,
    val tracks: List<AudioTrack>,
    val size: Long,
    /** Whether title, author and cover came from the files' tags */
    val described: Boolean = false,
    val addedAt: Long = 0,
    val lastOpenedAt: Long = 0,
    /** The picked folder or file on the phone the book came from */
    val source: String? = null,
) {
    /** Every file is on the phone, picked in place */
    val onPhone: Boolean get() = tracks.isNotEmpty() && tracks.all { it.uri != null }
}

/** Picked files that make one book (see [AudiobookRules.groupPicked]). */
data class PickedBook(
    val title: String,
    val folder: Boolean,
    val files: List<com.nononsenseapps.feeder.model.local.LocalEntry>,
    val cover: com.nononsenseapps.feeder.model.local.LocalEntry?,
)

/** A chapter across the book: file [i], start [startMs] in that file and [bookMs] in the book. */
data class Chapter(
    val i: Int,
    val startMs: Long,
    val bookMs: Long,
    val lengthMs: Long,
    val title: String,
)

/** A book found in a listing of /Audiobooks, before what is known about it is merged in. */
data class BookDraft(
    val id: String,
    val title: String,
    val dropboxPath: String,
    val folder: Boolean,
    val tracks: List<AudioTrack>,
    val size: Long,
    /** A picture in the folder (cover.jpg…), for the cover when the files have none */
    val coverPath: String?,
    val addedAt: Long,
)

/**
 * The library's rules, the same as the web app's (web/src/lib/audiobooks/library.ts and
 * timeline.ts): each top-level item in Dropbox's /Audiobooks folder is one book. A file there is
 * a book; a folder there is a book of every audio file inside it, subfolders included, in natural
 * name order with files before subfolders (as the Voice audiobook player orders them).
 */
object AudiobookRules {
    val AUDIO_EXTENSIONS = setOf("m4b", "m4a", "mp4", "aac", "mp3", "ogg", "oga", "opus", "flac", "wav", "webm")
    private val IMAGE = Regex("\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)

    fun extOf(name: String) = name.lowercase(Locale.ROOT).substringAfterLast('.', "")

    fun isAudioName(name: String) = extOf(name) in AUDIO_EXTENSIONS

    /** "02 - The Road_South.mp3" → "02 - The Road South" */
    fun nameWithoutExt(name: String) =
        name
            .replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
            .replace('_', ' ')
            .trim()
            .ifEmpty { name }

    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    /** Natural order: "2" before "10", case-insensitive. */
    fun naturalCompare(
        a: String,
        b: String,
    ): Int {
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (k in 0 until minOf(ra.size, rb.size)) {
            val x = ra[k]
            val y = rb[k]
            val c =
                if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }
                    val ny = y.trimStart('0').ifEmpty { "0" }
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else {
                    collator.compare(x, y)
                }
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    /** Play order of files inside one book, by their paths relative to the book's folder. */
    fun comparePaths(
        a: String,
        b: String,
    ): Int {
        val pa = a.split('/')
        val pb = b.split('/')
        for (k in 0 until minOf(pa.size, pb.size)) {
            val aFile = k == pa.size - 1
            val bFile = k == pb.size - 1
            if (aFile != bFile) return if (aFile) -1 else 1
            val c = naturalCompare(pa[k], pb[k])
            if (c != 0) return c
        }
        return pa.size - pb.size
    }

    private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A folder book's id: the same files in the same order give the same id, whatever the folder is called. */
    fun folderBookId(hashes: List<String>) = sha256Hex("audiobook\n" + hashes.joinToString("\n"))

    private fun modifiedAt(f: DropboxFile) = runCatching { Instant.parse(f.modified).toEpochMilli() }.getOrDefault(0L)

    private fun track(f: DropboxFile) = AudioTrack(path = f.path, name = f.name, hash = f.hash, size = f.size)

    /** Books in a listing of /Audiobooks (every file, subfolders included). */
    fun booksInListing(
        files: List<DropboxFile>,
        root: String = DropboxClient.AUDIOBOOKS_FOLDER,
        now: Long = System.currentTimeMillis(),
    ): List<BookDraft> {
        val prefix = root.lowercase(Locale.ROOT) + "/"
        val singles = mutableListOf<DropboxFile>()

        class Folder(
            val name: String,
        ) {
            val files = mutableListOf<Pair<String, DropboxFile>>()
            val images = mutableListOf<Pair<String, DropboxFile>>()
        }
        val folders = LinkedHashMap<String, Folder>()
        for (f in files) {
            if (!f.path.lowercase(Locale.ROOT).startsWith(prefix)) continue
            val rel = f.path.substring(prefix.length)
            val cut = rel.indexOf('/')
            if (cut < 0) {
                if (isAudioName(f.name) && f.hash.isNotEmpty()) singles += f
                continue
            }
            val name = rel.substring(0, cut)
            val entry = folders.getOrPut(name.lowercase(Locale.ROOT)) { Folder(name) }
            val inner = rel.substring(cut + 1)
            if (isAudioName(f.name) && f.hash.isNotEmpty()) {
                entry.files += inner to f
            } else if (IMAGE.containsMatchIn(f.name)) {
                entry.images += inner to f
            }
        }
        val out =
            singles
                .map { f ->
                    BookDraft(f.hash, nameWithoutExt(f.name), f.path, false, listOf(track(f)), f.size, null, modifiedAt(f).takeIf { it > 0 } ?: now)
                }.toMutableList()
        for (folder in folders.values) {
            if (folder.files.isEmpty()) continue
            val sorted = folder.files.sortedWith { a, b -> comparePaths(a.first, b.first) }
            val tracks = sorted.map { track(it.second) }
            val top = folder.images.filter { '/' !in it.first }
            val cover = top.firstOrNull { Regex("^(cover|folder|front)\\.", RegexOption.IGNORE_CASE).containsMatchIn(it.second.name) } ?: top.firstOrNull() ?: folder.images.firstOrNull()
            out +=
                BookDraft(
                    id = folderBookId(tracks.map { it.hash }),
                    title =
                        folder.name
                            .replace('_', ' ')
                            .trim()
                            .ifEmpty { folder.name },
                    dropboxPath = sorted[0].second.path.substring(0, prefix.length) + folder.name,
                    folder = true,
                    tracks = tracks,
                    size = tracks.sumOf { it.size },
                    coverPath = cover?.second?.path,
                    addedAt = sorted.maxOf { modifiedAt(it.second) }.takeIf { it > 0 } ?: now,
                )
        }
        return out
    }

    /** How the library changes after a listing: new books, moved ones, and ones no longer there. */
    fun mergeBooks(
        have: List<Audiobook>,
        drafts: List<BookDraft>,
        keep: (Audiobook) -> Boolean = { false },
    ): Pair<List<Audiobook>, List<String>> {
        val byId = have.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val put = mutableListOf<Audiobook>()
        for (d in drafts) {
            if (!seen.add(d.id)) continue
            val old = byId[d.id]
            if (old == null) {
                put += Audiobook(d.id, d.title, "", d.dropboxPath, d.folder, d.tracks, d.size, false, d.addedAt, 0)
                continue
            }
            val known = old.tracks.associateBy { it.hash }
            val tracks = d.tracks.map { t -> (known[t.hash] ?: t).copy(path = t.path, name = t.name) }
            val moved = old.dropboxPath != d.dropboxPath || tracks.indices.any { tracks[it].path != old.tracks.getOrNull(it)?.path }
            if (moved) put += old.copy(dropboxPath = d.dropboxPath, tracks = tracks, title = if (old.described) old.title else d.title)
        }
        val remove = mutableListOf<String>()
        for (b in have) {
            if (b.id in seen || b.dropboxPath == null) continue
            // Gone from Dropbox: forget it unless the phone has its files, then it stays as a phone book
            if (b.onPhone || keep(b)) put += b.copy(dropboxPath = null) else remove += b.id
        }
        return put to remove
    }

    /**
     * How picked files make books, the same as the web app's groupPicked: loose files or a folder
     * of files only make one book named [title] (several .m4b files make one book each); a folder
     * with subfolders of audio follows Dropbox's rule, each file and subfolder there is one book.
     * Paths of the files are cut to inside their book.
     */
    fun groupPicked(
        picked: List<com.nononsenseapps.feeder.model.local.LocalEntry>,
        title: String,
    ): List<PickedBook> {
        val audio = picked.filter { isAudioName(it.name) }
        val images = picked.filter { IMAGE.containsMatchIn(it.name) }
        val nested = audio.any { '/' in it.rel }
        val allM4b = audio.size > 1 && audio.all { extOf(it.name) == "m4b" }
        val named = Regex("^(cover|folder|front)\\.", RegexOption.IGNORE_CASE)

        fun coverOf(list: List<com.nononsenseapps.feeder.model.local.LocalEntry>) = list.filter { '/' !in it.rel }.let { top -> top.firstOrNull { named.containsMatchIn(it.name) } ?: top.firstOrNull() }
        if (!nested && !allM4b) {
            if (audio.isEmpty()) return emptyList()
            val sorted = audio.sortedWith { a, b -> comparePaths(a.rel, b.rel) }
            return listOf(PickedBook(if (audio.size == 1) nameWithoutExt(audio[0].name) else title, audio.size > 1, sorted, coverOf(images)))
        }
        val out = mutableListOf<PickedBook>()
        val folders = LinkedHashMap<String, MutableList<com.nononsenseapps.feeder.model.local.LocalEntry>>()
        for (p in audio) {
            val cut = p.rel.indexOf('/')
            if (cut < 0) out += PickedBook(nameWithoutExt(p.name), false, listOf(p), null) else folders.getOrPut(p.rel.substring(0, cut)) { mutableListOf() } += p
        }
        for ((name, list) in folders) {
            val inner = list.map { it.copy(rel = it.rel.substring(name.length + 1)) }.sortedWith { a, b -> comparePaths(a.rel, b.rel) }
            val pics = images.filter { it.rel.startsWith("$name/") }.map { it.copy(rel = it.rel.substring(name.length + 1)) }
            out += PickedBook(name.replace('_', ' ').trim().ifEmpty { name }, true, inner, coverOf(pics) ?: pics.firstOrNull())
        }
        return out
    }

    // ---- The timeline ----

    /** The book's length in ms, 0 when any file's length is still unknown. */
    fun bookLengthMs(b: Audiobook): Long = if (b.tracks.any { it.duration == null }) 0 else b.tracks.sumOf { it.lengthMs }

    fun trackStartMs(
        b: Audiobook,
        i: Int,
    ): Long = b.tracks.take(i.coerceAtLeast(0)).sumOf { it.lengthMs }

    fun chaptersOf(b: Audiobook): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var base = 0L
        b.tracks.forEachIndexed { i, t ->
            val len = t.lengthMs
            val marks = t.marks.ifEmpty { listOf(Mark(0, t.title ?: nameWithoutExt(t.name))) }
            marks.forEachIndexed { k, m ->
                val end = marks.getOrNull(k + 1)?.startMs ?: len
                out += Chapter(i, m.startMs, base + m.startMs, (end - m.startMs).coerceAtLeast(0), m.title)
            }
            base += len
        }
        return out
    }

    /** Index of the chapter playing at [ms] into file [i]. */
    fun chapterAt(
        chapters: List<Chapter>,
        i: Int,
        ms: Long,
    ): Int {
        var found = -1
        for (k in chapters.indices) {
            val c = chapters[k]
            if (c.i < i || (c.i == i && c.startMs <= ms + 250)) found = k else break
        }
        return found.coerceAtLeast(0)
    }

    /** Where to carry on from a saved position (found by file hash first), or null to start over. */
    fun resumePoint(
        b: Audiobook,
        pos: AudioPos?,
    ): Pair<Int, Long>? {
        if (pos == null || b.tracks.isEmpty()) return null
        var i = if (pos.h.isNotEmpty()) b.tracks.indexOfFirst { it.hash == pos.h } else -1
        if (i < 0) i = if (pos.i in b.tracks.indices) pos.i else return null
        if (pos.f >= 0.999 && i == b.tracks.size - 1) return null
        val len = b.tracks[i].lengthMs
        var ms = pos.ms.coerceAtLeast(0)
        if (len > 0 && ms > len - 2000) ms = (len - 2000).coerceAtLeast(0)
        return i to ms
    }

    /** 0–1 through the book at [ms] into file [i]; 0 while lengths are unknown. */
    fun fractionAt(
        b: Audiobook,
        i: Int,
        ms: Long,
    ): Double {
        val total = bookLengthMs(b)
        if (total <= 0) return 0.0
        return ((trackStartMs(b, i) + ms).toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** The file and offset at [bookMs] into the whole book. */
    fun locate(
        b: Audiobook,
        bookMs: Long,
    ): Pair<Int, Long> {
        var base = 0L
        b.tracks.forEachIndexed { i, t ->
            val len = t.lengthMs
            if (len == 0L || bookMs < base + len || i == b.tracks.size - 1) return i to (bookMs - base).coerceAtLeast(0)
            base += len
        }
        return 0 to 0L
    }

    /** "1:02:03" or "4:05" */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "5 h 12 min" or "42 min" */
    fun lengthText(ms: Long): String {
        val min = Math.round(ms / 60000.0)
        if (min < 60) return "${min.coerceAtLeast(1)} min"
        val h = min / 60
        return if (min % 60 > 0) "$h h ${min % 60} min" else "$h h"
    }
}
