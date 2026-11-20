package com.nononsenseapps.feeder.ui.compose.feed

import org.junit.Test
import kotlin.test.assertEquals

class FlipPagesTest {
    private fun pages(vararg hasImage: Boolean) = buildFlipPages(hasImage.toList()) { it }

    @Test
    fun articlesWithImagesGetAPageEach() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), pages(true, true, true))
    }

    @Test
    fun articlesWithoutImagesArePaired() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), pages(false, false, false, false))
    }

    @Test
    fun aLoneTextArticleBeforeAnImageGetsItsOwnPage() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), pages(false, true, false, false))
    }

    @Test
    fun aTrailingTextArticleGetsItsOwnPage() {
        assertEquals(listOf(listOf(0), listOf(1)), pages(true, false))
    }

    @Test
    fun emptyListHasNoPages() {
        assertEquals(emptyList(), pages())
    }
}
