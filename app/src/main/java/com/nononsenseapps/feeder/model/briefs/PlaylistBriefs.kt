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
    /** Source: the feed's (site's) name, which the brief names */
    val f: String? = null,
)

/** One story in the rundown: item index (into items) and what the host says about it. */
@Serializable
data class BriefSeg(
    val i: Int,
    val say: String,
)

/** A pick to read in full: item index and why it suits the reader. */
@Serializable
data class BriefPick(
    val i: Int,
    val why: String,
)

/**
 * A playlist's Morning brief, as synced (kind "brief", key = playlist id). Same shape as the web app's
 * BriefValue (web/src/lib/briefs.ts): asked for on any device, written by Claude Code in
 * Feeder for Mac (web/src/lib/macBrief.ts) or by Claude in the Claude app (feeder-mcp's
 * save_morning_brief), from each article's full text. The template is a morning radio show: an
 * opener, the rundown, 1 to 3 picks to read in full chosen from the reader's taste, a sign-off.
 * Making one never marks anything read.
 */
@Serializable
data class Brief(
    /** "asked", "working", "done" or "failed" */
    val s: String,
    val asked: Long,
    val name: String,
    val items: List<BriefItem> = emptyList(),
    val by: String? = null,
    /** "mac" (Claude Code in Feeder for Mac) or "claude" (the Claude app) */
    val via: String? = null,
    /** Articles read so far, while working */
    val n: Int? = null,
    /** The opener */
    val hello: String? = null,
    /** The rundown, in the playlist's order */
    val segs: List<BriefSeg>? = null,
    /** Worth reading in full for this reader, best first */
    val picks: List<BriefPick>? = null,
    /** The sign-off */
    val bye: String? = null,
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
            put(
                "items",
                items.map { item ->
                    buildMap {
                        put("id", item.id)
                        put("t", item.t)
                        put("u", item.u)
                        item.f?.let { put("f", it) }
                    }
                },
            )
            by?.let { put("by", it) }
            via?.let { put("via", it) }
            n?.let { put("n", it) }
            hello?.let { put("hello", it) }
            segs?.let { list -> put("segs", list.map { mapOf("i" to it.i, "say" to it.say) }) }
            picks?.let { list -> put("picks", list.map { mapOf("i" to it.i, "why" to it.why) }) }
            bye?.let { put("bye", it) }
            text?.let { put("text", it) }
            err?.let { put("err", it) }
            at?.let { put("at", it) }
        }

    /** The brief as one script: for sharing and reading aloud (same as web briefAsText). */
    fun asText(): String {
        text?.let { return it }

        fun title(i: Int) = items.getOrNull(i)?.t.orEmpty()
        val parts = mutableListOf<String>()
        hello?.takeIf { it.isNotBlank() }?.let { parts += it }
        segs.orEmpty().forEach { seg ->
            val from =
                items
                    .getOrNull(seg.i)
                    ?.f
                    ?.let { ", from $it" }
                    .orEmpty()
            parts += "${title(seg.i)}$from." + if (seg.say.isNotBlank()) " ${seg.say}" else ""
        }
        val p = picks.orEmpty()
        if (p.isNotEmpty()) {
            parts += if (p.size == 1) "My pick for you today:" else "My picks for you today:"
            p.forEach { parts += "${title(it.i)}. ${it.why}" }
        }
        bye?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString("\n\n").trim()
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

        private fun pairs(
            v: Any?,
            field: String,
            count: Int,
        ): List<Pair<Int, String>>? =
            (v as? List<*>)?.mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                val i = (m["i"] as? Number)?.toInt() ?: return@mapNotNull null
                if (i !in 0 until count) return@mapNotNull null
                i to (m[field] as? String ?: "")
            }

        /** A record from sync, checked; null when it isn't a brief this version understands. */
        fun fromValue(v: Any?): Brief? {
            val m = v as? Map<*, *> ?: return null
            val s = m["s"] as? String ?: return null
            if (s !in setOf(ASKED, WORKING, DONE, FAILED)) return null
            val items =
                (m["items"] as? List<*>).orEmpty().mapNotNull { x ->
                    val im = x as? Map<*, *> ?: return@mapNotNull null
                    val id = im["id"] as? String ?: return@mapNotNull null
                    BriefItem(id, im["t"] as? String ?: "", im["u"] as? String, (im["f"] as? String)?.takeIf { it.isNotBlank() })
                }
            return Brief(
                s = s,
                asked = m["asked"].asLong() ?: 0L,
                name = m["name"] as? String ?: "",
                items = items,
                by = m["by"] as? String,
                via = m["via"] as? String,
                n = (m["n"] as? Number)?.toInt(),
                hello = m["hello"] as? String,
                segs = pairs(m["segs"], "say", items.size)?.map { BriefSeg(it.first, it.second) },
                picks = pairs(m["picks"], "why", items.size)?.map { BriefPick(it.first, it.second) },
                bye = m["bye"] as? String,
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
