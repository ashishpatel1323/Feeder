package com.nononsenseapps.feeder.model.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryEventTest {
    @Test
    fun valueHasTheWebAppsFieldsInOrder() {
        val e = HistoryEvent(id = "x", c = "audiobook", i = "b1", a = "pause", r = "bluetooth", at = 9000, p = "Ch. 2 · 01:03", s = "Two", f = 0.25, x = "1:63000", dv = "d", dn = "Pixel 8")
        assertEquals(listOf("c", "i", "a", "r", "at", "p", "s", "f", "x", "dv", "dn"), e.toValue().keys.toList())
        // Empty fields are left out
        assertEquals(listOf("c", "i", "a", "at"), HistoryEvent(id = "y", c = "zim", i = "z", a = "open", at = 1).toValue().keys.toList())
    }

    @Test
    fun readsEventsFromOtherDevices() {
        val e = HistoryEvent.fromValue("ev1", mapOf("c" to "ebook", "i" to "e1", "a" to "close", "r" to "background", "at" to 2000.0, "p" to "37%", "f" to 0.37, "x" to "epubcfi(/6/4)", "dv" to "w", "dn" to "Chrome on Mac"), 5)!!
        assertEquals("ev1", e.id)
        assertEquals(2000L, e.at)
        assertEquals("Chrome on Mac", e.dn)
        assertEquals(0.37, e.f, 0.0001)
        assertEquals(true, e.synced)
        // No time: the record's
        assertEquals(77L, HistoryEvent.fromValue("ev2", mapOf("c" to "zim", "i" to "z", "a" to "open"), 77)!!.at)
        // Broken ones are skipped
        assertNull(HistoryEvent.fromValue("ev3", mapOf("c" to "nope", "i" to "z", "a" to "open"), 1))
        assertNull(HistoryEvent.fromValue("ev4", mapOf("c" to "zim", "i" to "", "a" to "open"), 1))
        assertNull(HistoryEvent.fromValue("ev5", "x", 1))
    }

    @Test
    fun chips() {
        assertEquals("01:03", ItemHistory.clockText(63_000))
        assertEquals("1:02:03", ItemHistory.clockText(3_723_000))
        assertEquals("p. 41 of 310", ItemHistory.bookChip(0.1, 41, 310))
        assertEquals("37%", ItemHistory.bookChip(0.37, 0, 0))
    }
}
