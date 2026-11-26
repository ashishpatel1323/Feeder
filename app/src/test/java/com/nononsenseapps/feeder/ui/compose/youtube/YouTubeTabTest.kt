package com.nononsenseapps.feeder.ui.compose.youtube

import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.sync2.Sync2Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class YouTubeTabTest {
    @Test
    fun readsYouTubeTakeoutSubscriptions() {
        val csv =
            listOf(
                "Channel Id,Channel Url,Channel Title",
                "UCBJycsmduvYEL83R_U4JriQ,http://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ,Marques Brownlee",
                "UCuAXFkgsw1L7xaCfnd5JJOw,http://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw,\"Rick Astley, \"\"official\"\"\"",
                "",
                "UCBJycsmduvYEL83R_U4JriQ,http://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ,Marques Brownlee",
            ).joinToString("\n")
        val rows = YouTubeViewModel.parseTakeoutCsv(csv)
        assertEquals(
            listOf(
                "UCBJycsmduvYEL83R_U4JriQ" to "Marques Brownlee",
                "UCuAXFkgsw1L7xaCfnd5JJOw" to "Rick Astley, \"official\"",
            ),
            rows,
        )
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCBJycsmduvYEL83R_U4JriQ",
            YouTubeViewModel.channelFeedUrl(rows.first().first).toString(),
        )
    }

    @Test
    fun theMusicFlagTravelsWithTheFeedOnlyWhenOn() {
        val feed = Feed(url = URL("https://www.youtube.com/feeds/videos.xml?channel_id=UCuAXFkgsw1L7xaCfnd5JJOw"), title = "Rick")
        // Feeds that aren't music look exactly as before, so nothing is sent again after updating
        assertFalse(Sync2Client.feedValue(feed).containsKey("music"))
        val music = feed.copy(youtubeMusic = true)
        assertEquals(true, Sync2Client.feedValue(music)["music"])
        assertTrue(Sync2Client.applyFeedValue(Sync2Client.feedValue(music), feed, 1L).youtubeMusic)
        assertFalse(Sync2Client.applyFeedValue(Sync2Client.feedValue(feed), music, 1L).youtubeMusic)
    }
}
