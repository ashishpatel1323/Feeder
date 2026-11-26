package com.nononsenseapps.feeder.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarTabsTest {
    @Test
    fun defaultsToFeedsYouTubePlaylistsLibrary() {
        assertEquals(listOf(MainTab.FEEDS, MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.LIBRARY), BottomBarTabs.parse(null))
    }

    @Test
    fun keepsTheChosenOrderAndAlwaysFeedsAndLibrary() {
        assertEquals(
            listOf(MainTab.ZIM, MainTab.EBOOKS, MainTab.FEEDS, MainTab.LIBRARY),
            BottomBarTabs.parse("ZIM,EBOOKS,NOT_A_TAB"),
        )
        assertEquals(
            listOf(MainTab.LIBRARY, MainTab.FEEDS),
            BottomBarTabs.parse("LIBRARY,LIBRARY,FEEDS"),
        )
    }

    @Test
    fun keepsAtMostFiveDroppingOptionalOnes() {
        val tabs = BottomBarTabs.normalize(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.EBOOKS, MainTab.AUDIOBOOKS, MainTab.ZIM))
        assertEquals(listOf(MainTab.YOUTUBE, MainTab.PLAYLISTS, MainTab.EBOOKS, MainTab.FEEDS, MainTab.LIBRARY), tabs)
    }
}
