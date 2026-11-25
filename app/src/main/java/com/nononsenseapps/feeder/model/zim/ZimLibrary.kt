package com.nononsenseapps.feeder.model.zim

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import androidx.core.content.edit
import androidx.core.net.toUri
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A ZIM book (an offline website such as Wikipedia) kept on this device. */
data class ZimBook(
    val id: String,
    val file: File,
    val title: String,
    val description: String,
    val language: String,
    val date: String,
    val flavour: String,
    val articleCount: Int,
    val size: Long,
    val icon: ByteArray?,
    val hasFulltext: Boolean,
)

/** An import or download in progress. */
data class ZimJob(
    val key: String,
    val label: String,
    val done: Long,
    val total: Long,
    val downloadId: Long? = null,
)

/** One book from the Kiwix online catalog. */
data class ZimCatalogEntry(
    val id: String,
    val title: String,
    val summary: String,
    val language: String,
    val flavour: String,
    val size: Long,
    val articleCount: Int,
    val url: String,
    val date: String,
)

/** Loads libzim, the part of Kiwix that reads ZIM files. Missing on 32-bit phones. */
object ZimNative {
    private const val LOG_TAG = "FEEDER_ZIM"

    val available: Boolean by lazy {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("zim")
            System.loadLibrary("zim_wrapper")
            true
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "libzim is not available on this device", e)
            false
        }
    }
}

/**
 * The books live in the app's own folder on the device (Android/data/<app>/files/zim), so they
 * need no storage permission and go away with the app.
 */
class ZimLibrary private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("zim", Context.MODE_PRIVATE)
    val dir: File = (context.getExternalFilesDir("zim") ?: File(context.filesDir, "zim")).apply { mkdirs() }

    private val _books = MutableStateFlow<List<ZimBook>?>(null)
    val books: StateFlow<List<ZimBook>?> = _books.asStateFlow()
    private val _jobs = MutableStateFlow<List<ZimJob>>(emptyList())
    val jobs: StateFlow<List<ZimJob>> = _jobs.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)

    /** A one-off message for the user (an import finished or failed). */
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val dropbox = DropboxClient.get(context)
    private val _remote = MutableStateFlow<List<DropboxFile>?>(null)

    /** ZIM files in Dropbox's /ZIM folder (the shared library), or null when not signed in. */
    val remote: StateFlow<List<DropboxFile>?> = _remote.asStateFlow()

    private val archives = HashMap<String, Archive>()
    private val described = HashMap<String, ZimBook>()
    private val imports = HashMap<String, Job>()

    fun consumeMessage() {
        _messages.value = null
    }

    fun file(id: String) = File(dir, "$id.zim")

    /** The open archive of a book, kept open while the app runs. */
    fun archive(id: String): Archive =
        synchronized(archives) {
            archives.getOrPut(id) { Archive(file(id).absolutePath) }
        }

    fun refresh() {
        scope.launch { load() }
    }

    /** Reads Dropbox's /ZIM folder again. */
    fun refreshRemote() {
        scope.launch {
            if (dropbox.link.value == null) {
                _remote.value = null
                return@launch
            }
            try {
                _remote.value = dropbox.listFolder(DropboxClient.ZIM_FOLDER).filter { it.name.endsWith(".zim", ignoreCase = true) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not read Dropbox", e)
                _messages.value = e.message
            }
        }
    }

    /**
     * The Kiwix catalog's icon for a ZIM file, by name without date or flavour, for books only in
     * Dropbox (their own icon is inside the file). Remembered, including "none". Same as the web app.
     */
    suspend fun catalogIcon(file: String): String? =
        withContext(Dispatchers.IO) {
            val name = file.removeSuffix(".zim").replace(Regex("_\\d{4}-\\d{2}(-\\d{2})?$"), "")
            val key = "icon:$name"
            if (prefs.contains(key)) return@withContext prefs.getString(key, "")?.ifEmpty { null }
            var url = ""
            val parts = name.split('_')
            try {
                for (drop in 0..2) {
                    if (parts.size - drop < 2 || url.isNotEmpty()) break
                    val q = URLEncoder.encode(parts.subList(0, parts.size - drop).joinToString("_"), "UTF-8")
                    val conn = URL("$OPDS/catalog/v2/entries?name=$q&count=1").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val xml = conn.inputStream.use { it.readBytes().decodeToString() }
                    val id = Regex("<entry>.*?<id>urn:uuid:([^<]+)</id>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
                    if (id != null) url = "$OPDS/catalog/v2/illustration/$id/?size=48"
                }
                prefs.edit { putString(key, url) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "No catalog icon for $name", e)
            }
            url.ifEmpty { null }
        }

    /** Whether a book here is the same file as one in Dropbox (same name and size). */
    fun sameZim(
        book: ZimBook,
        f: DropboxFile,
    ): Boolean = book.size == f.size && (book.file.name.equals(f.name, ignoreCase = true) || book.file.name == "${safeName(f.name)}.zim")

    /** Deletes a book from Dropbox (the copy every device shares); copies on devices stay. */
    fun deleteFromDropbox(f: DropboxFile) {
        scope.launch {
            try {
                dropbox.delete(f.path)
                _messages.value = "Deleted ${f.name} from Dropbox"
            } catch (e: Exception) {
                _messages.value = e.message ?: "Could not delete from Dropbox"
            } finally {
                refreshRemote()
            }
        }
    }

    /** Puts a book that is on this phone into Dropbox too, for the other devices. */
    fun uploadToDropbox(book: ZimBook) = uploadFile(book.file, book.title)

    private fun uploadFile(
        file: File,
        label: String,
    ) {
        val key = "up:${file.name}"
        if (_jobs.value.any { it.key == key }) return
        _jobs.update { it + ZimJob(key, "Uploading $label to Dropbox", 0, file.length()) }
        scope.launch {
            try {
                dropbox.upload("${DropboxClient.ZIM_FOLDER}/${file.name}", file) { done, total ->
                    _jobs.update { jobs -> jobs.map { if (it.key == key) it.copy(done = done, total = total) else it } }
                }
                _messages.value = "$label is in Dropbox"
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Upload failed", e)
                _messages.value = e.message ?: "Upload failed"
            } finally {
                _jobs.update { jobs -> jobs.filterNot { it.key == key } }
                refreshRemote()
            }
        }
    }

    /** New books go to Dropbox too when it is signed in. */
    private fun shareNew(
        name: String,
        label: String,
    ) {
        if (dropbox.link.value != null) uploadFile(File(dir, name), label)
    }

    /** Downloads a book from Dropbox into the phone's library. */
    fun downloadFromDropbox(f: DropboxFile) {
        val name = uniqueName(f.name)
        val key = name
        _jobs.update { it + ZimJob(key, f.name, 0, f.size) }
        imports[key] =
            scope.launch {
                try {
                    dropbox.download(f.path, File(dir, name)) { done, total ->
                        _jobs.update { jobs -> jobs.map { if (it.key == key) it.copy(done = done, total = total) else it } }
                    }
                    _messages.value = "${f.name} is ready to read"
                } catch (e: Exception) {
                    File(dir, "$name.part").delete()
                    if (e !is kotlinx.coroutines.CancellationException) _messages.value = e.message ?: "Download failed"
                } finally {
                    imports.remove(key)
                    _jobs.update { jobs -> jobs.filterNot { it.key == key } }
                    load()
                }
            }
    }

    private suspend fun load() {
        // Reading a book needs libzim loaded first (global search can get here before the ZIM tab)
        if (!ZimNative.available) {
            _books.value = emptyList()
            return
        }
        checkDownloads()
        val downloading = _jobs.value.map { it.key }.toSet()
        val list =
            dir
                .listFiles { f -> f.isFile && f.name.endsWith(".zim") && f.name !in downloading }
                .orEmpty()
                .mapNotNull { f ->
                    try {
                        describe(f)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Could not open ${f.name}", e)
                        null
                    }
                }.sortedBy { it.title.lowercase() }
        _books.value = list
    }

    private fun describe(f: File): ZimBook {
        val key = "${f.name}:${f.length()}"
        described[key]?.let { return it }
        val id = f.name.removeSuffix(".zim")
        val a = archive(id)

        fun meta(name: String) =
            try {
                a.getMetadata(name)
            } catch (_: Exception) {
                ""
            }
        val icon =
            try {
                if (a.hasIllustration(48)) {
                    a.getIllustrationItem(48).data.data.takeUnless { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { blank(it) } ?: true
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        val book =
            ZimBook(
                id = id,
                file = f,
                title = meta("Title").ifBlank { id },
                description = meta("Description"),
                language = meta("Language"),
                date = meta("Date"),
                flavour = meta("Flavour"),
                articleCount = a.articleCount,
                size = f.length(),
                icon = icon,
                hasFulltext = a.hasFulltextIndex(),
            )
        described[key] = book
        return book
    }

    fun delete(book: ZimBook) {
        scope.launch {
            synchronized(archives) { archives.remove(book.id)?.dispose() }
            book.file.delete()
            ZimSnapshots.get(context).forget(book.id)
            prefs.edit { remove("last:${book.id}") }
            load()
        }
    }

    private fun safeName(base: String): String =
        base
            .removeSuffix(".zim")
            .replace(Regex("[^\\w.-]+"), "_")
            .trim('_')
            .take(120)
            .ifEmpty { "book" }

    private fun uniqueName(base: String): String {
        val safe = safeName(base)
        var i = 0
        while (true) {
            val name = if (i == 0) "$safe.zim" else "$safe-$i.zim"
            if (!File(dir, name).exists() && !File(dir, "$name.part").exists()) return name
            i++
        }
    }

    /** Copies a .zim file the user picked into the library. */
    fun import(uri: Uri) {
        val resolver = context.contentResolver
        var displayName = "book.zim"
        var total = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                displayName = c.getString(0) ?: displayName
                total = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val name = uniqueName(displayName)
        val key = name
        _jobs.update { it + ZimJob(key, displayName, 0, total) }
        imports[key] =
            scope.launch {
                val part = File(dir, "$name.part")
                try {
                    resolver.openInputStream(uri)!!.use { input ->
                        val head = ByteArray(4)
                        input.read(head)
                        val magic =
                            (head[0].toInt() and 0xff) or ((head[1].toInt() and 0xff) shl 8) or
                                ((head[2].toInt() and 0xff) shl 16) or ((head[3].toInt() and 0xff) shl 24)
                        require(magic == ZIM_MAGIC) { "$displayName is not a ZIM file" }
                        part.outputStream().use { out ->
                            out.write(head)
                            val buf = ByteArray(1 shl 20)
                            var done = 4L
                            var last = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (System.currentTimeMillis() - last > 250) {
                                    last = System.currentTimeMillis()
                                    _jobs.update { jobs -> jobs.map { if (it.key == key) it.copy(done = done) else it } }
                                }
                            }
                        }
                    }
                    check(part.renameTo(File(dir, name))) { "Could not save $name" }
                    _messages.value = "$displayName is ready to read"
                    shareNew(name, displayName)
                } catch (e: Exception) {
                    part.delete()
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(LOG_TAG, "Import failed", e)
                        _messages.value = e.message ?: "Import failed"
                    }
                } finally {
                    imports.remove(key)
                    _jobs.update { jobs -> jobs.filterNot { it.key == key } }
                    load()
                }
            }
    }

    /** Downloads a book with Android's download manager (it keeps going when the app is closed). */
    fun download(entry: ZimCatalogEntry) {
        val fileName = entry.url.substringAfterLast('/').substringBefore('?')
        val name = uniqueName(fileName)
        val request =
            DownloadManager
                .Request(entry.url.toUri())
                .setTitle(entry.title)
                .setDescription(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(File(dir, "$name.part")))
        val id = downloadManager().enqueue(request)
        prefs.edit { putStringSet(KEY_DOWNLOADS, downloads() + "$id|$name|${entry.title}") }
        refresh()
    }

    fun cancel(job: ZimJob) {
        val downloadId = job.downloadId
        if (downloadId != null) {
            downloadManager().remove(downloadId)
            prefs.edit { putStringSet(KEY_DOWNLOADS, downloads().filterNot { it.startsWith("$downloadId|") }.toSet()) }
            File(dir, "${job.key}.part").delete()
            refresh()
        } else {
            imports[job.key]?.cancel()
        }
    }

    private fun downloads(): Set<String> = prefs.getStringSet(KEY_DOWNLOADS, emptySet()).orEmpty()

    private fun downloadManager() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Updates download progress; finished downloads become books. */
    fun checkDownloads() {
        val stored = downloads()
        val keep = mutableSetOf<String>()
        val active = mutableListOf<ZimJob>()
        for (line in stored) {
            val (idText, name, title) = line.split('|', limit = 3).let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            val id = idText.toLongOrNull() ?: continue
            val cursor = downloadManager().query(DownloadManager.Query().setFilterById(id))
            cursor.use { c ->
                if (!c.moveToFirst()) return@use
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        File(dir, "$name.part").renameTo(File(dir, name))
                        _messages.value = "$title is ready to read"
                        shareNew(name, title)
                    }

                    DownloadManager.STATUS_FAILED -> {
                        File(dir, "$name.part").delete()
                        _messages.value = "$title: download failed"
                    }

                    else -> {
                        keep += line
                        active += ZimJob(name, title, done, total, downloadId = id)
                    }
                }
            }
        }
        if (keep != stored) prefs.edit { putStringSet(KEY_DOWNLOADS, keep) }
        val importing = _jobs.value.filter { it.downloadId == null }
        _jobs.value = importing + active
    }

    fun lastPath(id: String): String? = prefs.getString("last:$id", null)

    fun rememberPath(
        id: String,
        path: String,
    ) = prefs.edit { putString("last:$id", path) }

    /** Searches the Kiwix online catalog (library.kiwix.org). */
    suspend fun searchCatalog(
        query: String,
        lang: String,
    ): List<ZimCatalogEntry> =
        withContext(Dispatchers.IO) {
            val params =
                buildString {
                    append("count=40")
                    if (query.isNotBlank()) append("&q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
                    if (lang.isNotBlank()) append("&lang=").append(lang)
                }
            val conn = URL("https://library.kiwix.org/catalog/v2/entries?$params").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            try {
                conn.inputStream.use { parseCatalog(it) }
            } finally {
                conn.disconnect()
            }
        }

    private fun parseCatalog(input: java.io.InputStream): List<ZimCatalogEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val out = mutableListOf<ZimCatalogEntry>()
        var fields = mutableMapOf<String, String>()
        var inEntry = false
        var tag = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "entry") {
                        inEntry = true
                        fields = mutableMapOf()
                    } else if (inEntry && tag == "link" && parser.getAttributeValue(null, "type") == "application/x-zim") {
                        fields["url"] = parser.getAttributeValue(null, "href").orEmpty().removeSuffix(".meta4")
                        fields["size"] = parser.getAttributeValue(null, "length").orEmpty()
                    }
                }

                XmlPullParser.TEXT -> if (inEntry && tag.isNotEmpty()) fields[tag] = (fields[tag].orEmpty() + parser.text).trim()

                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry") {
                        inEntry = false
                        val url = fields["url"].orEmpty()
                        if (url.isNotEmpty()) {
                            out +=
                                ZimCatalogEntry(
                                    id = fields["id"].orEmpty(),
                                    title = fields["title"].orEmpty(),
                                    summary = fields["summary"].orEmpty(),
                                    language = fields["language"].orEmpty(),
                                    flavour = fields["flavour"].orEmpty(),
                                    size = fields["size"]?.toLongOrNull() ?: 0,
                                    articleCount = fields["articleCount"]?.toIntOrNull() ?: 0,
                                    url = url,
                                    date = fields["updated"].orEmpty().take(10),
                                )
                        }
                    }
                    tag = ""
                }
            }
        }
        return out
    }

    companion object {
        private const val LOG_TAG = "FEEDER_ZIM"
        private const val KEY_DOWNLOADS = "downloads"
        private const val ZIM_MAGIC = 72173914
        private const val OPDS = "https://opds.library.kiwix.org"

        @Volatile
        private var instance: ZimLibrary? = null

        fun get(context: Context): ZimLibrary =
            instance ?: synchronized(this) {
                instance ?: ZimLibrary(context.applicationContext).also { instance = it }
            }
    }
}
