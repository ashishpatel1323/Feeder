package com.nononsenseapps.feeder.model.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsPlaylistTest {
    @Test
    fun keepsSavedOrderAndAppendsNewOnesOldestFirst() {
        val downloadedAt = mapOf(1L to 10L, 2L to 20L, 3L to 30L, 4L to 5L, 5L to 50L)
        val order = DownloadsPlaylist.order(listOf(3L, 1L, 9L), listOf(1L, 2L, 3L, 4L, 5L)) { downloadedAt.getValue(it) }
        assertEquals(listOf(3L, 1L, 4L, 2L, 5L), order)
    }

    @Test
    fun dropsDeletedVideos() {
        assertEquals(listOf(2L), DownloadsPlaylist.order(listOf(1L, 2L), listOf(2L)) { 0L })
    }

    @Test
    fun overallPercentCountsVideoThenSound() {
        assertEquals(40f, overallPercent(VideoPhase.VIDEO, 50f))
        assertEquals(90f, overallPercent(VideoPhase.AUDIO, 50f))
        assertEquals(100f, overallPercent(VideoPhase.SUBTITLES, 0f))
    }
}
