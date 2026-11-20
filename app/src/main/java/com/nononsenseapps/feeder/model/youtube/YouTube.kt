package com.nononsenseapps.feeder.model.youtube

import java.net.URI

/*
 * YouTube articles play in YouTube's own player inside the reader, like the web app
 * (web/src/lib/youtube.ts). These helpers match the web app's.
 */

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val PATH_ID = Regex("^/(?:shorts|live|embed|v)/([^/?#]+)")

/** The video id of a YouTube watch, short, shorts, live or embed link, or null. */
fun youtubeVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri =
        try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
    val host =
        uri.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?.removePrefix("music.")
            ?: return null
    val path = uri.rawPath ?: ""
    val id =
        when (host) {
            "youtu.be" -> path.split('/').getOrNull(1)

            "youtube.com", "youtube-nocookie.com" ->
                if (path == "/watch") {
                    queryParam(uri.rawQuery, "v")
                } else {
                    PATH_ID.find(path)?.groupValues?.get(1)
                }

            else -> null
        }
    return id?.takeIf { VIDEO_ID.matches(it) }
}

private fun queryParam(
    query: String?,
    name: String,
): String? =
    query
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.ifEmpty { null }

/** The watch page, which Android opens in the YouTube app. `start` is in seconds. */
fun youtubeWatchUrl(
    id: String,
    start: Int = 0,
): String =
    if (start > 0) {
        "https://www.youtube.com/watch?v=$id&t=${start}s"
    } else {
        "https://www.youtube.com/watch?v=$id"
    }
