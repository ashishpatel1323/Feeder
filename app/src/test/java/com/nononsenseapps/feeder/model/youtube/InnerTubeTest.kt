package com.nononsenseapps.feeder.model.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/** Parses real answers from youtube.com/youtubei/v1 (saved Nov 2026, video dQw4w9WgXcQ). */
class InnerTubeTest {
    private fun load(name: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("innertube/$name"))
        val text = (if (name.endsWith(".gz")) GZIPInputStream(stream) else stream).bufferedReader().readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun readsLengthViewsAndLikesFromThePlayerEndpoint() {
        val meta = checkNotNull(InnerTube.parsePlayer("dQw4w9WgXcQ", load("player.json")))
        assertEquals(213, meta.duration)
        assertTrue((meta.views ?: 0) > 1_000_000_000)
        assertTrue((meta.likes ?: 0) > 1_000_000)
        assertFalse(meta.isLive)
    }

    @Test
    fun readsChannelLikesCommentsTokenAndRelatedFromTheNextEndpoint() {
        val next = InnerTube.parseNext(load("next.json.gz"))
        assertEquals("Rick Astley", next.channelName)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", next.channelId)
        assertEquals("4.56M subscribers", next.subscribersText)
        assertEquals("19M", next.likesText)
        assertNotNull(next.channelAvatar)
        assertNotNull(next.commentsToken)
        assertTrue(next.related.size >= 5)
        val first = next.related.first { it.videoId == "b2U2agYvFWU" }
        assertEquals("1:46:23", first.durationText)
        assertEquals("Christmas Melodies", first.channel)
        assertEquals("43K views", first.viewsText)
        assertEquals("3w ago", first.ageText)
        assertTrue(first.thumbnail!!.startsWith("https://i.ytimg.com/"))
        assertTrue(first.channelAvatar!!.startsWith("https://yt3.ggpht.com/"))
    }

    @Test
    fun readsCommentsInOrderWithTheNextPage() {
        val page = InnerTube.parseComments(load("comments.json.gz"))
        assertEquals("2,462,578 Comments", page.countText)
        assertTrue(page.comments.size >= 10)
        val top = page.comments.first()
        assertEquals("@bdr8823", top.author)
        assertEquals("My algebra teacher said that this was a link to the test answers", top.text)
        assertEquals("73K", top.likes)
        assertEquals("721", top.replies)
        assertEquals("7 years ago", top.published)
        assertNotNull(page.next)
    }
}
