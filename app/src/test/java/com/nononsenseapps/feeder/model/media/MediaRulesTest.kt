package com.nononsenseapps.feeder.model.media

import com.nononsenseapps.feeder.dropbox.DropboxFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the web app's web/src/lib/__tests__/media.test.ts. */
class MediaRulesTest {
    private fun file(
        path: String,
        hash: String = path,
        size: Long = 100,
    ) = DropboxFile("id:$path", path.substringAfterLast('/'), path, size, hash, "2026-11-01T10:00:00Z")

    private fun media(
        id: String,
        name: String,
        folder: String = "",
        dropboxPath: String? = "/Media/$name",
        size: Long = 100,
        uri: String? = null,
        duration: Double? = null,
    ) = MediaFile(id = id, name = name, folder = folder, dropboxPath = dropboxPath, size = size, kind = MediaRules.VIDEO, uri = uri, duration = duration)

    @Test
    fun knowsAudioFromVideo() {
        assertEquals(MediaRules.VIDEO, MediaRules.kindOf("a.MP4"))
        assertEquals(MediaRules.VIDEO, MediaRules.kindOf("a.mkv"))
        assertEquals(MediaRules.AUDIO, MediaRules.kindOf("song.flac"))
        assertNull(MediaRules.kindOf("notes.txt"))
        assertNull(MediaRules.kindOf("cover.jpg"))
    }

    @Test
    fun keepsFoldersOrderSubtitlesAndEachFileOnce() {
        val drafts =
            MediaRules.mediaInListing(
                listOf(
                    file("/Media/Talks/10 Ending.mp4", "h10"),
                    file("/Media/Talks/2 Start.mp4", "h2"),
                    file("/Media/Talks/2 Start.en.srt", "s1"),
                    file("/Media/Talks/2 Start.vtt", "s2"),
                    file("/Media/Talks/2024/Keynote.webm", "hk"),
                    file("/Media/song.mp3", "hs"),
                    file("/Media/Copy/song.mp3", "hs"),
                    file("/Media/readme.txt", "ht"),
                    file("/Audiobooks/book.m4b", "hb"),
                ),
            )
        assertEquals(
            listOf("" to "song.mp3", "Talks" to "2 Start.mp4", "Talks" to "10 Ending.mp4", "Talks/2024" to "Keynote.webm"),
            drafts.map { it.folder to it.name },
        )
        assertEquals("/Media/Talks/2 Start.vtt", drafts.first { it.id == "h2" }.subs)
        assertNull(drafts.first { it.id == "h10" }.subs)
        assertEquals(MediaRules.AUDIO, drafts.first { it.id == "hs" }.kind)
    }

    @Test
    fun showsFoldersAndTheFolderFiles() {
        val items =
            listOf(
                media("1", "b.mp4", "Talks"),
                media("2", "a.mp4", "Talks"),
                media("3", "k.mp4", "Talks/2024", size = 50),
                media("4", "top.mp3", ""),
                media("5", "x.mp3", "Music"),
            )
        val (folders, files) = MediaRules.folderView(items, "")
        assertEquals(listOf(Triple("Music", 1, 100L), Triple("Talks", 3, 250L)), folders.map { Triple(it.name, it.count, it.size) })
        assertEquals(listOf("4"), files.map { it.id })
        val (sub, talks) = MediaRules.folderView(items, "Talks")
        assertEquals(listOf("Talks/2024"), sub.map { it.path })
        assertEquals(listOf("2", "1"), talks.map { it.id })
        assertEquals(listOf("2", "1", "3"), MediaRules.filesUnder(items, "Talks").map { it.id })
        assertEquals(emptyList<MediaFile>(), MediaRules.folderView(items, "Talk").second)
    }

    @Test
    fun mergesANewListing() {
        val have =
            listOf(
                media("a", "a.mp4", "Old", "/Media/Old/a.mp4", duration = 60.0),
                media("b", "b.mp4"),
                media("c", "c.mp4"),
                media("d", "d.mp4", dropboxPath = null, uri = "content://x/d"),
            )
        val out =
            MediaRules.merge(
                have,
                listOf(
                    MediaFile(id = "a", name = "a.mp4", folder = "New", dropboxPath = "/Media/New/a.mp4", size = 100, kind = MediaRules.VIDEO),
                    MediaFile(id = "e", name = "e.mp3", dropboxPath = "/Media/e.mp3", size = 5, kind = MediaRules.AUDIO),
                ),
            ) { it.id == "c" }
        val byId = out.associateBy { it.id }
        assertEquals(setOf("a", "e", "c", "d"), byId.keys)
        assertEquals("New", byId["a"]!!.folder)
        assertEquals(60.0, byId["a"]!!.duration)
        assertNull(byId["c"]!!.dropboxPath)
        assertEquals("content://x/d", byId["d"]!!.uri)
        assertFalse("b" in byId)
    }

    @Test
    fun uploadsIntoTheSameFolder() {
        assertEquals("/Media/Holiday/Day 1/clip 1.mp4", MediaRules.uploadPath(media("x", "clip:1.mp4", "Holiday/Day 1", dropboxPath = null)))
        assertEquals("/Media/a.mp3", MediaRules.uploadPath(media("y", "a.mp3", "", dropboxPath = null)))
    }

    @Test
    fun playsAsAOneFileBook() {
        val b = MediaRules.asBook(media("hash1", "My_Clip.mp4", "Talks/2024", duration = 90.0))
        assertEquals("hash1", b.id)
        assertEquals("My Clip", b.title)
        assertEquals("2024", b.author)
        assertEquals(1, b.tracks.size)
        assertEquals("hash1", b.tracks[0].hash)
        assertEquals(90.0, b.tracks[0].duration)
    }
}
