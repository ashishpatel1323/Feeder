package com.nononsenseapps.feeder.model.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSectionsTest {
    private fun cue(
        sec: Int,
        text: String = "line at $sec",
    ) = TranscriptCue(sec * 1000L, sec * 1000L + 4000, text)

    @Test
    fun linesGoUnderTheirChapter() {
        val cues = listOf(cue(0), cue(5), cue(62), cue(70), cue(130))
        val sections =
            transcriptSections(
                cues,
                listOf(TranscriptChapter(60_000, "Two"), TranscriptChapter(0, "One"), TranscriptChapter(120_000, "Three")),
            )
        assertEquals(listOf("One", "Two", "Three"), sections.map { it.title })
        assertEquals(listOf(0 until 2, 2 until 4, 4 until 5), sections.map { it.firstCue until it.endCue })
        assertEquals(1, sections.indexOfCue(3))
        assertEquals(2, sections.indexAtTime(125_000))
    }

    @Test
    fun linesBeforeTheFirstChapterBelongToIt() {
        val sections = transcriptSections(listOf(cue(1), cue(20)), listOf(TranscriptChapter(10_000, "Late start")))
        assertEquals(0, sections.single().firstCue)
        assertEquals(2, sections.single().endCue)
    }

    @Test
    fun chapterWithoutLinesStaysAsAHeading() {
        val sections = transcriptSections(listOf(cue(0), cue(200)), listOf(TranscriptChapter(0, "A"), TranscriptChapter(60_000, "B"), TranscriptChapter(180_000, "C")))
        assertTrue(sections[1].isEmpty)
        assertEquals(3, sections.size)
    }

    @Test
    fun shortVideoWithoutChaptersHasNoHeadings() {
        assertTrue(transcriptSections(listOf(cue(0), cue(200)), emptyList()).isEmpty())
    }

    @Test
    fun longVideoWithoutChaptersIsSplitIntoFiveMinuteBlocks() {
        val cues = listOf(cue(0, "Welcome back to the channel, today we look at telescopes"), cue(100), cue(310, "Next part"), cue(900, "Much later"))
        val sections = transcriptSections(cues, emptyList())
        assertEquals(listOf(0L, 300_000L, 900_000L), sections.map { it.startMs })
        assertEquals("Welcome back to the channel, today we…", sections[0].title)
        assertEquals("Next part", sections[1].title)
        assertEquals(listOf(0 until 2, 2 until 3, 3 until 4), sections.map { it.firstCue until it.endCue })
    }
}
