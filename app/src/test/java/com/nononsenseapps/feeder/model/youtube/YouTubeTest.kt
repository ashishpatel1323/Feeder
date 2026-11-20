package com.nononsenseapps.feeder.model.youtube

import com.nononsenseapps.feeder.sync2.Sync2Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTest {
    @Test
    fun findsTheVideoIdLikeTheWebApp() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, youtubeVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, youtubeVideoId("https://m.youtube.com/watch?feature=share&v=$id&t=4"))
        assertEquals(id, youtubeVideoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/live/$id"))
        assertEquals(id, youtubeVideoId("https://www.youtube.com/embed/$id?feature=oembed"))
        assertEquals(id, youtubeVideoId("https://www.youtube-nocookie.com/embed/$id"))
        assertNull(youtubeVideoId("https://www.youtube.com/channel/UC123"))
        assertNull(youtubeVideoId("https://example.com/watch?v=$id"))
        assertNull(youtubeVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(youtubeVideoId(null))
        assertNull(youtubeVideoId("not a url"))
    }

    @Test
    fun watchUrlCarriesTheStart() {
        assertEquals("https://www.youtube.com/watch?v=abc", youtubeWatchUrl("abc"))
        assertEquals("https://www.youtube.com/watch?v=abc&t=90s", youtubeWatchUrl("abc", 90))
    }

    @Test
    fun resumesAFewSecondsBackUnlessWatched() {
        assertEquals(0, VideoPositions.resumeAt(null))
        assertEquals(0, VideoPositions.resumeAt(VideoPos(t = 4, d = 600, ts = 1)))
        assertEquals(97, VideoPositions.resumeAt(VideoPos(t = 100, d = 600, ts = 1)))
        assertEquals(0, VideoPositions.resumeAt(VideoPos(t = 580, d = 600, ts = 1)))
        assertTrue(VideoPositions.isWatched(VideoPos(t = 570, d = 600, ts = 1)))
        assertFalse(VideoPositions.isWatched(VideoPos(t = 569, d = 600, ts = 1)))
        assertFalse(VideoPositions.isWatched(VideoPos(t = 900, d = 0, ts = 1)))
    }

    @Test
    fun savesOnlyRealChanges() {
        assertNull(VideoPositions.nextPos(null, 3.0, 600.0, 10))
        assertEquals(VideoPos(12, 600, 10), VideoPositions.nextPos(null, 12.4, 600.2, 10))
        val old = VideoPos(12, 600, 10)
        assertNull(VideoPositions.nextPos(old, 12.2, 600.0, 20))
        assertNull(VideoPositions.nextPos(old, 12.0, 0.0, 20))
        // Keeps the known length, and the time always moves forward
        assertEquals(VideoPos(40, 600, 11), VideoPositions.nextPos(old, 40.0, 0.0, 5))
    }

    @Test
    fun labelsLikeTheWebApp() {
        assertEquals("4:12 / 14:19", VideoPositions.positionLabel(VideoPos(252, 859, 1), "Watched"))
        assertEquals("1:02:05", VideoPositions.positionLabel(VideoPos(3725, 0, 1), "Watched"))
        assertEquals("Watched", VideoPositions.positionLabel(VideoPos(850, 859, 1), "Watched"))
    }

    @Test
    fun storedFormatRoundTrips() {
        val p = VideoPos(252, 859, 1_700_000_000_000)
        assertEquals(p, VideoPositions.decode(VideoPositions.encode(p)))
        assertNull(VideoPositions.decode("garbage"))
    }

    @Test
    fun articleKeyMatchesSync() {
        assertEquals("https://a/feed\nguid", VideoPositions.articleKey("https://a/feed", "guid"))
        assertEquals("", VideoPositions.articleKey(null, "guid"))
    }

    @Test
    fun syncedVideoValuesCompareAsNumbers() {
        // Moshi reads JSON numbers as doubles
        assertTrue(Sync2Client.sameVideoValue(mapOf("t" to 12.0, "d" to 600.0), 12, 600))
        assertFalse(Sync2Client.sameVideoValue(mapOf("t" to 13.0, "d" to 600.0), 12, 600))
        assertTrue(Sync2Client.sameVideoValue(mapOf("t" to 12.0), 12, 0))
        assertFalse(Sync2Client.sameVideoValue(null, 12, 0))
    }
}
