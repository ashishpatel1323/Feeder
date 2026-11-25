package com.nononsenseapps.feeder.model.history

import android.content.Context
import android.util.Log
import com.nononsenseapps.feeder.sync2.ThisDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * One play, pause, open or close of an audiobook, an ebook or a ZIM site, where it was then and on
 * which device. The same fields as the web app's lib/history.ts, which documents them:
 * [c] kind, [i] item id, [a] action, [r] why, [at] when, [p] position chip, [s] chapter or article,
 * [f] 0–1 through it, [x] where to go back to ("file:ms", a CFI, a ZIM path).
 */
@Serializable
data class HistoryEvent(
    val id: String,
    val c: String,
    val i: String,
    val a: String,
    val r: String = "",
    val at: Long,
    val p: String = "",
    val s: String = "",
    val f: Double = -1.0,
    val x: String = "",
    val dv: String = "",
    val dn: String = "",
    /** Sent to the sync server, or came from there */
    val synced: Boolean = false,
) {
    /** The synced value, in the web app's field order. */
    fun toValue(): Map<String, Any?> =
        linkedMapOf<String, Any?>("c" to c, "i" to i, "a" to a).apply {
            if (r.isNotEmpty()) put("r", r)
            put("at", at)
            if (p.isNotEmpty()) put("p", p)
            if (s.isNotEmpty()) put("s", s)
            if (f >= 0) put("f", f)
            if (x.isNotEmpty()) put("x", x)
            if (dv.isNotEmpty() && dn.isNotEmpty()) {
                put("dv", dv)
                put("dn", dn)
            }
        }

    companion object {
        /** An event from another device, or null when it can't be read. */
        fun fromValue(
            id: String,
            v: Any?,
            ts: Long,
        ): HistoryEvent? {
            val m = v as? Map<*, *> ?: return null
            val c = m["c"] as? String ?: return null
            val i = m["i"] as? String ?: return null
            val a = m["a"] as? String ?: return null
            if (c !in KINDS || i.isEmpty() || a !in ACTIONS) return null
            val dv = m["dv"] as? String ?: ""
            val dn = m["dn"] as? String ?: ""
            val known = dv.isNotEmpty() && dn.isNotEmpty()
            return HistoryEvent(
                id = id,
                c = c,
                i = i,
                a = a,
                r = m["r"] as? String ?: "",
                at = (m["at"] as? Number)?.toLong() ?: ts,
                p = (m["p"] as? String).orEmpty().take(80),
                s = (m["s"] as? String).orEmpty().take(200),
                f = (m["f"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: -1.0,
                x = (m["x"] as? String).orEmpty().take(2000),
                dv = if (known) dv else "",
                dn = if (known) dn else "",
                synced = true,
            )
        }

        val KINDS = setOf(ItemHistory.AUDIOBOOK, ItemHistory.EBOOK, ItemHistory.ZIM)
        val ACTIONS = setOf(ItemHistory.PLAY, ItemHistory.PAUSE, ItemHistory.OPEN, ItemHistory.CLOSE)
    }
}

/** Where something was, for its history. */
data class HistoryWhere(
    val p: String = "",
    val s: String = "",
    val f: Double = -1.0,
    val x: String = "",
)

/**
 * The history of long reads and listens, kept in a file on the phone (the newest [MAX_EVENTS]).
 * Every event is one sync record of kind "history" keyed by its id: it never changes, so events
 * from every device add up. Sync2Client sends the ones not [HistoryEvent.synced] yet.
 */
class ItemHistory private constructor(
    context: Context,
) {
    private val device = ThisDevice.get(context)
    private val file = File(context.filesDir, "item_history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadExecutor()
    private val _events = MutableStateFlow(load())

    /** Every event, oldest first. */
    val events: StateFlow<List<HistoryEvent>> = _events.asStateFlow()

    /** Told after an event is noted here (not after one from sync). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun load(): List<HistoryEvent> =
        try {
            if (file.exists()) json.decodeFromString(ListSerializer(HistoryEvent.serializer()), file.readText()) else emptyList()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unreadable history, starting over", e)
            emptyList()
        }

    /** Notes an event here; it goes to other devices with the next sync. Null when it repeats the last one. */
    fun record(
        c: String,
        i: String,
        a: String,
        where: HistoryWhere = HistoryWhere(),
        reason: String = "",
        at: Long = System.currentTimeMillis(),
    ): HistoryEvent? {
        if (i.isEmpty()) return null
        val me = device.stamp()
        val ev =
            synchronized(this) {
                val last = _events.value.lastOrNull { it.c == c && it.i == i }
                // The same action again this soon (a double tap, a quick reopen) is not a new event
                if (last != null && last.a == a && kotlin.math.abs(at - last.at) < REPEAT_MS) return null
                val ev =
                    HistoryEvent(
                        id = "${at.toString(36)}-${UUID.randomUUID().toString().take(13)}",
                        c = c,
                        i = i,
                        a = a,
                        r = reason,
                        at = at,
                        p = where.p.take(80),
                        s = where.s.take(200),
                        f = if (where.f >= 0) Math.round(where.f.coerceIn(0.0, 1.0) * 10000) / 10000.0 else -1.0,
                        x = where.x.take(2000),
                        dv = me.id,
                        dn = me.name,
                    )
                put(_events.value + ev)
                ev
            }
        onChanged?.invoke()
        return ev
    }

    /** One item's events, newest first. */
    fun of(
        c: String,
        i: String,
    ): List<HistoryEvent> = _events.value.filter { it.c == c && it.i == i }.sortedByDescending { it.at }

    fun unsynced(): List<HistoryEvent> = _events.value.filter { !it.synced }

    @Synchronized
    fun markSynced(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        put(_events.value.map { if (it.id in set && !it.synced) it.copy(synced = true) else it })
    }

    /** Joining or starting another sync, or Force sync: send everything again. */
    @Synchronized
    fun markAllUnsynced() {
        put(_events.value.map { if (it.synced) it.copy(synced = false) else it })
    }

    /** Events from other devices, those not here yet. Returns how many were new. */
    @Synchronized
    fun applyRemote(events: List<HistoryEvent>): Int {
        if (events.isEmpty()) return 0
        val have = _events.value.mapTo(HashSet()) { it.id }
        val fresh = events.filter { have.add(it.id) }
        if (fresh.isNotEmpty()) put((_events.value + fresh).sortedBy { it.at })
        return fresh.size
    }

    private fun put(list: List<HistoryEvent>) {
        val kept = if (list.size > MAX_EVENTS) list.sortedBy { it.at }.takeLast(MAX_EVENTS) else list
        _events.value = kept
        writer.execute {
            try {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(ListSerializer(HistoryEvent.serializer()), _events.value))
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not save history", e)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_HISTORY"
        const val MAX_EVENTS = 5000
        const val REPEAT_MS = 3000L

        /** Away from the app at least this long counts as closing and opening again. */
        const val AWAY_MS = 5 * 60_000L

        const val AUDIOBOOK = "audiobook"
        const val EBOOK = "ebook"
        const val ZIM = "zim"
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val OPEN = "open"
        const val CLOSE = "close"

        /** "01:03", or "1:02:03" past an hour (same as the web app). */
        fun clockText(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600
            return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%02d:%02d".format((s % 3600) / 60, s % 60)
        }

        /** Where an ebook is: "p. 41 of 310", or "37%". */
        fun bookChip(
            f: Double,
            page: Int,
            pages: Int,
        ): String = if (page > 0 && pages > 0) "p. $page of $pages" else "${Math.round(f.coerceIn(0.0, 1.0) * 100)}%"

        @Volatile
        private var instance: ItemHistory? = null

        fun get(context: Context): ItemHistory =
            instance ?: synchronized(this) {
                instance ?: ItemHistory(context.applicationContext).also { instance = it }
            }
    }
}
