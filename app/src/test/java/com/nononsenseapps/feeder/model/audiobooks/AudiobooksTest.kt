package com.nononsenseapps.feeder.model.audiobooks

import com.nononsenseapps.feeder.dropbox.DropboxFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the web app's web/src/lib/__tests__/audiobooks.test.ts, on the same files. */
class AudiobooksTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/audiobooks/$name")!!.readBytes()

    private val titles = listOf("Opening", "Middle part", "The End")

    /** Renames every [from] box type to [to], to hide a box from the parser. */
    private fun renameBox(
        bytes: ByteArray,
        from: String,
        to: String,
    ): ByteArray {
        val out = bytes.copyOf()
        val a = from.toByteArray()
        val b = to.toByteArray()
        for (i in 0..out.size - 4) {
            if (out[i] == a[0] && out[i + 1] == a[1] && out[i + 2] == a[2] && out[i + 3] == a[3]) b.copyInto(out, i)
        }
        return out
    }

    @Test
    fun readsMp4LengthTagsAndNeroChapters() =
        runBlocking {
            val t = AudioTags.readMp4(BytesReader(fixture("tiny.m4b")))
            assertTrue(t.duration!! in 5.9..6.2)
            assertEquals("Test Book", t.title)
            assertEquals("Jane Author", t.artist)
            assertEquals("Test Book Album", t.album)
            assertEquals(titles, t.chapters.map { it.title })
            assertEquals(listOf(0L, 2000L, 4000L), t.chapters.map { it.startMs })
        }

    @Test
    fun fallsBackToTheChapterTrack() =
        runBlocking {
            val t = AudioTags.readMp4(BytesReader(renameBox(fixture("tiny.m4b"), "chpl", "free")))
            assertEquals(titles, t.chapters.map { it.title })
            assertTrue(t.chapters[1].startMs in 1900..2100)
        }

    @Test
    fun readsId3TagsAndChapters() =
        runBlocking {
            val t = AudioTags.read("x.mp3", BytesReader(fixture("tiny.mp3")))
            assertEquals("Test Book", t.title)
            assertEquals("Jane Author", t.artist)
            assertEquals(titles, t.chapters.map { it.title })
            assertEquals(listOf(0L, 2000L, 4000L), t.chapters.map { it.startMs })
        }

    @Test
    fun unreadableFilesGiveEmptyTags() =
        runBlocking {
            val none = AudioTags.read("x.m4b", BytesReader(ByteArray(40)))
            assertEquals(emptyList<Mark>(), none.chapters)
            assertNull(none.duration)
            assertNull(AudioTags.read("x.ogg", BytesReader(fixture("tiny.m4b"))).title)
        }

    @Test
    fun tidiesMarks() {
        assertEquals(
            listOf(Mark(0, "A"), Mark(5000, "Chapter 2")),
            AudioTags.tidyMarks(listOf(Mark(5000, ""), Mark(1000, "A"), Mark(1200, "dup"), Mark(99_000, "past the end")), 60.0),
        )
        assertEquals(emptyList<Mark>(), AudioTags.tidyMarks(listOf(Mark(3000, "only")), null))
    }

    private fun file(
        path: String,
        hash: String = path,
    ) = DropboxFile(id = path, name = path.substringAfterLast('/'), path = path, size = 100, hash = hash, modified = "2026-01-01T00:00:00Z")

    @Test
    fun ordersFilesNaturallyFilesBeforeFolders() {
        val names = listOf("10.mp3", "2.mp3", "CD 2/1.mp3", "1.mp3", "CD 1/2.mp3", "CD 1/1.mp3")
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3", "CD 1/1.mp3", "CD 1/2.mp3", "CD 2/1.mp3"), names.sortedWith(AudiobookRules::comparePaths))
    }

    @Test
    fun oneBookPerTopLevelFileAndFolder() {
        val books =
            AudiobookRules.booksInListing(
                listOf(
                    file("/Audiobooks/Dune.m4b", "h-dune"),
                    file("/Audiobooks/notes.txt"),
                    file("/Audiobooks/The Hobbit/02.mp3", "h2"),
                    file("/Audiobooks/The Hobbit/01.mp3", "h1"),
                    file("/Audiobooks/The Hobbit/cover.jpg"),
                    file("/Audiobooks/The Hobbit/Extras/bonus.mp3", "h3"),
                    file("/Audiobooks/Empty/readme.txt"),
                ),
            )
        assertEquals(2, books.size)
        val dune = books.first { !it.folder }
        assertEquals("h-dune", dune.id)
        assertEquals("Dune", dune.title)
        val hobbit = books.first { it.folder }
        assertEquals("The Hobbit", hobbit.title)
        assertEquals("/Audiobooks/The Hobbit", hobbit.dropboxPath)
        assertEquals(listOf("h1", "h2", "h3"), hobbit.tracks.map { it.hash })
        assertEquals("/Audiobooks/The Hobbit/cover.jpg", hobbit.coverPath)
        val renamed = AudiobookRules.booksInListing(listOf(file("/Audiobooks/Hobbit/01.mp3", "h1"), file("/Audiobooks/Hobbit/02.mp3", "h2"), file("/Audiobooks/Hobbit/Extras/bonus.mp3", "h3")))
        assertEquals(hobbit.id, renamed[0].id)
    }

    @Test
    fun folderIdsMatchTheWebApp() {
        // sha256("audiobook\nh1\nh2") as the web app computes it (folderBookId)
        assertEquals(
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("audiobook\nh1\nh2".toByteArray())
                .joinToString("") { "%02x".format(it) },
            AudiobookRules.folderBookId(listOf("h1", "h2")),
        )
    }

    @Test
    fun mergeKeepsWhatWasReadAndDropsGoneBooks() {
        val d = AudiobookRules.booksInListing(listOf(file("/Audiobooks/A/1.mp3", "x1")))
        val old = AudiobookRules.mergeBooks(emptyList(), d).first[0].let { it.copy(described = true, title = "Read title", tracks = it.tracks.map { t -> t.copy(duration = 42.0, scanned = true) }) }
        val moved = AudiobookRules.booksInListing(listOf(file("/Audiobooks/B/1.mp3", "x1")))
        val (put, remove) = AudiobookRules.mergeBooks(listOf(old, old.copy(id = "gone")), moved)
        assertEquals(listOf("gone"), remove)
        assertEquals("Read title", put[0].title)
        assertEquals("/Audiobooks/B", put[0].dropboxPath)
        assertEquals(42.0, put[0].tracks[0].duration!!, 0.0)
        assertEquals("/Audiobooks/B/1.mp3", put[0].tracks[0].path)
    }

    private fun track(
        hash: String,
        duration: Double?,
        marks: List<Mark> = emptyList(),
    ) = AudioTrack(path = "/$hash", name = "$hash.mp3", hash = hash, size = 1, duration = duration, marks = marks, scanned = true)

    private val book =
        Audiobook("b", "Book", dropboxPath = "/Audiobooks/Book", folder = true, tracks = listOf(track("a", 60.0, listOf(Mark(0, "One"), Mark(30_000, "Two"))), track("b", 120.0)), size = 2)

    @Test
    fun timelineAcrossFiles() {
        val ch = AudiobookRules.chaptersOf(book)
        assertEquals(listOf("One", "Two", "b"), ch.map { it.title })
        assertEquals(listOf(0L, 30_000L, 60_000L), ch.map { it.bookMs })
        assertEquals(listOf(30_000L, 30_000L, 120_000L), ch.map { it.lengthMs })
        assertEquals(1, AudiobookRules.chapterAt(ch, 0, 45_000))
        assertEquals(2, AudiobookRules.chapterAt(ch, 1, 5_000))
        assertEquals(180_000L, AudiobookRules.bookLengthMs(book))
        assertEquals(0.5, AudiobookRules.fractionAt(book, 1, 30_000), 0.0001)
        assertEquals(1 to 30_000L, AudiobookRules.locate(book, 90_000))
    }

    @Test
    fun resumesByHashAndStartsOverWhenFinished() {
        fun pos(
            i: Int,
            h: String,
            ms: Long,
            f: Double,
        ) = AudioPos(i, h, ms, f, 1)
        assertEquals(1 to 10_000L, AudiobookRules.resumePoint(book, pos(0, "b", 10_000, 0.4)))
        assertEquals(1 to 10_000L, AudiobookRules.resumePoint(book, pos(1, "gone", 10_000, 0.4)))
        assertNull(AudiobookRules.resumePoint(book, pos(1, "b", 120_000, 1.0)))
        assertEquals(0 to 58_000L, AudiobookRules.resumePoint(book, pos(0, "a", 59_900, 0.3)))
        assertEquals("1:02:03", AudiobookRules.clock(3_723_000))
        assertEquals("5 h 12 min", AudiobookRules.lengthText(312 * 60_000L))
    }

    private fun picked(rel: String) = com.nononsenseapps.feeder.model.local.LocalEntry(io.mockk.mockk(relaxed = true), rel, rel.substringAfterLast('/'), 1, 0)

    @Test
    fun picksMakeBooksLikeTheWebApp() {
        val one = AudiobookRules.groupPicked(listOf("10 End.mp3", "2 Middle.mp3", "1 Start.mp3", "cover.jpg").map(::picked), "Dune")
        assertEquals(listOf("Dune"), one.map { it.title })
        assertEquals(listOf("1 Start.mp3", "2 Middle.mp3", "10 End.mp3"), one[0].files.map { it.rel })
        assertEquals("cover.jpg", one[0].cover?.rel)
        assertEquals(listOf("A", "B"), AudiobookRules.groupPicked(listOf("A.m4b", "B.m4b").map(::picked), "x").map { it.title })
        val lib = AudiobookRules.groupPicked(listOf("Loose.mp3", "Dune/02.mp3", "Dune/01.mp3", "Dune/folder.jpg", "Emma/CD1/1.mp3").map(::picked), "x")
        assertEquals(listOf("Loose", "Dune", "Emma"), lib.map { it.title })
        assertEquals(listOf("01.mp3", "02.mp3"), lib[1].files.map { it.rel })
        assertEquals("folder.jpg", lib[1].cover?.rel)
        assertEquals(listOf("CD1/1.mp3"), lib[2].files.map { it.rel })
    }

    @Test
    fun phoneBooksStayWhenTheyLeaveDropbox() {
        val t = AudioTrack(path = "/Audiobooks/a.mp3", name = "a.mp3", hash = "a", size = 1)
        val onPhone = Audiobook("a", "A", dropboxPath = "/Audiobooks/a.mp3", folder = false, tracks = listOf(t.copy(uri = "content://x")), size = 1)
        val onlyDropbox = Audiobook("b", "B", dropboxPath = "/Audiobooks/b.mp3", folder = false, tracks = listOf(t.copy(hash = "b")), size = 1)
        val phoneOnly = Audiobook("c", "C", dropboxPath = null, folder = false, tracks = listOf(t.copy(hash = "c", uri = "content://c")), size = 1)
        val (put, remove) = AudiobookRules.mergeBooks(listOf(onPhone, onlyDropbox, phoneOnly), emptyList())
        assertEquals(listOf("a" to null), put.map { it.id to it.dropboxPath })
        assertEquals(listOf("b"), remove)
    }
}
