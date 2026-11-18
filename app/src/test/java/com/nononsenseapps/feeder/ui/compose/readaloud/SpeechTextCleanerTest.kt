package com.nononsenseapps.feeder.ui.compose.readaloud

import com.nononsenseapps.feeder.model.SpeechSegment
import com.nononsenseapps.feeder.model.html.LinearArticle
import com.nononsenseapps.feeder.model.html.LinearListItem
import com.nononsenseapps.feeder.model.html.LinearTable
import com.nononsenseapps.feeder.model.html.LinearTableCellItem
import com.nononsenseapps.feeder.model.html.LinearTableCellItemType
import com.nononsenseapps.feeder.model.html.LinearText
import com.nononsenseapps.feeder.model.html.LinearTextAnnotation
import com.nononsenseapps.feeder.model.html.LinearTextAnnotationLink
import com.nononsenseapps.feeder.model.html.LinearTextAnnotationSuperscript
import com.nononsenseapps.feeder.model.html.LinearTextBlockStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextCleanerTest {
    private fun text(
        value: String,
        vararg annotations: LinearTextAnnotation,
    ) = LinearText(emptySet(), value, annotations.toList(), LinearTextBlockStyle.TEXT)

    private fun clean(value: String) = spokenTextOf(text(value)).text

    private fun LinearText.sup(word: String): LinearTextAnnotation {
        val start = text.indexOf(word)
        return LinearTextAnnotation(LinearTextAnnotationSuperscript, start, start + word.length - 1)
    }

    @Test fun referenceNumbersDropped() {
        assertEquals("The sky is blue. Water is wet.", clean("The sky is blue.[1] Water is wet[12][13]."))
        assertEquals("Known since 1900 and  later.", clean("Known since 1900[2, 3] and [citation needed] later[a]."))
    }

    @Test fun urlsDroppedButSentencePunctuationKept() {
        assertEquals("Read more at .", clean("Read more at https://example.com/a?b=1."))
        assertEquals("The report says so.", clean("The report (https://example.com/report) says so."))
        assertEquals("Wiki  page", clean("Wiki https://en.wikipedia.org/wiki/Foo_(bar) page"))
    }

    @Test fun parentheticalCitationsDropped() {
        assertEquals("Sleep matters. Really.", clean("Sleep matters (Walker, 2017). Really."))
        assertEquals("As shown.", clean("As shown (Smith & Jones, 2019; Doe et al., 2020a)."))
        assertEquals("Earlier work agrees.", clean("Earlier work (see Müller, 2001, p. 12) agrees."))
    }

    @Test fun otherParenthesesKept() {
        listOf("It launched (March, 2021) quietly.", "The film (2019) was fine.", "Windows (Windows 2000) ran it.", "A (big) deal.")
            .forEach { assertEquals(it, clean(it)) }
    }

    @Test fun footnoteSuperscriptsDroppedButExponentsAndOrdinalsKept() {
        val base = text("Cats sleep1 a lot. 106 times. The 1st time.")
        val withSups =
            base.copy(
                annotations =
                    listOf(
                        LinearTextAnnotation(LinearTextAnnotationSuperscript, 10, 10),
                        LinearTextAnnotation(LinearTextAnnotationSuperscript, 21, 21),
                        base.sup("st"),
                    ),
            )
        assertEquals("Cats sleep a lot. 106 times. The 1st time.", spokenTextOf(withSups).text)
    }

    @Test fun footnoteLinksDropped() {
        val base = text("Cats sleep 1 a lot.")
        val linked = base.copy(annotations = listOf(LinearTextAnnotation(LinearTextAnnotationLink("https://x.org/a#fn1"), 11, 11)))
        assertEquals("Cats sleep  a lot.", spokenTextOf(linked).text)
    }

    @Test fun creditAndCopyrightLinesSkipped() {
        assertTrue(isSkippedLine("© 2026 Example Media. All rights reserved."))
        assertTrue(isSkippedLine("Photo: Jane Doe / Getty Images"))
        assertTrue(isSkippedLine("Image credit: NASA"))
        assertFalse(isSkippedLine("The photo shows a cat sitting on a wall in the sun."))
        assertEquals("", clean("Photo: Jane Doe / Getty Images"))
    }

    @Test fun tablesAndFootnoteSectionsNotRead() {
        val intro = text("Intro.[1]")
        val cell = text("Cell")
        val table =
            LinearTable(
                ids = emptySet(),
                rowCount = 1,
                colCount = 1,
                cells = listOf(LinearTableCellItem(LinearTableCellItemType.DATA, 1, 1, listOf(cell))),
                leftToRight = true,
            )
        val note = LinearListItem(setOf("fn1"), null, listOf(text("The footnote.")))
        val article = LinearArticle(listOf(intro, table, note))
        assertEquals("Intro.", article.speechText())
    }

    @Test fun highlightsAndTapsStayOnTheWordsShown() {
        val paragraph = text("They are cuter[2] (Smith, 2020). Next sentence.")
        val article = LinearArticle(listOf(paragraph))
        val source = article.speechText()
        assertEquals("They are cuter. Next sentence.", source)
        val next = source.indexOf("Next")
        val highlight = SpeechHighlightIndex(article, source).highlights(SpeechSegment(next, source.length)).getValue(paragraph)
        assertEquals("Next sentence.", paragraph.text.substring(highlight))
        assertEquals(next, article.speechOffsetOf(paragraph, paragraph.text.indexOf("Next")))
        // A tap on skipped clutter lands on what is read next
        assertEquals('.', source[checkNotNull(article.speechOffsetOf(paragraph, paragraph.text.indexOf("Smith")))])
    }
}
