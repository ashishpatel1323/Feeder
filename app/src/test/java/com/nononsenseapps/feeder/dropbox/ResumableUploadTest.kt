package com.nononsenseapps.feeder.dropbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResumableUploadTest {
    private class MemStore : UploadStore {
        val map = mutableMapOf<String, UploadSession>()

        override fun get(key: String) = map[key]

        override fun put(
            key: String,
            session: UploadSession,
        ) {
            map[key] = session
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    /** A Dropbox that keeps how many bytes each session has, and fails when told to. */
    private class FakeServer : UploadTransport {
        val sessions = mutableMapOf<String, Long>()
        val sent = mutableListOf<Pair<Long, Long>>()
        var failNext: (String) -> IOException? = { null }
        var lostAnswer = false
        var finished: Long = -1

        override fun start(): String {
            failNext("start")?.let { throw it }
            val id = "s${sessions.size + 1}"
            sessions[id] = 0
            return id
        }

        override fun append(
            sessionId: String,
            offset: Long,
            length: Long,
        ) {
            failNext("append")?.let { throw it }
            val have = sessions[sessionId] ?: throw DropboxException(409, "lookup_failed/not_found/", "{}")
            if (have != offset) {
                throw DropboxException(409, "incorrect_offset/", """{"error_summary":"incorrect_offset/","error":{".tag":"incorrect_offset","correct_offset":$have}}""")
            }
            sent += offset to length
            sessions[sessionId] = have + length
            if (lostAnswer) {
                lostAnswer = false
                throw SocketTimeoutException("timeout")
            }
        }

        override fun finish(
            sessionId: String,
            offset: Long,
            length: Long,
        ): JsonObject {
            failNext("finish")?.let { throw it }
            val have = sessions[sessionId] ?: throw DropboxException(409, "lookup_failed/not_found/", "{}")
            if (have != offset) {
                throw DropboxException(
                    409,
                    "lookup_failed/incorrect_offset/",
                    """{"error":{".tag":"lookup_failed","lookup_failed":{".tag":"incorrect_offset","correct_offset":$have}}}""",
                )
            }
            sent += offset to length
            finished = have + length
            sessions.remove(sessionId)
            return JsonObject(mapOf("path_display" to JsonPrimitive("/a.mp3")))
        }
    }

    private val waits = mutableListOf<Long>()

    private fun upload(
        fake: FakeServer,
        store: MemStore,
    ) = ResumableUpload(fake, store, chunk = 10, sleep = { waits += it }, now = { 1000 })

    @Test
    fun uploadsInPieces() {
        val fake = FakeServer()
        val store = MemStore()
        upload(fake, store).run("k", 35, { _, _ -> })
        assertEquals(listOf(0L to 10L, 10L to 10L, 20L to 10L, 30L to 5L), fake.sent)
        assertEquals(35, fake.finished)
        assertNull(store.map["k"])
    }

    @Test
    fun triesAPieceAgainWhenTheNetworkDrops() {
        val fake = FakeServer()
        var drops = 3
        fake.failNext = { what -> if (what == "append" && drops-- > 0) IOException("unexpected end of stream") else null }
        upload(fake, MemStore()).run("k", 35, { _, _ -> })
        assertEquals(35, fake.finished)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
    }

    @Test
    fun triesAgainOnDropboxServerErrors() {
        val fake = FakeServer()
        var errors = 2
        fake.failNext = { what -> if (what == "finish" && errors-- > 0) DropboxException(503, "unavailable") else null }
        upload(fake, MemStore()).run("k", 25, { _, _ -> })
        assertEquals(25, fake.finished)
    }

    @Test
    fun goesOnWhereItStoppedOnTheNextTry() {
        val fake = FakeServer()
        val store = MemStore()
        var pieces = 0
        fake.failNext = { what -> if (what == "append" && ++pieces == 3) IOException("offline") else null }
        val up = ResumableUpload(fake, store, chunk = 10, sleep = { throw IOException("offline for good") }, now = { 1000 })
        assertFailsWith<IOException> { up.run("k", 45, { _, _ -> }) }
        assertEquals(20, store.map["k"]!!.offset)

        // Later, with the network back: no byte before 20 is sent again
        fake.sent.clear()
        fake.failNext = { null }
        val progress = mutableListOf<Long>()
        upload(fake, store).run("k", 45, { done, _ -> progress += done })
        assertEquals(listOf(20L to 10L, 30L to 10L, 40L to 5L), fake.sent)
        assertEquals(20, progress.first())
        assertEquals(45, fake.finished)
    }

    @Test
    fun followsDropboxWhenAPieceGotThereButTheAnswerDidNot() {
        val fake = FakeServer()
        fake.lostAnswer = true
        upload(fake, MemStore()).run("k", 35, { _, _ -> })
        // The first piece is not sent twice: the retry is told the correct offset
        assertEquals(35, fake.finished)
        assertEquals(1, fake.sent.count { it.first == 0L })
    }

    @Test
    fun startsOverWhenTheSessionIsGone() {
        val fake = FakeServer()
        val store = MemStore()
        store.put("k", UploadSession("old", 20, 35, 1000))
        upload(fake, store).run("k", 35, { _, _ -> })
        assertEquals(35, fake.finished)
    }

    @Test
    fun givesUpOnErrorsThatDoNotGoAway() {
        val fake = FakeServer()
        fake.failNext = { what -> if (what == "append") DropboxException(409, "path/insufficient_space/") else null }
        assertFailsWith<DropboxException> { upload(fake, MemStore()).run("k", 35, { _, _ -> }) }
        assertEquals(emptyList(), waits)
    }

    @Test
    fun aSessionForAnotherSizeIsNotUsed() {
        val fake = FakeServer()
        val store = MemStore()
        store.put("k", UploadSession("old", 20, 99, 1000))
        upload(fake, store).run("k", 35, { _, _ -> })
        assertEquals(0L, fake.sent.first().first)
    }
}
