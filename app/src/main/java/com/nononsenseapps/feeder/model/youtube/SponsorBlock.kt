package com.nononsenseapps.feeder.model.youtube

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A part of a video that SponsorBlock users marked as a sponsor read (or similar). */
@Immutable
data class SkipSegment(
    val start: Double,
    val end: Double,
    val category: String,
    val uuid: String,
)

/**
 * SponsorBlock (sponsor.ajay.app), as in Flow and NewPipe: segments to skip. Asks with the
 * first characters of the video id's hash, so the server doesn't learn which video is watched.
 */
class SponsorBlock(
    private val okHttpClient: OkHttpClient,
) {
    private val cache = ConcurrentHashMap<String, List<SkipSegment>>()

    suspend fun segments(videoId: String): List<SkipSegment> {
        cache[videoId]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val prefix = sha256(videoId).take(4)
                val url =
                    "https://sponsor.ajay.app/api/skipSegments/$prefix"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("categories", CATEGORIES)
                        .build()
                val found =
                    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        // 404 = nothing marked
                        val body = if (response.isSuccessful) response.body?.string() else null
                        parse(body ?: "[]", videoId)
                    }
                cache[videoId] = found
                found
            } catch (e: Exception) {
                // Not cached: tried again next time
                Log.w(LOG_TAG, "SponsorBlock failed: ${e.message}")
                emptyList()
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_SPONSORBLOCK"

        /** Sponsor reads, self promotion and "like and subscribe" reminders. */
        private const val CATEGORIES = """["sponsor","selfpromo","interaction"]"""

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(
            text: String,
            videoId: String,
        ): List<SkipSegment> {
            val videos = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return videos
                .mapNotNull { it as? JsonObject }
                .filter { (it["videoID"] as? JsonPrimitive)?.content == videoId }
                .flatMap { (it["segments"] as? JsonArray).orEmpty() }
                .mapNotNull { s ->
                    val o = s as? JsonObject ?: return@mapNotNull null
                    val action = (o["actionType"] as? JsonPrimitive)?.content ?: "skip"
                    if (action != "skip") return@mapNotNull null
                    val range = o["segment"] as? JsonArray ?: return@mapNotNull null
                    val start = (range.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val end = (range.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    if (end <= start) return@mapNotNull null
                    SkipSegment(
                        start = start,
                        end = end,
                        category = (o["category"] as? JsonPrimitive)?.content ?: "",
                        uuid = (o["UUID"] as? JsonPrimitive)?.content ?: "$start",
                    )
                }.sortedBy { it.start }
        }

        private fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
