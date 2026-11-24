package com.nononsenseapps.feeder.sync2

import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.PlayStatus
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.PlaylistSort
import com.nononsenseapps.feeder.model.playlists.SmartRules
import com.nononsenseapps.feeder.model.playlists.starterPlaylists
import com.nononsenseapps.feeder.model.playlists.stampChanged
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncedPlaylistTest {
    private val feeds = mapOf(1L to "https://a/feed", 2L to "https://b/feed")
    private val items = mapOf(10L to "https://a/feed\ng1", 11L to "https://b/feed\ng2")

    private val smart =
        Playlist(
            id = "pl-1",
            name = "Drive",
            icon = "🚗",
            color = 0xFF1D4ED8,
            smart = true,
            rules = SmartRules(feedIds = listOf(1L), kinds = listOf(ItemKind.PODCAST), statuses = listOf(PlayStatus.IN_PROGRESS), maxMinutes = 40),
            sort = PlaylistSort.SHORTEST,
            createdAt = 5,
        )

    @Test
    fun writesTheWebAppsWordsAndColours() {
        val v = SyncedPlaylist.of(smart, { feeds[it] }, { items[it] })
        assertEquals("#1d4ed8", v.color)
        assertEquals(listOf("https://a/feed"), v.feeds)
        assertEquals(listOf("podcast"), v.kinds)
        assertEquals(listOf("inprogress"), v.statuses)
        assertEquals("shortest", v.sort)
    }

    @Test
    fun survivesJsonWithDoubles() {
        val v = SyncedPlaylist.of(smart, { feeds[it] }, { items[it] })
        val adapter = Moshi.Builder().build().adapter(Any::class.java).serializeNulls()
        val back = SyncedPlaylist.fromMap(adapter.fromJson(adapter.toJson(v.toMap())) as Map<*, *>)
        assertEquals(v, back)
        val pl = back.toPlaylist("pl-1", 9, feedIdOf = { url -> feeds.entries.firstOrNull { it.value == url }?.key }, itemIdOf = { null })
        assertEquals(smart.copy(modifiedAt = 9), pl)
    }

    @Test
    fun keepsArticlesThisDeviceDoesNotHaveInPlace() {
        val manual = smart.copy(smart = false, rules = SmartRules(), itemIds = listOf(10L, 11L))
        val v =
            SyncedPlaylist.of(
                manual,
                { feeds[it] },
                { items[it] },
                unresolvedItems = listOf(IndexedValue(1, "https://c/feed\nelsewhere")),
            )
        assertEquals(listOf("https://a/feed\ng1", "https://c/feed\nelsewhere", "https://b/feed\ng2"), v.items)
    }

    @Test
    fun startersGetFixedIdsUnlessAlreadySynced() {
        val old = starterPlaylists().map { it.copy(id = "pl-${it.name.length}") }
        val adopted = SyncedPlaylist.adoptStarterIds(old, synced = setOf(old[2].id))
        assertEquals(listOf("starter-morning-drive", "starter-new-podcast-episodes", old[2].id), adopted.map { it.id })
        assertEquals(listOf("starter-morning-drive", "starter-new-podcast-episodes", "starter-short-reads"), starterPlaylists().map { it.id })
    }

    @Test
    fun stampsOnlyChangedPlaylists() {
        val a = smart
        val b = smart.copy(id = "pl-2", name = "Other")
        val next = stampChanged(listOf(a, b), listOf(a, b.copy(name = "Renamed")), now = 77)
        assertEquals(0L, next[0].modifiedAt)
        assertEquals(77L, next[1].modifiedAt)
        assertNotEquals(b, next[1])
    }
}
