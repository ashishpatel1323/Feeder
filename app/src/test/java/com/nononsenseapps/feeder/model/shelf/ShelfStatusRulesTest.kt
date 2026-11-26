package com.nononsenseapps.feeder.model.shelf

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfStatusRulesTest {
    @Test
    fun followsProgress() {
        assertEquals(BookStatus.NEW, ShelfStatusRules.auto(null, opened = false))
        assertEquals(BookStatus.CURRENT, ShelfStatusRules.auto(null, opened = true))
        assertEquals(BookStatus.CURRENT, ShelfStatusRules.auto(0.4, opened = false))
        assertEquals(BookStatus.DONE, ShelfStatusRules.auto(0.985, opened = true))
        // A ZIM site never finishes by itself
        assertEquals(BookStatus.CURRENT, ShelfStatusRules.auto(1.0, opened = true, canFinish = false))
    }

    @Test
    fun pickedStatusHoldsUntilTheBookIsUsedAgain() {
        val picked = PickedStatus("done", ts = 100)
        assertEquals(BookStatus.DONE, ShelfStatusRules.effective(BookStatus.CURRENT, picked, lastActivity = 90))
        assertEquals(BookStatus.CURRENT, ShelfStatusRules.effective(BookStatus.CURRENT, picked, lastActivity = 120))
        assertEquals(BookStatus.NEW, ShelfStatusRules.effective(BookStatus.NEW, null, lastActivity = 0))
        assertEquals(BookStatus.NEW, ShelfStatusRules.effective(BookStatus.NEW, PickedStatus("bogus", 5), lastActivity = 0))
    }

    @Test
    fun readsCoverSearchResults() {
        val itunes = Json.parseToJsonElement("""{"results":[{"collectionName":"Dune","artistName":"Frank Herbert","artworkUrl100":"https://is1.mzstatic.com/a/b/100x100bb.jpg"}]}""")
        val c = BookCovers.parseItunes(itunes).single()
        assertEquals("https://is1.mzstatic.com/a/b/600x600bb.jpg", c.url)
        assertEquals("Dune · Frank Herbert", c.label)
        val ol = Json.parseToJsonElement("""{"docs":[{"cover_i":42,"title":"Dune","author_name":["Frank Herbert"]},{"title":"No cover"}]}""")
        assertEquals("https://covers.openlibrary.org/b/id/42-L.jpg", BookCovers.parseOpenLibrary(ol).single().url)
        val g = Json.parseToJsonElement("""{"items":[{"volumeInfo":{"title":"Dune","imageLinks":{"thumbnail":"http://books.google.com/x?id=1&zoom=1&edge=curl"}}}]}""")
        assertEquals("https://books.google.com/x?id=1&zoom=0", BookCovers.parseGoogleBooks(g).single().url)
    }
}
