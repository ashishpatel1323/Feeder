package com.nononsenseapps.feeder.model.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemLengthTest {
    @Test
    fun articlesUseReadingTime() {
        assertEquals(240L, itemSeconds(ItemKind.ARTICLE, wordCount = 880, enclosureDuration = 1500))
        assertNull(itemSeconds(ItemKind.ARTICLE, wordCount = 0))
    }

    @Test
    fun episodesAndVideosUseTheirRealLength() {
        assertEquals(1500L, itemSeconds(ItemKind.PODCAST, wordCount = 880, enclosureDuration = 1500))
        // A learned length wins over the feed's, which wins over the last position's
        assertEquals(1490L, itemSeconds(ItemKind.PODCAST, 0, enclosureDuration = 1500, knownLength = 1490, positionLength = 1480))
        assertEquals(600L, itemSeconds(ItemKind.YOUTUBE, 0, enclosureDuration = null, positionLength = 600))
        assertNull(itemSeconds(ItemKind.YOUTUBE, wordCount = 2000))
    }

    @Test
    fun lengthTextPerType() {
        assertEquals(LengthText.Read(4), lengthText(ItemKind.ARTICLE, 240))
        assertEquals(LengthText.Total(24), lengthText(ItemKind.PODCAST, 24 * 60))
        assertEquals(LengthText.Left(19), lengthText(ItemKind.PODCAST, 24 * 60, ItemProgress(5 * 60, 24 * 60)))
        // Finished: back to the full length
        assertEquals(LengthText.Total(24), lengthText(ItemKind.YOUTUBE, 24 * 60, ItemProgress(24 * 60 - 10, 24 * 60)))
        assertNull(lengthText(ItemKind.PODCAST, null))
    }

    @Test
    fun progress() {
        assertTrue(ItemProgress(60, 600).partlyPlayed)
        assertFalse(ItemProgress(2, 600).partlyPlayed)
        assertFalse(ItemProgress(590, 600).partlyPlayed)
        assertEquals(0.5f, ItemProgress(300, 600).fraction)
    }

    @Test
    fun clock() {
        assertEquals("4:05", clockText(245))
        assertEquals("1:02:03", clockText(3723))
        assertEquals(1, badgeMinutes(20))
    }
}
