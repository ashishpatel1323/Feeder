package com.nononsenseapps.feeder.ui.compose.feed

import com.nononsenseapps.feeder.model.SpeechSegment
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlipReadAloudTest {
    private val title = "Big news"
    private val body = "First sentence here. Second one follows."
    private val parts = listOf(title, body)
    private val spoken = spokenTextOf(parts)

    @Test
    fun partsStartWhereReadAloudJoinsThem() {
        assertEquals(listOf(0, 10), spokenPartStarts(parts))
        assertEquals(listOf(0, null, 10), spokenPartStarts(listOf(title, " ", body)))
        assertEquals("Big news\n\nFirst sentence here. Second one follows.", spoken)
    }

    @Test
    fun titleIsHighlightedWhileItIsRead() {
        val ranges = spokenRangesOf(parts, spoken, SpeechSegment(0, 8), SpeechSegment(4, 8))
        assertEquals(SpokenRanges(0..7, 4..7), ranges[0])
        assertNull(ranges[1])
    }

    @Test
    fun bodySentenceAndWordAreInBodyPositions() {
        val second = spoken.indexOf("Second")
        val follows = spoken.indexOf("follows")
        val ranges =
            spokenRangesOf(
                parts,
                spoken,
                SpeechSegment(second, spoken.length),
                SpeechSegment(follows, follows + "follows".length),
            )
        assertNull(ranges[0])
        val bodyRanges = ranges[1]!!
        assertEquals(body.indexOf("Second")..body.lastIndex, bodyRanges.sentence)
        assertEquals("follows", body.substring(bodyRanges.word!!.first, bodyRanges.word!!.last + 1))
    }

    @Test
    fun otherTextIsNotHighlighted() {
        assertTrue(spokenRangesOf(parts, "Some other article", SpeechSegment(0, 4), null).isEmpty())
        assertTrue(spokenRangesOf(parts, spoken, null, null).isEmpty())
    }

    @Test
    fun summaryBulletsAreHighlightedOneByOne() {
        val bullets = listOf(title, "One point", "Another point")
        val text = spokenTextOf(bullets)
        val start = text.indexOf("Another")
        val ranges = spokenRangesOf(bullets, text, SpeechSegment(start, text.length), null)
        assertEquals(listOf(null, null, SpokenRanges(0..12, null)), ranges)
    }

    @Test
    fun bodySentenceIsHandedToTheArticle() {
        val second = spoken.indexOf("Second")
        assertEquals("Second one follows.", spokenBodySentence(parts, spoken, SpeechSegment(second, spoken.length)))
        // Reading the title starts the article from the top
        assertNull(spokenBodySentence(parts, spoken, SpeechSegment(0, 8)))
        assertNull(spokenBodySentence(parts, "Other text", SpeechSegment(0, 4)))
    }

    @Test
    fun ellipsisOfACutCardIsDropped() {
        val cut = listOf(title, "Start of a sentence that was cut…")
        val text = spokenTextOf(cut)
        assertEquals(
            "Start of a sentence that was cut",
            spokenBodySentence(cut, text, SpeechSegment(10, text.length)),
        )
    }

    @Test
    fun sentenceIsFoundInTheArticleIgnoringWhiteSpace() {
        val article = "Intro paragraph.\n\nFirst sentence here.   Second one\nfollows. More text."
        assertEquals(article.indexOf("Second"), speechOffsetOfSentence(article, "Second one follows."))
    }

    @Test
    fun sentenceCutOffOnTheCardIsFoundByItsStart() {
        val article = "Intro. The quick brown fox jumps over the lazy dog today. End."
        assertEquals(
            article.indexOf("The quick"),
            speechOffsetOfSentence(article, "The quick brown fox jumps over the lazy cat"),
        )
    }

    @Test
    fun missingSentenceIsNotFound() {
        assertNull(speechOffsetOfSentence("Completely different article text.", "Not in there at all, nothing here."))
        assertNull(speechOffsetOfSentence("Anything", "   "))
    }
}
