package com.nononsenseapps.feeder.model.search

import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.ebooks.Book
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.newPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    private val now = 1_795_000_000_000L

    private fun item(
        id: Long,
        title: String,
        link: String? = "https://a.example/$id",
        enclosure: String? = null,
        feedId: Long = 1,
        feedTitle: String = "The Verge",
        feedTag: String = "Tech",
    ) = PlaylistCandidate(
        id = id,
        title = title,
        link = link,
        enclosureLink = enclosure,
        enclosureType = if (enclosure != null) "audio/mpeg" else null,
        enclosureDuration = null,
        read = false,
        bookmarked = false,
        offline = false,
        sortTime = now - 86_400_000L,
        wordCount = 100,
        wordCountFull = 0,
        image = null,
        feedId = feedId,
        feedUrl = "https://feed$feedId.example/rss",
        feedTag = feedTag,
        feedTitle = feedTitle,
        feedImage = null,
        fullTextByDefault = false,
        guid = "g$id",
    )

    @Test
    fun foldsCaseAndAccentsKeepingPositions() {
        assertEquals("cafe nvidia", fold("Café NVIDIA"))
        assertEquals(4, fold("Café").length)
        assertEquals(listOf("nvidia", "chips"), queryTerms("  Nvidia nvidia CHIPS "))
    }

    @Test
    fun needsEveryTerm() {
        assertEquals(listOf(0..5, 13..17), findTerms("nvidia rubin chips", listOf("nvidia", "chips")))
        assertNull(findTerms("nvidia rubin", listOf("nvidia", "chips")))
    }

    @Test
    fun emptyQueryFindsNothing() {
        val r = searchAll("  ", SearchData(items = listOf(item(1, "Nvidia")), now = now))
        assertTrue(r.items.isEmpty())
        assertEquals(0, r.count(SearchScope.ALL))
    }

    @Test
    fun titleMatchRanksAboveSnippetMatch() {
        val r =
            searchAll(
                "nvidia",
                SearchData(
                    items = listOf(item(1, "Data centers"), item(2, "Nvidia's Rubin chips ship early")),
                    texts = mapOf(1L to ItemText("Oracle buys chips from NVIDIA.", null)),
                    now = now,
                ),
            )
        assertEquals(listOf(2L to MatchField.TITLE, 1L to MatchField.TEXT), r.items.map { it.item.id to it.field })
        assertTrue(
            r.items[1]
                .snippet!!
                .text
                .contains("NVIDIA"),
        )
    }

    @Test
    fun findsEpisodeByTranscriptWithLineTime() {
        val ep = item(7, "Two ways big bets on data centers", enclosure = "https://pod.example/ep.mp3", feedTitle = "Planet Money")
        val r =
            searchAll(
                "nvidia",
                SearchData(
                    items = listOf(ep),
                    transcripts =
                        mapOf(
                            7L to
                                listOf(
                                    TranscriptLine(960_000, "This bit has more implications."),
                                    TranscriptLine(977_000, "So, Oracle buys chips from NVIDIA to build this cloud."),
                                    TranscriptLine(982_000, "And then NVIDIA invests money into OpenAI."),
                                ),
                        ),
                    now = now,
                ),
            )
        val hit = r.items.single()
        assertEquals(ItemKind.PODCAST, hit.kind)
        assertEquals(MatchField.TRANSCRIPT, hit.field)
        assertEquals(977_000L, hit.atMs)
        assertEquals(2, hit.transcriptMatches)
        assertEquals("16:17", clock(hit.atMs!!))
    }

    @Test
    fun findsHighlightsFeedsFoldersAndPlaylists() {
        val hl =
            Highlight(
                id = "h1",
                itemKey = "https://feed1.example/rss\ng1",
                itemTitle = "Two ways big bets",
                text = "And then NVIDIA invests money into OpenAI",
                note = "circular money",
                createdAt = now,
            )
        val data =
            SearchData(
                items = listOf(item(1, "Something else", feedTitle = "Planet Money", feedTag = "Economics")),
                highlights = listOf(hl),
                playlists = listOf(newPlaylist(name = "Money talks", smart = false, existing = emptyList())),
                now = now,
            )
        assertEquals(listOf("h1"), searchAll("nvidia", data).highlights.map { it.highlight.id })
        assertEquals(listOf("h1"), searchAll("circular", data).highlights.map { it.highlight.id })
        val money = searchAll("money", data).library.map { it::class.simpleName }.sortedBy { it }
        assertEquals(listOf("FeedHit", "PlaylistHit"), money)
        assertTrue(searchAll("econ", data).library.single() is FolderHit)
    }

    @Test
    fun countsScopes() {
        val data =
            SearchData(
                items =
                    listOf(
                        item(1, "Chips everywhere"),
                        item(2, "Chips video", link = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                        item(3, "Chips pod", enclosure = "https://pod.example/x.mp3"),
                    ),
                now = now,
            )
        val r = searchAll("chips", data)
        assertEquals(3, r.count(SearchScope.ALL))
        assertEquals(1, r.count(SearchScope.ARTICLE))
        assertEquals(1, r.count(SearchScope.YOUTUBE))
        assertEquals(1, r.count(SearchScope.PODCAST))
        assertEquals(listOf(2L), r.inScope(SearchScope.YOUTUBE).map { (it as ItemHit).item.id })
    }

    @Test
    fun marksAccentedTitles() {
        val r = searchAll("cafe", SearchData(items = listOf(item(1, "Le Café du Coin")), now = now))
        assertEquals(
            listOf(3..6),
            r.items
                .single()
                .title.ranges,
        )
    }

    @Test
    fun findsEbooksByTitleOrAuthor() {
        val data =
            SearchData(
                books =
                    listOf(
                        Book(id = "b1", title = "The Time Machine", author = "H. G. Wells", ext = "epub", size = 1, addedAt = 0),
                        Book(id = "b2", title = "Dune", author = "Frank Herbert", ext = "epub", size = 1, addedAt = 0),
                    ),
                now = now,
            )
        assertEquals(listOf("b1"), searchAll("time machine", data).books.map { it.book.id })
        assertEquals(listOf("b2"), searchAll("herbert", data).books.map { it.book.id })
        val r = searchAll("wells", data)
        assertEquals(1, r.count(SearchScope.BOOK))
        assertEquals(1, r.count(SearchScope.ALL))
    }

    @Test
    fun addsZimMatchesBelowYourOwn() {
        val r = searchAll("python", SearchData(items = listOf(item(1, "Python 3.14 is out")), now = now))
        val z =
            addZimHits(
                r,
                listOf(
                    ZimTitle("wiki", "Wikipedia", "Python_(language)", "Python (language)"),
                    ZimTitle("wiki", "Wikipedia", "Monty", "Monty Python"),
                    // Same page again from full text: the title match stays
                    ZimTitle("wiki", "Wikipedia", "Monty", "Monty Python", "a python sketch"),
                    ZimTitle("wiki", "Wikipedia", "Snake", "Snakes", "the python is a snake"),
                ),
            )
        assertEquals(listOf("Python_(language)", "Monty", "Snake"), z.zim.map { it.path })
        assertEquals(3, z.count(SearchScope.ZIM))
        assertEquals(4, z.count(SearchScope.ALL))
        assertTrue(z.inScope(SearchScope.ALL).first() is ItemHit)
        assertNull(z.zim.first().snippet)
        val last = z.zim.last()
        assertEquals("the python is a snake", last.snippet?.text)
    }
}
