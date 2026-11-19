package com.nononsenseapps.feeder.sync2

import com.nononsenseapps.feeder.db.room.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.Instant

class Sync2KeysTest {
    // Same values the web app derives (web/src/lib/sync2.ts deriveKeys/recordId)
    private val code = "ab".repeat(32)
    private val hmacKey = ByteArray(32) { it.toByte() }

    @Test
    fun derivesTheSameValuesAsTheWebApp() {
        val keys = Sync2Keys(code, hmacKey)
        assertEquals("aXrYheQ9GkVKE2V7zhKT7Jf1FDbXXz3YK3hEDm9z9_g", keys.account)
        assertEquals("Fh7FmE1XdTSMK70WYlvdMs6lb0hq-J_b7CMpfZd561Y", keys.auth)
        assertEquals("dPi8sP0dQAkUEWDIyntNac6y9q9hHLBtCcuBYe7d1xI", keys.recordId("read", "https://a/feed\nguid-1"))
    }

    @Test
    fun syncCodeCaseDoesNotMatter() {
        assertEquals(Sync2Keys(code, hmacKey).account, Sync2Keys(code.uppercase(), hmacKey).account)
    }

    @Test
    fun feedValueRoundTrips() {
        val feed = Feed(url = URL("https://a/feed"), title = "A", customTitle = "Mine", tag = "News", fullTextByDefault = true)
        val v = Sync2Client.feedValue(feed)
        val back = Sync2Client.applyFeedValue(v, Feed(url = URL("https://a/feed")), 1234)
        assertEquals(v, Sync2Client.feedValue(back))
        assertEquals(Instant.ofEpochMilli(1234), back.whenModified)
        assertTrue(Sync2Client.isDeleted(mapOf("deleted" to true)))
        assertEquals(64, Sync2Client.randomSyncCode().length)
    }
}
