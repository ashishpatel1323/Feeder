package com.nononsenseapps.feeder.model.audiobooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.content.edit
import android.net.Uri
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.local.LocalEntry
import com.nononsenseapps.feeder.model.local.LocalFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A download to the phone in progress: bytes done of all the book's bytes. */
data class AudiobookJob(
    val bookId: String,
    val done: Long,
    val total: Long,
)

/**
 * The Audiobooks tab's library, with the same rules as the web app's: books live in Dropbox's
 * /Audiobooks folder (see [AudiobookRules]) and stream from there. A book can also be downloaded
 * to the phone (Android/data/<app>/files/audiobooks) to play offline; the player then uses those
 * files. Books can also be only on the phone: files or folders picked with Android's file picker
 * are read where they are (nothing is copied or uploaded), and a picked folder is looked at again
 * on every refresh, so new books there show up. Title, author, cover, lengths and chapters are
 * read from the files once, on first play.
 */
class AudiobookLibrary private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("audiobooks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Audiobook.serializer())
    private val dropbox = DropboxClient.get(context)
    private val mutex = Mutex()
    private val coverDir = File(context.filesDir, "audiobook-covers").apply { mkdirs() }
    val dir: File = (context.getExternalFilesDir("audiobooks") ?: File(context.filesDir, "audiobooks")).apply { mkdirs() }

    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Audiobook>> = _books.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _jobs = MutableStateFlow<Map<String, AudiobookJob>>(emptyMap())
    val jobs: StateFlow<Map<String, AudiobookJob>> = _jobs.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /** Bumped when a cover or the downloaded files change, so screens reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val coverPaths = ConcurrentHashMap<String, String>()
    private val scans = ConcurrentHashMap<String, Deferred<Audiobook?>>()
    private val pickedCovers = ConcurrentHashMap<String, Uri>()
    private val _adding = MutableStateFlow<String?>(null)

    /** What is being added from the phone right now ("Reading Dune…"), or null. */
    val adding: StateFlow<String?> = _adding.asStateFlow()

    fun consumeMessage() {
        _messages.value = null
    }

    private fun load(): List<Audiobook> = runCatching { json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    @Synchronized
    private fun change(f: (List<Audiobook>) -> List<Audiobook>) {
        val list = f(_books.value)
        _books.value = list
        prefs.edit { putString(KEY, json.encodeToString(serializer, list)) }
    }

    private fun patch(
        id: String,
        f: (Audiobook) -> Audiobook,
    ) = change { list -> list.map { if (it.id == id) f(it) else it } }

    fun get(id: String): Audiobook? = _books.value.firstOrNull { it.id == id }

    fun coverFile(id: String) = File(coverDir, "$id.jpg")

    /** The phone's copy of a file, when it was downloaded. */
    fun localFile(t: AudioTrack): File? = File(dir, "${t.hash}.${AudiobookRules.extOf(t.name).ifBlank { "audio" }}").takeIf { it.exists() && it.length() == t.size }

    fun isDownloaded(b: Audiobook) = b.tracks.all { localFile(it) != null }

    /** Whether the book plays without Dropbox: picked on the phone, or downloaded. */
    fun onPhone(b: Audiobook) = b.onPhone || isDownloaded(b)

    // ---- Dropbox ----

    /** Reads Dropbox's /Audiobooks folder again. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                _refreshing.value = true
                try {
                    scanPicked()
                    if (dropbox.link.value == null) return@withLock
                    val drafts = AudiobookRules.booksInListing(dropbox.listTree(DropboxClient.AUDIOBOOKS_FOLDER))
                    drafts.forEach { d -> d.coverPath?.let { coverPaths[d.id] = it } }
                    val (put, remove) = AudiobookRules.mergeBooks(_books.value, drafts) { isDownloaded(it) }
                    val byId = put.associateBy { it.id }
                    change { list -> list.filterNot { it.id in remove }.map { byId[it.id] ?: it } + put.filter { p -> list.none { it.id == p.id } } }
                    // Covers and titles for new books, a few at a time
                    _books.value.filter { !it.described }.take(12).forEach { b ->
                        runCatching { describe(b, null) }.onFailure { Log.w(LOG_TAG, "Could not describe ${b.title}", it) }
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not read Dropbox", e)
                    _messages.value = e.message
                } finally {
                    _refreshing.value = false
                }
            }
        }
    }

    /** Reads only the asked-for bytes of a Dropbox file, 64 KB at a time, with a small cache. */
    private fun reader(t: AudioTrack): RangeReader {
        val picked = t.uri?.let(Uri::parse)
        if (localFile(t) == null && picked != null) {
            return object : RangeReader {
                override val size = t.size

                override suspend fun read(
                    offset: Long,
                    length: Int,
                ): ByteArray =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openFileDescriptor(picked, "r")!!.use { pfd ->
                            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                                val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
                                val buf = java.nio.ByteBuffer.allocate(n)
                                ch.position(offset)
                                while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
                                buf.array().copyOf(buf.position())
                            }
                        }
                    }
            }
        }
        localFile(t)?.let { f ->
            return object : RangeReader {
                override val size = f.length()

                override suspend fun read(
                    offset: Long,
                    length: Int,
                ): ByteArray =
                    withContext(Dispatchers.IO) {
                        java.io.RandomAccessFile(f, "r").use { raf ->
                            val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
                            val out = ByteArray(n)
                            raf.seek(offset)
                            raf.readFully(out)
                            out
                        }
                    }
            }
        }
        val cache = HashMap<Long, ByteArray>()
        val block = 64 * 1024
        return object : RangeReader {
            override val size = t.size

            override suspend fun read(
                offset: Long,
                length: Int,
            ): ByteArray {
                val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
                if (n == 0) return ByteArray(0)
                if (n > block * 4) return dropbox.downloadRange(t.path, offset, n)
                val first = offset / block
                val last = (offset + n - 1) / block
                val out = ByteArrayOutputStream()
                for (k in first..last) {
                    out.write(cache.getOrPut(k) { dropbox.downloadRange(t.path, k * block, minOf(block.toLong(), size - k * block).toInt()) })
                }
                val all = out.toByteArray()
                val from = (offset - first * block).toInt()
                return all.copyOfRange(from, (from + n).coerceAtMost(all.size))
            }
        }
    }

    private suspend fun measure(t: AudioTrack): Double? =
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                val local = localFile(t)
                when {
                    local != null -> r.setDataSource(local.path)
                    t.uri != null -> r.setDataSource(context, Uri.parse(t.uri))
                    else -> r.setDataSource(dropbox.temporaryLink(t.path), emptyMap())
                }
                r
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { it / 1000.0 }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not measure ${t.name}", e)
                null
            } finally {
                runCatching { r.release() }
            }
        }

    private suspend fun scanTrack(t: AudioTrack): Pair<AudioTrack, FileTags?> {
        val tags = runCatching { AudioTags.read(t.name, reader(t)) }.getOrNull()
        val duration = tags?.duration ?: measure(t)
        return t.copy(duration = duration, title = tags?.title, marks = tags?.chapters.orEmpty(), scanned = true) to tags
    }

    /**
     * Reads the files' lengths, chapters and tags (once per book on this phone), and the title,
     * author and cover. [onProgress] gets files done and files in all.
     */
    suspend fun scan(
        id: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Audiobook? {
        val job =
            scans.getOrPut(id) {
                scope
                    .async {
                        val b = get(id) ?: return@async null
                        var done = b.tracks.count { it.scanned }
                        onProgress(done, b.tracks.size)
                        var firstTags: FileTags? = null
                        val gate = Semaphore(4)
                        b.tracks
                            .withIndex()
                            .filter { !it.value.scanned }
                            .map { (i, t) ->
                                async {
                                    gate.withPermit {
                                        val (track, tags) = scanTrack(t)
                                        if (i == 0) firstTags = tags
                                        patch(id) { cur -> cur.copy(tracks = cur.tracks.map { if (it.hash == track.hash && it.path == track.path) track else it }) }
                                        synchronized(this@AudiobookLibrary) { done++ }
                                        onProgress(done, b.tracks.size)
                                    }
                                }
                            }.awaitAll()
                        get(id)?.let { if (!it.described) describe(it, firstTags) }
                        get(id)
                    }.also { d -> d.invokeOnCompletion { scans.remove(id) } }
            }
        return job.await()
    }

    /** Title, author and cover from the first file's tags or the folder's picture. */
    private suspend fun describe(
        b: Audiobook,
        tags: FileTags?,
    ) {
        val first = tags ?: b.tracks.firstOrNull()?.let { runCatching { AudioTags.read(it.name, reader(it)) }.getOrNull() }
        var cover = first?.cover?.let(::smallCover)
        if (cover == null) {
            pickedCovers[b.id]?.let { uri -> cover = runCatching { context.contentResolver.openInputStream(uri)?.use { smallCover(it.readBytes()) } }.getOrNull() }
        }
        if (cover == null) {
            coverPaths[b.id]?.let { path ->
                runCatching {
                    val tmp = File(coverDir, "${b.id}.download")
                    dropbox.download(path, tmp)
                    cover = smallCover(tmp.readBytes())
                    tmp.delete()
                }
            }
        }
        cover?.let {
            coverFile(b.id).writeBytes(it)
            _version.update { v -> v + 1 }
        }
        val title = (if (b.folder) first?.album else first?.title ?: first?.album)?.takeIf { it.isNotBlank() } ?: b.title
        patch(b.id) { it.copy(title = title, author = first?.artist?.takeIf { a -> a.isNotBlank() } ?: it.author, described = true) }
    }

    private fun smallCover(bytes: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(480, maxOf(bmp.width, bmp.height))
        val scale = side.toFloat() / maxOf(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    fun markOpened(id: String) = patch(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    // ---- On the phone ----

    /** Downloads every file of a book so it plays offline. */
    fun download(id: String) {
        val b = get(id) ?: return
        if (_jobs.value.containsKey(id)) return
        _jobs.update { it + (id to AudiobookJob(id, 0, b.size)) }
        scope.launch {
            try {
                var before = 0L
                for (t in b.tracks) {
                    if (localFile(t) == null) {
                        val dest = File(dir, "${t.hash}.${AudiobookRules.extOf(t.name).ifBlank { "audio" }}")
                        dropbox.download(t.path, dest) { done, _ -> _jobs.update { it + (id to AudiobookJob(id, before + done, b.size)) } }
                    }
                    before += t.size
                }
                _messages.value = "${b.title} is on this phone"
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Download failed", e)
                _messages.value = e.message ?: "Download failed"
            } finally {
                _jobs.update { it - id }
                _version.update { it + 1 }
            }
        }
    }

    // ---- Picked on the phone ----

    /** Picked folders and files, as "folder|<uri>" or "files|<title>|<uri> <uri>…". */
    private fun picks(): Set<String> = prefs.getStringSet(KEY_PICKS, emptySet()).orEmpty()

    /** Books the user removed from the library: a rescan of their folder leaves them out. */
    private fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    /** Adds a folder from the phone: one book, or a book per subfolder and per file (see [AudiobookRules.groupPicked]). */
    fun addFolder(tree: Uri) {
        LocalFiles.keep(context, tree)
        prefs.edit { putStringSet(KEY_PICKS, picks() + "folder|$tree") }
        scope.launch {
            mutex.withLock {
                val n = addPicked(tree.toString(), LocalFiles.walk(context, tree), LocalFiles.treeName(context, tree), clearHidden = true)
                _messages.value = if (n == 0) "No audio files in that folder" else if (n == 1) "Added 1 audiobook" else "Added $n audiobooks"
            }
        }
    }

    /** Adds picked files: one file is a book; several make one book (several .m4b files, one book each). */
    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { LocalFiles.keep(context, it) }
        scope.launch {
            mutex.withLock {
                val entries = uris.mapNotNull { LocalFiles.describe(context, it) }
                val title = commonTitle(entries.map { it.name })
                prefs.edit { putStringSet(KEY_PICKS, picks() + "files|${title.replace('|', ' ')}|${uris.joinToString(" ")}") }
                val n = addPicked(uris.joinToString(" "), entries, title, clearHidden = true)
                _messages.value = if (n == 0) "Pick audio files: M4B, M4A, MP3, OGG, Opus or FLAC" else if (n == 1) "Added 1 audiobook" else "Added $n audiobooks"
            }
        }
    }

    /** Looks at the picked folders again: new books show up, books whose files are gone leave. */
    private suspend fun scanPicked() {
        val seen = mutableSetOf<String>()
        for (pick in picks()) {
            val parts = pick.split('|')
            try {
                if (parts[0] == "folder") {
                    val tree = Uri.parse(parts[1])
                    seen += addPickedIds(parts[1], LocalFiles.walk(context, tree), LocalFiles.treeName(context, tree))
                } else if (parts[0] == "files" && parts.size >= 3) {
                    val entries = parts[2].split(' ').mapNotNull { LocalFiles.describe(context, Uri.parse(it)) }
                    seen += addPickedIds(parts[2], entries, parts[1])
                }
            } catch (e: Exception) {
                // The folder is gone or access was taken away: its books stay until it can be read
                Log.w(LOG_TAG, "Could not read $pick", e)
                seen += _books.value.filter { it.source == parts.getOrNull(1) || it.source == parts.getOrNull(2) }.map { it.id }
            }
        }
        // Phone books whose files are no longer picked: forget the files, and the book unless it is in Dropbox
        val gone = _books.value.filter { b -> b.tracks.any { it.uri != null } && b.id !in seen }
        if (gone.isNotEmpty()) {
            change { list ->
                list.mapNotNull { b ->
                    if (gone.none { it.id == b.id }) b else if (b.dropboxPath != null || isDownloaded(b)) b.copy(tracks = b.tracks.map { it.copy(uri = null) }, source = null) else null
                }
            }
        }
    }

    private suspend fun addPicked(
        source: String,
        entries: List<LocalEntry>,
        title: String,
        clearHidden: Boolean,
    ): Int {
        val ids = addPickedIds(source, entries, title, clearHidden)
        _books.value.filter { it.id in ids && !it.described }.forEach { b -> runCatching { describe(b, null) }.onFailure { Log.w(LOG_TAG, "Could not describe ${b.title}", it) } }
        return ids.size
    }

    /** Makes books of picked files (reading their content hashes once) and returns their ids. */
    private suspend fun addPickedIds(
        source: String,
        entries: List<LocalEntry>,
        title: String,
        clearHidden: Boolean = false,
    ): Set<String> {
        val groups = AudiobookRules.groupPicked(entries, title)
        val ids = mutableSetOf<String>()
        for (g in groups) {
            _adding.value = g.title
            val tracks =
                withContext(Dispatchers.IO) {
                    g.files.map { e -> AudioTrack(path = "local:${e.rel}", name = e.name, hash = LocalFiles.hash(context, e), size = e.size, uri = e.uri.toString()) }
                }
            val id = if (g.folder) AudiobookRules.folderBookId(tracks.map { it.hash }) else tracks[0].hash
            if (clearHidden) prefs.edit { putStringSet(KEY_HIDDEN, hidden() - id) } else if (id in hidden()) continue
            ids += id
            g.cover?.let { pickedCovers[id] = it.uri }
            val byHash = tracks.associateBy { it.hash }
            change { list ->
                val old = list.firstOrNull { it.id == id }
                if (old != null) {
                    list.map { b -> if (b.id != id) b else b.copy(tracks = b.tracks.map { t -> byHash[t.hash]?.let { p -> t.copy(uri = p.uri) } ?: t }, source = source) }
                } else {
                    list + Audiobook(id = id, title = g.title, dropboxPath = null, folder = g.folder, tracks = tracks, size = tracks.sumOf { it.size }, addedAt = System.currentTimeMillis(), source = source)
                }
            }
        }
        _adding.value = null
        return ids
    }

    /** Takes a phone book out of the library; its files stay where they are. */
    fun forget(id: String) {
        val b = get(id) ?: return
        prefs.edit { putStringSet(KEY_HIDDEN, hidden() + id) }
        if (b.dropboxPath != null) {
            patch(id) { it.copy(tracks = it.tracks.map { t -> t.copy(uri = null) }, source = null) }
        } else {
            b.tracks.forEach { t -> localFile(t)?.delete() }
            coverFile(id).delete()
            change { list -> list.filterNot { it.id == id } }
        }
        // Files picked one by one: let go of them
        val pick = picks().firstOrNull { it.startsWith("files|") && it.substringAfterLast('|') == b.source }
        if (pick != null) {
            prefs.edit { putStringSet(KEY_PICKS, picks() - pick) }
            b.source?.split(' ')?.forEach { LocalFiles.release(context, Uri.parse(it)) }
        }
        _version.update { it + 1 }
        _messages.value = "Removed ${b.title} from Feeder; the files stay on the phone"
    }

    private fun commonTitle(names: List<String>): String {
        if (names.size == 1) return AudiobookRules.nameWithoutExt(names[0])
        var p = names.firstOrNull().orEmpty()
        for (n in names) while (p.isNotEmpty() && !n.startsWith(p)) p = p.dropLast(1)
        return p.trimEnd(' ', '-', '_', '.', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9').trim().ifEmpty { AudiobookRules.nameWithoutExt(names.first()) }
    }

    /** Frees the space on the phone; the book stays in Dropbox. */
    fun removeDownload(id: String) {
        val b = get(id) ?: return
        b.tracks.forEach { t -> localFile(t)?.delete() }
        _version.update { it + 1 }
        _messages.value = "Removed ${b.title} from this phone; it stays in Dropbox"
    }

    companion object {
        private const val KEY = "books"
        private const val KEY_PICKS = "picks"
        private const val KEY_HIDDEN = "hidden"
        private const val LOG_TAG = "FEEDER_AUDIOBOOKS"

        @Volatile
        private var instance: AudiobookLibrary? = null

        fun get(context: Context): AudiobookLibrary =
            instance ?: synchronized(this) {
                instance ?: AudiobookLibrary(context.applicationContext).also { instance = it }
            }
    }
}
