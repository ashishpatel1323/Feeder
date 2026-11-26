package com.nononsenseapps.feeder.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarTabsTest {
    @Test
    fun defaultsToFeedsYouTubePlaylistsBooksLibrary() {
        assertEquals(listOf(MainTab.FEEDS, MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.BOOKS, MainTab.LIBRARY), BottomBarTabs.parse(null))
    }

    @Test
    fun keepsTheChosenOrderAndAlwaysFeedsAndLibrary() {
        assertEquals(
            listOf(MainTab.HISTORY, MainTab.YOUTUBE, MainTab.FEEDS, MainTab.LIBRARY),
            BottomBarTabs.parse("HISTORY,YOUTUBE,NOT_A_TAB"),
        )
        assertEquals(
            listOf(MainTab.LIBRARY, MainTab.FEEDS),
            BottomBarTabs.parse("LIBRARY,LIBRARY,FEEDS"),
        )
    }

    @Test
    fun oldEbooksAudiobooksAndZimTabsBecomeOneBooksTabInTheFirstOnesPlace() {
        assertEquals(
            listOf(MainTab.FEEDS, MainTab.BOOKS, MainTab.PLAYLISTS, MainTab.LIBRARY),
            BottomBarTabs.parse("FEEDS,ZIM,PLAYLISTS,EBOOKS,AUDIOBOOKS,LIBRARY"),
        )
    }

    @Test
    fun keepsAtMostFiveDroppingOptionalOnes() {
        val tabs = BottomBarTabs.normalize(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.BOOKS, MainTab.HISTORY))
        assertEquals(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.BOOKS, MainTab.FEEDS, MainTab.LIBRARY), tabs)
    }
}
