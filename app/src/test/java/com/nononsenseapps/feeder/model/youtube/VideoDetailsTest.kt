package com.nononsenseapps.feeder.model.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailsTest {
    private val sample =
        """
        {
          "id": "abcdefghijk", "title": "A talk", "channel": "Lenny's Podcast",
          "channel_follower_count": 654000, "view_count": 66123, "like_count": 956,
          "upload_date": "20260801", "description": "About things", "duration": 5630.0,
          "language": "en",
          "thumbnail": "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg",
          "chapters": [
            {"start_time": 0.0, "end_time": 120.0, "title": "Intro"},
            {"start_time": 120.0, "end_time": 5630.0, "title": "Big milestones"}
          ],
          "formats": [
            {"format_id": "sb0", "protocol": "mhtml", "url": "https://x/sb", "vcodec": "none", "acodec": "none", "ext": "mhtml"},
            {"format_id": "139", "protocol": "https", "url": "https://x/139",
             "vcodec": "none", "acodec": "mp4a.40.5", "ext": "m4a", "tbr": 48, "language": "en", "format_note": "English original (default), low"},
            {"format_id": "140", "protocol": "https", "url": "https://x/140",
             "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a", "tbr": 129, "language": "en", "format_note": "English original (default), medium",
             "http_headers": {"User-Agent": "UA/1"}},
            {"format_id": "140-es", "protocol": "https", "url": "https://x/140es",
             "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a", "tbr": 129, "language": "es", "format_note": "Spanish, medium"},
            {"format_id": "251", "protocol": "https", "url": "https://x/251", "vcodec": "none", "acodec": "opus", "ext": "webm", "tbr": 140, "language": "en"},
            {"format_id": "18", "protocol": "https", "url": "https://x/18", "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "ext": "mp4", "height": 360},
            {"format_id": "134", "protocol": "https", "url": "https://x/134", "vcodec": "avc1.4d401e", "acodec": "none", "ext": "mp4", "height": 360, "tbr": 300},
            {"format_id": "396", "protocol": "https", "url": "https://x/396", "vcodec": "av01.0.01M.08", "acodec": "none", "ext": "mp4", "height": 360, "tbr": 250},
            {"format_id": "136", "protocol": "https", "url": "https://x/136", "vcodec": "avc1.4d401f", "acodec": "none", "ext": "mp4", "height": 720, "tbr": 1200},
            {"format_id": "137", "protocol": "https", "url": "https://x/137", "vcodec": "avc1.640028", "acodec": "none", "ext": "mp4", "height": 1080, "tbr": 2500},
            {"format_id": "401", "protocol": "https", "url": "https://x/401", "vcodec": "av01.0.12M.08", "acodec": "none", "ext": "mp4", "height": 2160, "tbr": 9000},
            {"format_id": "hls-720", "protocol": "m3u8_native", "url": "https://x/hls", "vcodec": "avc1", "acodec": "mp4a", "ext": "mp4", "height": 720}
          ],
          "subtitles": {
            "en": [{"ext": "json3", "url": "https://x/en.json3", "name": "English"}, {"ext": "vtt", "url": "https://x/en.vtt", "name": "English"}],
            "live_chat": [{"ext": "json", "url": "https://x/chat"}]
          },
          "automatic_captions": {
            "en-orig": [{"ext": "vtt", "url": "https://x/en-orig.vtt", "name": "English (Original)"}],
            "de-orig": [{"ext": "vtt", "url": "https://x/de-orig.vtt", "name": "German (Original)"}],
            "fr": [{"ext": "vtt", "url": "https://x/fr.vtt", "name": "French"}]
          }
        }
        """.trimIndent()

    @Test
    fun parsesInfo() {
        val d = VideoDetails.parse(sample)
        assertEquals("abcdefghijk", d.id)
        assertEquals("Lenny's Podcast", d.channel)
        assertEquals(654000L, d.channelFollowers)
        assertEquals(66123L, d.viewCount)
        assertEquals(956L, d.likeCount)
        assertEquals("20260801", d.uploadDate)
        assertEquals(5630.0, d.duration, 0.0)
        assertEquals(listOf("Intro", "Big milestones"), d.chapters.map { it.title })
        assertEquals("Big milestones", d.chapterAt(300.0)?.title)
        assertEquals("Intro", d.chapterAt(0.0)?.title)
        assertEquals("UA/1", d.userAgent)
    }

    @Test
    fun picksOneDirectMp4VideoPerHeightUpTo1080PreferringH264() {
        val d = VideoDetails.parse(sample)
        assertEquals(listOf(1080, 720, 360), d.videos.map { it.height })
        // H.264 over AV1 at 360p, even with a lower bitrate
        assertEquals("134", d.videos.last().formatId)
        assertEquals(720, d.videoFor(720)?.height)
        assertEquals(720, d.videoFor(900)?.height)
        assertEquals(360, d.videoFor(144)?.height)
        assertEquals("https://x/18", d.muxedUrl)
        assertTrue(d.hasStreams)
    }

    @Test
    fun picksTheBestM4aPerLanguageOriginalFirst() {
        val d = VideoDetails.parse(sample)
        assertEquals(listOf("140", "140-es"), d.audios.map { it.formatId })
        assertTrue(d.audios.first().isOriginal)
        assertEquals("English original", d.audios.first().label)
        assertFalse(d.audios[1].isOriginal)
    }

    @Test
    fun keepsManualSubtitlesAndOnlyTheVideosOwnAutomaticOnes() {
        val d = VideoDetails.parse(sample)
        // English manual wins over English automatic; German original automatic stays; French translation goes
        assertEquals(listOf("en", "de-orig"), d.subtitles.map { it.language })
        assertEquals("https://x/en.vtt", d.subtitles[0].url)
        assertFalse(d.subtitles[0].automatic)
        assertTrue(d.subtitles[1].automatic)
    }

    @Test
    fun withoutFormatsThereAreNoStreams() {
        val d = VideoDetails.parse("""{"id":"x","title":"t"}""")
        assertFalse(d.hasStreams)
        assertNull(d.videoFor(720))
    }

    @Test
    fun compactCounts() {
        assertEquals("956", VideoDetails.compactCount(956))
        assertEquals("66.1K", VideoDetails.compactCount(66123))
        assertEquals("654K", VideoDetails.compactCount(654000))
        assertEquals("1.2M", VideoDetails.compactCount(1_200_000))
        assertEquals("3B", VideoDetails.compactCount(3_000_000_000))
    }

    @Test
    fun sponsorBlockKeepsThisVideosSkipSegments() {
        val text =
            """
            [
              {"videoID": "other", "segments": [{"segment": [1, 2], "category": "sponsor", "UUID": "o", "actionType": "skip"}]},
              {"videoID": "abcdefghijk", "segments": [
                {"segment": [300.5, 360.0], "category": "sponsor", "UUID": "b", "actionType": "skip"},
                {"segment": [10.0, 20.0], "category": "selfpromo", "UUID": "a", "actionType": "skip"},
                {"segment": [50.0, 50.0], "category": "sponsor", "UUID": "c", "actionType": "skip"},
                {"segment": [70.0, 80.0], "category": "sponsor", "UUID": "d", "actionType": "mute"}
              ]}
            ]
            """.trimIndent()
        val segments = SponsorBlock.parse(text, "abcdefghijk")
        assertEquals(listOf("a", "b"), segments.map { it.uuid })
        assertEquals(300.5, segments[1].start, 0.0)
        assertEquals(emptyList<SkipSegment>(), SponsorBlock.parse("[]", "x"))
    }
}
