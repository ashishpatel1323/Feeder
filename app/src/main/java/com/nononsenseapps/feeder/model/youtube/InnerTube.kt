package com.nononsenseapps.feeder.model.youtube

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What YouTube's player endpoint says about a video, for subscription rows. */
@Immutable
data class VideoMeta(
    val videoId: String,
    /** Seconds; 0 for live and upcoming streams. */
    val duration: Int,
    val views: Long?,
    val likes: Long?,
    val isLive: Boolean,
)

/** A video in the "related" list under the player. */
@Immutable
data class RelatedVideo(
    val videoId: String,
    val title: String,
    val thumbnail: String?,
    /** "12:34", or "" (live, or not given). */
    val durationText: String,
    val channel: String,
    val channelAvatar: String?,
    /** "43K views", as YouTube words it. */
    val viewsText: String,
    /** "3 weeks ago". */
    val ageText: String,
)

@Immutable
data class Comment(
    val id: String,
    val author: String,
    val avatar: String?,
    val text: String,
    val published: String,
    val likes: String,
    val replies: String,
)

/** One page of comments; [next] continues the list (null at the end). */
@Immutable
data class CommentPage(
    val comments: List<Comment>,
    val countText: String?,
    val next: String?,
)

/** The watch page's extras that yt-dlp leaves out or Flow shows: channel, likes, comments, related videos. */
@Immutable
data class WatchNext(
    val likesText: String?,
    val channelName: String?,
    val channelId: String?,
    val channelAvatar: String?,
    val subscribersText: String?,
    val commentsToken: String?,
    val related: List<RelatedVideo>,
)

/**
 * A few calls to YouTube's own web API (InnerTube, the API youtube.com uses), as Flow does,
 * without signing in: the player endpoint for lengths and views, and the next endpoint for the
 * watch page's comments and related videos. Every call may fail: callers hide what is missing.
 */
class InnerTube(
    okHttpClient: OkHttpClient,
) {
    private val client =
        okHttpClient
            .newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    private val noRedirects =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val nextCache = ConcurrentHashMap<String, WatchNext>()

    suspend fun player(videoId: String): VideoMeta? = post("player", buildJsonObject { put("videoId", videoId) })?.let { parsePlayer(videoId, it) }

    suspend fun next(videoId: String): WatchNext? {
        nextCache[videoId]?.let { return it }
        return post("next", buildJsonObject { put("videoId", videoId) })
            ?.let(::parseNext)
            ?.also { nextCache[videoId] = it }
    }

    suspend fun comments(token: String): CommentPage? = post("next", buildJsonObject { put("continuation", token) })?.let(::parseComments)

    /** A Short: youtube.com/shorts/<id> answers itself instead of redirecting to the watch page. */
    suspend fun isShort(videoId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url("https://www.youtube.com/shorts/$videoId")
                        .head()
                        .header("User-Agent", USER_AGENT)
                        .build()
                noRedirects.newCall(request).execute().use { it.code == 200 }
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun post(
        endpoint: String,
        body: JsonObject,
    ): JsonObject? =
        withContext(Dispatchers.IO) {
            try {
                val payload =
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB")
                                put("clientVersion", CLIENT_VERSION)
                                put("hl", "en")
                                put("gl", "US")
                            }
                        }
                        body.forEach { (k, v) -> put(k, v) }
                    }
                val request =
                    Request
                        .Builder()
                        .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                        .header("User-Agent", USER_AGENT)
                        .header("X-YouTube-Client-Name", "1")
                        .header("X-YouTube-Client-Version", CLIENT_VERSION)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                client.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.w(LOG_TAG, "InnerTube $endpoint: HTTP ${res.code}")
                        return@use null
                    }
                    json.parseToJsonElement(res.body?.string() ?: return@use null) as? JsonObject
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "InnerTube $endpoint failed: ${e.message}")
                null
            }
        }

    companion object {
        private const val LOG_TAG = "FEEDER_INNERTUBE"
        private const val CLIENT_VERSION = "2.20251120.01.00"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val json = Json { ignoreUnknownKeys = true }

        fun parsePlayer(
            videoId: String,
            o: JsonObject,
        ): VideoMeta? {
            val details = o.obj("videoDetails") ?: return null
            val micro = o.obj("microformat")?.obj("playerMicroformatRenderer")
            return VideoMeta(
                videoId = videoId,
                duration = details.str("lengthSeconds")?.toIntOrNull() ?: 0,
                views = details.str("viewCount")?.toLongOrNull(),
                likes = micro?.str("likeCount")?.toLongOrNull(),
                isLive = details.bool("isLive") || o.obj("playabilityStatus")?.str("status") == "LIVE_STREAM_OFFLINE",
            )
        }

        fun parseNext(o: JsonObject): WatchNext {
            val watch = o.obj("contents")?.obj("twoColumnWatchNextResults")
            val results =
                watch
                    ?.obj("results")
                    ?.obj("results")
                    ?.arr("contents")
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
            val owner =
                results
                    .firstNotNullOfOrNull { it.obj("videoSecondaryInfoRenderer") }
                    ?.obj("owner")
                    ?.obj("videoOwnerRenderer")
            val likes = findString(o, "likeCountIfIndifferent")
            val commentsToken =
                results
                    .mapNotNull { it.obj("itemSectionRenderer") }
                    .firstOrNull { it.str("sectionIdentifier") == "comment-item-section" || it.str("targetId") == "comments-section" }
                    ?.let { findContinuation(it) }
            val related =
                watch
                    ?.obj("secondaryResults")
                    ?.obj("secondaryResults")
                    ?.arr("results")
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .flatMap { item ->
                        // Sometimes wrapped in an item section
                        item.obj("itemSectionRenderer")?.arr("contents")?.mapNotNull { it as? JsonObject } ?: listOf(item)
                    }.mapNotNull { parseLockup(it) ?: parseCompactVideo(it) }
            return WatchNext(
                likesText = likes,
                channelName = owner?.obj("title")?.let(::text),
                channelId = owner?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId"),
                channelAvatar = owner?.obj("thumbnail")?.let(::bestThumbnail),
                subscribersText = owner?.obj("subscriberCountText")?.let(::text),
                commentsToken = commentsToken,
                related = related,
            )
        }

        fun parseComments(o: JsonObject): CommentPage {
            val items =
                o
                    .arr("onResponseReceivedEndpoints")
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .flatMap { e ->
                        (
                            e.obj("reloadContinuationItemsCommand")?.arr("continuationItems")
                                ?: e.obj("appendContinuationItemsAction")?.arr("continuationItems")
                        ).orEmpty()
                    }.mapNotNull { it as? JsonObject }
            val countText =
                items
                    .firstNotNullOfOrNull { it.obj("commentsHeaderRenderer") }
                    ?.obj("countText")
                    ?.let(::text)
            val keys =
                items.mapNotNull {
                    it
                        .obj("commentThreadRenderer")
                        ?.obj("commentViewModel")
                        ?.obj("commentViewModel")
                        ?.str("commentKey")
                }
            val payloads =
                o
                    .obj("frameworkUpdates")
                    ?.obj("entityBatchUpdate")
                    ?.arr("mutations")
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.obj("payload")?.obj("commentEntityPayload") }
                    .associateBy { it.str("key") ?: "" }
            val comments =
                keys.mapNotNull { key ->
                    val p = payloads[key] ?: return@mapNotNull null
                    val props = p.obj("properties") ?: return@mapNotNull null
                    val author = p.obj("author")
                    val toolbar = p.obj("toolbar")
                    Comment(
                        id = props.str("commentId") ?: key,
                        author = author?.str("displayName") ?: "",
                        avatar = author?.str("avatarThumbnailUrl"),
                        text = props.obj("content")?.str("content") ?: "",
                        published = props.str("publishedTime") ?: "",
                        likes = toolbar?.str("likeCountNotliked")?.trim() ?: "",
                        replies = toolbar?.str("replyCount")?.trim() ?: "",
                    )
                }
            val next = items.lastOrNull()?.obj("continuationItemRenderer")?.let { findContinuation(it) }
            return CommentPage(comments, countText, next)
        }

        private fun parseLockup(o: JsonObject): RelatedVideo? {
            val lockup = o.obj("lockupViewModel") ?: return null
            if (lockup.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
            val id = lockup.str("contentId") ?: return null
            val thumb = lockup.obj("contentImage")?.obj("thumbnailViewModel")
            val meta = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
            val rows =
                meta
                    ?.obj("metadata")
                    ?.obj("contentMetadataViewModel")
                    ?.arr("metadataRows")
                    .orEmpty()
                    .mapNotNull { row ->
                        (row as? JsonObject)?.arr("metadataParts")?.mapNotNull { (it as? JsonObject)?.obj("text")?.str("content") }
                    }
            val second = rows.getOrNull(1).orEmpty()
            return RelatedVideo(
                videoId = id,
                title = meta?.obj("title")?.str("content") ?: return null,
                thumbnail = thumb?.obj("image")?.let(::bestSource),
                durationText = findString(thumb ?: JsonObject(emptyMap()), "text") ?: "",
                channel = rows.firstOrNull()?.firstOrNull() ?: "",
                channelAvatar = meta.obj("image")?.let { findSources(it) }?.let(::bestSourceOf),
                viewsText = second.getOrNull(0)?.let { if (it.endsWith("views") || it.endsWith("watching")) it else "$it views" } ?: "",
                ageText = second.getOrNull(1) ?: "",
            )
        }

        private fun parseCompactVideo(o: JsonObject): RelatedVideo? {
            val v = o.obj("compactVideoRenderer") ?: return null
            return RelatedVideo(
                videoId = v.str("videoId") ?: return null,
                title = v.obj("title")?.let(::text) ?: return null,
                thumbnail = v.obj("thumbnail")?.let(::bestThumbnail),
                durationText = v.obj("lengthText")?.let(::text) ?: "",
                channel = v.obj("longBylineText")?.let(::text) ?: "",
                channelAvatar = v.obj("channelThumbnail")?.let(::bestThumbnail),
                viewsText = v.obj("shortViewCountText")?.let(::text) ?: v.obj("viewCountText")?.let(::text) ?: "",
                ageText = v.obj("publishedTimeText")?.let(::text) ?: "",
            )
        }

        private fun findContinuation(e: JsonElement): String? =
            when (e) {
                is JsonObject -> e.obj("continuationCommand")?.str("token") ?: e.values.firstNotNullOfOrNull { findContinuation(it) }
                is JsonArray -> e.firstNotNullOfOrNull { findContinuation(it) }
                else -> null
            }

        /** The first string value named [key] anywhere under [e], depth first. */
        private fun findString(
            e: JsonElement,
            key: String,
        ): String? =
            when (e) {
                is JsonObject -> {
                    (e[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: (e[key] as? JsonObject)?.str("content")
                        ?: e.values.firstNotNullOfOrNull { findString(it, key) }
                }
                is JsonArray -> e.firstNotNullOfOrNull { findString(it, key) }
                else -> null
            }

        private fun findSources(e: JsonElement): JsonArray? =
            when (e) {
                is JsonObject -> (e["sources"] as? JsonArray) ?: e.values.firstNotNullOfOrNull { findSources(it) }
                is JsonArray -> e.firstNotNullOfOrNull { findSources(it) }
                else -> null
            }

        private fun bestSource(image: JsonObject): String? = (image["sources"] as? JsonArray)?.let(::bestSourceOf)

        private fun bestSourceOf(sources: JsonArray): String? =
            sources
                .mapNotNull { it as? JsonObject }
                .maxByOrNull { (it["width"] as? JsonPrimitive)?.longOrNull ?: 0 }
                ?.str("url")
                ?.let(::absolute)

        private fun bestThumbnail(o: JsonObject): String? = (o["thumbnails"] as? JsonArray)?.let(::bestSourceOf)

        private fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url

        private fun text(o: JsonObject): String? =
            o.str("simpleText")
                ?: o.str("content")
                ?: o.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text") ?: "" }?.ifBlank { null }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
    }
}
