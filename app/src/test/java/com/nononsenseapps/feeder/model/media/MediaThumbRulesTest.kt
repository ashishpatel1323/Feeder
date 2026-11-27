package com.nononsenseapps.feeder.model.media

import com.nononsenseapps.feeder.dropbox.DropboxFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the web app's thumbnail tests in web/src/lib/__tests__/media.test.ts. */
class MediaThumbRulesTest {
    private fun file(
        path: String,
        hash: String = path,
    ) = DropboxFile("id:$path", path.substringAfterLast('/'), path, 100, hash, "2026-11-01T10:00:00Z")

    @Test
    fun takesTheFrameATenthIn() {
        assertEquals(5_000L, MediaThumbRules.frameAtMs(null))
        assertEquals(500L, MediaThumbRules.frameAtMs(1_000))
        assertEquals(1_000L, MediaThumbRules.frameAtMs(5_000))
        assertEquals(36_000L, MediaThumbRules.frameAtMs(360_000))
        assertEquals(60_000L, MediaThumbRules.frameAtMs(3_600_000))
    }

    @Test
    fun triesAgainAfterAWeekOrOnceOnThePhone() {
        val now = 10 * MediaThumbRules.RETRY_MS
        assertTrue(MediaThumbRules.shouldTry(null, onPhone = false, now = now))
        assertFalse(MediaThumbRules.shouldTry(now - 1000 to MediaThumbs.REMOTE, onPhone = false, now = now))
        assertTrue(MediaThumbRules.shouldTry(now - 1000 to MediaThumbs.REMOTE, onPhone = true, now = now))
        assertFalse(MediaThumbRules.shouldTry(now - 1000 to MediaThumbs.PHONE, onPhone = true, now = now))
        assertTrue(MediaThumbRules.shouldTry(now - MediaThumbRules.RETRY_MS - 1 to MediaThumbs.PHONE, onPhone = true, now = now))
    }

    @Test
    fun findsAPictureNextToTheFile() {
        val drafts =
            MediaRules.mediaInListing(
                listOf(
                    file("/Media/YouTube/Ch/Talk.mp4", "h1"),
                    file("/Media/YouTube/Ch/talk.JPG", "p1"),
                    file("/Media/YouTube/Ch/Other.mp4", "h2"),
                    file("/Media/Elsewhere/Other.png", "p2"),
                ),
            )
        assertEquals("/Media/YouTube/Ch/talk.JPG", drafts.first { it.id == "h1" }.thumb)
        assertNull(drafts.first { it.id == "h2" }.thumb)
        assertEquals(2, drafts.size)
    }

    @Test
    fun dropsThePictureWhenTheFileLeavesDropbox() {
        val have = listOf(MediaFile(id = "a", name = "a.mp4", dropboxPath = "/Media/a.mp4", thumb = "/Media/a.jpg", uri = "content://a"))
        val out = MediaRules.merge(have, emptyList()) { true }
        assertNull(out.single().thumb)
    }
}
