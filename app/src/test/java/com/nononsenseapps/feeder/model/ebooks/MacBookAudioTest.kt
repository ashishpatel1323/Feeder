package com.nononsenseapps.feeder.model.ebooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacBookAudioTest {
    private val now = 1_000_000_000L

    @Test
    fun onlyChaptersWithAudioAndMetaCount() {
        assertEquals(setOf(2, 3), macReadyChapters(listOf("s2.m4a", "s2.json", "s3.json", "s3.m4a", "s4.m4a", "s5.json", "mac.json")))
    }

    @Test
    fun chapterInDropboxIsLeftToTheMacEvenWhenItIsOff() {
        assertTrue(macCovers(null, setOf(4), "book", 4, now))
    }

    @Test
    fun activeMacKeepsTheChaptersItPlans() {
        val status = MacStatus(seen = now - 60_000, books = listOf(MacBook(id = "book", planned = listOf(3, 4, 5))))
        assertTrue(macCovers(status, emptySet(), "book", 5, now))
        assertFalse(macCovers(status, emptySet(), "book", 6, now))
        assertFalse(macCovers(status, emptySet(), "other", 5, now))
    }

    @Test
    fun macNotSeenForAWhileLeavesChaptersToThePhone() {
        val status = MacStatus(seen = now - MAC_ACTIVE_MILLIS - 1, books = listOf(MacBook(id = "book", planned = listOf(3, 4, 5))))
        assertFalse(status.isActive(now))
        assertFalse(macCovers(status, emptySet(), "book", 4, now))
    }
}
