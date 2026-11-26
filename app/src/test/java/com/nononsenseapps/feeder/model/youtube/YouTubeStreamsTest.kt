package com.nononsenseapps.feeder.model.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real visionOS answer from /youtubei/v1/player (Nov 2026, dQw4w9WgXcQ; description replaced). */
class YouTubeStreamsTest {
    private val visionOs = YouTubeStreams.CLIENTS.first()

    private fun load(name: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("innertube/$name"))
        return Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    @Test
    fun readsH264StreamsSoundAndInfo() {
        val d = checkNotNull(YouTubeStreams.parse("dQw4w9WgXcQ", load("player_visionos.json"), visionOs))
        assertEquals("VISIONOS", d.source)
        assertTrue(d.fromInnerTube)
        assertTrue(d.hasStreams)
        assertEquals("Rick Astley", d.channel)
        assertEquals(213.0, d.duration, 0.0)
        assertTrue((d.viewCount ?: 0) > 1_000_000_000)
        // H.264 only, up to 1080p, highest first
        assertEquals(listOf(1080, 720, 480, 360, 240, 144), d.videos.map { it.height })
        assertEquals("136", d.videoFor(720)?.formatId)
        // The best AAC sound
        assertEquals("140", d.audios.single().formatId)
        assertEquals(visionOs.userAgent, d.userAgent)
    }

    @Test
    fun readsCaptionsAsVtt() {
        val d = checkNotNull(YouTubeStreams.parse("dQw4w9WgXcQ", load("player_visionos.json"), visionOs))
        val manual = d.subtitles.first { it.language == "en" }
        assertTrue(manual.url.endsWith("&fmt=vtt"))
        val auto = d.subtitles.single { it.automatic }
        assertEquals("en-orig", auto.language)
    }

    @Test
    fun chaptersComeFromTheDescription() {
        val d = checkNotNull(YouTubeStreams.parse("dQw4w9WgXcQ", load("player_visionos.json"), visionOs))
        assertEquals(listOf("Start", "Verse one", "Chorus", "Outro"), d.chapters.map { it.title })
        assertEquals(45.0, d.chapters[1].start, 0.0)
        assertEquals(213.0, d.chapters.last().end, 0.0)
    }

    @Test
    fun noChaptersUnlessTheyStartAtZero() {
        assertTrue(YouTubeStreams.chaptersFromDescription("1:00 a\n2:00 b\n3:00 c", 400.0).isEmpty())
        assertTrue(YouTubeStreams.chaptersFromDescription("0:00 a\n2:00 b", 400.0).isEmpty())
        assertEquals(3, YouTubeStreams.chaptersFromDescription("(0:00) a\n1:02:03 - b\n1:10:00 c", 5000.0).size)
    }

    @Test
    fun notPlayableGivesNothing() {
        val o = Json.parseToJsonElement("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}""").jsonObject
        assertNull(YouTubeStreams.parse("x", o, visionOs))
        assertFalse(YouTubeStreams.isBotWall(o))
        val wall =
            Json
                .parseToJsonElement(
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""",
                ).jsonObject
        assertTrue(YouTubeStreams.isBotWall(wall))
    }

    @Test
    fun keptUntilHalfAnHourBeforeExpiry() {
        val o = load("player_visionos.json")
        val expire = 1795677268_000L
        assertEquals(expire - 30 * 60 * 1000L, YouTubeStreams.keepUntil(o, now = expire - 6 * 60 * 60 * 1000L))
    }

    @Test
    fun webExtrasGiveLikesAndUploadDate() {
        val o =
            Json
                .parseToJsonElement(
                    """{"microformat":{"playerMicroformatRenderer":{"likeCount":"19519163","publishDate":"2009-10-24T23:57:33-07:00"}}}""",
                ).jsonObject
        val extras = checkNotNull(YouTubeStreams.parseWebExtras(o))
        assertEquals(19519163L, extras.likes)
        assertEquals("20091024", extras.uploadDate)
    }
}
