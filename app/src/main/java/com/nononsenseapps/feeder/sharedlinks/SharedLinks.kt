package com.nononsenseapps.feeder.sharedlinks

import android.util.Log
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.blob.blobOutputStream
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.db.room.MAX_SNIPPET_LENGTH
import com.nononsenseapps.feeder.db.room.MAX_TITLE_LENGTH
import com.nononsenseapps.feeder.db.room.Sync2Dao
import com.nononsenseapps.feeder.model.ImageFromHTML
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.util.FilePathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Shared links: any web page or YouTube video shared to Feeder. They become saved articles of
 * a built-in source that is not a real feed, and sync as ordinary saved records, the same as
 * the web app (web/src/lib/share.ts).
 */

/** The "feed" every shared link belongs to. Never fetched (.invalid never resolves). Same on the web. */
const val SHARED_FEED_URL_STRING = "https://shared.feeder.invalid/"
val SHARED_FEED_URL = URL(SHARED_FEED_URL_STRING)

/** retry_after so far ahead that refreshing never picks the shared links source. */
private val NEVER: Instant = Instant.parse("9999-12-31T00:00:00Z")

fun isSharedFeed(url: URL?): Boolean = url?.toString() == SHARED_FEED_URL_STRING

private val LINK = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
private val BARE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$", RegexOption.IGNORE_CASE)
private val TRACKING = Regex("^(utm_.*|fbclid|gclid|mc_cid|mc_eid|ref|ref_src|igshid|si)$", RegexOption.IGNORE_CASE)

/** The first http(s) link in shared text ("Look at this https://…"), or null. */
fun findLink(text: String?): String? {
    if (text.isNullOrBlank()) return null
    LINK.find(text)?.let { return it.value.trimEnd(')', ',', '.', ';', '!', '?') }
    val t = text.trim()
    return if (BARE.matches(t)) "https://$t" else null
}

/** Drops tracking parameters and the fragment, and gives every YouTube link one form, like the web app. */
fun normalizeLink(link: String): String {
    youtubeVideoId(link)?.let { return youtubeWatchUrl(it) }
    return try {
        val uri = URI(link)
        val query =
            uri.rawQuery
                ?.split('&')
                ?.filter { it.isNotEmpty() && !TRACKING.matches(it.substringBefore('=')) }
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(path)
            if (query != null) append('?').append(query)
        }
    } catch (_: Exception) {
        link
    }
}

fun siteOf(link: String?): String =
    try {
        URI(link ?: "").host?.removePrefix("www.") ?: ""
    } catch (_: Exception) {
        ""
    }

data class LinkInfo(
    val title: String,
    val snippet: String,
    val imageUrl: String?,
    val author: String?,
)

sealed class SaveResult {
    data class Saved(
        val itemId: Long,
        val link: String,
        val info: LinkInfo,
        val existed: Boolean,
    ) : SaveResult()

    data class NotALink(
        val text: String?,
    ) : SaveResult()
}

class SharedLinks(
    override val di: DI,
) : DIAware {
    private val repository: Repository by instance()
    private val itemDao: FeedItemDao by instance()
    private val sync2Dao: Sync2Dao by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val filePathProvider: FilePathProvider by instance()

    /** The shared links source, created the first time. */
    suspend fun sharedFeedId(): Long {
        repository.getFeed(SHARED_FEED_URL)?.let { return it.id }
        return repository.saveFeed(
            Feed(
                url = SHARED_FEED_URL,
                title = "Shared links",
                // Shared pages open as the full article
                fullTextByDefault = true,
                retryAfter = NEVER,
                lastSync = Instant.now(),
            ),
        )
    }

    /** Fetches the title (and picture) of the shared link and saves it. Network errors still save the bare link. */
    suspend fun save(text: String?): SaveResult {
        val found = findLink(text) ?: return SaveResult.NotALink(text)
        val link = normalizeLink(found)
        val feedId = sharedFeedId()
        repository.loadFeedItem(guid = link, feedId = feedId)?.let { existing ->
            // Shared again: save it again and move it to the top
            existing.bookmarked = true
            existing.readTime = null
            existing.primarySortTime = Instant.now()
            itemDao.updateFeedItem(existing)
            sync2Dao.deleteHidden(SHARED_FEED_URL_STRING, link)
            return SaveResult.Saved(existing.id, link, LinkInfo(existing.plainTitle, existing.plainSnippet, existing.thumbnailImage?.url, existing.author), existed = true)
        }
        val info =
            try {
                withContext(Dispatchers.IO) { fetchInfo(link) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not read $link", e)
                LinkInfo(title = siteOf(link).ifEmpty { link }, snippet = "", imageUrl = null, author = null)
            }
        val id = insert(feedId, link, info, Instant.now(), read = false)
        return SaveResult.Saved(id, link, info, existed = false)
    }

    /** A shared link saved on another device, which this one has never seen. */
    suspend fun addFromSync(
        link: String,
        info: LinkInfo,
        sortTime: Instant,
        read: Boolean,
    ) {
        if (link in sync2Dao.getHiddenGuids(SHARED_FEED_URL_STRING)) return
        val feedId = sharedFeedId()
        if (repository.loadFeedItem(guid = link, feedId = feedId) != null) return
        insert(feedId, link, info, sortTime, read)
    }

    private suspend fun insert(
        feedId: Long,
        link: String,
        info: LinkInfo,
        sortTime: Instant,
        read: Boolean,
    ): Long {
        val title = info.title.take(MAX_TITLE_LENGTH)
        val item =
            FeedItem(
                id = ID_UNSET,
                guid = link,
                title = title,
                plainTitle = title,
                plainSnippet = info.snippet.take(MAX_SNIPPET_LENGTH),
                thumbnailImage = info.imageUrl?.let { ImageFromHTML(url = it) },
                author = info.author,
                pubDate = ZonedDateTime.ofInstant(sortTime, ZoneId.systemDefault()),
                link = link,
                feedId = feedId,
                firstSyncedTime = Instant.now(),
                primarySortTime = sortTime,
                bookmarked = true,
                readTime = if (read) Instant.now() else null,
            )
        val id = itemDao.insertFeedItem(item)
        // Until the full article is fetched in the reader, the description shows
        filePathProvider.articleDir.mkdirs()
        blobOutputStream(id, filePathProvider.articleDir).bufferedWriter().use {
            it.write(if (info.snippet.isNotBlank()) "<p>${escapeHtml(info.snippet)}</p>" else "")
        }
        return id
    }

    private fun fetchInfo(link: String): LinkInfo {
        val videoId = youtubeVideoId(link)
        if (videoId != null) {
            val fallback = LinkInfo("YouTube video", "", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", null)
            val oembed = "https://www.youtube.com/oembed?format=json&url=${URLEncoder.encode(link, "UTF-8")}"
            return try {
                val o = JSONObject(get(oembed))
                LinkInfo(
                    title = o.optString("title").ifBlank { fallback.title },
                    snippet = "",
                    imageUrl = o.optString("thumbnail_url").ifBlank { fallback.imageUrl },
                    author = o.optString("author_name").ifBlank { null },
                )
            } catch (e: Exception) {
                fallback
            }
        }
        val doc = Jsoup.parse(get(link), link)

        fun meta(vararg names: String): String? =
            names.firstNotNullOfOrNull { n ->
                doc
                    .selectFirst("meta[property=$n], meta[name=$n]")
                    ?.attr("content")
                    ?.trim()
                    ?.ifEmpty { null }
            }
        val image =
            meta("og:image", "og:image:url", "twitter:image")?.let {
                try {
                    URL(URL(link), it).toString()
                } catch (_: Exception) {
                    null
                }
            }
        return LinkInfo(
            title = meta("og:title", "twitter:title") ?: doc.title().trim().ifEmpty { siteOf(link) },
            snippet = meta("og:description", "description", "twitter:description") ?: doc.select("p").firstOrNull { it.text().length > 80 }?.text() ?: "",
            imageUrl = image,
            author = meta("author", "article:author") ?: meta("og:site_name"),
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return res.body?.string() ?: ""
        }
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val LOG_TAG = "FEEDER_SHARED"
    }
}
