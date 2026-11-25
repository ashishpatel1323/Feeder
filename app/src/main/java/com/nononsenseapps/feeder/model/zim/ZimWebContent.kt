package com.nononsenseapps.feeder.model.zim

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.kiwix.libzim.Archive
import org.kiwix.libzim.SuggestionSearcher
import java.io.ByteArrayInputStream

/**
 * Serves a book's pages to the reader's WebView at https://zim.feeder/<book>/<path>, the way
 * Kiwix does it: every request is answered from the ZIM file, nothing goes to the network.
 *
 * Paths are as in the ZIM: "Foo" for new ZIMs (content namespace C), "A/Foo" for old ones.
 */
object ZimWebContent {
    const val HOST = "zim.feeder"
    private const val LOG_TAG = "FEEDER_ZIM_WEB"

    fun url(
        book: String,
        path: String,
    ): String = "https://$HOST/${Uri.encode(book)}/" + path.split('/').joinToString("/") { Uri.encode(it) }

    /** The book and path of a reader URL, or null for anything else. */
    fun parse(uri: Uri): Pair<String, String>? {
        if (uri.host != HOST) return null
        // Not pathSegments: it drops a trailing "/", which zimit paths such as "site.com/" keep
        val parts = (uri.encodedPath ?: return null).removePrefix("/").split('/')
        if (parts.isEmpty() || parts[0].isEmpty()) return null
        return Uri.decode(parts[0]) to parts.drop(1).joinToString("/") { Uri.decode(it) }
    }

    /** The URL path of the main page. */
    fun mainPath(archive: Archive): String? =
        try {
            archive.mainEntry.getItem(true).path
        } catch (_: Exception) {
            null
        }

    fun respond(
        library: ZimLibrary,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val (book, path) = parse(request.url) ?: return null
        return try {
            val archive = library.archive(book)
            val item = archive.getEntryByPath(path).getItem(true)
            val mime = item.mimetype
            val data = item.data.data
            val headers = mutableMapOf("Access-Control-Allow-Origin" to "*", "Accept-Ranges" to "bytes")
            val range =
                request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Range", true) }
                    ?.value
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            val (type, encoding) = splitMime(mime)
            if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
                val size = data.size
                val start = m.groupValues[1].toIntOrNull() ?: (size - (m.groupValues[2].toIntOrNull() ?: 0)).coerceAtLeast(0)
                val end = if (m.groupValues[1].isNotEmpty()) (m.groupValues[2].toIntOrNull() ?: (size - 1)).coerceAtMost(size - 1) else size - 1
                if (start >= size) return WebResourceResponse(type, encoding, 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
                headers["Content-Range"] = "bytes $start-$end/$size"
                WebResourceResponse(type, encoding, 206, "Partial Content", headers, ByteArrayInputStream(data, start, end - start + 1))
            } else {
                WebResourceResponse(type, encoding, 200, "OK", headers, ByteArrayInputStream(data))
            }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Not in book: $path")
            WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(notFound(path).toByteArray()))
        }
    }

    private fun splitMime(mime: String): Pair<String, String?> {
        val type = mime.substringBefore(';').trim()
        val charset = Regex("charset=([^;]+)").find(mime)?.groupValues?.get(1)
        return type to (charset ?: if (type.startsWith("text/") || type.endsWith("javascript")) "utf-8" else null)
    }

    private fun notFound(path: String) =
        "<!doctype html><meta charset=utf-8><body style='font:16px sans-serif;padding:2rem;color:#666'>This page is not in the book: " +
            path.replace(Regex("[<>&]"), "") + "</body>"

    data class Hit(
        val title: String,
        val path: String,
        val snippet: String?,
    )

    /** Title suggestions (libzim's title index). */
    fun suggest(
        archive: Archive,
        query: String,
        limit: Int = 15,
    ): List<Hit> {
        if (query.isBlank()) return emptyList()
        val searcher = SuggestionSearcher(archive)
        return try {
            val results = searcher.suggest(query).getResults(0, limit)
            buildList {
                while (results.hasNext()) {
                    val s = results.next()
                    add(Hit(s.title, s.path, null))
                }
            }
        } finally {
            searcher.dispose()
        }
    }

    /** Full-text search (the book's Xapian index), with snippets. */
    fun search(
        archive: Archive,
        query: String,
        limit: Int = 30,
    ): List<Hit> {
        if (query.isBlank() || !archive.hasFulltextIndex()) return emptyList()
        val searcher = org.kiwix.libzim.Searcher(archive)
        return try {
            val results = searcher.search(org.kiwix.libzim.Query(query)).getResults(0, limit)
            buildList {
                while (results.hasNext()) {
                    val entry = results.next()
                    // After next() the iterator points at the entry it returned
                    val snippet =
                        try {
                            results.snippet
                        } catch (_: Exception) {
                            null
                        }
                    add(Hit(entry.title, entry.path, snippet?.replace(Regex("<[^>]+>"), "")?.trim()?.ifEmpty { null }))
                }
            }
        } finally {
            searcher.dispose()
        }
    }
}
