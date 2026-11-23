package com.nononsenseapps.feeder.ui.compose.discover

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverSourcesTest {
    @Test
    fun readsFeedly() {
        val r =
            parseFeedly(
                """{"results":[{"feedId":"feed/http://www.livemint.com/rss/opinion","title":"mint - opinion","subscribers":9483},""" +
                    """{"feedId":"user/x/category/y"},{"id":"feed/https://www.youtube.com/feeds/videos.xml?channel_id=UCHnyfMqiRRG1u-2MsSQLbXA","title":"V"}]}""",
            )
        assertEquals(2, r.size)
        assertEquals("http://www.livemint.com/rss/opinion", r[0].feedUrl)
        assertEquals("9.5K Feedly followers", r[0].meta)
        assertEquals(DiscoverKind.YOUTUBE, r[1].kind)
        assertEquals(listOf("mint india", "mintindia", "india", "mint"), feedlyQueries("Mint India"))
    }

    @Test
    fun readsPodcasts() {
        val r = parseItunes("""{"results":[{"collectionId":1,"collectionName":"Lex","artistName":"Lex F","feedUrl":"https://lex/feed","trackCount":5},{"collectionName":"no feed"}]}""")
        assertEquals(1, r.size)
        assertEquals("Lex F · 5 episodes", r[0].meta)
        assertEquals(listOf("1", "2"), parseChartIds("""{"feed":{"entry":[{"id":{"attributes":{"im:id":"1"}}},{"id":{"attributes":{"im:id":"2"}}}]}}"""))
        assertEquals(listOf("7"), parseChartIds("""{"feed":{"entry":{"id":{"attributes":{"im:id":"7"}}}}}"""))
    }

    @Test
    fun readsYouTubeChannels() {
        val data =
            """{"a":[{"channelRenderer":{"channelId":"UCHnyfMqiRRG1u-2MsSQLbXA","title":{"simpleText":"Veritasium"},""" +
                """"thumbnail":{"thumbnails":[{"url":"//yt3/small"},{"url":"//yt3/big"}]},"descriptionSnippet":{"runs":[{"text":"An element of "},{"text":"truth"}]},""" +
                """"videoCountText":{"simpleText":"21.5M subscribers"},"subscriberCountText":{"simpleText":"@veritasium"}}},{"videoRenderer":{}}]}"""
        val r = parseYouTubeChannels("<script>var ytInitialData = $data;</script>")
        assertEquals(1, r.size)
        assertEquals(youtubeFeedUrl("UCHnyfMqiRRG1u-2MsSQLbXA"), r[0].feedUrl)
        assertEquals("https://yt3/big", r[0].image)
        assertEquals("@veritasium · 21.5M subscribers", r[0].meta)
        assertEquals("https://www.youtube.com/@veritasium", r[0].site)
        assertEquals("An element of truth", r[0].description)
        assertEquals(emptyList(), parseYouTubeChannels("<html></html>"))
    }

    @Test
    fun helpers() {
        assertTrue(looksLikeUrl("livemint.com"))
        assertTrue(!looksLikeUrl("mint india"))
        assertEquals("22M", compactNumber(21_500_000))
        assertEquals("1.2M", compactNumber(1_200_000))
        assertEquals("in", defaultCountry(Locale.US, "Asia/Kolkata"))
        assertEquals("us", defaultCountry(Locale.FRANCE, "Europe/Paris"))
        assertEquals(
            "https://news.google.com/rss/search?q=mint%20india&hl=en-IN&gl=IN&ceid=IN:en",
            googleNewsResult("mint india", Locale("en", "IN")).feedUrl,
        )
        val followed = listOf("https://www.youtube.com/feeds/videos.xml?channel_id=UCHnyfMqiRRG1u-2MsSQLbXA", "https://www.livemint.com/rss/news")
        assertTrue(isFollowed(DiscoverResult(DiscoverKind.YOUTUBE, "", youtubeFeedUrl("UCHnyfMqiRRG1u-2MsSQLbXA") + "&x"), followed))
        assertTrue(isFollowed(DiscoverResult(DiscoverKind.TEXT, "", "http://livemint.com/rss/news/"), followed))
    }

    @Test
    fun bundledTopicsHaveIndiaBusiness() {
        val file = listOf("src/main/assets/discover-feeds.json", "app/src/main/assets/discover-feeds.json").map(::File).first { it.exists() }
        val topics = parseCurated(file.readText())
        val business = topics.first { it.name == "India: Business" }
        assertTrue(business.feeds.any { it.feedUrl == "https://www.livemint.com/rss/markets" })
        assertTrue(searchCurated(topics, "mint money").any { it.feedUrl == "https://www.livemint.com/rss/money" })
    }
}
