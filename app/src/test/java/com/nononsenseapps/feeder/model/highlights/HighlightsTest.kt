package com.nononsenseapps.feeder.model.highlights

import com.nononsenseapps.feeder.sync2.Sync2Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsTest {
    private val paragraphs =
        listOf(
            "The quick brown fox jumps over the lazy dog.",
            "A second paragraph, where the fox sleeps.",
            "The end of the fox.",
        )
    private val locator = HighlightLocator(paragraphs)

    @Test
    fun findsASelectionInOneParagraph() {
        val anchor = locator.anchorOf("  brown fox jumps ")!!
        assertEquals("brown fox jumps", anchor.text)
        assertEquals(10, anchor.start)
        assertEquals("The quick ", anchor.prefix)
        assertEquals(mapOf(0 to 10..24), locator.ranges(anchor.text, anchor.prefix, anchor.start))
    }

    @Test
    fun findsASelectionAcrossParagraphsWhateverJoinsThem() {
        // Compose joins selected paragraphs with or without a line break; the web app's text has none
        for (selected in listOf("lazy dog.\nA second", "lazy dog.A second", "lazy dog. A second")) {
            val r = locator.ranges(selected, "", 0)
            assertEquals(selected, mapOf(0 to 35..43, 1 to 0..7), r)
        }
    }

    @Test
    fun picksTheCopyWhosePrefixMatches() {
        // "fox" is in every paragraph; the prefix says which one
        assertEquals(mapOf(1 to 30..32), locator.ranges("fox", "paragraph, where the ", 0))
        assertEquals(mapOf(2 to 15..17), locator.ranges("fox", "The end of the ", 0))
        // Without a prefix, the one nearest the saved start
        val third = locator.full.indexOf("fox.", 60)
        assertEquals(mapOf(2 to 15..17), locator.ranges("fox", "", third))
    }

    @Test
    fun textNotInTheArticleIsNotFound() {
        assertNull(locator.anchorOf("a cat"))
        assertTrue(locator.ranges("a cat", "", 0).isEmpty())
    }

    @Test
    fun syncValueRoundTripsInTheWebAppsFieldOrder() {
        val h =
            Highlight(
                id = "hl-1",
                itemKey = "https://f/feed\ng1",
                itemTitle = "Title",
                itemLink = "https://f/a",
                feedTitle = "F",
                imageUrl = null,
                text = "a quote",
                start = 10,
                prefix = "before ",
                color = "pink",
                note = "nice",
                createdAt = 1000,
                modifiedAt = 2000,
            )
        val v = Sync2Client.highlightValue(h)
        assertEquals(listOf("item", "title", "link", "feed", "image", "text", "start", "prefix", "color", "note", "createdAt"), v.keys.toList())
        // Numbers come back from JSON as doubles
        val parsed = v.mapValues { (_, x) -> if (x is Number) x.toDouble() else x }
        assertEquals(h.copy(modifiedAt = 5000), Sync2Client.highlightFromValue("hl-1", parsed, 5000))
        // Unknown colors fall back to yellow; no text means nothing to keep
        assertEquals("yellow", Sync2Client.highlightFromValue("x", parsed + ("color" to "purple"), 1)?.color)
        assertNull(Sync2Client.highlightFromValue("x", parsed - "text", 1))
    }

    @Test
    fun quoteTextMatchesTheWebApp() {
        assertEquals("“q”\nmy note\n— T, F\nhttps://l", quoteText("q", "T", "F", "https://l", "my note"))
        val hs =
            listOf(
                Highlight(id = "1", itemKey = "k", itemTitle = "T", feedTitle = "F", itemLink = "https://l", text = "one", note = "n", createdAt = 1),
                Highlight(id = "2", itemKey = "k", itemTitle = "T", feedTitle = "F", itemLink = "https://l", text = "two", createdAt = 1),
            )
        assertEquals("“one”\n  n\n\n“two”\n\n— T, F\nhttps://l", allQuotesText(hs))
    }
}
