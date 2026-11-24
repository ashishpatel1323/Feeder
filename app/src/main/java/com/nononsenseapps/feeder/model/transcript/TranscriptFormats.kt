package com.nononsenseapps.feeder.model.transcript

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One line of a transcript: what is said from [startMs] to [endMs], and by whom if known. */
@Serializable
data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
)

/** The cue playing at [positionMs]: the last one that started at or before it. -1 before the first. */
fun List<TranscriptCue>.indexAt(positionMs: Long): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** One place the search text appears: in cue [cue], characters [start] until [end] of its text. */
data class TranscriptMatch(
    val cue: Int,
    val start: Int,
    val end: Int,
)

/** Every place [query] appears in the transcript, ignoring case and accents. */
fun List<TranscriptCue>.findMatches(query: String): List<TranscriptMatch> {
    val q = foldForSearch(query.trim())
    if (q.isEmpty()) return emptyList()
    val matches = mutableListOf<TranscriptMatch>()
    forEachIndexed { index, cue ->
        // Folding only drops accent marks, so positions are mapped back through the original text
        val map = mutableListOf<Int>()
        val folded = StringBuilder()
        cue.text.forEachIndexed { j, c ->
            val f = foldForSearch(c.toString())
            repeat(f.length) { map += j }
            folded.append(f)
        }
        var at = folded.indexOf(q)
        while (at >= 0) {
            matches += TranscriptMatch(index, map[at], map[at + q.length - 1] + 1)
            at = folded.indexOf(q, at + q.length)
        }
    }
    return matches
}

/** The whole transcript as plain text for the clipboard: a time on each line, speakers when they change. */
fun List<TranscriptCue>.toPlainText(title: String? = null): String {
    val cues = this
    return buildString {
        if (!title.isNullOrBlank()) append(title).append("\n\n")
        cues.forEachIndexed { i, cue ->
            if (cue.speaker != null && cue.speaker != cues.getOrNull(i - 1)?.speaker) {
                if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                append(cue.speaker).append(":\n")
            }
            append('[').append(clock(cue.startMs)).append("] ").append(cue.text).append('\n')
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val COMBINING_MARKS = Regex("\\p{M}+")

private fun foldForSearch(text: String): String =
    java.text.Normalizer
        .normalize(text, java.text.Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

/**
 * Parsers for the transcript files podcasts publish with the Podcasting 2.0 `podcast:transcript`
 * tag: WebVTT, SubRip and the Podcast Index JSON format. Formats without timing (HTML, plain
 * text) cannot follow playback and are not read.
 */
object TranscriptFormats {
    private val json = Json { ignoreUnknownKeys = true }

    /** True for a `type` (MIME type) this can parse, so it follows playback. */
    fun isTimed(type: String?): Boolean = kindOf(type, null) != null

    /** Parses [content] by its MIME [type], or by its URL's ending when the type is vague. */
    fun parse(
        content: String,
        type: String?,
        url: String? = null,
    ): List<TranscriptCue> =
        when (kindOf(type, url) ?: sniff(content)) {
            Kind.VTT -> parseVtt(content)
            Kind.SRT -> parseSrt(content)
            Kind.JSON -> parseJson(content)
            null -> emptyList()
        }.filter { it.text.isNotBlank() }
            .sortedBy { it.startMs }

    private enum class Kind { VTT, SRT, JSON }

    private fun kindOf(
        type: String?,
        url: String?,
    ): Kind? {
        val t =
            type
                ?.lowercase()
                ?.substringBefore(';')
                ?.trim()
                .orEmpty()
        val ending =
            url
                ?.substringBefore('?')
                ?.substringAfterLast('.', "")
                ?.lowercase()
                .orEmpty()
        return when {
            t == "text/vtt" || ending == "vtt" -> Kind.VTT
            t == "application/srt" || t == "application/x-subrip" || t == "text/srt" || ending == "srt" -> Kind.SRT
            t == "application/json" || ending == "json" -> Kind.JSON
            else -> null
        }
    }

    private fun sniff(content: String): Kind? {
        val head = content.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("WEBVTT") -> Kind.VTT
            head.startsWith("{") -> Kind.JSON
            TIMING.containsMatchIn(head.take(500)) -> Kind.SRT
            else -> null
        }
    }

    // 00:01:02.345 --> 00:01:04.000 (hours optional; SRT uses a comma)
    private val TIMING =
        Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val VOICE = Regex("""<v(?:\.[^ >]*)?\s+([^>]*)>""")
    private val TAG = Regex("""<[^>]+>""")

    fun parseVtt(content: String): List<TranscriptCue> = parseBlocks(content)

    fun parseSrt(content: String): List<TranscriptCue> = parseBlocks(content)

    /** VTT and SRT both are blocks split by blank lines, each with a timing line and its text. */
    private fun parseBlocks(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        for (block in content.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (timingIndex < 0) continue
            val match = TIMING.find(lines[timingIndex]) ?: continue
            val raw = lines.drop(timingIndex + 1).joinToString(" ").trim()
            val speaker =
                VOICE
                    .find(raw)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::speakerName)
            val text = decodeEntities(TAG.replace(raw, "")).replace(Regex("\\s+"), " ").trim()
            cues += TranscriptCue(timeMs(match.groupValues[1]), timeMs(match.groupValues[2]), text, speaker)
        }
        return cues
    }

    /**
     * YouTube's automatic captions as WebVTT: each cue repeats the line before it (the captions
     * roll up) with word timings inside, and 10 ms cues hold the finished line. Gives each spoken
     * line once, at the time it starts.
     */
    fun parseYouTubeAutoVtt(content: String): List<TranscriptCue> {
        // Split at timing lines, not blank lines: each cue's text starts with a line of one space
        val blocks = mutableListOf<Pair<MatchResult, MutableList<String>>>()
        for (line in content.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val match = TIMING.find(line)
            if (match != null) {
                blocks += match to mutableListOf()
            } else {
                blocks.lastOrNull()?.second?.add(line)
            }
        }
        val cues = mutableListOf<TranscriptCue>()
        var previous = emptyList<String>()
        for ((match, lines) in blocks) {
            val start = timeMs(match.groupValues[1])
            val end = timeMs(match.groupValues[2])
            val texts =
                lines
                    .map { decodeEntities(TAG.replace(it, "")).replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotEmpty() }
            if (end - start < 50 || texts.isEmpty()) continue
            for (text in texts) {
                if (text !in previous && text != cues.lastOrNull()?.text) cues += TranscriptCue(start, end, text)
            }
            previous = texts
        }
        return cues
    }

    private fun timeMs(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().toDouble()
        val minutes = parts.getOrNull(parts.size - 2)?.toLong() ?: 0L
        val hours = if (parts.size >= 3) parts[parts.size - 3].toLong() else 0L
        return (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1000).toLong()
    }

    private fun decodeEntities(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    /** `{"version": "1.0.0", "segments": [{"speaker": "…", "startTime": 0.5, "endTime": 1.2, "body": "…"}]}` */
    fun parseJson(content: String): List<TranscriptCue> {
        val root = runCatching { json.parseToJsonElement(content.trimStart('\uFEFF')) }.getOrNull() ?: return emptyList()
        val segments: JsonArray =
            when (root) {
                is JsonObject -> root["segments"]?.let { runCatching { it.jsonArray }.getOrNull() }
                is JsonArray -> root
                else -> null
            } ?: return emptyList()
        val parsed =
            segments.mapNotNull { element ->
                val segment = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val start = segment["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = segment["endTime"]?.jsonPrimitive?.doubleOrNull ?: start
                val body = segment["body"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val speaker = segment["speaker"]?.jsonPrimitive?.contentOrNull?.let(::speakerName)
                TranscriptCue((start * 1000).toLong(), (end * 1000).toLong(), body.trim(), speaker)
            }
        return joinWords(parsed)
    }

    private val GENERIC_SPEAKER = Regex("""(?i)speaker_(\d+)""")

    /** "SPEAKER_00" (speaker detection without names) reads as "Speaker 1". */
    private fun speakerName(raw: String): String? {
        val name = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val generic = GENERIC_SPEAKER.matchEntire(name) ?: return name
        return "Speaker ${(generic.groupValues[1].toIntOrNull() ?: 0) + 1}"
    }

    /**
     * Some JSON transcripts have one segment per word. Those are joined into lines of about a
     * sentence (a pause, a new speaker, a sentence end or about 12 words starts a new line).
     */
    private fun joinWords(segments: List<TranscriptCue>): List<TranscriptCue> {
        if (segments.size < 20) return segments
        val averageWords = segments.sumOf { it.text.split(' ').size }.toDouble() / segments.size
        if (averageWords > 2.0) return segments
        val lines = mutableListOf<TranscriptCue>()
        var current: TranscriptCue? = null
        var words = 0
        for (segment in segments) {
            val open = current
            val newLine =
                open == null ||
                    segment.speaker != open.speaker ||
                    segment.startMs - open.endMs > 1200 ||
                    words >= 12 ||
                    open.text.endsWith('.') ||
                    open.text.endsWith('?') ||
                    open.text.endsWith('!')
            if (newLine) {
                open?.let(lines::add)
                current = segment
                words = 1
            } else {
                current = open!!.copy(endMs = segment.endMs, text = open.text + " " + segment.text)
                words++
            }
        }
        current?.let(lines::add)
        return lines
    }
}
