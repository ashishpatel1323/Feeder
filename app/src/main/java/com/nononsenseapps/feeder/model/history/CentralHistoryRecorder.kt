package com.nononsenseapps.feeder.model.history

import android.app.Application
import android.util.Log
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.Sync2Dao
import com.nononsenseapps.feeder.model.audiobooks.AudioPositions
import com.nononsenseapps.feeder.model.audiobooks.AudiobookLibrary
import com.nononsenseapps.feeder.model.ebooks.BookPositions
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.itemKind
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.sync2.DeviceStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.util.concurrent.ConcurrentHashMap

/**
 * Notes what happens in the app in [CentralHistory] (History), with the titles it needs: the
 * same rules as the web app's recent.ts. Everything runs off the main thread.
 */
class CentralHistoryRecorder(
    override val di: DI,
) : DIAware {
    private val app: Application by instance()
    private val scope: ApplicationCoroutineScope by instance()
    private val history: CentralHistory by instance()
    private val repository: Repository by instance()
    private val sync2Dao: Sync2Dao by instance()
    private val playlistStore: PlaylistStore by instance()
    private val videoPositions: VideoPositions by instance()

    /** Item id to (type, key), for progress of what was opened. */
    private val keys = ConcurrentHashMap<Long, Pair<String, String>>()

    private fun launch(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not note history", e)
            }
        }
    }

    /** An article, video or episode was opened (reader, video page, player), maybe from a playlist. */
    fun itemOpened(
        itemId: Long,
        playlistId: String? = null,
        playlistName: String? = null,
    ) = launch {
        val article = repository.getArticle(itemId) ?: return@launch
        val key = article.articleKey.ifEmpty { return@launch }
        val t = typeOf(article)
        keys[itemId] = t to key
        val progress =
            when (t) {
                CentralHistory.YOUTUBE -> {
                    val p = videoPositions.get(key)
                    if (p != null) progressOf(p.t.toLong(), p.d.toLong()) else progressOf(playlistStore.positions.value[itemId])
                }
                CentralHistory.PODCAST -> progressOf(playlistStore.positions.value[itemId], article.enclosureDuration)
                else -> RecentNote(t, key, d = true)
            }
        history.note(
            progress.copy(
                t = t,
                i = key,
                n = article.title.ifBlank { "(untitled)" },
                s = article.feedDisplayTitle,
                img = article.image?.url,
                u = article.link,
                x = if (t == CentralHistory.YOUTUBE) article.youtubeId else null,
                pl = playlistId?.takeIf { it.isNotBlank() },
                pn = playlistName?.takeIf { playlistId != null && it.isNotBlank() },
            ),
        )
    }

    /** An episode's or video's saved place moved (seconds of length): its entry follows, if it has one. */
    fun itemProgress(
        itemId: Long,
        seconds: Long,
        length: Long,
    ) {
        val known = keys[itemId]
        if (known != null) {
            keyProgress(known.second, seconds, length)
            return
        }
        launch {
            val article = repository.getArticle(itemId) ?: return@launch
            val key = article.articleKey.ifEmpty { return@launch }
            keys[itemId] = typeOf(article) to key
            keyProgress(key, seconds, length)
        }
    }

    /** The same, by article key ("feedUrl\nguid"), as VideoPositions saves it. */
    fun keyProgress(
        key: String,
        seconds: Long,
        length: Long,
    ) {
        val t =
            when {
                history.get(CentralHistory.keyOf(CentralHistory.YOUTUBE, key)) != null -> CentralHistory.YOUTUBE
                history.get(CentralHistory.keyOf(CentralHistory.PODCAST, key)) != null -> CentralHistory.PODCAST
                else -> return
            }
        val old = history.get(CentralHistory.keyOf(t, key))
        val p = progressOf(seconds, length)
        // Saved every few seconds while playing: only steps worth showing
        if (old != null && old.d == p.d && old.pos != null && kotlin.math.abs(old.pos - seconds) < 15 && (p.dur == null || old.dur == p.dur)) return
        history.note(p.copy(t = t, i = key), touch = false, create = false)
    }

    /** From [ItemHistory]: an audiobook played or paused, an ebook opened or closed. */
    fun fromEvent(ev: HistoryEvent) {
        if (ev.c == ItemHistory.ZIM) return // ZIM pages are noted one by one, see [zimPage]
        val starts = ev.a == ItemHistory.OPEN || ev.a == ItemHistory.PLAY
        val f = ev.f.takeIf { it >= 0 }
        val done = f != null && f >= 0.99
        if (ev.c == ItemHistory.AUDIOBOOK) {
            val b = AudiobookLibrary.get(app).get(ev.i)
            history.note(
                RecentNote(CentralHistory.AUDIOBOOK, ev.i, n = b?.title ?: "Audiobook", s = b?.author, f = f, d = done, x = ev.x),
                touch = starts,
                create = starts,
            )
        } else if (ev.c == ItemHistory.EBOOK) {
            val b = EbookLibrary.get(app).get(ev.i)
            history.note(
                RecentNote(CentralHistory.EBOOK, ev.i, n = b?.title ?: "Ebook", s = b?.author, f = f, d = done, x = ev.x),
                touch = starts,
                create = starts,
            )
        }
    }

    /** A ZIM page was shown. Each page is its own entry, with the site as its source. */
    fun zimPage(
        bookId: String,
        path: String,
        title: String,
        site: String,
    ) {
        if (bookId.isEmpty() || path.isEmpty()) return
        history.note(RecentNote(CentralHistory.ZIM, "$bookId\n$path", n = title.ifBlank { site.ifBlank { bookId } }, s = site, x = path))
    }

    /** A highlight was made or deleted, or its note added or cleared. [before] is how it was. */
    fun highlightChanged(
        before: Highlight?,
        after: Highlight?,
    ) {
        val h = after ?: before ?: return

        fun snap(
            t: String,
            a: String,
            q: String,
        ) = RecentNote(
            t = t,
            a = a,
            i = h.id,
            n = h.itemTitle.ifBlank { "an article" },
            s = h.feedTitle,
            q = q.trim().take(280),
            c = h.color,
            x = h.itemKey,
            u = h.itemLink,
            img = h.imageUrl,
        )
        val had = before?.note?.trim().orEmpty()
        val has = after?.note?.trim().orEmpty()
        when {
            before == null && after != null -> {
                history.note(snap(CentralHistory.HIGHLIGHT, CentralHistory.CREATE, after.text))
                if (has.isNotEmpty()) history.note(snap(CentralHistory.NOTE, CentralHistory.CREATE, has))
            }
            before != null && after == null -> {
                history.note(snap(CentralHistory.HIGHLIGHT, CentralHistory.DELETE, before.text))
                if (had.isNotEmpty()) history.note(snap(CentralHistory.NOTE, CentralHistory.DELETE, had))
            }
            had.isEmpty() && has.isNotEmpty() -> history.note(snap(CentralHistory.NOTE, CentralHistory.CREATE, has))
            had.isNotEmpty() && has.isEmpty() -> history.note(snap(CentralHistory.NOTE, CentralHistory.DELETE, had))
            has.isNotEmpty() && had != has -> history.note(snap(CentralHistory.NOTE, CentralHistory.CREATE, has), touch = false, create = false)
        }
    }

    /** A feed was subscribed to here (not when sync or an OPML import brings it). */
    fun feedSubscribed(
        url: String,
        title: String,
        image: String? = null,
    ) {
        history.note(RecentNote(CentralHistory.FEED, url, CentralHistory.CREATE, n = title.ifBlank { url }, u = url, img = image))
    }

    /** Before feeds are deleted here: notes each one as deleted. */
    suspend fun feedsDeleted(feeds: List<Feed>) {
        for (f in feeds) {
            history.note(
                RecentNote(CentralHistory.FEED, f.url.toString(), CentralHistory.DELETE, n = f.displayTitle.ifBlank { f.url.toString() }, u = f.url.toString(), img = f.imageUrl?.toString()),
            )
        }
    }

    /** The item id of an article key on this phone, or null. */
    suspend fun itemIdOf(key: String): Long? {
        val cut = key.indexOf('\n')
        if (cut <= 0) return null
        return sync2Dao.getItemId(key.substring(0, cut), key.substring(cut + 1))
    }

    /**
     * Fills History once per device from what is already known: video and episode positions,
     * ebook and audiobook places and the per-book timeline. Read articles are left out (Mark all
     * read would flood it).
     */
    fun backfillOnce() {
        val prefs = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BACKFILLED, false)) return
        prefs.edit().putBoolean(PREF_BACKFILLED, true).apply()
        launch { backfill() }
    }

    private suspend fun backfill() {
        val now = System.currentTimeMillis()
        val since = maxOf(now - CentralHistory.MAX_AGE_MS, history.clearedBefore())

        fun add(f: RecentNote) {
            val at = f.at ?: return
            if (at <= since) return
            if (history.get(CentralHistory.keyOf(f.t, f.i, f.a)) != null) return
            history.note(f)
        }
        // Videos and episodes
        val videos = videoPositions.all.value
        for ((key, p) in videos) {
            val id = itemIdOf(key) ?: continue
            val article = repository.getArticle(id) ?: continue
            val t = typeOf(article)
            if (t == CentralHistory.ARTICLE) continue
            add(
                progressOf(p.t.toLong(), p.d.toLong().takeIf { it > 0 } ?: article.enclosureDuration ?: 0).copy(
                    t = t,
                    i = key,
                    at = p.ts,
                    n = article.title.ifBlank { "(untitled)" },
                    s = article.feedDisplayTitle,
                    img = article.image?.url,
                    u = article.link,
                    x = if (t == CentralHistory.YOUTUBE) article.youtubeId else null,
                    device = p.by,
                ),
            )
        }
        for ((id, pos) in playlistStore.stampedPositions()) {
            val at = pos.third.takeIf { it > 0 } ?: continue
            val article = repository.getArticle(id) ?: continue
            val key = article.articleKey.ifEmpty { continue }
            if (key in videos) continue
            val t = typeOf(article)
            if (t == CentralHistory.ARTICLE) continue
            add(
                progressOf(pos.first, pos.second).copy(
                    t = t,
                    i = key,
                    at = at,
                    n = article.title.ifBlank { "(untitled)" },
                    s = article.feedDisplayTitle,
                    img = article.image?.url,
                    u = article.link,
                    x = if (t == CentralHistory.YOUTUBE) article.youtubeId else null,
                ),
            )
        }
        // Ebooks and audiobooks
        val bookPositions = BookPositions.get(app)
        for (b in EbookLibrary.get(app).books.value) {
            val pos = bookPositions.get(b.id)
            val at = pos?.ts ?: b.lastOpenedAt
            val f = pos?.f
            add(
                RecentNote(
                    CentralHistory.EBOOK,
                    b.id,
                    n = b.title.ifBlank { "Ebook" },
                    s = b.author,
                    at = at.takeIf { it > 0 },
                    f = f,
                    d = (f ?: 0.0) >= 0.99,
                    x = pos?.cfi,
                    device = pos?.by,
                ),
            )
        }
        val audioPositions = AudioPositions.get(app)
        for (b in AudiobookLibrary.get(app).books.value) {
            val pos = audioPositions.get(b.id)
            val at = pos?.ts ?: b.lastOpenedAt
            val f = pos?.f
            add(
                RecentNote(
                    CentralHistory.AUDIOBOOK,
                    b.id,
                    n = b.title.ifBlank { "Audiobook" },
                    s = b.author,
                    at = at.takeIf { it > 0 },
                    f = f,
                    d = (f ?: 0.0) >= 0.99,
                    x = pos?.let { "${it.i}:${it.ms}" },
                    device = pos?.by,
                ),
            )
        }
        // The newest open of each ZIM page, ebook and audiobook in the per-book timeline
        val seen = HashSet<String>()
        val events = ItemHistory.get(app).events.value
        for (e in events.sortedByDescending { it.at }) {
            if (e.a != ItemHistory.OPEN && e.a != ItemHistory.PLAY) continue
            val stamp = DeviceStamp(e.dv, e.dn)
            when (e.c) {
                ItemHistory.ZIM -> {
                    if (e.x.isEmpty()) continue
                    val id = "${e.i}\n${e.x}"
                    if (!seen.add("zim:$id")) continue
                    add(RecentNote(CentralHistory.ZIM, id, at = e.at, n = e.p.ifBlank { e.i }, x = e.x, device = stamp))
                }
                ItemHistory.EBOOK, ItemHistory.AUDIOBOOK -> {
                    if (!seen.add("${e.c}:${e.i}")) continue
                    val title =
                        if (e.c == ItemHistory.EBOOK) EbookLibrary.get(app).get(e.i)?.title else AudiobookLibrary.get(app).get(e.i)?.title
                    add(RecentNote(e.c, e.i, at = e.at, n = title ?: e.c, f = e.f.takeIf { it >= 0 }, x = e.x, device = stamp))
                }
            }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_RECENT"
        private const val PREFS = "central_history"
        private const val PREF_BACKFILLED = "backfilled"

        fun typeOf(article: Article): String =
            when (itemKind(article.link, article.enclosure.link.takeIf { article.enclosure.present }, article.enclosure.type)) {
                ItemKind.YOUTUBE -> CentralHistory.YOUTUBE
                ItemKind.PODCAST -> CentralHistory.PODCAST
                ItemKind.ARTICLE -> CentralHistory.ARTICLE
            }

        private fun progressOf(
            p: Pair<Long, Long>?,
            fallbackLength: Long? = null,
        ): RecentNote = progressOf(p?.first ?: 0, p?.second?.takeIf { it > 0 } ?: fallbackLength ?: 0)

        /** Position, length, 0–1 and watched for [t] seconds of [d]. */
        fun progressOf(
            t: Long,
            d: Long,
        ): RecentNote =
            RecentNote(
                t = "",
                i = "",
                pos = t.takeIf { it > 0 },
                dur = d.takeIf { it > 0 },
                f = if (d > 0) minOf(1.0, t.toDouble() / d) else null,
                d = d > 0 && t > 0 && t >= d - VideoPositions.WATCHED_TAIL,
            )
    }
}
