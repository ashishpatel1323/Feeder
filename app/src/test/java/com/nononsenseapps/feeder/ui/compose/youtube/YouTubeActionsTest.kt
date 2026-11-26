package com.nononsenseapps.feeder.ui.compose.youtube

import com.nononsenseapps.feeder.db.room.YouTubeFeedItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.time.Instant

/** Read above / below in the YouTube tab follow the order the list shows. */
class YouTubeActionsTest {
    private fun row(id: Long) =
        VideoRow(
            item =
                YouTubeFeedItem(
                    id = id,
                    guid = "g$id",
                    title = "Video $id",
                    link = "https://www.youtube.com/watch?v=abcdefghij$id",
                    thumbnail = null,
                    pubDate = null,
                    sortTime = Instant.EPOCH,
                    bookmarked = false,
                    readTime = null,
                    feedId = 1,
                    feedUrl = URL("https://www.youtube.com/feeds/videos.xml?channel_id=x"),
                    feedTitle = "Channel",
                    feedCustomTitle = "",
                    feedImageUrl = null,
                ),
            videoId = "abcdefghij$id",
            channel = "Channel",
            channelAvatar = null,
            duration = 60,
            views = null,
            isShort = false,
            isLive = false,
            progress = 0f,
        )

    private val list = listOf(row(5), row(3), row(9), row(1))

    @Test
    fun aboveIsEverythingListedBefore() {
        assertEquals(listOf(5L, 3L), YouTubeViewModel.videosAbove(list, 9))
        assertEquals(emptyList<Long>(), YouTubeViewModel.videosAbove(list, 5))
    }

    @Test
    fun belowIsEverythingListedAfter() {
        assertEquals(listOf(9L, 1L), YouTubeViewModel.videosBelow(list, 3))
        assertEquals(emptyList<Long>(), YouTubeViewModel.videosBelow(list, 1))
    }

    @Test
    fun notListedMarksNothing() {
        assertEquals(emptyList<Long>(), YouTubeViewModel.videosAbove(list, 42))
        assertEquals(emptyList<Long>(), YouTubeViewModel.videosBelow(list, 42))
    }
}
