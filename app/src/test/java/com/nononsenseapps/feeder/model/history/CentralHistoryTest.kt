package com.nononsenseapps.feeder.model.history

import com.nononsenseapps.feeder.sync2.DeviceStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CentralHistoryTest {
    private var now = 1_000_000_000L

    private fun store(file: File? = null) = CentralHistory(file, { DeviceStamp("dev1", "Pixel 8") }, { now })

    private val articleKey = "https://example.com/feed\nguid-1"

    @Test
    fun keys() {
        assertEquals("article:a", CentralHistory.keyOf("article", "a"))
        assertEquals("feed:https://x/rss:create", CentralHistory.keyOf("feed", "https://x/rss", "create"))
    }

    @Test
    fun notesAndUpdatesAnEntry() {
        val h = store()
        val e = h.note(RecentNote("article", articleKey, n = "Title", s = "Feed", img = "https://img/x.jpg", d = true))!!
        assertEquals("article:$articleKey", e.key)
        assertEquals(now, e.at)
        assertEquals(now, e.ts)
        assertEquals("Pixel 8", e.dn)
        assertFalse(e.synced)
        // Progress without touch keeps "at" but moves the record time
        now += 10
        val p = h.note(RecentNote("article", articleKey, f = 0.5), touch = false)!!
        assertEquals(e.at, p.at)
        assertEquals(now, p.ts)
        assertEquals("Title", p.n)
        assertEquals(0.5, p.f!!, 0.0)
        // The same again changes nothing
        assertNull(h.note(RecentNote("article", articleKey, f = 0.5), touch = false))
        // create = false never makes a new one
        assertNull(h.note(RecentNote("youtube", "other", pos = 3), touch = false, create = false))
        // Non-http images are dropped
        assertEquals("", h.note(RecentNote("zim", "b\np", img = "file:///x"))!!.img)
    }

    @Test
    fun playlistSticks() {
        val h = store()
        h.note(RecentNote("podcast", articleKey, pl = "p1", pn = "Commute"))
        now++
        val e = h.note(RecentNote("podcast", articleKey, pl = "p2", pn = "Other"))!!
        assertEquals("p1", e.pl)
        assertEquals("Commute", e.pn)
    }

    @Test
    fun valueRoundTrip() {
        val h = store()
        val e =
            h.note(
                RecentNote(
                    "youtube",
                    articleKey,
                    n = "Video",
                    s = "Channel",
                    img = "https://i/x.jpg",
                    f = 0.25,
                    pos = 60,
                    dur = 240,
                    pl = "p",
                    pn = "List",
                    x = "abc123",
                    u = "https://youtube.com/watch?v=abc123",
                ),
            )!!
        val v = e.toValue()
        assertEquals(listOf("t", "a", "i", "n", "s", "img", "at", "f", "pos", "dur", "pl", "pn", "x", "u", "dv", "dn"), v.keys.toList())
        // As Moshi reads it back: numbers are doubles
        val wire = v.mapValues { (_, x) -> if (x is Number) x.toDouble() else x }
        val back = RecentEntry.fromValue(e.key, wire, e.ts)!!
        assertEquals(e.copy(synced = true), back)
        // Done is sent as 1 and read from 1 or true
        val done = h.note(RecentNote("article", "k", d = true))!!
        assertEquals(1, done.toValue()["d"])
        assertTrue(RecentEntry.fromValue(done.key, mapOf("t" to "article", "a" to "open", "i" to "k", "d" to 1.0), 5)!!.d)
        // Broken ones are skipped, and the key must match the value
        assertNull(RecentEntry.fromValue("article:k", mapOf("t" to "nope", "i" to "k"), 1))
        assertNull(RecentEntry.fromValue("article:other", mapOf("t" to "article", "i" to "k"), 1))
        assertNull(RecentEntry.fromValue("article:k", "x", 1))
        // A removal
        assertTrue(RecentEntry.fromValue("article:k", mapOf("deleted" to true), 9)!!.del)
    }

    @Test
    fun latestWins() {
        val h = store()
        h.note(RecentNote("article", articleKey, n = "Mine"))
        val mine = h.get("article:$articleKey")!!
        // Older from another device: ignored
        assertFalse(h.applyRemote(mine.key, mapOf("t" to "article", "i" to articleKey, "n" to "Theirs", "at" to 1.0), mine.ts - 1))
        assertEquals("Mine", h.get(mine.key)!!.n)
        // Newer: taken, and marked synced
        assertTrue(h.applyRemote(mine.key, mapOf("t" to "article", "i" to articleKey, "n" to "Theirs", "at" to 5.0), mine.ts + 5))
        val got = h.get(mine.key)!!
        assertEquals("Theirs", got.n)
        assertTrue(got.synced)
        assertTrue(h.unsynced().isEmpty())
    }

    @Test
    fun removalIsATombstone() {
        val h = store()
        h.note(RecentNote("article", articleKey, n = "A"))
        val key = "article:$articleKey"
        now += 5
        h.remove(key)
        assertNull(h.get(key))
        assertTrue(h.entries.value.isEmpty())
        val sent = h.unsynced().single()
        assertEquals(mapOf("deleted" to true), sent.value)
        assertEquals(now, sent.ts)
        h.markSynced(listOf(sent))
        assertTrue(h.unsynced().isEmpty())
        // An older copy from another device doesn't bring it back
        assertFalse(h.applyRemote(key, mapOf("t" to "article", "i" to articleKey, "n" to "A", "at" to 1.0), now - 1))
        assertNull(h.get(key))
        // A remote removal of a newer local entry is ignored; of an older one, applied
        h.note(RecentNote("article", "k2", n = "B"))
        val ts = h.get("article:k2")!!.ts
        assertFalse(h.applyRemote("article:k2", mapOf("deleted" to true), ts - 1))
        assertTrue(h.applyRemote("article:k2", mapOf("deleted" to true), ts + 1))
        assertNull(h.get("article:k2"))
        // Opening it again brings it back with a newer record time
        now += 100
        assertNotNull(h.note(RecentNote("article", articleKey, n = "A")))
        assertTrue(h.get(key)!!.ts > sent.ts)
    }

    @Test
    fun clearMarker() {
        val h = store()
        h.note(RecentNote("article", "a", n = "A"))
        now += 10
        h.clear()
        assertTrue(h.entries.value.isEmpty())
        val marker = h.unsynced().single()
        assertEquals(CentralHistory.CLEAR_KEY, marker.key)
        assertEquals(mapOf("before" to now), marker.value)
        assertEquals(now, marker.ts)
        h.markSynced(listOf(marker))
        assertTrue(h.unsynced().isEmpty())
        // Remote entries from before the clear are ignored, newer ones kept
        assertFalse(h.applyRemote("article:old", mapOf("t" to "article", "i" to "old", "at" to (now - 1).toDouble()), now + 50))
        assertTrue(h.applyRemote("article:new", mapOf("t" to "article", "i" to "new", "at" to (now + 1).toDouble()), now + 50))
        // A remote clear drops what is older than it here
        now += 100
        h.note(RecentNote("article", "later", n = "L"))
        assertTrue(h.applyRemote(CentralHistory.CLEAR_KEY, mapOf("before" to (now - 10).toDouble()), now - 10))
        assertNull(h.get("article:new"))
        assertNotNull(h.get("article:later"))
        // An older clear changes nothing
        assertFalse(h.applyRemote(CentralHistory.CLEAR_KEY, mapOf("before" to 5.0), 5))
        // Force sync sends the marker again
        h.markAllUnsynced()
        assertTrue(h.unsynced().any { it.key == CentralHistory.CLEAR_KEY })
    }

    @Test
    fun retention() {
        val h = store()
        // Too old: dropped at once
        h.note(RecentNote("article", "ancient", at = now - CentralHistory.MAX_AGE_MS - 1))
        assertNull(h.get("article:ancient"))
        repeat(CentralHistory.MAX_ENTRIES + 5) { k ->
            h.note(RecentNote("article", "a$k", at = now - CentralHistory.MAX_ENTRIES + k))
        }
        assertEquals(CentralHistory.MAX_ENTRIES, h.entries.value.size)
        // The oldest went
        assertNull(h.get("article:a0"))
        assertNotNull(h.get("article:a${CentralHistory.MAX_ENTRIES + 4}"))
        val newest = h.entries.value.first()
        assertEquals("article:a${CentralHistory.MAX_ENTRIES + 4}", newest.key)
    }

    @Test
    fun markSyncedSkipsEntriesChangedSince() {
        val h = store()
        h.note(RecentNote("article", "a", n = "A"))
        val sent = h.unsynced()
        now += 5
        h.note(RecentNote("article", "a", f = 0.3), touch = false)
        h.markSynced(sent)
        assertEquals(1, h.unsynced().size)
    }

    @Test
    fun savesAndLoads() {
        val dir = Files.createTempDirectory("recent").toFile()
        val file = File(dir, "central_history.json")
        val h = store(file)
        h.note(RecentNote("ebook", "b1", n = "Book", f = 0.4, x = "epubcfi(/6/4)"))
        h.remove("ebook:none")
        // Written on a background thread
        var tries = 0
        while ((!file.exists() || !file.readText().contains("b1")) && tries++ < 100) Thread.sleep(20)
        val again = store(file)
        assertEquals("Book", again.get("ebook:b1")!!.n)
        dir.deleteRecursively()
    }
}
