package com.nononsenseapps.feeder.model.ebooks

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** An ebook in the Ebooks tab. Same fields and rules as the web app's Book (web/src/lib/db.ts). */
@Serializable
data class Book(
    /** Dropbox content hash of the file: the same on every device. */
    val id: String,
    val title: String,
    val author: String = "",
    val ext: String,
    val size: Long,
    /** Where the file is in Dropbox, or null when it is only on this phone. */
    val dropboxPath: String? = null,
    val local: Boolean = false,
    /** Whether title, author and cover came from the book itself (else from its file name). */
    val described: Boolean = false,
    val addedAt: Long,
    val lastOpenedAt: Long = 0,
)

/** An upload or download in progress. */
data class BookJob(
    val key: String,
    val label: String,
    val done: Long,
    val total: Long,
)

/**
 * The Ebooks tab's library. Dropbox (/Ebooks in the app folder) is where books live for every
 * device; the phone keeps copies of the ones opened here (Android/data/<app>/files/ebooks) so they
 * open offline. Highlights of a book are article highlights under item "ebook:\n<book id>".
 */
class EbookLibrary private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ebooks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Book.serializer())
    private val dropbox = DropboxClient.get(context)
    private val mutex = Mutex()
    val dir: File = (context.getExternalFilesDir("ebooks") ?: File(context.filesDir, "ebooks")).apply { mkdirs() }
    private val coverDir = File(context.filesDir, "ebook-covers").apply { mkdirs() }

    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Book>> = _books.asStateFlow()
    private val _jobs = MutableStateFlow<List<BookJob>>(emptyList())
    val jobs: StateFlow<List<BookJob>> = _jobs.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /** Bumped when a cover changes, so screens reload it. */
    private val _coverVersion = MutableStateFlow(0)
    val coverVersion: StateFlow<Int> = _coverVersion.asStateFlow()

    fun consumeMessage() {
        _messages.value = null
    }

    private fun load(): List<Book> = runCatching { json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun write(list: List<Book>) {
        _books.value = list
        prefs.edit { putString(KEY, json.encodeToString(serializer, list)) }
    }

    @Synchronized
    private fun change(f: (List<Book>) -> List<Book>) = write(f(_books.value))

    private fun patch(
        id: String,
        f: (Book) -> Book,
    ) = change { list -> list.map { if (it.id == id) f(it) else it } }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun file(b: Book) = File(dir, "${b.id}.${b.ext.ifBlank { "epub" }}")

    fun coverFile(id: String) = File(coverDir, "$id.jpg")

    // -----------------------------------------------------------------------------------------
    // Dropbox

    /** Reads Dropbox's /Ebooks folder again, and uploads books only this phone has. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                if (dropbox.link.value == null) return@withLock
                try {
                    val files = dropbox.listFolder(DropboxClient.EBOOKS_FOLDER)
                    change { merge(it, files).books }
                    uploadLocalOnly()
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not read Dropbox", e)
                    _messages.value = e.message
                }
            }
        }
    }

    private suspend fun uploadLocalOnly() {
        for (b in _books.value.filter { it.local && it.dropboxPath == null }) {
            try {
                upload(b)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not upload ${b.title}", e)
            }
        }
    }

    private suspend fun upload(b: Book) {
        val f = file(b)
        if (!f.exists()) return
        val key = "up:${b.id}"
        _jobs.update { it + BookJob(key, "Uploading ${b.title}", 0, f.length()) }
        try {
            val up =
                dropbox.upload("${DropboxClient.EBOOKS_FOLDER}/${uploadName(b)}", f) { done, total ->
                    _jobs.update { jobs -> jobs.map { if (it.key == key) it.copy(done = done, total = total) else it } }
                }
            patch(b.id) { it.copy(dropboxPath = up.path) }
        } finally {
            _jobs.update { jobs -> jobs.filterNot { it.key == key } }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Adding, opening, removing

    /** Adds a book file the user picked: kept here, and uploaded to Dropbox when signed in. */
    fun import(uri: Uri) {
        val resolver = context.contentResolver
        var displayName = "book.epub"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
        }
        val ext = extOf(displayName)
        if (ext !in EXTENSIONS) {
            _messages.value = "$displayName: Feeder reads EPUB, MOBI, AZW3, FB2, CBZ and PDF books"
            return
        }
        val key = "add:$displayName:${System.nanoTime()}"
        _jobs.update { it + BookJob(key, displayName, 0, 0) }
        scope.launch {
            val tmp = File(dir, "import-${System.nanoTime()}.part")
            try {
                resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val id = DropboxClient.contentHash(tmp)
                val existing = get(id)
                val dest = File(dir, "$id.$ext")
                if (dest.exists()) tmp.delete() else check(tmp.renameTo(dest)) { "Could not save $displayName" }
                val book =
                    existing?.copy(local = true)
                        ?: Book(id = id, title = titleFromName(displayName), ext = ext, size = dest.length(), local = true, addedAt = System.currentTimeMillis())
                change { list -> list.filterNot { it.id == id } + book }
                _messages.value = "Added ${book.title}"
                if (book.dropboxPath == null && dropbox.link.value != null) upload(book)
            } catch (e: Exception) {
                tmp.delete()
                Log.w(LOG_TAG, "Import failed", e)
                _messages.value = e.message ?: "Could not add $displayName"
            } finally {
                _jobs.update { jobs -> jobs.filterNot { it.key == key } }
            }
        }
    }

    /** The book's file, downloading it from Dropbox first when it isn't on the phone. */
    suspend fun ensureLocal(
        id: String,
        onProgress: (Long, Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val b = get(id) ?: throw IllegalStateException("This book is no longer in the library")
            val f = file(b)
            if (!f.exists()) {
                val path = b.dropboxPath ?: throw IllegalStateException("This book is not on this phone, and not in Dropbox")
                dropbox.download(path, f, onProgress)
            }
            patch(id) { it.copy(local = true, lastOpenedAt = System.currentTimeMillis()) }
            f
        }

    /** Downloads a book from Dropbox without opening it. */
    fun download(id: String) {
        val b = get(id) ?: return
        val key = "down:$id"
        if (_jobs.value.any { it.key == key }) return
        _jobs.update { it + BookJob(key, b.title, 0, b.size) }
        scope.launch {
            try {
                ensureLocal(id) { done, total -> _jobs.update { jobs -> jobs.map { if (it.key == key) it.copy(done = done, total = total) else it } } }
                _messages.value = context.getString(com.nononsenseapps.feeder.R.string.ebook_downloaded_toast, b.title)
            } catch (e: Exception) {
                _messages.value = e.message ?: "Download failed"
            } finally {
                _jobs.update { jobs -> jobs.filterNot { it.key == key } }
            }
        }
    }

    /** Title, author and cover the reader found in the book. */
    fun describe(
        id: String,
        title: String,
        author: String,
        coverDataUrl: String?,
    ) {
        scope.launch {
            if (coverDataUrl != null && coverDataUrl.startsWith("data:image/")) {
                runCatching {
                    coverFile(id).writeBytes(Base64.decode(coverDataUrl.substringAfter(','), Base64.DEFAULT))
                    _coverVersion.update { it + 1 }
                }
            }
            patch(id) { it.copy(title = title.ifBlank { it.title }, author = author, described = true) }
        }
    }

    /** Frees the space on the phone; the book stays in Dropbox. */
    fun removeFromDevice(id: String) {
        val b = get(id) ?: return
        file(b).delete()
        if (b.dropboxPath != null) patch(id) { it.copy(local = false) } else change { list -> list.filterNot { it.id == id } }
    }

    /** Deletes the book from Dropbox and the phone. Positions and highlights stay. */
    fun deleteEverywhere(id: String) {
        val b = get(id) ?: return
        scope.launch {
            try {
                b.dropboxPath?.let { dropbox.delete(it) }
                file(b).delete()
                coverFile(id).delete()
                change { list -> list.filterNot { it.id == id } }
                _messages.value = "Deleted ${b.title}"
            } catch (e: Exception) {
                _messages.value = e.message
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_EBOOKS"
        private const val KEY = "books_v1"
        const val ITEM_PREFIX = "ebook:\n"
        val EXTENSIONS = setOf("epub", "mobi", "azw3", "azw", "kf8", "fb2", "fbz", "cbz", "pdf")

        fun itemKey(id: String) = "$ITEM_PREFIX$id"

        fun bookIdOfItem(itemKey: String): String? = if (itemKey.startsWith(ITEM_PREFIX)) itemKey.removePrefix(ITEM_PREFIX) else null

        fun extOf(name: String): String {
            val n = name.lowercase()
            if (n.endsWith(".fb2.zip")) return "fbz"
            return n.substringAfterLast('.', "").takeIf { it.matches(Regex("[a-z0-9]+")) } ?: ""
        }

        fun titleFromName(name: String): String =
            name
                .replace(Regex("\\.fb2\\.zip$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\.[a-zA-Z0-9]+$"), "")
                .replace('_', ' ')
                .trim()
                .ifEmpty { "Untitled" }

        fun uploadName(b: Book): String {
            val base = if (b.author.isNotBlank()) "${b.title} - ${b.author}" else b.title
            return base.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), " ").replace(Regex("\\s+"), " ").trim().take(150) + ".${b.ext}"
        }

        /** What a Dropbox listing of /Ebooks adds to, changes in or removes from the library. */
        data class Merge(
            val books: List<Book>,
        )

        fun merge(
            books: List<Book>,
            files: List<DropboxFile>,
            now: Long = System.currentTimeMillis(),
        ): Merge {
            val byId = books.associateBy { it.id }.toMutableMap()
            val seen = mutableSetOf<String>()
            for (f in files) {
                val ext = extOf(f.name)
                if (ext !in EXTENSIONS || f.hash.isBlank() || !seen.add(f.hash)) continue
                val b = byId[f.hash]
                byId[f.hash] =
                    if (b == null) {
                        Book(id = f.hash, title = titleFromName(f.name), ext = ext, size = f.size, dropboxPath = f.path, addedAt = now)
                    } else {
                        b.copy(dropboxPath = f.path, title = if (b.described) b.title else titleFromName(f.name))
                    }
            }
            for (b in books) {
                if (b.dropboxPath == null || b.id in seen) continue
                // Gone from Dropbox: forget it, unless the phone has a copy (it stays as a local book)
                if (b.local) byId[b.id] = b.copy(dropboxPath = null) else byId.remove(b.id)
            }
            return Merge(byId.values.toList())
        }

        @Volatile
        private var instance: EbookLibrary? = null

        fun get(context: Context): EbookLibrary =
            instance ?: synchronized(this) {
                instance ?: EbookLibrary(context.applicationContext).also { instance = it }
            }
    }
}
