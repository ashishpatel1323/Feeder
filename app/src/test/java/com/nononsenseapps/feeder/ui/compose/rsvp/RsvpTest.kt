package com.nononsenseapps.feeder.ui.compose.rsvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsvpTest {
    @Test
    fun splitsWordsAndMarksParagraphEnds() {
        val words = rsvpWords("Hello there, world.\n\nNew para — here")
        assertEquals(listOf("Hello", "there,", "world.", "New", "para", "here"), words.map { it.text })
        assertEquals(listOf(false, false, true, false, false, true), words.map { it.paragraphEnd })
        assertEquals("Hello there, world.\n\n".length, words[3].start)
    }

    @Test
    fun splitsVeryLongWords() {
        val long = "Pneumonoultramicroscopicsilicovolcanoconiosis"
        val words = rsvpWords(long)
        assertTrue(words.size > 2)
        assertTrue(words.dropLast(1).all { it.text.endsWith("-") })
        assertEquals(long, words.joinToString("") { it.text.removeSuffix("-") })
        assertEquals(words[0].text.length - 1, words[1].start)
    }

    @Test
    fun breaksCompoundsAtTheirHyphens() {
        assertEquals(
            listOf("state-of-the-", "art-machine-", "learning"),
            rsvpWords("state-of-the-art-machine-learning").map { it.text },
        )
    }

    @Test
    fun focusLetterIsLeftOfCentre() {
        assertEquals(0, focusIndex("a"))
        assertEquals(1, focusIndex("word"))
        assertEquals(2, focusIndex("reading"))
        assertEquals(3, focusIndex("presentation"))
        assertEquals(2, focusIndex("\"Hello"))
    }

    @Test
    fun pausesLongerAtPunctuation() {
        val (plain, comma, stop) = rsvpWords("word word, word.")
        assertEquals(200L, plain.delayMillis(300))
        assertTrue(comma.delayMillis(300) > 200L)
        assertTrue(stop.delayMillis(300) > comma.delayMillis(300))
    }

    @Test
    fun rewindsBySentence() {
        val words = rsvpWords("One two three. Four five six seven. Eight nine.")
        assertEquals(3, words.sentenceStartBefore(7))
        assertEquals(0, words.sentenceStartBefore(4))
        assertEquals(0, words.sentenceStartBefore(0))
        assertEquals(6, words.sentenceEndFrom(4))
        assertEquals(3, words.wordIndexAt("One two three. Fo".length))
    }
}
