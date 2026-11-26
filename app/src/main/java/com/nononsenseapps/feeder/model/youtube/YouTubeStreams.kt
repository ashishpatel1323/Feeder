package com.nononsenseapps.feeder.model.youtube

import android.content.SharedPreferences
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A video's streams straight from YouTube's player endpoint, the way Flow starts videos: one
 * request (well under a second) as an app client whose stream addresses need no deciphering and
 * no PO token, instead of running yt-dlp (15 to 20 s on a phone). yt-dlp stays the fallback.
 *
 * Results are kept in memory until shortly before their addresses expire, so a video prefetched
 * when its row shows (or its page opens) starts at once.
 */
class YouTubeStreams(
    override val di: DI,
) : DIAware {
    private val okHttpClient: OkHttpClient by instance()
    private val applicationCoroutineScope: ApplicationCoroutineScope by instance()
    private val prefs: SharedPreferences by instance()

    private val client by lazy {
        okHttpClient
            .newBuilder()
            .callTimeout(PER_CLIENT_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, VideoDetails>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<VideoDetails?>>()

    // Prefetches stay in the background: at most two at a time
    private val prefetchPermits = Semaphore(2)

    /** Kept streams of [videoId] that have not expired yet. */
    fun cached(videoId: String): VideoDetails? {
        val (until, details) = cache[videoId] ?: return null
        if (System.currentTimeMillis() < until) return details
        cache.remove(videoId)
        return null
    }

    /** The streams of [videoId], or null when no app client gives any (age-restricted, private…). */
    suspend fun details(videoId: String): VideoDetails? {
        cached(videoId)?.let { return it }
        val running =
            inFlight.computeIfAbsent(videoId) {
                applicationCoroutineScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        fetch(videoId)
                    } finally {
                        inFlight.remove(videoId)
                    }
                }
            }
        return running.await()
    }

    /** Fetches [videoIds] in the background, so they start at once when opened. */
    fun prefetch(videoIds: List<String>) {
        for (id in videoIds.distinct()) {
            if (id.isBlank() || cached(id) != null || inFlight.containsKey(id)) continue
            applicationCoroutineScope.launch(Dispatchers.IO) {
                prefetchPermits.withPermit { details(id) }
            }
        }
    }

    /** Opens the connection to YouTube ahead of the first video (DNS and TLS), off the main thread. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        applicationCoroutineScope.launch(Dispatchers.IO) {
            try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url("https://www.youtube.com/generate_204")
                            .header("User-Agent", CLIENTS.first().userAgent)
                            .build(),
                    ).execute()
                    .close()
                visitor(fresh = false)
            } catch (e: Exception) {
                warmed = false
            }
        }
    }

    @Volatile
    private var warmed = false

    /** Streams that stopped playing: the next open asks again. */
    fun forget(videoId: String) {
        cache.remove(videoId)
    }

    private suspend fun fetch(videoId: String): VideoDetails? =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            // Likes and the upload date are only in the web client's answer: asked alongside, outside
            // this scope, so a slow answer never holds up the streams
            val extras = applicationCoroutineScope.async(Dispatchers.IO) { webExtras(videoId) }
            // YouTube asks unknown visitors to sign in ("not a bot"): once, again as a new visitor
            for (attempt in 0..1) {
                val visitor = visitor(fresh = attempt > 0)
                var walled = false
                for (c in CLIENTS) {
                    // A cold start pays DNS and TLS inside the first request: one retry on the warm connection
                    val response = request(c, videoId, visitor) ?: if (c === CLIENTS.first()) request(c, videoId, visitor) else null
                    if (response != null && isBotWall(response)) walled = true
                    val details = response?.let { parse(videoId, it, c) } ?: continue
                    val meta = withTimeoutOrNull(EXTRAS_WAIT_MS) { extras.await() }
                    extras.cancel()
                    val full = details.copy(likeCount = meta?.likes, uploadDate = meta?.uploadDate)
                    val until = keepUntil(response)
                    if (until > System.currentTimeMillis()) cache[videoId] = until to full
                    YouTubeTiming.log(videoId, "streams via ${c.name} in ${System.currentTimeMillis() - started} ms")
                    return@withContext full
                }
                if (!walled) break
                YouTubeTiming.log(videoId, "bot check: trying again as a new visitor")
            }
            extras.cancel()
            YouTubeTiming.log(videoId, "no app client gave streams (${System.currentTimeMillis() - started} ms)")
            null
        }

    @Volatile
    private var visitorData: String? = null
    private val visitorLock = Any()

    /**
     * The visitor id sent with every request, like the YouTube apps do: without one, quick
     * requests soon get "Sign in to confirm you're not a bot". [fresh] replaces a walled one.
     */
    private fun visitor(fresh: Boolean): String? =
        synchronized(visitorLock) {
            if (!fresh) {
                visitorData?.let { return it }
                // Kept between app starts: the first video after a start skips minting one
                prefs.getString(PREF_VISITOR, null)?.let {
                    visitorData = it
                    return it
                }
            }
            val minted = mintVisitor()
            if (minted != null || fresh) {
                visitorData = minted
                prefs.edit().putString(PREF_VISITOR, minted).apply()
            }
            visitorData
        }

    private fun mintVisitor(): String? =
        try {
            val payload =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", WEB_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                        }
                    }
                }
            val request =
                Request
                    .Builder()
                    .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                    .header("User-Agent", WEB_USER_AGENT)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val o = json.parseToJsonElement(res.body.string()) as? JsonObject
                o
                    ?.obj("responseContext")
                    ?.str("visitorData")
                    ?.takeIf { it.startsWith("Cg") }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No visitor id: ${e.message}")
            null
        }

    private fun request(
        c: Client,
        videoId: String,
        visitor: String?,
    ): JsonObject? =
        try {
            val payload =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", c.name)
                            put("clientVersion", c.version)
                            c.context.forEach { (k, v) -> put(k, v) }
                            put("hl", "en")
                            put("gl", "US")
                            visitor?.let { put("visitorData", it) }
                        }
                    }
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
            val request =
                Request
                    .Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", c.userAgent)
                    .header("X-YouTube-Client-Name", c.id)
                    .header("X-YouTube-Client-Version", c.version)
                    .apply { visitor?.let { header("X-Goog-Visitor-Id", it) } }
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(LOG_TAG, "${c.name} player: HTTP ${res.code}")
                    null
                } else {
                    json.parseToJsonElement(res.body.string()) as? JsonObject
                }
            }
        } catch (e: InterruptedIOException) {
            Log.w(LOG_TAG, "${c.name} player timed out")
            null
        } catch (e: Exception) {
            Log.w(LOG_TAG, "${c.name} player failed: ${e.message}")
            null
        }

    private fun webExtras(videoId: String): WebExtras? =
        try {
            val payload =
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB")
                            put("clientVersion", WEB_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                        }
                    }
                    put("videoId", videoId)
                }
            val request =
                Request
                    .Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", WEB_USER_AGENT)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val o = json.parseToJsonElement(res.body.string()) as? JsonObject ?: return null
                parseWebExtras(o)
            }
        } catch (e: Exception) {
            null
        }

    /** An InnerTube app client. */
    data class Client(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val context: Map<String, String> = emptyMap(),
    )

    data class WebExtras(
        val likes: Long?,
        /** yyyyMMdd, like yt-dlp's upload_date. */
        val uploadDate: String?,
    )

    companion object {
        private const val LOG_TAG = "FEEDER_YT_STREAMS"
        private const val PREF_VISITOR = "youtube_visitor_data"

        /**
         * For comparing timings: `adb shell setprop debug.feeder.yt_slow 1` plays like before
         * (yt-dlp only, ExoPlayer's default buffering). Back with `setprop … 0`. Only adb can set
         * debug.* properties.
         */
        fun slowMode(): Boolean =
            try {
                Class
                    .forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "debug.feeder.yt_slow", "0") == "1"
            } catch (e: Exception) {
                false
            }

        private const val PER_CLIENT_TIMEOUT_S = 6L
        private const val EXTRAS_WAIT_MS = 400L

        /** Addresses are dropped this long before YouTube says they expire. */
        private const val EXPIRY_MARGIN_MS = 30 * 60 * 1000L
        private const val WEB_VERSION = "2.20251120.01.00"
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Tried in order. visionOS first, as in Flow: direct addresses with no PO token and no
         * signature to decipher. Android VR (Quest) is the token-free fallback.
         */
        val CLIENTS =
            listOf(
                Client(
                    name = "VISIONOS",
                    version = "1.02",
                    id = "101",
                    userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                    context =
                        mapOf(
                            "osName" to "visionOS",
                            "osVersion" to "26.5.23O471",
                            "deviceMake" to "Apple",
                            "deviceModel" to "RealityDevice17,1",
                        ),
                ),
                Client(
                    name = "ANDROID_VR",
                    version = "1.65.10",
                    id = "28",
                    userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                    context =
                        mapOf(
                            "osName" to "Android",
                            "osVersion" to "12L",
                            "deviceMake" to "Oculus",
                            "deviceModel" to "Quest 3",
                            "androidSdkVersion" to "32",
                        ),
                ),
            )

        /** Until when [response]'s addresses are worth keeping: their `expire`, less a margin. */
        fun keepUntil(
            response: JsonObject,
            now: Long = System.currentTimeMillis(),
        ): Long {
            val streaming = response.obj("streamingData")
            val firstUrl =
                (streaming?.arr("adaptiveFormats").orEmpty() + streaming?.arr("formats").orEmpty())
                    .firstNotNullOfOrNull { (it as? JsonObject)?.str("url") }
            val expire =
                firstUrl
                    ?.let {
                        Regex("""[?&]expire=(\d+)""")
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    }?.let { it * 1000 }
                    ?: streaming?.str("expiresInSeconds")?.toLongOrNull()?.let { now + it * 1000 }
                    ?: (now + 60 * 60 * 1000L)
            // Already within the margin: not kept at all
            return expire - EXPIRY_MARGIN_MS
        }

        /** The player endpoint's answer as [VideoDetails], or null when it gives no playable streams. */
        fun parse(
            videoId: String,
            o: JsonObject,
            c: Client,
        ): VideoDetails? {
            val status = o.obj("playabilityStatus")?.str("status")
            if (status != "OK") {
                Log.w(LOG_TAG, "${c.name}: $status ${o.obj("playabilityStatus")?.str("reason") ?: ""}")
                return null
            }
            val streaming = o.obj("streamingData") ?: return null
            val adaptive = streaming.arr("adaptiveFormats").mapNotNull { it as? JsonObject }.filter { it.str("url") != null }
            val muxed = streaming.arr("formats").mapNotNull { it as? JsonObject }.filter { it.str("url") != null }
            val vd = o.obj("videoDetails")
            val duration = vd?.str("lengthSeconds")?.toDoubleOrNull() ?: 0.0
            val description = vd?.str("shortDescription") ?: ""
            val details =
                VideoDetails(
                    id = vd?.str("videoId") ?: videoId,
                    title = vd?.str("title") ?: "",
                    channel = vd?.str("author") ?: "",
                    channelFollowers = null,
                    viewCount = vd?.str("viewCount")?.toLongOrNull(),
                    likeCount = null,
                    uploadDate = null,
                    description = description,
                    duration = duration,
                    thumbnail =
                        vd
                            ?.obj("thumbnail")
                            ?.arr("thumbnails")
                            ?.lastOrNull()
                            ?.let { it as? JsonObject }
                            ?.str("url"),
                    chapters = chaptersFromDescription(description, duration),
                    videos = videoStreams(adaptive),
                    audios = audioStreams(adaptive),
                    muxedUrl =
                        muxed
                            .filter { it.str("mimeType")?.startsWith("video/mp4") == true }
                            .maxByOrNull { it.int("height") ?: 0 }
                            ?.str("url"),
                    subtitles = subtitles(o),
                    userAgent = c.userAgent,
                    source = c.name,
                )
            return details.takeIf { it.hasStreams }
        }

        // H.264 in MP4: every phone plays it, and ExoPlayer starts it without a manifest
        private fun videoStreams(formats: List<JsonObject>): List<VideoStream> =
            formats
                .filter { f ->
                    val mime = f.str("mimeType") ?: ""
                    mime.startsWith("video/mp4") && mime.contains("avc1")
                }.mapNotNull { f ->
                    val height = f.int("height") ?: return@mapNotNull null
                    if (height > VideoDetails.MAX_HEIGHT) return@mapNotNull null
                    height to f
                }.groupBy { it.first }
                .map { (height, list) ->
                    val best = list.maxBy { it.second.long("bitrate") ?: 0L }.second
                    VideoStream(height, best.str("url")!!, best.int("itag")?.toString() ?: "")
                }.sortedByDescending { it.height }

        private fun audioStreams(formats: List<JsonObject>): List<AudioStream> =
            formats
                .filter { it.str("mimeType")?.startsWith("audio/mp4") == true }
                .groupBy { it.obj("audioTrack")?.str("id")?.substringBefore(".") ?: "" }
                .map { (language, list) ->
                    val best = list.maxBy { it.long("bitrate") ?: 0L }
                    val track = best.obj("audioTrack")
                    val label = track?.str("displayName") ?: ""
                    AudioStream(
                        url = best.str("url")!!,
                        formatId = best.int("itag")?.toString() ?: "",
                        language = language,
                        label = label.ifBlank { language.ifBlank { "Default" } },
                        isOriginal = track == null || track.bool("audioIsDefault") || label.contains("original"),
                    )
                }.sortedByDescending { it.isOriginal }

        private fun subtitles(o: JsonObject): List<SubtitleTrack> {
            val tracks =
                o
                    .obj("captions")
                    ?.obj("playerCaptionsTracklistRenderer")
                    ?.arr("captionTracks")
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
            return tracks.mapNotNull { t ->
                val url = t.str("baseUrl") ?: return@mapNotNull null
                val language = t.str("languageCode") ?: return@mapNotNull null
                val name = t.obj("name")
                val label =
                    name?.str("simpleText")
                        ?: name?.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text") ?: "" }
                        ?: language
                val automatic = t.str("kind") == "asr"
                SubtitleTrack(
                    // yt-dlp's names: the spoken language's automatic captions are "<lang>-orig"
                    language = if (automatic) "$language-orig" else language,
                    label = label,
                    url = url.replace(Regex("""&fmt=[^&]*"""), "") + "&fmt=vtt",
                    automatic = automatic,
                )
            }
        }

        private val CHAPTER_LINE = Regex("""^\s*[(\[]?((?:\d{1,2}:)?\d{1,2}:\d{2})[)\]]?\s*[-–—:|.]?\s*(.+?)\s*$""")

        /**
         * YouTube's chapters are the description's timestamp lines (yt-dlp reads them the same way):
         * at least three, the first at 0:00, each later than the one before.
         */
        fun chaptersFromDescription(
            description: String,
            duration: Double,
        ): List<Chapter> {
            val marks =
                description
                    .lineSequence()
                    .mapNotNull { line ->
                        val m = CHAPTER_LINE.find(line) ?: return@mapNotNull null
                        val parts = m.groupValues[1].split(":").map { it.toInt() }
                        val seconds = parts.fold(0) { acc, p -> acc * 60 + p }.toDouble()
                        seconds to m.groupValues[2]
                    }.toList()
            if (marks.size < 3 || marks.first().first != 0.0) return emptyList()
            if (marks.zipWithNext().any { (a, b) -> b.first <= a.first }) return emptyList()
            if (duration > 0 && marks.last().first >= duration) return emptyList()
            return marks.mapIndexed { i, (start, title) ->
                Chapter(start, marks.getOrNull(i + 1)?.first ?: duration.takeIf { it > 0 } ?: start, title)
            }
        }

        /** "Sign in to confirm you're not a bot": YouTube distrusts this visitor, not the video. */
        fun isBotWall(o: JsonObject): Boolean {
            val status = o.obj("playabilityStatus") ?: return false
            return status.str("status") == "LOGIN_REQUIRED" && status.str("reason")?.contains("bot", ignoreCase = true) == true
        }

        fun parseWebExtras(o: JsonObject): WebExtras? {
            val micro = o.obj("microformat")?.obj("playerMicroformatRenderer") ?: return null
            val date = (micro.str("publishDate") ?: micro.str("uploadDate"))?.take(10)?.replace("-", "")
            return WebExtras(
                likes = micro.str("likeCount")?.toLongOrNull(),
                uploadDate = date?.takeIf { it.length == 8 && it.all(Char::isDigit) },
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject.arr(key: String): List<kotlinx.serialization.json.JsonElement> = (this[key] as? JsonArray).orEmpty()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
    }
}
