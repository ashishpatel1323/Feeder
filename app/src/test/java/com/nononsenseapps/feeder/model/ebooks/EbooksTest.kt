package com.nononsenseapps.feeder.model.ebooks

import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import com.nononsenseapps.feeder.dropbox.DropboxLink
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.sync2.Sync2Client
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class EbooksTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test
    fun contentHashMatchesDropboxAcrossBlocks() {
        val bytes = ByteArray(9 * 1024 * 1024 + 123) { (it * 31).toByte() }
        val f = File.createTempFile("book", ".epub").apply { writeBytes(bytes) }
        val block = 4 * 1024 * 1024
        val parts = (bytes.indices step block).map { sha(bytes.copyOfRange(it, minOf(it + block, bytes.size))) }
        val expected = sha(parts.fold(ByteArray(0)) { a, b -> a + b }).joinToString("") { "%02x".format(it) }
        assertEquals(expected, DropboxClient.contentHash(f))
        // The web app's test vector for "hello" (one block)
        val hello = File.createTempFile("hello", ".txt").apply { writeText("hello") }
        assertEquals(sha(sha("hello".toByteArray())).joinToString("") { "%02x".format(it) }, DropboxClient.contentHash(hello))
    }

    @Test
    fun headerArgIsAscii() {
        assertEquals(
            "{\"path\":\"/Ebooks/Caf\\u00e9 \\u2013 \\u65e5\\u672c.epub\"}",
            DropboxClient.headerArg(buildJsonObject { put("path", "/Ebooks/Café – 日本.epub") }),
        )
    }

    @Test
    fun namesAndIds() {
        assertEquals("epub", EbookLibrary.extOf("A.EPUB"))
        assertEquals("fbz", EbookLibrary.extOf("x.fb2.zip"))
        assertEquals("The Time Machine", EbookLibrary.titleFromName("The_Time Machine.epub"))
        assertEquals("abc", EbookLibrary.bookIdOfItem(EbookLibrary.itemKey("abc")))
        assertNull(EbookLibrary.bookIdOfItem("https://feed\nguid"))
        assertEquals("A Book - An Author.epub", EbookLibrary.uploadName(Book(id = "x", title = "A Book", author = "An Author", ext = "epub", size = 1, addedAt = 0)))
    }

    private fun book(
        id: String,
        path: String? = null,
        local: Boolean = false,
        described: Boolean = false,
    ) = Book(id = id, title = id, ext = "epub", size = 1, dropboxPath = path, local = local, described = described, addedAt = 0)

    private fun file(
        name: String,
        hash: String,
    ) = DropboxFile("id:$hash", name, "/Ebooks/$name", 10, hash)

    @Test
    fun mergesADropboxListingLikeTheWebApp() {
        val books =
            listOf(
                book("kept", "/Ebooks/kept.epub", local = true, described = true),
                book("moved", "/Ebooks/old.epub"),
                book("gone-remote", "/Ebooks/x.epub"),
                book("gone-local", "/Ebooks/y.epub", local = true),
                book("local-only", local = true),
            )
        val merged =
            EbookLibrary
                .merge(books, listOf(file("kept.epub", "kept"), file("New_Book.epub", "new"), file("new.mobi", "moved"), file("notes.pdf", "pdf"), file("dupe.epub", "new")))
                .books
                .associateBy { it.id }
        assertEquals("New Book", merged["new"]!!.title)
        assertEquals("/Ebooks/New_Book.epub", merged["new"]!!.dropboxPath)
        assertEquals("/Ebooks/new.mobi", merged["moved"]!!.dropboxPath)
        assertNull(merged["gone-local"]!!.dropboxPath)
        assertNull(merged["gone-remote"])
        assertNull(merged["pdf"])
        assertEquals("kept", merged["kept"]!!.title)
        assertEquals(true, merged["local-only"]!!.local)
    }

    @Test
    fun ebookHighlightsCarryTheirCfiThroughSync() {
        val h = Highlight(id = "h", itemKey = EbookLibrary.itemKey("b1"), itemTitle = "A Book", text = "quoted", createdAt = 5, cfi = "epubcfi(/6/4!/4/2,/1:0,/1:6)")
        val v = Sync2Client.highlightValue(h)
        assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:6)", v["cfi"])
        assertEquals(listOf("item", "title", "link", "feed", "image", "text", "start", "prefix", "color", "note", "createdAt", "cfi"), v.keys.toList())
        assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:6)", Sync2Client.highlightFromValue("h", v, 9)!!.cfi)
        // Article highlights have no cfi field, as before
        val article = Sync2Client.highlightValue(h.copy(itemKey = "https://f\ng", cfi = null))
        assertEquals(false, article.containsKey("cfi"))
    }

    @Test
    fun dropboxLinkFieldsMatchTheWebApp() {
        assertEquals(
            listOf("appKey", "refreshToken", "name", "email"),
            Sync2Client.dropboxValue(DropboxLink("k", "r", "n", "e")).keys.toList(),
        )
    }
}
