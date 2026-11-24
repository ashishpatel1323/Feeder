package com.nononsenseapps.feeder.model

import com.nononsenseapps.feeder.db.room.Feed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.time.Instant

class KeepSettingsDuringDownloadTest {
    @Test
    fun folderMovedBySyncDuringDownloadIsKept() {
        // Read before the download, then updated with what the download found
        val downloaded =
            Feed(id = 1, url = URL("https://a/feed"), tag = "Old", customTitle = "", whenModified = Instant.ofEpochMilli(10)).apply {
                title = "Fresh title"
                imageUrl = URL("https://a/icon.png")
            }
        // Sync moved it meanwhile
        val current =
            Feed(id = 1, url = URL("https://a/feed"), title = "Stale", tag = "New", customTitle = "Mine", whenModified = Instant.ofEpochMilli(20))

        keepSettingsChangedDuringDownload(downloaded, current)

        assertEquals("New", downloaded.tag)
        assertEquals("Mine", downloaded.customTitle)
        assertEquals(Instant.ofEpochMilli(20), downloaded.whenModified)
        // What the download found stays
        assertEquals("Fresh title", downloaded.title)
        assertEquals(URL("https://a/icon.png"), downloaded.imageUrl)
    }
}
