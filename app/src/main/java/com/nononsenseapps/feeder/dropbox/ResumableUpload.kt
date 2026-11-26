package com.nononsenseapps.feeder.dropbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** How far an unfinished Dropbox upload session got, kept so a failed upload goes on from there. */
@Serializable
data class UploadSession(
    val sessionId: String,
    val offset: Long,
    val size: Long,
    /** When the session was started (Dropbox drops sessions after about a week) */
    val startedAt: Long = 0,
)

/** Where unfinished sessions are kept between tries (SharedPreferences on the phone). */
interface UploadStore {
    fun get(key: String): UploadSession?

    fun put(
        key: String,
        session: UploadSession,
    )

    fun remove(key: String)
}

/** The three requests of a Dropbox upload session. */
interface UploadTransport {
    /** files/upload_session/start with no bytes: returns the session id */
    fun start(): String

    /** files/upload_session/append_v2 of [length] bytes of the file from [offset] */
    fun append(
        sessionId: String,
        offset: Long,
        length: Long,
    )

    /** files/upload_session/finish with the last [length] bytes from [offset] */
    fun finish(
        sessionId: String,
        offset: Long,
        length: Long,
    ): JsonObject
}

/**
 * A big upload in pieces that survives a bad connection: every piece is tried again (with growing
 * waits) when the network drops or Dropbox has a hiccup, and how far it got is saved after every
 * piece, so trying the upload again later goes on from there instead of from zero.
 */
class ResumableUpload(
    private val transport: UploadTransport,
    private val store: UploadStore,
    private val chunk: Long = CHUNK,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun run(
        key: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ): JsonObject {
        var session = store.get(key)?.takeIf { it.size == size && now() - it.startedAt < SESSION_MAX_AGE_MS }
        if (session == null) store.remove(key)
        var restarts = 0
        var fixes = 0
        while (true) {
            val s =
                session ?: UploadSession(retrying { transport.start() }, 0, size, now()).also {
                    store.put(key, it)
                    session = it
                }
            onProgress(s.offset, size)
            var offset = s.offset
            try {
                while (size - offset > chunk) {
                    retrying { transport.append(s.sessionId, offset, chunk) }
                    offset += chunk
                    session = s.copy(offset = offset).also { store.put(key, it) }
                    fixes = 0
                    onProgress(offset, size)
                }
                val res = retrying { transport.finish(s.sessionId, offset, size - offset) }
                store.remove(key)
                onProgress(size, size)
                return res
            } catch (e: DropboxException) {
                // A piece got there but the answer did not (or the other way round): Dropbox says
                // where it really is, go on from there
                val correct = correctOffset(e)
                if (correct != null && correct in 0..size && fixes < 5) {
                    fixes++
                    session = s.copy(offset = correct).also { store.put(key, it) }
                    continue
                }
                // The session is gone (too old, or already finished): start over
                if (e.status == 409 && restarts < 2 && ("not_found" in e.summary || "closed" in e.summary)) {
                    restarts++
                    store.remove(key)
                    session = null
                    continue
                }
                throw e
            }
        }
    }

    /** Runs [block] again when the network or Dropbox failed for a moment. */
    fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (!transient(e) || attempt >= WAITS_MS.size) throw e
                sleep(WAITS_MS[attempt++])
            }
        }
    }

    companion object {
        const val CHUNK = 8L * 1024 * 1024

        /** About four minutes in all, enough for a train tunnel or a Wi-Fi switch */
        val WAITS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000, 30_000, 30_000, 30_000)
        private const val SESSION_MAX_AGE_MS = 6L * 24 * 60 * 60 * 1000

        fun transient(e: IOException): Boolean =
            when (e) {
                is DropboxException -> e.status == 0 || e.status == 408 || e.status == 429 || e.status >= 500
                else -> true
            }

        private val json = Json { ignoreUnknownKeys = true }

        /** correct_offset from an incorrect_offset error, wherever Dropbox nests it. */
        fun correctOffset(e: DropboxException): Long? {
            if ("incorrect_offset" !in e.summary && "incorrect_offset" !in e.body) return null
            val root = runCatching { json.parseToJsonElement(e.body) }.getOrNull() ?: return null
            return find(root, "correct_offset")
        }

        private fun find(
            el: JsonElement,
            name: String,
        ): Long? =
            when (el) {
                is JsonObject -> (el[name] as? JsonPrimitive)?.longOrNull ?: el.values.firstNotNullOfOrNull { find(it, name) }
                is JsonArray -> el.firstNotNullOfOrNull { find(it, name) }
                else -> null
            }
    }
}
