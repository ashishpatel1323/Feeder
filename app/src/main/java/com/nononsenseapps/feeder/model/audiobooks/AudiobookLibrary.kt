package com.nononsenseapps.feeder.model.audiobooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.content.edit
import com.nononsenseapps.feeder.dropbox.DropboxClient
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
 * files. Title, author, cover, lengths and chapters are read from the files once, on first play.
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

    // ---- Dropbox ----

    /** Reads Dropbox's /Audiobooks folder again. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                if (dropbox.link.value == null) return@withLock
                _refreshing.value = true
                try {
                    val drafts = AudiobookRules.booksInListing(dropbox.listTree(DropboxClient.AUDIOBOOKS_FOLDER))
                    drafts.forEach { d -> d.coverPath?.let { coverPaths[d.id] = it } }
                    val (put, remove) = AudiobookRules.mergeBooks(_books.value, drafts)
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
                if (local != null) r.setDataSource(local.path) else r.setDataSource(dropbox.temporaryLink(t.path), emptyMap())
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

    /** Frees the space on the phone; the book stays in Dropbox. */
    fun removeDownload(id: String) {
        val b = get(id) ?: return
        b.tracks.forEach { t -> localFile(t)?.delete() }
        _version.update { it + 1 }
        _messages.value = "Removed ${b.title} from this phone; it stays in Dropbox"
    }

    companion object {
        private const val KEY = "books"
        private const val LOG_TAG = "FEEDER_AUDIOBOOKS"

        @Volatile
        private var instance: AudiobookLibrary? = null

        fun get(context: Context): AudiobookLibrary =
            instance ?: synchronized(this) {
                instance ?: AudiobookLibrary(context.applicationContext).also { instance = it }
            }
    }
}
