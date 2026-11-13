package com.nononsenseapps.feeder.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HackerNewsScoresTest {
    @Test
    fun normalizeIgnoresSchemeWwwSlashFragmentAndTracking() {
        assertEquals(
            "techcrunch.com/2026/11/12/muse-calls",
            normalizeLinkForHn("https://www.techcrunch.com/2026/11/12/muse-calls/?utm_source=rss&utm_medium=feed#comments"),
        )
        assertEquals(
            "example.com/post?id=5",
            normalizeLinkForHn("http://Example.com/post?id=5&fbclid=abc"),
        )
    }

    @Test
    fun normalizeRejectsUnsupportedLinks() {
        assertNull(normalizeLinkForHn(null))
        assertNull(normalizeLinkForHn(""))
        assertNull(normalizeLinkForHn("mailto:someone@example.com"))
        assertNull(normalizeLinkForHn("https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun searchQueryDropsQueryParameters() {
        assertEquals("example.com/post", hnSearchQueryFor("https://example.com/post?id=5"))
    }

    @Test
    fun parsePicksMostUpvotedMatchingSubmission() {
        val json =
            """
            {"hits":[
              {"objectID":"1","url":"https://example.com/other","points":900,"num_comments":10},
              {"objectID":"2","url":"http://www.example.com/post/","points":120,"num_comments":40},
              {"objectID":"3","url":"https://example.com/post","points":350,"num_comments":88},
              {"objectID":"4","url":null,"points":5000,"num_comments":1}
            ]}
            """.trimIndent()
        assertEquals(
            HnStory(id = "3", points = 350, comments = 88),
            parseHnSearchResponse(json, "https://example.com/post?utm_source=rss"),
        )
    }

    @Test
    fun parseReturnsNullWhenNoMatch() {
        val json = """{"hits":[{"objectID":"1","url":"https://example.com/other","points":9,"num_comments":0}]}"""
        assertNull(parseHnSearchResponse(json, "https://example.com/post"))
        assertNull(parseHnSearchResponse("not json", "https://example.com/post"))
    }

    @Test
    fun formatsPointsShort() {
        assertEquals("0", formatHnPoints(0))
        assertEquals("999", formatHnPoints(999))
        assertEquals("1k", formatHnPoints(1000))
        assertEquals("1.2k", formatHnPoints(1234))
        assertEquals("15k", formatHnPoints(15_400))
    }
}
