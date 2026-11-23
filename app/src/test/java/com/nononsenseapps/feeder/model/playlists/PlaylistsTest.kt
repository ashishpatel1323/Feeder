package com.nononsenseapps.feeder.model.playlists

import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.parseDurationSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private var n = 0L

    private fun item(
        feedId: Long = 1,
        tag: String = "News",
        link: String? = "https://example.com/a",
        enclosure: String? = null,
        duration: Long? = null,
        read: Boolean = false,
        bookmarked: Boolean = false,
        sortTime: Long = now - (++n) * 1000,
        words: Int = 700,
    ) = PlaylistCandidate(
        id = ++n,
        title = "t$n",
        link = link,
        enclosureLink = enclosure,
        enclosureType = null,
        enclosureDuration = duration,
        read = read,
        bookmarked = bookmarked,
        offline = false,
        sortTime = sortTime,
        wordCount = words,
        wordCountFull = 0,
        image = null,
        feedId = feedId,
        feedUrl = "https://example.com/feed$feedId",
        feedTag = tag,
        feedTitle = "Feed $feedId",
        feedImage = null,
        fullTextByDefault = false,
    )

    private fun episode(duration: Long? = 1800) = item(feedId = 2, tag = "Podcasts", enclosure = "https://cdn.example.com/ep.mp3", duration = duration)

    private val ctx = PlaylistContext(now = now)

    @Test
    fun parsesItunesDurations() {
        assertEquals(3600L, parseDurationSeconds("3600"))
        assertEquals(3665L, parseDurationSeconds("61:05"))
        assertEquals(3665L, parseDurationSeconds("1:01:05"))
        assertNull(parseDurationSeconds("about an hour"))
        assertNull(parseDurationSeconds(""))
        assertNull(parseDurationSeconds("0"))
    }

    @Test
    fun tellsKindsApart() {
        assertEquals(ItemKind.PODCAST, episode().kind)
        assertEquals(ItemKind.YOUTUBE, item(link = "https://www.youtube.com/watch?v=abcdefghijk").kind)
        assertEquals(ItemKind.ARTICLE, item().kind)
        assertEquals(ItemKind.ARTICLE, item(enclosure = "https://example.com/cover.jpg").kind)
    }

    @Test
    fun lengths() {
        assertEquals(300L, readAloudSeconds(700))
        assertEquals(150L, readAloudSeconds(700, 2f))
        assertEquals(1800L, episode().seconds(ctx))
        assertNull(episode(duration = null).seconds(ctx))
        assertEquals("48 min", formatTotal(48 * 60))
        assertEquals("2 h 05 m", formatTotal(125 * 60))
    }

    @Test
    fun rules() {
        assertTrue(item().matches(SmartRules(), ctx))
        assertTrue(item().matches(SmartRules(folders = listOf("News")), ctx))
        assertFalse(episode().matches(SmartRules(folders = listOf("News")), ctx))
        assertTrue(episode().matches(SmartRules(folders = listOf("News"), feedIds = listOf(2)), ctx))
        assertFalse(item().matches(SmartRules(kinds = listOf(ItemKind.PODCAST)), ctx))
        assertFalse(item(read = true).matches(SmartRules(statuses = listOf(PlayStatus.UNREAD)), ctx))
        assertFalse(item().matches(SmartRules(savedOnly = true), ctx))
        assertFalse(item(sortTime = now - 3 * day).matches(SmartRules(withinDays = 2), ctx))
        assertTrue(item(sortTime = now - day).matches(SmartRules(withinDays = 2), ctx))
        assertTrue(episode().matches(SmartRules(maxMinutes = 40), ctx))
        assertFalse(episode(3600).matches(SmartRules(maxMinutes = 40), ctx))
        assertFalse(episode(null).matches(SmartRules(maxMinutes = 40), ctx))
    }

    @Test
    fun inProgress() {
        val e = episode()
        val c = ctx.copy(positions = mapOf(e.id to (600L to 1800L)))
        assertEquals(PlayStatus.IN_PROGRESS, e.status(c))
        assertFalse(e.matches(SmartRules(statuses = listOf(PlayStatus.UNREAD)), c))
        assertTrue(e.matches(SmartRules(statuses = listOf(PlayStatus.UNREAD, PlayStatus.IN_PROGRESS)), c))
    }

    @Test
    fun sortsAndKeepsManualOrder() {
        val a = episode(600)
        val b = episode(3000)
        val c = episode(null)
        assertEquals(listOf(a, b, c), listOf(c, b, a).sortedFor(PlaylistSort.SHORTEST, ctx))
        assertEquals(listOf(b, a, c), listOf(c, a, b).sortedFor(PlaylistSort.LONGEST, ctx))
        val pl = newPlaylist("Mine", smart = false, existing = emptyList(), itemIds = listOf(b.id, 999L, a.id))
        assertEquals(listOf(b, a), pl.items(listOf(a, b, c), ctx))
        val more = pl.withItems(listOf(a.id, c.id, c.id))
        assertEquals(listOf(b.id, 999L, a.id, c.id), more.itemIds)
        assertEquals(listOf(c.id, b.id, 999L, a.id), more.moveItem(c.id, 0).itemIds)
        assertEquals(listOf(b.id, 999L, c.id), more.withoutItems(listOf(a.id)).itemIds)
    }

    @Test
    fun starters() {
        val s = starterPlaylists()
        assertEquals(listOf("Morning drive", "New podcast episodes", "Short reads"), s.map { it.name })
        assertEquals(3, s.map { it.color }.toSet().size)
        assertEquals(listOf("Podcast · Article", "Unread or In progress", "Last 2 days", "Under 40 min"), s[0].rules.describe { "" })
    }
}
