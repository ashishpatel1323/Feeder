package com.nononsenseapps.feeder.ui.compose.home

import com.nononsenseapps.feeder.db.room.HomeFeedRow
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLogicTest {
    private fun row(
        url: String,
        total: Int = 10,
        audio: Int = 0,
        video: Int = 0,
    ) = HomeFeedRow(1, "t", url, "", null, 0, 0, total, audio, video)

    @Test
    fun detectsKinds() {
        assertEquals(FeedKind.YOUTUBE, detectKind(row("https://www.youtube.com/feeds/videos.xml?channel_id=x", total = 0)))
        assertEquals(FeedKind.YOUTUBE, detectKind(row("https://example.com/rss", video = 8)))
        assertEquals(FeedKind.PODCAST, detectKind(row("https://example.com/pod", audio = 9)))
        assertEquals(FeedKind.TEXT, detectKind(row("https://example.com/blog", audio = 1)))
        assertEquals(FeedKind.TEXT, detectKind(row("https://example.com/empty", total = 0)))
    }

    private fun feed(
        id: Long,
        title: String,
        tag: String,
        unread: Int,
        latest: Long,
        kind: FeedKind = FeedKind.TEXT,
    ) = HomeFeed(id, title, tag, null, kind, unread, latest)

    private val feeds =
        listOf(
            feed(1, "Alpha", "Tech", 1, 10),
            feed(2, "Bravo", "Tech", 0, 30),
            feed(3, "Charlie", "", 2, 21, FeedKind.PODCAST),
            feed(4, "Delta", "Fun", 0, 0, FeedKind.YOUTUBE),
        )

    @Test
    fun filtersAndSorts() {
        assertEquals(listOf("Alpha", "Bravo"), feeds.filterHome(HomeFilter(kind = FeedKind.TEXT)).map { it.title })
        assertEquals(listOf("Charlie"), feeds.filterHome(HomeFilter(folder = "")).map { it.title })
        assertEquals(listOf("Alpha", "Charlie"), feeds.filterHome(HomeFilter(unreadOnly = true)).map { it.title })
        assertEquals(listOf("Delta"), feeds.filterHome(HomeFilter(kind = FeedKind.TEXT, folder = "Fun"), skipKind = true).map { it.title })
        assertEquals(listOf("Bravo", "Charlie", "Alpha", "Delta"), feeds.sortHome(HomeSort.LATEST).map { it.title })
        assertEquals("Charlie", feeds.sortHome(HomeSort.UNREAD).first().title)
    }

    @Test
    fun groupsFoldersThenLooseFeeds() {
        val (folders, loose) = feeds.groupByFolder(HomeSort.AZ)
        assertEquals(listOf("Fun" to 1, "Tech" to 2), folders.map { it.name to it.feeds.size })
        assertEquals(1, folders[1].unread)
        assertEquals(listOf("Charlie"), loose.map { it.title })
    }

    @Test
    fun initialsAndColors() {
        assertEquals("HN", initials("Hacker News"))
        assertEquals("VE", initials("The Verge"))
        assertEquals("PG", initials("Paul Graham: Essays"))
        assertEquals(tileColors("Hacker News"), tileColors("Hacker News"))
    }

    @Test
    fun shortAges() {
        val now = 1_000_000_000_000L
        assertEquals("", shortAge(0L, now))
        assertEquals("now", shortAge(now - 10_000L, now))
        assertEquals("12m", shortAge(now - 12 * 60_000L, now))
        assertEquals("3h", shortAge(now - 3 * 3_600_000L, now))
        assertEquals("2d", shortAge(now - 2 * 86_400_000L, now))
        assertEquals("2w", shortAge(now - 15 * 86_400_000L, now))
    }
}
