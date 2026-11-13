package com.nononsenseapps.feeder.model

import android.util.Log
import com.nononsenseapps.feeder.archmodel.Repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Looks up how popular an article's link is on Hacker News, using the free and keyless
 * Algolia search API (https://hn.algolia.com/api). Only runs when the user has turned on
 * "Show Hacker News score", since each lookup sends the article link to hn.algolia.com.
 *
 * Scores are stored on the feed item. Network failures leave the item unchecked so it is
 * retried on the next sync; "not on Hacker News" is stored as a check with no points.
 */
class HackerNewsScores(
    override val di: DI,
) : DIAware {
    private val repository: Repository by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val mutex = Mutex()

    /**
     * Checks recent articles that have never been checked, or whose score is older than
     * [REFRESH_INTERVAL]. Called after each sync. Blocking network calls, so call it from a
     * background dispatcher.
     */
    suspend fun refreshRecent(limit: Int = MAX_PER_REFRESH) {
        if (!repository.showHnScore.value) {
            return
        }
        mutex.withLock {
            if (!enabled) {
                return
            }
            val now = Instant.now()
            val items =
                repository.getItemsNeedingHnScore(
                    since = now.minus(MAX_ARTICLE_AGE),
                    staleBefore = now.minus(REFRESH_INTERVAL),
                    limit = limit,
                )
            for (item in items) {
                if (!enabled) {
                    // Turned off while refreshing, stop sending links
                    break
                }
                val link = item.link ?: continue
                if (!refresh(id = item.id, link = link, now = now)) {
                    // Probably offline or rate limited, try again on next sync
                    break
                }
            }
        }
    }

    /**
     * Refreshes a single article's score when it is opened, if the stored score is older
     * than [OPEN_REFRESH_INTERVAL].
     */
    suspend fun refreshIfStale(
        id: Long,
        link: String?,
    ) {
        if (!repository.showHnScore.value || link.isNullOrBlank()) {
            return
        }
        val now = Instant.now()
        val checked = repository.getHnCheckedTime(id) ?: return
        if (checked.isAfter(now.minus(OPEN_REFRESH_INTERVAL))) {
            return
        }
        mutex.withLock {
            if (enabled) {
                refresh(id = id, link = link, now = now)
            }
        }
    }

    /**
     * Removes all stored scores. Holds the same lock as the lookups so an in-flight
     * lookup can't write a score back after the feature was turned off.
     */
    suspend fun clearAll() {
        mutex.withLock {
            repository.clearHnScores()
        }
    }

    private val enabled: Boolean
        get() = repository.showHnScore.value

    /**
     * Returns false if the lookup failed (network error), true otherwise.
     */
    private suspend fun refresh(
        id: Long,
        link: String,
        now: Instant,
    ): Boolean {
        val story =
            try {
                lookup(link)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Hacker News lookup failed: ${e.message}")
                return false
            }
        if (!enabled) {
            // Turned off during the lookup, don't store anything
            return false
        }
        repository.updateHnScore(
            id = id,
            points = story?.points,
            comments = story?.comments,
            hnId = story?.id,
            checkedTime = now,
        )
        return true
    }

    private fun lookup(link: String): HnStory? {
        val query = hnSearchQueryFor(link) ?: return null
        val url =
            HttpUrl
                .Builder()
                .scheme("https")
                .host("hn.algolia.com")
                .addPathSegments("api/v1/search")
                .addQueryParameter("query", query)
                .addQueryParameter("restrictSearchableAttributes", "url")
                .addQueryParameter("tags", "story")
                .addQueryParameter("hitsPerPage", "20")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            parseHnSearchResponse(json = response.body.string(), link = link)
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_HN"

        // Lookups per sync. Algolia allows about 10 000 requests an hour per IP.
        const val MAX_PER_REFRESH = 40

        // Only articles this new get checked during sync
        val MAX_ARTICLE_AGE: Duration = Duration.ofDays(3)

        // How long a score is kept before it is checked again during sync
        val REFRESH_INTERVAL: Duration = Duration.ofHours(3)

        // How long a score is kept before it is checked again when opening the article
        val OPEN_REFRESH_INTERVAL: Duration = Duration.ofMinutes(30)
    }
}

data class HnStory(
    val id: String,
    val points: Int,
    val comments: Int,
)

fun hackerNewsDiscussionUrl(hnId: String): String = "https://news.ycombinator.com/item?id=$hnId"

/**
 * Short form of a points count, like Inoreader: 999, 1.2k, 15k.
 */
fun formatHnPoints(points: Int): String =
    when {
        points < 1000 -> points.toString()
        points < 10_000 -> String.format(Locale.ROOT, "%.1fk", points / 1000.0).replace(".0k", "k")
        else -> "${points / 1000}k"
    }

private val trackingParams = setOf("fbclid", "gclid", "mc_cid", "mc_eid", "ref", "ref_src", "cmpid", "ncid", "guccounter")

/**
 * Normalizes a link so that the same article submitted with small URL differences matches:
 * ignores scheme, "www.", trailing slashes, fragments and common tracking parameters.
 * Returns null for links that can't be looked up.
 */
internal fun normalizeLinkForHn(link: String?): String? {
    if (link.isNullOrBlank()) {
        return null
    }
    val uri =
        try {
            URI(link.trim())
        } catch (_: Exception) {
            return null
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return null
    }
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    if (host == "news.ycombinator.com") {
        // Links to Hacker News itself, nothing to look up
        return null
    }
    val path = (uri.rawPath ?: "").trimEnd('/')
    val query =
        uri.rawQuery
            ?.split('&')
            ?.filter { param ->
                val name = param.substringBefore('=').lowercase()
                param.isNotBlank() && !name.startsWith("utm_") && name !in trackingParams
            }?.joinToString("&")
            ?.takeIf { it.isNotEmpty() }
    return buildString {
        append(host)
        append(path)
        if (query != null) {
            append('?')
            append(query)
        }
    }
}

/**
 * Search text sent to Algolia: host and path only. Query parameters are left out because
 * Algolia requires every word to match and tracking parameters are rarely in submissions.
 */
internal fun hnSearchQueryFor(link: String?): String? = normalizeLinkForHn(link)?.substringBefore('?')

/**
 * Picks the most upvoted submission whose URL is the same article as [link]. Tracking
 * parameters are ignored, other query parameters must match since some sites use them
 * to identify articles (like ?id=5).
 */
internal fun parseHnSearchResponse(
    json: String,
    link: String,
): HnStory? {
    val target = normalizeLinkForHn(link) ?: return null
    val hits =
        try {
            Json.parseToJsonElement(json).jsonObject["hits"]?.jsonArray
        } catch (_: Exception) {
            null
        } ?: return null
    return hits
        .mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val hitUrl = normalizeLinkForHn(hit["url"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
            // Must be the same page, including query parameters that identify the article
            if (hitUrl != target) {
                return@mapNotNull null
            }
            val id = hit["objectID"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            HnStory(
                id = id,
                points = hit["points"]?.jsonPrimitive?.intOrNull ?: 0,
                comments = hit["num_comments"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.maxByOrNull { it.points }
}
