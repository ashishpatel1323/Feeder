package com.nononsenseapps.feeder.model.transcript

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** A `<podcast:transcript url="…" type="…" language="…" rel="captions"/>` of one episode. */
data class TranscriptLink(
    val url: String,
    val type: String?,
    val language: String?,
    val rel: String?,
)

/**
 * Finds an episode's Podcasting 2.0 transcripts in its feed's XML. The episode is matched by its
 * guid, else by its enclosure (audio file) URL.
 */
object FeedTranscriptLinks {
    fun find(
        feedXml: String,
        guid: String?,
        enclosureUrl: String?,
    ): List<TranscriptLink> {
        val doc = Jsoup.parse(feedXml, "", Parser.xmlParser())
        val items = doc.getElementsByTag("item") + doc.getElementsByTag("entry")
        val item =
            items.firstOrNull { !guid.isNullOrBlank() && it.childText("guid") == guid.trim() }
                ?: items.firstOrNull { !guid.isNullOrBlank() && it.childText("id") == guid.trim() }
                ?: items.firstOrNull { item -> !enclosureUrl.isNullOrBlank() && item.enclosureUrls().any { sameUrl(it, enclosureUrl) } }
                ?: return emptyList()
        return item
            .getElementsByTag("podcast:transcript")
            .mapNotNull { element ->
                val url = element.attr("url").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TranscriptLink(
                    url = url,
                    type = element.attr("type").trim().ifEmpty { null },
                    language = element.attr("language").trim().ifEmpty { null },
                    rel = element.attr("rel").trim().ifEmpty { null },
                )
            }
    }

    /** The best transcript to follow playback with: a timed format, captions first, then VTT, SRT, JSON. */
    fun best(links: List<TranscriptLink>): TranscriptLink? =
        links
            .filter { TranscriptFormats.isTimed(it.type) || it.url.substringBefore('?').let { u -> u.endsWith(".vtt") || u.endsWith(".srt") || u.endsWith(".json") } }
            .minByOrNull { link ->
                val type = link.type?.lowercase().orEmpty()
                val format =
                    when {
                        type == "text/vtt" || link.url.contains(".vtt") -> 0
                        type.contains("srt") || type.contains("subrip") || link.url.contains(".srt") -> 1
                        else -> 2
                    }
                (if (link.rel == "captions") 0 else 10) + format
            }

    private fun Element.childText(tag: String): String? = children().firstOrNull { it.tagName() == tag }?.text()?.trim()

    private fun Element.enclosureUrls(): List<String> =
        children().filter { it.tagName() == "enclosure" }.map { it.attr("url") } +
            children().filter { it.tagName() == "link" && it.attr("rel") == "enclosure" }.map { it.attr("href") }

    private fun sameUrl(
        a: String,
        b: String,
    ): Boolean = a.trim() == b.trim() || a.substringAfter("://").substringBefore('?') == b.substringAfter("://").substringBefore('?')
}
