package com.nononsenseapps.feeder.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarTabsTest {
    @Test
    fun defaultsToHomeFeedsLibrarySearch() {
        assertEquals(listOf(MainTab.HOME, MainTab.FEEDS, MainTab.LIBRARY, MainTab.SEARCH), BottomBarTabs.parse(null))
    }

    @Test
    fun keepsTheChosenOrderWithNoTabForced() {
        assertEquals(listOf(MainTab.HISTORY, MainTab.YOUTUBE), BottomBarTabs.parse("HISTORY,YOUTUBE,NOT_A_TAB"))
        assertEquals(listOf(MainTab.LIBRARY, MainTab.FEEDS), BottomBarTabs.parse("LIBRARY,LIBRARY,FEEDS"))
    }

    @Test
    fun nothingPickedFallsBackToTheDefault() {
        assertEquals(BottomBarTabs.DEFAULT, BottomBarTabs.parse("NOT_A_TAB"))
    }

    @Test
    fun oldEbooksAudiobooksAndZimTabsBecomeOneBooksTabInTheFirstOnesPlace() {
        assertEquals(
            listOf(MainTab.FEEDS, MainTab.BOOKS, MainTab.PLAYLISTS, MainTab.LIBRARY),
            BottomBarTabs.parse("FEEDS,ZIM,PLAYLISTS,EBOOKS,AUDIOBOOKS,LIBRARY"),
        )
    }

    @Test
    fun withoutLibraryTheBarEndsWithMoreAndKeepsAtMostFiveItems() {
        val picked = BottomBarTabs.normalize(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.BOOKS, MainTab.HISTORY, MainTab.HOME))
        assertEquals(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.BOOKS, MainTab.HISTORY), picked)
        assertEquals(picked + MainTab.LIBRARY, BottomBarTabs.shown(picked))
    }

    @Test
    fun withLibraryFiveTabsFit() {
        val picked = listOf(MainTab.YOUTUBE, MainTab.LIBRARY, MainTab.BOOKS, MainTab.HISTORY, MainTab.HOME)
        assertEquals(picked, BottomBarTabs.normalize(picked))
        assertEquals(picked, BottomBarTabs.shown(picked))
    }

    @Test
    fun aLeftOutFeedsTabsScreensBelongToMore() {
        val bar = BottomBarTabs.shown(listOf(MainTab.HOME, MainTab.PLAYLISTS))
        assertEquals(MainTab.LIBRARY, mainTabOf(HomeDestination.route, null, bar))
        assertEquals(MainTab.PLAYLISTS, mainTabOf(PlaylistsDestination.route, null, bar))
    }
}
