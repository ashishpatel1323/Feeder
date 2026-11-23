package com.nononsenseapps.feeder.model.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptFormatsTest {
    @Test
    fun parsesVttWithVoicesAndTags() {
        val vtt =
            """
            WEBVTT

            NOTE made by hand

            1
            00:00:00.500 --> 00:00:02.000
            <v Alice>Hello &amp; <i>welcome</i>

            00:01:02.250 --> 00:01:05.000 align:start
            Second line
            continues here
            """.trimIndent()
        val cues = TranscriptFormats.parse(vtt, "text/vtt")
        assertEquals(2, cues.size)
        assertEquals(TranscriptCue(500, 2000, "Hello & welcome", "Alice"), cues[0])
        assertEquals(62_250L, cues[1].startMs)
        assertEquals("Second line continues here", cues[1].text)
    }

    @Test
    fun parsesSrtWithHours() {
        val srt = "1\r\n01:00:01,000 --> 01:00:03,500\r\nFirst\r\n\r\n2\r\n01:00:04,000 --> 01:00:05,000\r\nSecond\r\n"
        val cues = TranscriptFormats.parse(srt, "application/srt")
        assertEquals(listOf(3_601_000L, 3_604_000L), cues.map { it.startMs })
        assertEquals(3_603_500L, cues[0].endMs)
    }

    @Test
    fun parsesPodcastIndexJson() {
        val json = """{"version":"1.0.0","segments":[{"speaker":"Bob","startTime":1.5,"endTime":3.25,"body":"Hi there"}]}"""
        assertEquals(listOf(TranscriptCue(1500, 3250, "Hi there", "Bob")), TranscriptFormats.parse(json, "application/json"))
    }

    @Test
    fun namesGenericSpeakers() {
        val json = """{"segments":[{"speaker":"SPEAKER_00","startTime":1,"endTime":2,"body":"Hi"}]}"""
        assertEquals("Speaker 1", TranscriptFormats.parse(json, "application/json").single().speaker)
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v SPEAKER_01>Hi"
        assertEquals("Speaker 2", TranscriptFormats.parse(vtt, "text/vtt").single().speaker)
    }

    @Test
    fun joinsWordLevelJson() {
        val words = (0 until 30).joinToString(",") { """{"startTime":${it * 0.3},"endTime":${it * 0.3 + 0.25},"body":"w$it"}""" }
        val cues = TranscriptFormats.parseJson("""{"segments":[$words]}""")
        assertEquals(3, cues.size)
        assertEquals("w0 w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11", cues[0].text)
    }

    @Test
    fun sniffsTypeFromContentAndUrl() {
        assertEquals(1, TranscriptFormats.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nShort time", "text/plain").size)
        assertEquals(1, TranscriptFormats.parse("1\n00:00:01,000 --> 00:00:02,000\nx", null, "https://a/b.srt?x=1").size)
        assertEquals(0, TranscriptFormats.parse("<html><p>No timing</p></html>", "text/html").size)
    }

    @Test
    fun searchesIgnoringCaseAndAccents() {
        val cues =
            listOf(
                TranscriptCue(0, 1, "Café au lait, cafe noir"),
                TranscriptCue(1, 2, "No coffee here"),
                TranscriptCue(2, 3, "CAFE again"),
            )
        assertEquals(
            listOf(TranscriptMatch(0, 0, 4), TranscriptMatch(0, 14, 18), TranscriptMatch(2, 0, 4)),
            cues.findMatches("cafe"),
        )
        assertEquals(emptyList<TranscriptMatch>(), cues.findMatches("  "))
    }

    @Test
    fun copiesAsPlainText() {
        val text =
            listOf(
                TranscriptCue(0, 1, "Hi.", "Ann"),
                TranscriptCue(2000, 3, "Welcome.", "Ann"),
                TranscriptCue(3_725_000, 4, "Thanks.", "Bo"),
            ).toPlainText("Episode 1")
        assertEquals("Episode 1\n\nAnn:\n[0:00] Hi.\n[0:02] Welcome.\n\nBo:\n[1:02:05] Thanks.\n", text)
    }

    @Test
    fun findsCueAtPosition() {
        val cues = listOf(TranscriptCue(1000, 2000, "a"), TranscriptCue(2000, 3000, "b"), TranscriptCue(5000, 6000, "c"))
        assertEquals(-1, cues.indexAt(500))
        assertEquals(0, cues.indexAt(1999))
        assertEquals(1, cues.indexAt(2000))
        assertEquals(1, cues.indexAt(4000))
        assertEquals(2, cues.indexAt(99_000))
    }

    @Test
    fun findsTranscriptLinksInFeed() {
        val xml =
            """
            <rss xmlns:podcast="https://podcastindex.org/namespace/1.0" version="2.0"><channel>
            <item><guid isPermaLink="false">ep-1</guid><enclosure url="https://cdn/ep1.mp3" type="audio/mpeg"/>
              <podcast:transcript url="https://t/ep1.html" type="text/html"/>
              <podcast:transcript url="https://t/ep1.json" type="application/json"/>
              <podcast:transcript url="https://t/ep1.vtt" type="text/vtt" rel="captions" language="en"/>
            </item>
            <item><guid>ep-2</guid><enclosure url="https://cdn/ep2.mp3?track=1" type="audio/mpeg"/>
              <podcast:transcript url="https://t/ep2.srt" type="application/srt"/>
            </item>
            </channel></rss>
            """.trimIndent()
        val links = FeedTranscriptLinks.find(xml, "ep-1", null)
        assertEquals(3, links.size)
        assertEquals("https://t/ep1.vtt", FeedTranscriptLinks.best(links)?.url)
        val byEnclosure = FeedTranscriptLinks.find(xml, "unknown", "http://cdn/ep2.mp3")
        assertEquals("https://t/ep2.srt", FeedTranscriptLinks.best(byEnclosure)?.url)
        assertNull(FeedTranscriptLinks.best(FeedTranscriptLinks.find(xml, "nope", "https://other/x.mp3")))
    }

    @Test
    fun resamplesToSixteenKilohertz() {
        val resampler = Resampler(48_000)
        val out = FloatArray(20_000)
        var end = 0
        repeat(48_000) { end = resampler.push(0.5f, out, end) }
        // One second in, about one second out
        assertEquals(16_000.0, end.toDouble(), 2.0)
        assertEquals(0.5f, out[end - 1], 0.001f)
    }

    @Test
    fun cutsInTheQuietestPlace() {
        val samples = FloatArray(32_000) { 0.8f }
        for (i in 20_000 until 21_600) samples[i] = 0f
        val cut = Transcripts.quietestCut(samples, 16_000, 32_000)
        assert(cut in 20_000..21_600) { "cut at $cut" }
    }

    @Test
    fun parsesWhisperSegments() {
        val bytes = "0\t2500\t Hello there.\n2500\t4000\t[MUSIC]\n4000\t6000\t Café 😀\n".toByteArray()
        val cues = WhisperNative.parseSegments(bytes, 60_000)
        assertEquals(listOf(TranscriptCue(60_000, 62_500, "Hello there."), TranscriptCue(64_000, 66_000, "Café 😀")), cues)
    }
}
