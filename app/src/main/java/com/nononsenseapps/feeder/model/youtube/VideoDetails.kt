package com.nononsenseapps.feeder.model.youtube

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A video stream without sound, one per height. */
@Immutable
data class VideoStream(
    val height: Int,
    val url: String,
    val formatId: String,
)

/** A sound stream: one per language (audio track), the best one of each. */
@Immutable
data class AudioStream(
    val url: String,
    val formatId: String,
    /** "en-US", or "" when YouTube does not say. */
    val language: String,
    /** "English (US) original", from yt-dlp's format note. */
    val label: String,
    val isOriginal: Boolean,
)

@Immutable
data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String,
    val automatic: Boolean,
)

@Immutable
data class Chapter(
    val start: Double,
    val end: Double,
    val title: String,
)

/**
 * What the Flow-style player needs about a YouTube video, from one `yt-dlp -J`: its streams
 * (qualities, audio tracks), subtitles, chapters and the info shown below the video.
 */
@Immutable
data class VideoDetails(
    val id: String,
    val title: String,
    val channel: String,
    val channelFollowers: Long?,
    val viewCount: Long?,
    val likeCount: Long?,
    /** yyyyMMdd */
    val uploadDate: String?,
    val description: String,
    val duration: Double,
    val thumbnail: String?,
    val chapters: List<Chapter>,
    /** Highest first. */
    val videos: List<VideoStream>,
    /** Original first. */
    val audios: List<AudioStream>,
    /** A stream with picture and sound, when there are no separate ones. */
    val muxedUrl: String?,
    val subtitles: List<SubtitleTrack>,
    /** The browser yt-dlp asked as: some streams only play for the same User-Agent. */
    val userAgent: String?,
) {
    val hasStreams: Boolean get() = (videos.isNotEmpty() && audios.isNotEmpty()) || muxedUrl != null

    /** The stream of [height], or the closest lower one (the lowest when none is lower). */
    fun videoFor(height: Int): VideoStream? = videos.firstOrNull { it.height <= height } ?: videos.lastOrNull()

    fun chapterAt(seconds: Double): Chapter? = chapters.lastOrNull { it.start <= seconds }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Heights offered, highest first. Phones don't need more than 1080p. */
        const val MAX_HEIGHT = 1080

        fun parse(text: String): VideoDetails = fromJson(json.parseToJsonElement(text).jsonObject)

        fun fromJson(o: JsonObject): VideoDetails {
            val formats = o.arr("formats").mapNotNull { it as? JsonObject }
            val original = o.str("language")
            return VideoDetails(
                id = o.str("id") ?: "",
                title = o.str("title") ?: "",
                channel = o.str("channel") ?: o.str("uploader") ?: "",
                channelFollowers = o.long("channel_follower_count"),
                viewCount = o.long("view_count"),
                likeCount = o.long("like_count"),
                uploadDate = o.str("upload_date"),
                description = o.str("description") ?: "",
                duration = o.double("duration") ?: 0.0,
                thumbnail = o.str("thumbnail"),
                chapters =
                    o.arr("chapters").mapNotNull { c ->
                        val co = c as? JsonObject ?: return@mapNotNull null
                        val start = co.double("start_time") ?: return@mapNotNull null
                        Chapter(start, co.double("end_time") ?: start, co.str("title") ?: "")
                    },
                videos = videoStreams(formats),
                audios = audioStreams(formats),
                muxedUrl =
                    formats
                        .filter { it.isDirect() && it.hasVideo() && it.hasAudio() && it.str("ext") == "mp4" }
                        .maxByOrNull { it.long("height") ?: 0 }
                        ?.str("url"),
                subtitles = subtitles(o, original),
                userAgent =
                    formats.firstNotNullOfOrNull { (it["http_headers"] as? JsonObject)?.str("User-Agent") }
                        ?: (o["http_headers"] as? JsonObject)?.str("User-Agent"),
            )
        }

        private fun videoStreams(formats: List<JsonObject>): List<VideoStream> =
            formats
                .filter { it.isDirect() && it.hasVideo() && !it.hasAudio() && it.str("ext") == "mp4" }
                .mapNotNull { f ->
                    val height = f.long("height")?.toInt() ?: return@mapNotNull null
                    if (height > MAX_HEIGHT) return@mapNotNull null
                    Triple(height, f, f.str("vcodec")?.startsWith("avc1") == true)
                }.groupBy { it.first }
                .map { (height, list) ->
                    // H.264 plays on every phone; then the highest bitrate
                    val best = list.sortedWith(compareByDescending<Triple<Int, JsonObject, Boolean>> { it.third }.thenByDescending { it.second.double("tbr") ?: 0.0 }).first().second
                    VideoStream(height, best.str("url")!!, best.str("format_id") ?: "")
                }.sortedByDescending { it.height }

        private fun audioStreams(formats: List<JsonObject>): List<AudioStream> =
            formats
                .filter { it.isDirect() && it.hasAudio() && !it.hasVideo() && it.str("ext") == "m4a" }
                .groupBy { it.str("language") ?: "" }
                .map { (language, list) ->
                    val best = list.maxBy { it.double("tbr") ?: it.double("abr") ?: 0.0 }
                    val note = best.str("format_note") ?: ""
                    // yt-dlp notes look like "English (US) original (default), medium"
                    val label = note.substringBefore(",").replace("(default)", "").trim()
                    AudioStream(
                        url = best.str("url")!!,
                        formatId = best.str("format_id") ?: "",
                        language = language,
                        label = label.ifBlank { language.ifBlank { "Default" } },
                        isOriginal = note.contains("original") || note.contains("default"),
                    )
                }.sortedByDescending { it.isOriginal }

        private fun subtitles(
            o: JsonObject,
            original: String?,
        ): List<SubtitleTrack> {
            val manual = tracks(o["subtitles"] as? JsonObject, automatic = false) { true }
            // Automatic captions come in every language YouTube can translate to: keep the video's own
            val auto =
                tracks(o["automatic_captions"] as? JsonObject, automatic = true) { lang ->
                    lang.endsWith("-orig") || (original != null && lang == original)
                }.filter { a -> manual.none { it.language == a.language.removeSuffix("-orig") } }
                    .distinctBy { it.language.removeSuffix("-orig") }
            return manual + auto
        }

        private fun tracks(
            map: JsonObject?,
            automatic: Boolean,
            keep: (String) -> Boolean,
        ): List<SubtitleTrack> =
            map
                ?.entries
                ?.filter { (lang, _) -> lang != "live_chat" && keep(lang) }
                ?.mapNotNull { (lang, list) ->
                    val entries = (list as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
                    val vtt = entries.firstOrNull { it.str("ext") == "vtt" } ?: return@mapNotNull null
                    val name = vtt.str("name") ?: entries.firstNotNullOfOrNull { it.str("name") } ?: lang
                    SubtitleTrack(
                        language = lang,
                        label = name,
                        url = vtt.str("url") ?: return@mapNotNull null,
                        automatic = automatic,
                    )
                }.orEmpty()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray)?.jsonArray.orEmpty()

        private fun JsonObject.hasVideo(): Boolean = str("vcodec").let { it != null && it != "none" }

        private fun JsonObject.hasAudio(): Boolean = str("acodec").let { it != null && it != "none" }

        // Plain https files play straight in ExoPlayer; HLS/DASH manifests and storyboards don't here
        private fun JsonObject.isDirect(): Boolean = str("protocol").let { it == "https" || it == "http" } && str("url") != null

        /** "1.2M" style counts. */
        fun compactCount(n: Long): String =
            when {
                n >= 1_000_000_000 -> trim(n / 1_000_000_000.0) + "B"
                n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
                n >= 1_000 -> trim(n / 1_000.0) + "K"
                else -> n.toString()
            }

        private fun trim(v: Double): String = if (v >= 100) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v).removeSuffix(".0")
    }
}
