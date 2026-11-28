package com.nononsenseapps.feeder.model.briefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One article in a brief: [id] is "feedUrl\nguid" (the key read and saved records use). */
@Serializable
data class BriefItem(
    val id: String,
    val t: String,
    val u: String? = null,
)

/**
 * A playlist's brief, as synced (kind "brief", key = playlist id). Same shape as the web app's
 * BriefValue (web/src/lib/briefs.ts): asked for on any device, written by Claude Code in
 * Feeder for Mac (web/src/lib/macBrief.ts) from each article's full text. Making one never marks
 * anything read.
 */
@Serializable
data class Brief(
    /** "asked", "working", "done" or "failed" */
    val s: String,
    val asked: Long,
    val name: String,
    val items: List<BriefItem> = emptyList(),
    val by: String? = null,
    /** Articles read so far, while working */
    val n: Int? = null,
    /** The big picture: a few bullets across all the articles */
    val ov: List<String>? = null,
    /** One summary per item, same order ("" when its text couldn't be read) */
    val sums: List<String>? = null,
    /** Claude's answer as plain text, when it couldn't be read as JSON */
    val text: String? = null,
    val err: String? = null,
    val at: Long? = null,
) {
    /** Waiting for the Mac (asked or being written), and not left hanging for days. */
    fun isPending(now: Long = System.currentTimeMillis()): Boolean = (s == ASKED || s == WORKING) && now - asked < STALE_ASK_MS

    fun toValue(): Map<String, Any?> =
        buildMap {
            put("s", s)
            put("asked", asked)
            put("name", name)
            put("items", items.map { mapOf("id" to it.id, "t" to it.t, "u" to it.u) })
            by?.let { put("by", it) }
            n?.let { put("n", it) }
            ov?.let { put("ov", it) }
            sums?.let { put("sums", it) }
            text?.let { put("text", it) }
            err?.let { put("err", it) }
            at?.let { put("at", it) }
        }

    /** Brief text for sharing: the big picture, then each article. */
    fun asText(): String {
        text?.let { return it }
        return buildString {
            append(name).append(": brief\n\n")
            ov?.takeIf { it.isNotEmpty() }?.let { list -> list.forEach { append("• ").append(it).append('\n') }.also { append('\n') } }
            items.forEachIndexed { i, it ->
                val sum = sums?.getOrNull(i)
                if (!sum.isNullOrBlank()) append(it.t).append('\n').append(sum).append("\n\n")
            }
        }.trim()
    }

    companion object {
        const val ASKED = "asked"
        const val WORKING = "working"
        const val DONE = "done"
        const val FAILED = "failed"

        /** At most this many articles go into one brief */
        const val MAX_ITEMS = 40

        /** A request nobody picked up in this long counts as stale */
        const val STALE_ASK_MS = 2 * 86_400_000L

        private fun Any?.asLong(): Long? = (this as? Number)?.toLong()

        private fun Any?.strings(): List<String>? = (this as? List<*>)?.map { it as? String ?: "" }

        /** A record from sync, checked; null when it isn't a brief this version understands. */
        fun fromValue(v: Any?): Brief? {
            val m = v as? Map<*, *> ?: return null
            val s = m["s"] as? String ?: return null
            if (s !in setOf(ASKED, WORKING, DONE, FAILED)) return null
            val items =
                (m["items"] as? List<*>).orEmpty().mapNotNull { x ->
                    val im = x as? Map<*, *> ?: return@mapNotNull null
                    val id = im["id"] as? String ?: return@mapNotNull null
                    BriefItem(id, im["t"] as? String ?: "", im["u"] as? String)
                }
            return Brief(
                s = s,
                asked = m["asked"].asLong() ?: 0L,
                name = m["name"] as? String ?: "",
                items = items,
                by = m["by"] as? String,
                n = (m["n"] as? Number)?.toInt(),
                ov = m["ov"].strings(),
                sums = m["sums"].strings(),
                text = m["text"] as? String,
                err = m["err"] as? String,
                at = m["at"].asLong(),
            )
        }
    }
}

@Serializable
data class StoredBrief(
    val v: Brief,
    val ts: Long,
)

/** Playlist briefs by playlist id, kept here and synced with the web app and the Mac (newest wins). */
class PlaylistBriefs private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("playlist_briefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, StoredBrief>> = _all.asStateFlow()

    /** Told after a change made here (not after one from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun load(): Map<String, StoredBrief> =
        prefs.all
            .mapNotNull { (k, v) -> (v as? String)?.let { runCatching { json.decodeFromString(StoredBrief.serializer(), it) }.getOrNull() }?.let { k to it } }
            .toMap()

    /** Asks the Mac for a brief of [items] (the playlist's first [Brief.MAX_ITEMS] articles). */
    @Synchronized
    fun request(
        playlistId: String,
        name: String,
        items: List<BriefItem>,
        by: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val ts = maxOf(now, (_all.value[playlistId]?.ts ?: 0) + 1)
        put(playlistId, StoredBrief(Brief(s = Brief.ASKED, asked = now, name = name, items = items.take(Brief.MAX_ITEMS), by = by), ts))
        onChanged?.invoke()
    }

    /** A brief from another device, kept when newer. */
    @Synchronized
    fun applyRemote(
        playlistId: String,
        v: Any?,
        ts: Long,
    ) {
        val brief = Brief.fromValue(v) ?: return
        val local = _all.value[playlistId]
        if (local != null && local.ts >= ts) return
        put(playlistId, StoredBrief(brief, ts))
    }

    private fun put(
        id: String,
        b: StoredBrief,
    ) {
        _all.value = _all.value + (id to b)
        prefs.edit { putString(id, json.encodeToString(StoredBrief.serializer(), b)) }
    }

    companion object {
        @Volatile
        private var instance: PlaylistBriefs? = null

        fun get(context: Context): PlaylistBriefs =
            instance ?: synchronized(this) {
                instance ?: PlaylistBriefs(context.applicationContext).also { instance = it }
            }
    }
}
