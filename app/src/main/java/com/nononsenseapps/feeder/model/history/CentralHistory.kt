package com.nononsenseapps.feeder.model.history

import android.content.Context
import android.util.Log
import com.nononsenseapps.feeder.sync2.DeviceStamp
import com.nononsenseapps.feeder.sync2.ThisDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * One entry of History: something opened (an article, video, episode, audiobook, ebook or ZIM
 * page) or a change (a highlight or note made or deleted, a feed subscribed to or deleted). The
 * fields are the web app's (web/src/lib/recent.ts, docs/central-history.md); [key], [ts], [del]
 * and [synced] are kept here only.
 */
@Serializable
data class RecentEntry(
    /** [CentralHistory.keyOf] */
    val key: String,
    val t: String = "",
    val a: String = CentralHistory.OPEN,
    val i: String = "",
    val n: String = "",
    val s: String = "",
    val img: String = "",
    val at: Long = 0,
    val f: Double? = null,
    val pos: Long? = null,
    val dur: Long? = null,
    val d: Boolean = false,
    val pl: String = "",
    val pn: String = "",
    val x: String = "",
    val q: String = "",
    val c: String = "",
    val u: String = "",
    val dv: String = "",
    val dn: String = "",
    /** The record's time: its last change (newest wins) */
    val ts: Long = 0,
    /** Removed from history (sent as {"deleted": true}) */
    val del: Boolean = false,
    /** Sent to the sync server as it is now, or came from there */
    val synced: Boolean = false,
) {
    /** The synced value, in the web app's field order, empty fields left out. */
    fun toValue(): Map<String, Any?> {
        if (del) return mapOf("deleted" to true)
        return linkedMapOf<String, Any?>().apply {
            put("t", t)
            put("a", a)
            put("i", i)
            if (n.isNotEmpty()) put("n", n)
            if (s.isNotEmpty()) put("s", s)
            if (img.isNotEmpty()) put("img", img)
            put("at", at)
            f?.let { put("f", it) }
            pos?.let { put("pos", it) }
            dur?.let { put("dur", it) }
            if (d) put("d", 1)
            if (pl.isNotEmpty()) put("pl", pl)
            if (pn.isNotEmpty()) put("pn", pn)
            if (x.isNotEmpty()) put("x", x)
            if (q.isNotEmpty()) put("q", q)
            if (c.isNotEmpty()) put("c", c)
            if (u.isNotEmpty()) put("u", u)
            if (dv.isNotEmpty() && dn.isNotEmpty()) {
                put("dv", dv)
                put("dn", dn)
            }
        }
    }

    /** Whether the record value (not the bookkeeping) is the same. */
    fun sameValue(o: RecentEntry): Boolean = toValue() == o.toValue()

    companion object {
        /** An entry from another device, or null when it can't be read. */
        fun fromValue(
            key: String,
            v: Any?,
            ts: Long,
        ): RecentEntry? {
            val m = v as? Map<*, *> ?: return null
            if (m["deleted"] == true) return RecentEntry(key = key, at = ts, ts = ts, del = true, synced = true)
            val t = m["t"] as? String ?: return null
            val a = m["a"] as? String ?: CentralHistory.OPEN
            val i = m["i"] as? String ?: return null
            if (t !in CentralHistory.TYPES || a !in CentralHistory.ACTIONS || i.isEmpty()) return null
            if (CentralHistory.keyOf(t, i, a) != key) return null

            fun str(
                k: String,
                n: Int,
            ) = (m[k] as? String).orEmpty().take(n)

            fun num(k: String) = (m[k] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val dv = str("dv", 100)
            val dn = str("dn", 100)
            val known = dv.isNotEmpty() && dn.isNotEmpty()
            val dRaw = m["d"]
            return RecentEntry(
                key = key,
                t = t,
                a = a,
                i = i,
                n = str("n", 300),
                s = str("s", 200),
                img = CentralHistory.httpOnly(str("img", 2000)),
                at = num("at")?.toLong() ?: ts,
                f = num("f")?.coerceIn(0.0, 1.0),
                pos = num("pos")?.roundToLong(),
                dur = num("dur")?.roundToLong(),
                d = dRaw == true || (dRaw as? Number)?.toDouble() == 1.0,
                pl = str("pl", 200),
                pn = str("pn", 120),
                x = str("x", 2000),
                q = str("q", 280),
                c = str("c", 12),
                u = CentralHistory.httpOnly(str("u", 2000)),
                dv = if (known) dv else "",
                dn = if (known) dn else "",
                ts = ts,
                synced = true,
            )
        }
    }
}

/**
 * What to note about an entry: null or empty fields are left as they were. [d] can only turn on.
 * Playlist fields stick once set.
 */
data class RecentNote(
    val t: String,
    val i: String,
    val a: String = CentralHistory.OPEN,
    val n: String? = null,
    val s: String? = null,
    val img: String? = null,
    val at: Long? = null,
    val f: Double? = null,
    val pos: Long? = null,
    val dur: Long? = null,
    val d: Boolean = false,
    val pl: String? = null,
    val pn: String? = null,
    val x: String? = null,
    val q: String? = null,
    val c: String? = null,
    val u: String? = null,
    /** Backfilled entries keep the device that made them */
    val device: DeviceStamp? = null,
)

/** The saved file: every entry (removals too) and when history was last cleared. */
@Serializable
private data class CentralHistoryFile(
    val entries: List<RecentEntry> = emptyList(),
    val clearedBefore: Long = 0,
    val clearSynced: Boolean = true,
)

/** One record for sync: [key], [value] and [ts]. */
data class RecentRecord(
    val key: String,
    val value: Any?,
    val ts: Long,
)

/**
 * History: one log of everything opened and every highlight, note and feed made or deleted,
 * kept in a file on the phone and synced as kind "recent" (one record per entry, newest change
 * wins; see docs/central-history.md). Removing an entry sends {"deleted": true}; Clear history
 * sends key "*" with {"before": ms}. Separate from [ItemHistory], the per-book timeline.
 */
class CentralHistory(
    private val file: File?,
    private val device: () -> DeviceStamp,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = if (file != null) Executors.newSingleThreadExecutor() else null
    private var clearedBefore = 0L
    private var clearSynced = true
    private val all = LinkedHashMap<String, RecentEntry>()
    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())

    /** Entries to show (not removed), newest first. */
    val entries: StateFlow<List<RecentEntry>> = _entries.asStateFlow()

    /** Told after a change made here (not after one from sync). */
    @Volatile
    var onChanged: ((positionOnly: Boolean) -> Unit)? = null

    init {
        load()
    }

    private fun load() {
        val f = file ?: return
        try {
            if (!f.exists()) return
            val saved = json.decodeFromString(CentralHistoryFile.serializer(), f.readText())
            clearedBefore = saved.clearedBefore
            clearSynced = saved.clearSynced
            saved.entries.forEach { all[it.key] = it }
            publish()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unreadable history, starting over", e)
        }
    }

    fun get(key: String): RecentEntry? = synchronized(this) { all[key]?.takeUnless { it.del } }

    /**
     * Notes an entry or updates it. [touch] moves it to the top (a new open); otherwise only what
     * it says changes (progress). [create] = false only updates an entry that is already there.
     * Returns the entry when something changed.
     */
    fun note(
        f: RecentNote,
        touch: Boolean = true,
        create: Boolean = true,
    ): RecentEntry? {
        if (f.i.isEmpty() || f.t !in TYPES || f.a !in ACTIONS) return null
        val key = keyOf(f.t, f.i, f.a)
        val now = clock()
        val saved =
            synchronized(this) {
                val old = all[key]
                val live = old?.takeUnless { it.del }
                if (live == null && !create) return null
                val base = live ?: RecentEntry(key = key, t = f.t, a = f.a, i = f.i, at = now)
                val stickyPlaylist = base.pl.isNotEmpty()
                val me = f.device?.takeIf { it.known } ?: device()
                var next =
                    base.copy(
                        n = f.n.orKeep(base.n, 300),
                        s = f.s.orKeep(base.s, 200),
                        img = httpOnly(f.img).ifEmpty { base.img },
                        f = f.f?.takeIf { it.isFinite() }?.let { Math.round(it.coerceIn(0.0, 1.0) * 10000) / 10000.0 } ?: base.f,
                        pos = f.pos?.takeIf { it >= 0 } ?: base.pos,
                        dur = f.dur?.takeIf { it > 0 } ?: base.dur,
                        d = base.d || f.d,
                        pl = if (stickyPlaylist) base.pl else f.pl.orKeep(base.pl, 200),
                        pn = if (stickyPlaylist) base.pn else f.pn.orKeep(base.pn, 120),
                        x = f.x.orKeep(base.x, 2000),
                        q = f.q.orKeep(base.q, 280),
                        c = f.c.orKeep(base.c, 12),
                        u = httpOnly(f.u).ifEmpty { base.u },
                        dv = me.id,
                        dn = me.name,
                        del = false,
                        synced = false,
                    )
                if (touch || live == null) next = next.copy(at = f.at ?: now)
                if (live != null && !touch && live.sameValue(next)) return null
                next = next.copy(ts = maxOf(now, (old?.ts ?: 0) + 1))
                all[key] = next
                trim(now)
                save()
                next
            }
        onChanged?.invoke(!touch)
        return saved
    }

    /** Removes one entry from history here and, with the next sync, everywhere. Never the thing itself. */
    fun remove(key: String) {
        synchronized(this) {
            val old = all[key] ?: return
            if (old.del) return
            all[key] = RecentEntry(key = key, t = old.t, a = old.a, i = old.i, at = old.at, ts = maxOf(clock(), old.ts + 1), del = true)
            save()
        }
        onChanged?.invoke(false)
    }

    /** Clears all history, here and on every device. */
    fun clear() {
        synchronized(this) {
            val before = maxOf(clock(), clearedBefore)
            all.clear()
            clearedBefore = before
            clearSynced = false
            save()
        }
        onChanged?.invoke(false)
    }

    /** When history was last cleared (0 = never). */
    fun clearedBefore(): Long = synchronized(this) { clearedBefore }

    /** Records changed here and not sent yet. */
    fun unsynced(): List<RecentRecord> =
        synchronized(this) {
            val out = all.values.filter { !it.synced }.map { RecentRecord(it.key, it.toValue(), maxOf(1L, it.ts)) }
            if (!clearSynced && clearedBefore > 0) out + RecentRecord(CLEAR_KEY, mapOf("before" to clearedBefore), clearedBefore) else out
        }

    /** These records were sent; entries changed again since stay unsynced. */
    fun markSynced(sent: Collection<RecentRecord>) {
        if (sent.isEmpty()) return
        synchronized(this) {
            var changed = false
            for (r in sent) {
                if (r.key == CLEAR_KEY) {
                    if (r.ts == clearedBefore && !clearSynced) {
                        clearSynced = true
                        changed = true
                    }
                    continue
                }
                val e = all[r.key] ?: continue
                if (!e.synced && maxOf(1L, e.ts) == r.ts) {
                    all[r.key] = e.copy(synced = true)
                    changed = true
                }
            }
            if (changed) save()
        }
    }

    /** Joining or starting another sync, or Force sync: send everything again. */
    fun markAllUnsynced() {
        synchronized(this) {
            for ((k, e) in all) if (e.synced) all[k] = e.copy(synced = false)
            if (clearedBefore > 0) clearSynced = false
            save()
        }
    }

    /** A record from another device: the newest change wins. Returns true when it changed something. */
    fun applyRemote(
        key: String,
        v: Any?,
        ts: Long,
    ): Boolean =
        synchronized(this) {
            if (key == CLEAR_KEY) {
                val before = ((v as? Map<*, *>)?.get("before") as? Number)?.toLong() ?: return false
                if (before <= clearedBefore) return false
                all.entries.removeAll { it.value.at <= before }
                clearedBefore = before
                clearSynced = true
                save()
                return true
            }
            val e = RecentEntry.fromValue(key, v, ts) ?: return false
            val mine = all[key]
            if (mine != null && mine.ts >= ts) return false
            if (!e.del && e.at <= clearedBefore) return false
            all[key] = e
            trim(clock())
            save()
            true
        }

    /** Keeps the newest [MAX_ENTRIES], none older than [MAX_AGE_MS] (here only; nothing is sent). */
    private fun trim(now: Long) {
        val since = now - MAX_AGE_MS
        all.entries.removeAll { (if (it.value.del) it.value.ts else it.value.at) < since }
        val live = all.values.count { !it.del }
        if (live > MAX_ENTRIES) {
            all.values
                .filter { !it.del }
                .sortedBy { it.at }
                .take(live - MAX_ENTRIES)
                .forEach { all.remove(it.key) }
        }
        // Removals only matter until older copies are gone from every device
        val removed = all.values.filter { it.del }
        if (removed.size > MAX_ENTRIES) removed.sortedBy { it.ts }.take(removed.size - MAX_ENTRIES).forEach { all.remove(it.key) }
    }

    private fun publish() {
        _entries.value = all.values.filter { !it.del }.sortedByDescending { it.at }
    }

    private fun save() {
        publish()
        val w = writer ?: return
        val snapshot = CentralHistoryFile(all.values.toList(), clearedBefore, clearSynced)
        w.execute {
            try {
                val f = file!!
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(json.encodeToString(CentralHistoryFile.serializer(), snapshot))
                tmp.renameTo(f)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not save history", e)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_RECENT"
        const val MAX_ENTRIES = 2000
        const val MAX_AGE_MS = 180L * 86_400_000L

        /** The key of the Clear history marker. */
        const val CLEAR_KEY = "*"

        const val ARTICLE = "article"
        const val YOUTUBE = "youtube"
        const val PODCAST = "podcast"
        const val AUDIOBOOK = "audiobook"
        const val EBOOK = "ebook"
        const val ZIM = "zim"
        const val HIGHLIGHT = "highlight"
        const val NOTE = "note"
        const val FEED = "feed"
        const val OPEN = "open"
        const val CREATE = "create"
        const val DELETE = "delete"

        val TYPES = setOf(ARTICLE, YOUTUBE, PODCAST, AUDIOBOOK, EBOOK, ZIM, HIGHLIGHT, NOTE, FEED)
        val ACTIONS = setOf(OPEN, CREATE, DELETE)

        fun keyOf(
            t: String,
            i: String,
            a: String = OPEN,
        ): String = if (a == OPEN) "$t:$i" else "$t:$i:$a"

        /** Only http(s) links are kept. */
        fun httpOnly(u: String?): String =
            u
                ?.trim()
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                ?.take(2000)
                .orEmpty()

        private fun String?.orKeep(
            old: String,
            max: Int,
        ): String = this?.trim()?.take(max)?.takeIf { it.isNotEmpty() } ?: old

        @Volatile
        private var instance: CentralHistory? = null

        fun get(context: Context): CentralHistory =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val me = ThisDevice.get(app)
                    CentralHistory(File(app.filesDir, "central_history.json"), { me.stamp() }).also { instance = it }
                }
            }
    }
}
