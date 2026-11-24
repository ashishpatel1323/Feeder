package com.nononsenseapps.feeder.ui.compose.managefeeds

import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManageFeedsLogicTest {
    private fun feed(
        id: Long,
        title: String,
        tag: String = "",
        rate: Double = 0.0,
        unread: Int = 0,
        recent: Int = 0,
        recentRead: Int = 0,
        last: Long? = null,
    ) = ManagedFeed(
        id = id,
        title = title,
        url = "https://example.com/$id",
        tag = tag,
        imageUrl = null,
        openArticlesWith = "",
        fullTextByDefault = false,
        lastSync = null,
        stats =
            FeedStats(
                rate = rate,
                unread = unread,
                recent = recent,
                recentRead = recentRead,
                lastArticle = last?.let { Instant.ofEpochMilli(it) },
            ),
    )

    private val feeds =
        listOf(
            feed(1, "beta", tag = "Tech", rate = 1.0, unread = 5, recent = 10, recentRead = 9),
            feed(2, "Alpha", tag = "Tech", rate = 3.0, unread = 1, recent = 10, recentRead = 1),
            feed(3, "Gamma", tag = "art", rate = 0.0, unread = 9, recent = 0, last = 100),
            feed(4, "Loose", rate = 0.5, recent = 2),
        )

    @Test
    fun foldersAtoZWithNoFolderLast() {
        val folders = groupManagedFeeds(feeds, "", ManageSort.NAME)
        assertEquals(listOf("art", "Tech", ""), folders.map { it.tag })
        assertEquals(listOf("Alpha", "beta"), folders[1].shown.map { it.title })
    }

    @Test
    fun sortsInsideFolders() {
        val tech = { s: ManageSort -> groupManagedFeeds(feeds, "", s).first { it.tag == "Tech" }.shown.map { it.id } }
        assertEquals(listOf(2L, 1L), tech(ManageSort.MOST_ACTIVE))
        assertEquals(listOf(1L, 2L), tech(ManageSort.LEAST_ACTIVE))
        assertEquals(listOf(1L, 2L), tech(ManageSort.MOST_UNREAD))
        assertEquals(listOf(2L, 1L), tech(ManageSort.READ_LEAST))
    }

    @Test
    fun staleKeepsOnlyFeedsWithNothingNewAndHidesEmptyFolders() {
        val folders = groupManagedFeeds(feeds, "", ManageSort.STALE)
        assertEquals(listOf("art"), folders.map { it.tag })
        assertTrue(isFiltering("", ManageSort.STALE))
    }

    @Test
    fun searchMatchesNameOrAddress() {
        assertEquals(listOf(2L), groupManagedFeeds(feeds, "alp", ManageSort.NAME).flatMap { f -> f.shown.map { it.id } })
        assertEquals(listOf(4L), groupManagedFeeds(feeds, "example.com/4", ManageSort.NAME).flatMap { f -> f.shown.map { it.id } })
        assertFalse(isFiltering(" ", ManageSort.NAME))
    }

    @Test
    fun collapsedFoldersStayOutOfSelectAll() {
        val state = ManageFeedsViewState(loaded = true, feeds = feeds, closed = setOf("Tech"))
        assertEquals(listOf(3L, 4L), state.visibleIds)
        // A search opens every folder
        assertEquals(listOf(2L), state.copy(query = "alpha").visibleIds)
    }
}
