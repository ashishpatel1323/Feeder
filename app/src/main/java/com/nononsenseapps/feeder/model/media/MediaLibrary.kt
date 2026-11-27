package com.nononsenseapps.feeder.model.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import com.nononsenseapps.feeder.model.audiobooks.AudioTrack
import com.nononsenseapps.feeder.model.audiobooks.Audiobook
import com.nononsenseapps.feeder.model.audiobooks.AudiobookRules
import com.nononsenseapps.feeder.model.local.LocalEntry
import com.nononsenseapps.feeder.model.local.LocalFiles
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * One audio or video file in Library > Player. Same fields as the web app's MediaFile
 * (web/src/lib/db.ts), plus [uri] for a file picked on the phone and read where it is.
 */
@Serializable
data class MediaFile(
    /** Dropbox content hash of the file: its place syncs as "audiopos" by it */
    val id: String,
    val name: String,
    /** The folder it is in, under /Media or inside what was picked ("Talks/2024"), "" at the top */
    val folder: String = "",
    /** Where it is in Dropbox, or null when it is only on the phone */
    val dropboxPath: String? = null,
    val size: Long = 0,
    /** "audio" or "video" */
    val kind: String = MediaRules.AUDIO,
    /** Length in seconds, once known */
    val duration: Double? = null,
    /** A subtitle file next to it (.vtt or .srt): its Dropbox path, or a document URI on the phone */
    val subs: String? = null,
    /** The file on the phone (a document URI from the file picker), read in place */
    val uri: String? = null,
    val addedAt: Long = 0,
    val lastOpenedAt: Long = 0,
    /** The picked folder or files it came from */
    val source: String? = null,
) {
    val isVideo: Boolean get() = kind == MediaRules.VIDEO
    val title: String get() = AudiobookRules.nameWithoutExt(name)
}

/** A subtitle file on the phone (a picked document, or a file the app keeps), not a Dropbox path. */
fun String.isPhonePath() = startsWith("content:") || startsWith("file:")

/** A folder in the Player list: its name and path, and how much is in it (subfolders included). */
data class MediaFolder(
    val name: String,
    val path: String,
    val count: Int,
    val size: Long,
)

/**
 * The Player's rules, the same as the web app's (web/src/lib/media/library.ts): every audio or
 * video file in Dropbox's /Media folder is one item, its folder is a folder in the list, and the
 * files of a folder play in natural name order. A .vtt or .srt with the same name (also
 * "name.en.srt") is the file's subtitles.
 */
object MediaRules {
    const val AUDIO = "audio"
    const val VIDEO = "video"
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts")
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "opus", "ogg", "oga", "flac", "wav", "wma")
    private val SUB_EXTENSIONS = setOf("vtt", "srt")

    fun kindOf(name: String): String? =
        when (AudiobookRules.extOf(name)) {
            in VIDEO_EXTENSIONS -> VIDEO
            in AUDIO_EXTENSIONS -> AUDIO
            else -> null
        }

    fun isSubtitle(name: String) = AudiobookRules.extOf(name) in SUB_EXTENSIONS

    private fun dirOf(rel: String) = rel.substringBeforeLast('/', "")

    private fun subStem(name: String) =
        name
            .replace(Regex("\\.(vtt|srt)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.[a-z]{2,3}(-[a-z]{2,4})?$", RegexOption.IGNORE_CASE), "")

    private fun mediaStem(name: String) = name.replace(Regex("\\.[a-zA-Z0-9]+$"), "")

    /** Files in a listing of /Media (every file, subfolders included); the same content twice shows once. */
    fun mediaInListing(
        files: List<DropboxFile>,
        root: String = DropboxClient.MEDIA_FOLDER,
        now: Long = System.currentTimeMillis(),
    ): List<MediaFile> {
        val prefix = root.lowercase(Locale.ROOT) + "/"
        val subs = mutableMapOf<String, String>()
        val media = mutableListOf<Triple<String, DropboxFile, String>>()
        for (f in files) {
            if (!f.path.lowercase(Locale.ROOT).startsWith(prefix)) continue
            val rel = f.path.substring(prefix.length)
            if (isSubtitle(f.name)) {
                val key = "${dirOf(rel).lowercase(Locale.ROOT)}/${subStem(f.name).lowercase(Locale.ROOT)}"
                if (key !in subs || AudiobookRules.extOf(f.name) == "vtt") subs[key] = f.path
                continue
            }
            val kind = kindOf(f.name) ?: continue
            if (f.hash.isNotEmpty()) media += Triple(rel, f, kind)
        }
        media.sortWith { a, b -> AudiobookRules.comparePaths(a.first, b.first) }
        val seen = mutableSetOf<String>()
        return media.mapNotNull { (rel, f, kind) ->
            if (!seen.add(f.hash)) return@mapNotNull null
            val folder = dirOf(rel)
            MediaFile(
                id = f.hash,
                name = f.name,
                folder = folder,
                dropboxPath = f.path,
                size = f.size,
                kind = kind,
                subs = subs["${folder.lowercase(Locale.ROOT)}/${mediaStem(f.name).lowercase(Locale.ROOT)}"],
                addedAt = runCatching { Instant.parse(f.modified).toEpochMilli() }.getOrDefault(now),
            )
        }
    }

    /**
     * How the list changes after a listing: new files, moved ones, and ones no longer in Dropbox
     * (kept, as phone files, when the phone has them: picked here or downloaded).
     */
    fun merge(
        have: List<MediaFile>,
        drafts: List<MediaFile>,
        onPhone: (MediaFile) -> Boolean,
    ): List<MediaFile> {
        val byId = have.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<MediaFile>()
        for (d in drafts) {
            seen += d.id
            val old = byId[d.id]
            out +=
                old?.copy(
                    dropboxPath = d.dropboxPath,
                    folder = if (old.uri != null && old.dropboxPath == null) old.folder else d.folder,
                    name = d.name,
                    subs = d.subs ?: old.subs?.takeIf { it.isPhonePath() },
                ) ?: d
        }
        for (m in have) {
            if (m.id in seen) continue
            if (m.dropboxPath == null) {
                out += m
            } else if (onPhone(m)) {
                out += m.copy(dropboxPath = null, subs = m.subs?.takeIf { it.isPhonePath() })
            }
        }
        return out
    }

    /** What is inside [path]: its subfolders (with how much is in each) and its own files, both in name order. */
    fun folderView(
        items: List<MediaFile>,
        path: String,
    ): Pair<List<MediaFolder>, List<MediaFile>> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val folders = linkedMapOf<String, MediaFolder>()
        val files = mutableListOf<MediaFile>()
        for (m in items) {
            if (m.folder == path) {
                files += m
                continue
            }
            if (path.isNotEmpty() && !m.folder.startsWith(prefix)) continue
            val name = m.folder.removePrefix(prefix).substringBefore('/')
            if (name.isEmpty()) continue
            val f = folders[name] ?: MediaFolder(name, prefix + name, 0, 0)
            folders[name] = f.copy(count = f.count + 1, size = f.size + m.size)
        }
        return folders.values.sortedWith { a, b -> AudiobookRules.naturalCompare(a.name, b.name) } to
            files.sortedWith { a, b -> AudiobookRules.naturalCompare(a.name, b.name) }
    }

    /** Every file under [path], subfolders included, in play order (files before subfolders at each level). */
    fun filesUnder(
        items: List<MediaFile>,
        path: String,
    ): List<MediaFile> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return items
            .filter { path.isEmpty() || it.folder == path || it.folder.startsWith(prefix) }
            .sortedWith { a, b -> AudiobookRules.comparePaths(full(a), full(b)) }
    }

    private fun full(m: MediaFile) = if (m.folder.isEmpty()) m.name else "${m.folder}/${m.name}"

    private fun safe(s: String) =
        s
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(150)
            .ifEmpty { "File" }

    /** Where a phone file goes in Dropbox: the same folder under /Media. */
    fun uploadPath(m: MediaFile): String {
        val dir = if (m.folder.isEmpty()) "" else m.folder.split('/').joinToString("/") { safe(it) } + "/"
        return "${DropboxClient.MEDIA_FOLDER}/$dir${safe(m.name)}"
    }

    /** Picked files (with their paths in what was picked) as Player files, subtitles matched by name. */
    fun picked(
        entries: List<LocalEntry>,
        hash: (LocalEntry) -> String,
        source: String,
        now: Long = System.currentTimeMillis(),
    ): List<MediaFile> {
        val subs = entries.filter { isSubtitle(it.name) }
        return entries.mapNotNull { e ->
            val kind = kindOf(e.name) ?: return@mapNotNull null
            val folder = dirOf(e.rel)
            val sub =
                subs.firstOrNull {
                    dirOf(it.rel) == folder && subStem(it.name).equals(mediaStem(e.name), ignoreCase = true)
                }
            MediaFile(
                id = hash(e),
                name = e.name,
                folder = folder,
                size = e.size,
                kind = kind,
                subs = sub?.uri?.toString(),
                uri = e.uri.toString(),
                addedAt = now,
                source = source,
            )
        }
    }

    /** Whether the subtitles are SubRip (.srt), else WebVTT. */
    fun subtitlesAreSrt(
        context: Context,
        m: MediaFile,
    ): Boolean {
        val s = m.subs ?: return false
        if (!s.startsWith("content:")) return s.lowercase(Locale.ROOT).endsWith(".srt")
        return LocalFiles
            .describe(context, Uri.parse(s))
            ?.name
            ?.lowercase(Locale.ROOT)
            ?.endsWith(".srt") ?: true
    }

    /** The file as a one-track book, for the shared player, which keeps its place as "audiopos". */
    fun asBook(m: MediaFile): Audiobook =
        Audiobook(
            id = m.id,
            title = m.title,
            author = m.folder.substringAfterLast('/'),
            dropboxPath = m.dropboxPath,
            folder = false,
            tracks = listOf(AudioTrack(path = m.dropboxPath ?: "local:${m.name}", name = m.name, hash = m.id, size = m.size, duration = m.duration, title = m.title, scanned = true, uri = m.uri)),
            size = m.size,
            described = true,
            addedAt = m.addedAt,
            lastOpenedAt = m.lastOpenedAt,
        )
}

/** A download to the phone (or an upload to Dropbox, [up]) in progress. */
data class MediaJob(
    val id: String,
    val done: Long,
    val total: Long,
    val up: Boolean = false,
)

/**
 * Library > Player's files: Dropbox's /Media folder (streamed from there, or downloaded to
 * Android/data/<app>/files/media to play offline) and files or folders picked on the phone, read
 * where they are (nothing is copied or uploaded unless asked). A picked folder is looked at again
 * on every refresh. Same rules as the web app (see [MediaRules]).
 */
class MediaLibrary private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("media_library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MediaFile.serializer())
    private val dropbox = DropboxClient.get(context)
    private val mutex = Mutex()
    val dir: File = (context.getExternalFilesDir("media") ?: File(context.filesDir, "media")).apply { mkdirs() }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<MediaFile>> = _items.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _jobs = MutableStateFlow<Map<String, MediaJob>>(emptyMap())
    val jobs: StateFlow<Map<String, MediaJob>> = _jobs.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()
    private val _adding = MutableStateFlow<String?>(null)

    /** What is being added from the phone right now, or null. */
    val adding: StateFlow<String?> = _adding.asStateFlow()

    /** Bumped when downloaded files change, so screens reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun consumeMessage() {
        _messages.value = null
    }

    private fun load(): List<MediaFile> = runCatching { json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    @Synchronized
    private fun change(f: (List<MediaFile>) -> List<MediaFile>) {
        val list = f(_items.value)
        _items.value = list
        prefs.edit { putString(KEY, json.encodeToString(serializer, list)) }
    }

    private fun patch(
        id: String,
        f: (MediaFile) -> MediaFile,
    ) = change { list -> list.map { if (it.id == id) f(it) else it } }

    fun get(id: String): MediaFile? = _items.value.firstOrNull { it.id == id }

    /** The phone's downloaded copy, when there is one. */
    fun localFile(m: MediaFile): File? = File(dir, "${m.id}.${AudiobookRules.extOf(m.name).ifBlank { "media" }}").takeIf { it.exists() && it.length() == m.size }

    /** The same, for the player's track (its hash is the file's id). */
    fun localFile(t: AudioTrack): File? = File(dir, "${t.hash}.${AudiobookRules.extOf(t.name).ifBlank { "media" }}").takeIf { it.exists() && it.length() == t.size }

    /** Plays without Dropbox: picked on the phone, or downloaded. */
    fun onPhone(m: MediaFile) = m.uri != null || localFile(m) != null

    fun markOpened(id: String) = patch(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    /**
     * Keeps [file] (moved here, e.g. from Downloads) as a Player file on this phone in [folder],
     * with [subtitles] next to it. A file already in the list (same content) gets this copy.
     * Returns its id.
     */
    fun addFile(
        file: File,
        name: String,
        folder: String,
        kind: String,
        subtitles: File?,
    ): String {
        val id = DropboxClient.contentHash(file)
        val dest = File(dir, "$id.${AudiobookRules.extOf(name).ifBlank { "media" }}")
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
        val subs =
            subtitles?.let { s ->
                val keep = File(dir, "$id.${s.extension.ifBlank { "vtt" }}")
                s.copyTo(keep, overwrite = true)
                android.net.Uri
                    .fromFile(keep)
                    .toString()
            }
        change { list ->
            val old = list.firstOrNull { it.id == id }
            if (old != null) {
                list.map { if (it.id == id) it.copy(subs = it.subs ?: subs) else it }
            } else {
                list + MediaFile(id = id, name = name, folder = folder, size = dest.length(), kind = kind, subs = subs, addedAt = System.currentTimeMillis())
            }
        }
        _version.update { it + 1 }
        return id
    }

    /** Deletes a file that is only on this phone (kept by the app, not picked): it is gone everywhere. */
    fun deleteFromPhone(id: String) {
        val m = get(id) ?: return
        localFile(m)?.delete()
        m.subs?.takeIf { it.startsWith("file:") }?.let {
            File(
                android.net.Uri
                    .parse(it)
                    .path ?: "",
            ).delete()
        }
        change { list -> list.filterNot { it.id == id } }
        _version.update { it + 1 }
        _messages.value = "Deleted ${m.title}"
    }

    /** The real length, once the player knows it. */
    fun setDuration(
        id: String,
        seconds: Double,
    ) {
        if (get(id)?.duration == null) patch(id) { it.copy(duration = seconds) }
    }

    /** Reads Dropbox's /Media folder and the picked folders again. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                _refreshing.value = true
                try {
                    scanPicked()
                    if (dropbox.link.value == null) return@withLock
                    val drafts = MediaRules.mediaInListing(dropbox.listTree(DropboxClient.MEDIA_FOLDER))
                    change { MediaRules.merge(it, drafts) { m -> onPhone(m) } }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Could not read Dropbox", e)
                    _messages.value = e.message
                } finally {
                    _refreshing.value = false
                }
            }
        }
    }

    // ---- Picked on the phone ----

    /** Picked folders and files, as "folder|<uri>" or "files|<uri> <uri>…". */
    private fun picks(): Set<String> = prefs.getStringSet(KEY_PICKS, emptySet()).orEmpty()

    /** Files the user took out of the list: a rescan of their folder leaves them out. */
    private fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    /** Adds a folder from the phone; its folders stay folders, under the folder's own name. */
    fun addFolder(tree: Uri) {
        LocalFiles.keep(context, tree)
        prefs.edit { putStringSet(KEY_PICKS, picks() + "folder|$tree") }
        scope.launch {
            mutex.withLock {
                val n = addPicked(tree.toString(), withName(LocalFiles.walk(context, tree), LocalFiles.treeName(context, tree)), clearHidden = true)
                _messages.value =
                    if (n == 0) {
                        "No audio or video files in that folder"
                    } else if (n == 1) {
                        "Added 1 file"
                    } else {
                        "Added $n files"
                    }
            }
        }
    }

    /** Adds picked files, at the top of the list. */
    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { LocalFiles.keep(context, it) }
        scope.launch {
            mutex.withLock {
                val source = uris.joinToString(" ")
                prefs.edit { putStringSet(KEY_PICKS, picks() + "files|$source") }
                val n = addPicked(source, uris.mapNotNull { LocalFiles.describe(context, it) }, clearHidden = true)
                _messages.value =
                    if (n == 0) {
                        "Pick audio or video files: MP3, M4A, Opus, FLAC, MP4, MKV, WebM…"
                    } else if (n == 1) {
                        "Added 1 file"
                    } else {
                        "Added $n files"
                    }
            }
        }
    }

    /** A picked folder's files, with the folder's own name in front of their paths. */
    private fun withName(
        entries: List<LocalEntry>,
        name: String,
    ) = entries.map { it.copy(rel = "$name/${it.rel}") }

    private suspend fun scanPicked() {
        val seen = mutableSetOf<String>()
        for (pick in picks()) {
            val kind = pick.substringBefore('|')
            val rest = pick.substringAfter('|')
            try {
                seen +=
                    if (kind == "folder") {
                        val tree = Uri.parse(rest)
                        addPickedIds(rest, withName(LocalFiles.walk(context, tree), LocalFiles.treeName(context, tree)))
                    } else {
                        addPickedIds(rest, rest.split(' ').mapNotNull { LocalFiles.describe(context, Uri.parse(it)) })
                    }
            } catch (e: Exception) {
                // The folder is gone or access was taken away: its files stay until it can be read
                Log.w(LOG_TAG, "Could not read $pick", e)
                seen += _items.value.filter { it.source == rest }.map { it.id }
            }
        }
        val gone =
            _items.value
                .filter { it.uri != null && it.id !in seen }
                .map { it.id }
                .toSet()
        if (gone.isNotEmpty()) {
            change { list ->
                list.mapNotNull { m ->
                    if (m.id !in gone) {
                        m
                    } else if (m.dropboxPath != null || localFile(m) != null) {
                        m.copy(uri = null, source = null)
                    } else {
                        null
                    }
                }
            }
        }
    }

    private suspend fun addPicked(
        source: String,
        entries: List<LocalEntry>,
        clearHidden: Boolean,
    ): Int = addPickedIds(source, entries, clearHidden).size

    private fun addPickedIds(
        source: String,
        entries: List<LocalEntry>,
        clearHidden: Boolean = false,
    ): Set<String> {
        val ids = mutableSetOf<String>()
        val files =
            MediaRules.picked(entries, { e ->
                _adding.value = e.name
                LocalFiles.hash(context, e)
            }, source)
        _adding.value = null
        for (f in files) {
            if (clearHidden) {
                prefs.edit { putStringSet(KEY_HIDDEN, hidden() - f.id) }
            } else if (f.id in hidden()) {
                continue
            }
            ids += f.id
            change { list ->
                val old = list.firstOrNull { it.id == f.id }
                if (old != null) {
                    list.map { if (it.id == f.id) it.copy(uri = f.uri, source = source, subs = it.subs ?: f.subs) else it }
                } else {
                    list + f
                }
            }
        }
        return ids
    }

    /** Takes a phone file out of the list; the file stays where it is. */
    fun forget(id: String) {
        val m = get(id) ?: return
        prefs.edit { putStringSet(KEY_HIDDEN, hidden() + id) }
        if (m.dropboxPath != null) {
            patch(id) { it.copy(uri = null, source = null) }
        } else {
            localFile(m)?.delete()
            change { list -> list.filterNot { it.id == id } }
        }
        _version.update { it + 1 }
        _messages.value = "Removed ${m.title} from Feeder; the file stays on the phone"
    }

    // ---- Dropbox ----

    /** Downloads a Dropbox file so it plays offline. */
    fun download(id: String) {
        val m = get(id) ?: return
        val path = m.dropboxPath ?: return
        if (_jobs.value.containsKey(id)) return
        _jobs.update { it + (id to MediaJob(id, 0, m.size)) }
        scope.launch {
            try {
                val dest = File(dir, "${m.id}.${AudiobookRules.extOf(m.name).ifBlank { "media" }}")
                dropbox.download(path, dest) { done, _ -> _jobs.update { it + (id to MediaJob(id, done, m.size)) } }
                _messages.value = "${m.title} is on this phone"
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Download failed", e)
                _messages.value = e.message ?: "Download failed"
            } finally {
                _jobs.update { it - id }
                _version.update { it + 1 }
            }
        }
    }

    /** Frees the space on the phone; the file stays in Dropbox. */
    fun removeDownload(id: String) {
        val m = get(id) ?: return
        localFile(m)?.delete()
        _version.update { it + 1 }
        _messages.value = "Removed ${m.title} from this phone; it stays in Dropbox"
    }

    /** Puts a phone file into Dropbox › Apps › Feeder › Media, in the same folder (and its subtitles next to it). */
    fun uploadToDropbox(id: String) {
        val m = get(id) ?: return
        if (m.dropboxPath != null || _jobs.value.containsKey(id)) return
        _jobs.update { it + (id to MediaJob(id, 0, m.size, up = true)) }
        scope.launch {
            try {
                val dest = MediaRules.uploadPath(m)
                val progress: (Long, Long) -> Unit = { done, _ -> _jobs.update { it + (id to MediaJob(id, done, m.size, up = true)) } }
                val up =
                    dropbox.keepRunning {
                        val file = localFile(m)
                        if (file != null) {
                            dropbox.upload(dest, file, onProgress = progress)
                        } else {
                            val uri = Uri.parse(m.uri ?: throw IllegalStateException("${m.name} is no longer on this phone"))
                            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s > 0 } } ?: m.size
                            dropbox.upload(dest, size, { from -> openAt(uri, from, m.name) }, progress, id = m.id)
                        }
                    }
                m.subs?.takeIf { it.isPhonePath() }?.let { s ->
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(Uri.parse(s))?.use { it.readBytes() } ?: return@runCatching
                        val ext = if (s.lowercase(Locale.ROOT).endsWith(".srt") || LocalFiles.describe(context, Uri.parse(s))?.name?.endsWith(".srt", true) == true) "srt" else "vtt"
                        dropbox.upload(up.path.replace(Regex("\\.[a-zA-Z0-9]+$"), "") + ".$ext", bytes.size.toLong(), { bytes.inputStream().also { st -> st.skip(it) } })
                    }.onFailure { Log.w(LOG_TAG, "Subtitles not uploaded", it) }
                }
                patch(id) { it.copy(dropboxPath = up.path) }
                _messages.value = "${m.title} is in Dropbox"
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Upload failed", e)
                _messages.value = "Upload of ${m.title} stopped (${e.message ?: "no connection"}). Tap Upload to Dropbox to go on from where it stopped"
            } finally {
                _jobs.update { it - id }
                _version.update { it + 1 }
            }
            refresh()
        }
    }

    /** A picked file as a stream from byte [from]. */
    private fun openAt(
        uri: Uri,
        from: Long,
        name: String,
    ): java.io.InputStream {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Could not read $name")
        val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
        if (from == 0L) return input
        try {
            input.channel.position(from)
        } catch (e: Exception) {
            var left = from
            val buf = ByteArray(1 shl 16)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.EOFException("$name is shorter than before")
                left -= n
            }
        }
        return input
    }

    /** Deletes the file (and its subtitles) from Dropbox for every device. When the phone has it, it stays here. */
    fun deleteFromDropbox(id: String) {
        val m = get(id) ?: return
        val path = m.dropboxPath ?: return
        scope.launch {
            try {
                dropbox.delete(path)
                m.subs?.takeIf { it.startsWith("/") }?.let { runCatching { dropbox.delete(it) } }
                if (onPhone(m)) {
                    patch(id) { it.copy(dropboxPath = null, subs = it.subs?.takeIf { s -> s.isPhonePath() }) }
                } else {
                    change { list -> list.filterNot { it.id == id } }
                }
                _messages.value = "Deleted ${m.title} from Dropbox"
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not delete ${m.title} from Dropbox", e)
                _messages.value = e.message ?: "Could not delete from Dropbox"
            } finally {
                _version.update { it + 1 }
            }
            refresh()
        }
    }

    /** Where the player reads the subtitles from: a document URI, or a Dropbox link (fetched now). */
    suspend fun subtitlesUri(m: MediaFile): Uri? {
        val s = m.subs ?: return null
        return runCatching { if (s.isPhonePath()) Uri.parse(s) else Uri.parse(dropbox.temporaryLink(s)) }.getOrNull()
    }

    companion object {
        private const val KEY = "items"
        private const val KEY_PICKS = "picks"
        private const val KEY_HIDDEN = "hidden"
        private const val LOG_TAG = "FEEDER_MEDIA"

        @Volatile
        private var instance: MediaLibrary? = null

        fun get(context: Context): MediaLibrary =
            instance ?: synchronized(this) {
                instance ?: MediaLibrary(context.applicationContext).also { instance = it }
            }
    }
}
