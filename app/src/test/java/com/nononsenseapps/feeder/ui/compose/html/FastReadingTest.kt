package com.nononsenseapps.feeder.ui.compose.html

import org.junit.Assert.assertEquals
import org.junit.Test

class FastReadingTest {
    private fun bold(text: String): String =
        buildString {
            var last = 0
            for (range in fastReadingRanges(text)) {
                append(text, last, range.first)
                append('[').append(text, range.first, range.last + 1).append(']')
                last = range.last + 1
            }
            append(text, last, text.length)
        }

    @Test
    fun boldsWordStartsLikeTextVide() {
        assertEquals("[T]he [qui]ck [bro]wn [f]ox [jum]ps [ov]er [t]he [la]zy [d]og.", bold("The quick brown fox jumps over the lazy dog."))
    }

    @Test
    fun handlesShortWordsNumbersAndAccents() {
        assertEquals("[a] [I] [i]t", bold("a I it"))
        assertEquals("[Read]ing [inform]ation, [ca]fé [3]D 2024!", bold("Reading information, café 3D 2024!"))
    }

    @Test
    fun fixationPointsGoFromMostToLeast() {
        assertEquals(5, fixationLength(7, fixationPoint = 1))
        assertEquals(4, fixationLength(7, fixationPoint = 3))
        assertEquals(0, fixationLength(0))
    }
}
