package com.nononsenseapps.feeder.model

import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedHeadTest {
    private fun head(text: String) = readFeedHead(Buffer().writeUtf8(text))

    private fun truncated(text: String) = head(text).truncated?.toString(Charsets.ISO_8859_1)

    @Test
    fun rssIsCutBeforeFirstItem() {
        val feed = "<rss><channel><title>T</title><itunes:image href=\"x\"/><item><title>1</title></item></channel></rss>"
        assertEquals("<rss><channel><title>T</title><itunes:image href=\"x\"/></channel></rss>", truncated(feed))
    }

    @Test
    fun itemWithAttributesIsFound() {
        assertEquals("<rss><channel><title>T</title></channel></rss>", truncated("<rss><channel><title>T</title><item id=\"a\"></item>"))
    }

    @Test
    fun atomIsCutBeforeFirstEntry() {
        val feed = "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>T</title><entry><id>1</id></entry></feed>"
        assertEquals("<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>T</title></feed>", truncated(feed))
    }

    @Test
    fun feedburnerElementIsNotMistakenForAtomRoot() {
        val feed = "<rss><channel><feedburner:info uri=\"x\"/><item></item>"
        assertEquals("<rss><channel><feedburner:info uri=\"x\"/></channel></rss>", truncated(feed))
    }

    @Test
    fun rdfClosesItsOwnRoot() {
        val feed = "<rdf:RDF><channel><title>T</title></channel><item rdf:about=\"x\"></item></rdf:RDF>"
        assertEquals("<rdf:RDF><channel><title>T</title></channel></rdf:RDF>", truncated(feed))
    }

    @Test
    fun similarTagNamesAreNotItems() {
        // <items> and <itunes:item...> must not count; no real item means no cut
        val feed = "<rss><channel><items/><itunes:itemCount>3</itunes:itemCount></channel></rss>"
        val result = head(feed)
        assertNull(result.truncated)
        assertEquals(feed, result.buffer.readUtf8())
    }

    @Test
    fun jsonFeedIsNotCut() {
        assertNull(truncated("  {\"title\":\"T\",\"items\":[{\"content_html\":\"<item >\"}]}"))
    }

    @Test
    fun markerAcrossChunkBoundaryIsFound() {
        val padding = "<description>" + "x".repeat(16 * 1024 - 20) + "</description>"
        val feed = "<rss><channel>$padding<item></item></channel></rss>"
        assertEquals("<rss><channel>$padding</channel></rss>", truncated(feed))
    }

    @Test
    fun keepsReadBytesForFallback() {
        val result = head("<rss><channel><title>T</title><item>rest")
        // Everything read so far is kept, so the full parse can continue from the source
        assertEquals(true, result.buffer.readUtf8().startsWith("<rss><channel><title>T</title><item"))
    }
}
