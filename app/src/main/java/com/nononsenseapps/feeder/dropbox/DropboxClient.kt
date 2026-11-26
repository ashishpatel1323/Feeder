package com.nononsenseapps.feeder.dropbox

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The Dropbox sign-in other devices share through sync (kind "dropbox", key "main"). Same fields
 * as the web app's DropboxLink (web/src/lib/dropbox.ts).
 */
@Serializable
data class DropboxLink(
    val appKey: String,
    val refreshToken: String,
    val name: String = "",
    val email: String = "",
)

/** A file in the Dropbox app folder. [hash] is Dropbox's content hash (see [contentHash]). */
data class DropboxFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val hash: String,
    /** When Dropbox got it (ISO 8601), empty when not known */
    val modified: String = "",
)

class DropboxException(
    val status: Int,
    val summary: String,
    /** The whole error answer (JSON), for details such as an upload's correct_offset */
    val body: String = "",
) : IOException(if (status == 401) "Dropbox sign-in expired: sign in again on Feeder web" else "Dropbox: ${summary.ifBlank { status.toString() }}")

/**
 * Dropbox as the shared place for big files: ebooks (/Ebooks) and ZIM books (/ZIM) in the app's
 * own folder. The phone signs in through sync: signing in once on Feeder web is enough (the
 * refresh token works from any device with the same app key, PKCE apps have no secret).
 */
class DropboxClient private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("dropbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    private val _link = MutableStateFlow(loadLink())

    /** Who is signed in, or null. */
    val link: StateFlow<DropboxLink?> = _link.asStateFlow()

    private fun loadLink(): DropboxLink? = prefs.getString(KEY_LINK, null)?.let { runCatching { json.decodeFromString(DropboxLink.serializer(), it) }.getOrNull() }

    /** When the link was last set or cleared (here or by the device it came from), for sync. */
    val changedAt: Long get() = prefs.getLong(KEY_TS, 0)

    /** A link from another device (null = signed out there). */
    @Synchronized
    fun applyFromSync(
        link: DropboxLink?,
        ts: Long,
    ) {
        val now = _link.value
        prefs.edit {
            putLong(KEY_TS, ts)
            if (link == null) {
                remove(KEY_LINK)
            } else {
                putString(KEY_LINK, json.encodeToString(DropboxLink.serializer(), link))
            }
            if (link?.refreshToken != now?.refreshToken || link?.appKey != now?.appKey) {
                remove(KEY_ACCESS)
                remove(KEY_EXPIRES)
            }
        }
        _link.value = link
    }

    /** Signs out on every device (the next sync tells them). Files stay in Dropbox. */
    @Synchronized
    fun signOut() {
        prefs.edit {
            remove(KEY_LINK)
            remove(KEY_ACCESS)
            remove(KEY_EXPIRES)
            putLong(KEY_TS, System.currentTimeMillis())
        }
        _link.value = null
    }

    // -----------------------------------------------------------------------------------------

    private fun accessToken(force: Boolean): String {
        val link = _link.value ?: throw DropboxException(401, "not signed in")
        synchronized(this) {
            val token = prefs.getString(KEY_ACCESS, null)
            if (!force && token != null && prefs.getLong(KEY_EXPIRES, 0) > System.currentTimeMillis()) return token
            val body =
                FormBody
                    .Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", link.refreshToken)
                    .add("client_id", link.appKey)
                    .build()
            http.newCall(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build()).execute().use { res ->
                val text = res.body.string()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val access = obj?.get("access_token")?.jsonPrimitive?.contentOrNull
                if (!res.isSuccessful || access == null) {
                    throw DropboxException(if (res.code == 400) 401 else res.code, obj?.get("error_description")?.jsonPrimitive?.contentOrNull ?: text.take(200))
                }
                val expires = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 14400
                prefs.edit {
                    putString(KEY_ACCESS, access)
                    putLong(KEY_EXPIRES, System.currentTimeMillis() + expires * 1000 - 60_000)
                }
                return access
            }
        }
    }

    private fun call(build: (token: String) -> Request): Response {
        var attempt = 0
        while (true) {
            val res = http.newCall(build(accessToken(force = attempt > 0))).execute()
            if (res.code == 401 && attempt == 0) {
                res.close()
                attempt++
                continue
            }
            if (res.code == 429 && attempt < 3) {
                val wait = res.header("Retry-After")?.toLongOrNull() ?: 2
                res.close()
                Thread.sleep(wait * 1000)
                attempt++
                continue
            }
            if (!res.isSuccessful) {
                val text = res.body.string()
                res.close()
                val summary = runCatching { json.parseToJsonElement(text).jsonObject["error_summary"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: text
                throw DropboxException(res.code, summary.take(200), text.take(2000))
            }
            return res
        }
    }

    private fun rpc(
        endpoint: String,
        arg: JsonObject,
    ): JsonObject =
        call { token ->
            Request
                .Builder()
                .url("https://api.dropboxapi.com/2/$endpoint")
                .header("Authorization", "Bearer $token")
                .post(arg.toString().toRequestBody(JSON))
                .build()
        }.use { json.parseToJsonElement(it.body.string()).jsonObject }

    private fun toFile(o: JsonObject) =
        DropboxFile(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            path = o["path_display"]?.jsonPrimitive?.contentOrNull ?: "",
            size = o["size"]?.jsonPrimitive?.long ?: 0,
            hash = o["content_hash"]?.jsonPrimitive?.contentOrNull ?: "",
            modified = o["server_modified"]?.jsonPrimitive?.contentOrNull ?: "",
        )

    /** Files in one folder of the app folder. A missing folder is empty. */
    suspend fun listFolder(path: String): List<DropboxFile> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<DropboxFile>()
            var res =
                try {
                    rpc(
                        "files/list_folder",
                        buildJsonObject {
                            put("path", path)
                            put("recursive", false)
                        },
                    )
                } catch (e: DropboxException) {
                    if (e.status == 409 && "not_found" in e.summary) return@withContext emptyList()
                    throw e
                }
            while (true) {
                res["entries"]?.jsonArray?.forEach { e ->
                    val o = e.jsonObject
                    if (o[".tag"]?.jsonPrimitive?.contentOrNull == "file") out += toFile(o)
                }
                if (res["has_more"]?.jsonPrimitive?.contentOrNull != "true") break
                res = rpc("files/list_folder/continue", buildJsonObject { put("cursor", res["cursor"]!!.jsonPrimitive.content) })
            }
            out
        }

    /** Every file under a folder, subfolders included. A missing folder is empty. */
    suspend fun listTree(path: String): List<DropboxFile> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<DropboxFile>()
            var res =
                try {
                    rpc(
                        "files/list_folder",
                        buildJsonObject {
                            put("path", path)
                            put("recursive", true)
                        },
                    )
                } catch (e: DropboxException) {
                    if (e.status == 409 && "not_found" in e.summary) return@withContext emptyList()
                    throw e
                }
            while (true) {
                res["entries"]?.jsonArray?.forEach { e ->
                    val o = e.jsonObject
                    if (o[".tag"]?.jsonPrimitive?.contentOrNull == "file") out += toFile(o)
                }
                if (res["has_more"]?.jsonPrimitive?.contentOrNull != "true") break
                res = rpc("files/list_folder/continue", buildJsonObject { put("cursor", res["cursor"]!!.jsonPrimitive.content) })
            }
            out
        }

    private val links = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * A direct link to a file that the player can stream (with seeking). Dropbox keeps it working
     * for 4 hours; it is reused for 3, and [fresh] asks for a new one (after a playback error).
     */
    suspend fun temporaryLink(
        path: String,
        fresh: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            val known = links[path]
            if (!fresh && known != null && known.second > System.currentTimeMillis()) return@withContext known.first
            val res = rpc("files/get_temporary_link", buildJsonObject { put("path", path) })
            val link = res["link"]?.jsonPrimitive?.contentOrNull ?: throw DropboxException(0, "no link")
            links[path] = link to System.currentTimeMillis() + LINK_REUSE_MS
            link
        }

    /** Bytes [offset, offset + length) of a file, for reading tags without downloading the audio. */
    suspend fun downloadRange(
        path: String,
        offset: Long,
        length: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            if (length <= 0) return@withContext ByteArray(0)
            val arg = headerArg(buildJsonObject { put("path", path) })
            call { token ->
                Request
                    .Builder()
                    .url("https://content.dropboxapi.com/2/files/download")
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", arg)
                    .header("Range", "bytes=$offset-${offset + length - 1}")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            }.use { res ->
                val all = res.body.bytes()
                // A server that ignored the range sent the whole file
                if (res.code == 206) {
                    all
                } else {
                    val from = offset.coerceAtMost(all.size.toLong()).toInt()
                    all.copyOfRange(from, (offset + length).coerceAtMost(all.size.toLong()).toInt())
                }
            }
        }

    /** Downloads a file to [dest] (through a .part file), reporting bytes done of total. */
    suspend fun download(
        path: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            call { token ->
                Request
                    .Builder()
                    .url("https://content.dropboxapi.com/2/files/download")
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", headerArg(buildJsonObject { put("path", path) }))
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            }.use { res ->
                val total =
                    runCatching { json.parseToJsonElement(res.header("Dropbox-API-Result") ?: "{}").jsonObject["size"]?.jsonPrimitive?.long }.getOrNull()
                        ?: res.body.contentLength()
                res.body.byteStream().use { input -> copy(input, part, total, onProgress) }
            }
            if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun copy(
        input: InputStream,
        to: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        to.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            var done = 0L
            var last = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (System.currentTimeMillis() - last > 250) {
                    last = System.currentTimeMillis()
                    onProgress(done, total)
                }
            }
            onProgress(done, total)
        }
    }

    /** Uploads going on now (path → bytes done, bytes in all), for the notification. */
    private val _uploads = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val uploads: StateFlow<Map<String, Pair<Long, Long>>> = _uploads.asStateFlow()

    private val appContext = context.applicationContext

    /** Work of several uploads (an audiobook's tracks) that keeps the app running between them */
    private val _holds = MutableStateFlow(0)
    val holds: StateFlow<Int> = _holds.asStateFlow()

    /** Runs [block] (uploads one after another) with the app kept running all through it. */
    suspend fun <T> keepRunning(block: suspend () -> T): T {
        _holds.update { it + 1 }
        UploadService.start(appContext)
        try {
            return block()
        } finally {
            _holds.update { it - 1 }
        }
    }

    /** Unfinished upload sessions, so an upload tried again goes on where it stopped. */
    private val sessions =
        object : UploadStore {
            private val up = context.getSharedPreferences("dropbox_uploads", Context.MODE_PRIVATE)

            override fun get(key: String) = up.getString(key, null)?.let { runCatching { json.decodeFromString(UploadSession.serializer(), it) }.getOrNull() }

            override fun put(
                key: String,
                session: UploadSession,
            ) = up.edit { putString(key, json.encodeToString(UploadSession.serializer(), session)) }

            override fun remove(key: String) = up.edit { remove(key) }
        }

    /** True when an earlier try of this upload left a session to go on with. */
    fun hasUnfinishedUpload(
        path: String,
        size: Long,
        id: String,
    ) = sessions.get(uploadKey(path, size, id)) != null

    /**
     * Uploads [file] into the app folder at [path]. A different file with the same name gets a
     * numbered name (Dropbox's autorename); the same file is kept as it is.
     */
    suspend fun upload(
        path: String,
        file: File,
        id: String = file.lastModified().toString(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DropboxFile =
        upload(path, file.length(), { from -> java.io.FileInputStream(file).also { it.channel.position(from) } }, onProgress, id = id)

    /**
     * Uploads [size] bytes read with [open], which gives a stream positioned at a byte offset
     * (e.g. a document picked on the phone, read in place), like [upload] of a file.
     *
     * Big files go up in 8 MB pieces. A piece that fails because of the network or Dropbox is
     * tried again, with growing waits, for about four minutes; when it still fails, the upload
     * stops but remembers how far it got (keyed by [path], [size] and [id], which says which
     * file it is), and the next upload of the same file goes on from there. While uploading the
     * app keeps running in the background (with a notification), so leaving it does not stop it.
     */
    suspend fun upload(
        path: String,
        size: Long,
        open: (from: Long) -> InputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        id: String = "",
    ): DropboxFile =
        withContext(Dispatchers.IO) {
            val commit =
                buildJsonObject {
                    put("path", path)
                    put("mode", "add")
                    put("autorename", true)
                    put("mute", true)
                }

            fun content(
                url: String,
                arg: JsonObject,
                from: Long,
                length: Long,
            ) = call { token ->
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Dropbox-API-Arg", headerArg(arg))
                    .post(StreamSlice(open, from, length))
                    .build()
            }

            fun cursor(
                sessionId: String,
                offset: Long,
            ) = buildJsonObject {
                put("session_id", sessionId)
                put("offset", offset)
            }

            val transport =
                object : UploadTransport {
                    override fun start() =
                        content("https://content.dropboxapi.com/2/files/upload_session/start", buildJsonObject { put("close", false) }, 0, 0)
                            .use { json.parseToJsonElement(it.body.string()).jsonObject["session_id"]!!.jsonPrimitive.content }

                    override fun append(
                        sessionId: String,
                        offset: Long,
                        length: Long,
                    ) = content(
                        "https://content.dropboxapi.com/2/files/upload_session/append_v2",
                        buildJsonObject {
                            put("cursor", cursor(sessionId, offset))
                            put("close", false)
                        },
                        offset,
                        length,
                    ).close()

                    override fun finish(
                        sessionId: String,
                        offset: Long,
                        length: Long,
                    ) = content(
                        "https://content.dropboxapi.com/2/files/upload_session/finish",
                        buildJsonObject {
                            put("cursor", cursor(sessionId, offset))
                            put("commit", commit)
                        },
                        offset,
                        length,
                    ).use { json.parseToJsonElement(it.body.string()).jsonObject }
                }
            val progress: (Long, Long) -> Unit = { done, total ->
                _uploads.update { it + (path to (done to total)) }
                onProgress(done, total)
            }
            _uploads.update { it + (path to (0L to size)) }
            UploadService.start(appContext)
            try {
                val upload = ResumableUpload(transport, sessions)
                val res =
                    if (size <= ResumableUpload.CHUNK) {
                        upload.retrying { content("https://content.dropboxapi.com/2/files/upload", commit, 0, size).use { json.parseToJsonElement(it.body.string()).jsonObject } }
                    } else {
                        upload.run(uploadKey(path, size, id), size, progress)
                    }
                progress(size, size)
                toFile(res)
            } finally {
                _uploads.update { it - path }
            }
        }

    private fun uploadKey(
        path: String,
        size: Long,
        id: String,
    ) = "$path|$size|$id"

    suspend fun delete(path: String) =
        withContext(Dispatchers.IO) {
            try {
                rpc("files/delete_v2", buildJsonObject { put("path", path) })
            } catch (e: DropboxException) {
                if (!(e.status == 409 && "not_found" in e.summary)) throw e
            }
            Unit
        }

    /** Part of a file as a request body, for upload sessions. */
    private class StreamSlice(
        val open: (Long) -> InputStream,
        val from: Long,
        val length: Long,
    ) : RequestBody() {
        override fun contentType() = OCTET

        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            open(from).use { input ->
                val buf = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    companion object {
        const val EBOOKS_FOLDER = "/Ebooks"
        const val ZIM_FOLDER = "/ZIM"
        const val AUDIOBOOKS_FOLDER = "/Audiobooks"
        private const val LINK_REUSE_MS = 3 * 60 * 60 * 1000L
        private const val KEY_LINK = "link"
        private const val KEY_TS = "ts"
        private const val KEY_ACCESS = "access"
        private const val KEY_EXPIRES = "expires"
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        @Volatile
        private var instance: DropboxClient? = null

        fun get(context: Context): DropboxClient =
            instance ?: synchronized(this) {
                instance ?: DropboxClient(context.applicationContext).also { instance = it }
            }

        /** JSON for the Dropbox-API-Arg header: HTTP headers must be ASCII. */
        fun headerArg(arg: JsonObject): String =
            buildString {
                for (c in arg.toString()) {
                    if (c.code in 0x20..0x7e) append(c) else append("\\u%04x".format(c.code))
                }
            }

        /** Dropbox's content hash: SHA-256 of each 4 MB block, then SHA-256 of those hashes. */
        fun contentHash(file: File): String = contentHash(file.inputStream())

        /** [contentHash] of a stream (a file picked on the phone); closes it. */
        fun contentHash(stream: java.io.InputStream): String {
            val all = MessageDigest.getInstance("SHA-256")
            stream.buffered().use { input ->
                val block = ByteArray(4 * 1024 * 1024)
                while (true) {
                    var filled = 0
                    while (filled < block.size) {
                        val n = input.read(block, filled, block.size - filled)
                        if (n < 0) break
                        filled += n
                    }
                    if (filled == 0) break
                    val d = MessageDigest.getInstance("SHA-256")
                    d.update(block, 0, filled)
                    all.update(d.digest())
                    if (filled < block.size) break
                }
            }
            return all.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
