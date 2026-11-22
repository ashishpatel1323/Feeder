package com.nononsenseapps.feeder.sharedlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
    @Test
    fun findsTheLinkInSharedText() {
        assertEquals("https://example.com/post?a=1", findLink("Look at this https://example.com/post?a=1, nice"))
        assertEquals("https://example.com/post", findLink("example.com/post"))
        assertNull(findLink("just words"))
    }

    @Test
    fun matchesTheWebAppsNormalizedLinks() {
        // Same results as web/src/lib/__tests__/share.test.ts, so a link shared on both is one article
        assertEquals("https://example.com/p?id=3", normalizeLink("https://example.com/p?utm_source=x&id=3#top"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalizeLink("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("https://example.com/", normalizeLink("https://example.com"))
    }

    @Test
    fun namesTheSite() {
        assertEquals("paulgraham.com", siteOf("https://www.paulgraham.com/best.html"))
    }
}
