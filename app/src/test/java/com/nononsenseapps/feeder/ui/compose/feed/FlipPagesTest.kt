package com.nononsenseapps.feeder.ui.compose.feed

import org.junit.Test
import kotlin.test.assertEquals

class FlipPagesTest {
    @Test
    fun everyArticleGetsItsOwnPage() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), buildFlipPages(listOf("a", "b", "c")))
    }

    @Test
    fun emptyListHasNoPages() {
        assertEquals(emptyList(), buildFlipPages(emptyList<String>()))
    }

    @Test
    fun longTextIsCutAtAWord() {
        assertEquals("one two…", "one two three".cutAt(9))
        assertEquals("short", "short".cutAt(9))
    }

    @Test
    fun summaryBulletsLoseTheirMarkers() {
        assertEquals(
            listOf("First point", "Second point", "Third point"),
            parseSummaryBullets("- First point\n* **Second** point\n\n1. Third point\n- Fourth point"),
        )
    }

    @Test
    fun summaryIgnoresHeadersAndLanguageLine() {
        assertEquals(listOf("Only point"), parseSummaryBullets("Lang: \"en\"\n# Summary\n• Only point"))
    }
}
