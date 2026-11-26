package com.nononsenseapps.feeder.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.audiobooks.AudiobookLibrary
import com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer
import com.nononsenseapps.feeder.model.audiobooks.AudiobookState
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.history.RecentEntry
import com.nononsenseapps.feeder.model.playlists.PlaylistPlayer
import com.nononsenseapps.feeder.model.playlists.QueueState
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.model.zim.ZimSnapshots
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.instance
import java.io.File

/** The four things the Continue widget shows, one of each. */
enum class ContinueKind { PLAYLIST, AUDIOBOOK, EBOOK, ZIM }

/** One line of the Continue widget: the last playlist, audiobook, ebook or ZIM page. */
data class ContinueItem(
    val kind: ContinueKind,
    /** The playlist, audiobook, ebook or ZIM book id */
    val id: String,
    /** Playlist: the article key of the item to start from. ZIM: the page path. */
    val itemKey: String,
    val title: String,
    val subtitle: String,
    /** The cover cut to the scalloped shape, or null for a plain one */
    val cover: Bitmap?,
    /** 0 to 1, or null when it isn't known (ZIM pages) */
    val progress: Float?,
    /** "42%" on reading pills */
    val percent: String?,
    /** Playing (or about to) right now */
    val playing: Boolean,
    /** Loaded in its player, so skips work without starting it */
    val loaded: Boolean,
    val at: Long,
    val tint: ContinueTint,
    /** The deep link that opens it in the app */
    val uri: String,
) {
    val audio: Boolean get() = kind == ContinueKind.PLAYLIST || kind == ContinueKind.AUDIOBOOK
}

/** What the widget's lines are made from; a change here is a reason to redraw. */
private data class Sources(
    val entries: List<RecentEntry>,
    val playlist: PlaylistNow,
    val audiobook: AudiobookNow,
    val libraries: Int,
)

/** The playlist player's state, coarse enough that playing doesn't redraw every second. */
private data class PlaylistNow(
    val playlistId: String?,
    val name: String,
    val currentId: Long?,
    val playing: Boolean,
    val title: String,
    val feedTitle: String,
    val artwork: String?,
    val positionStep: Long,
    val durationMs: Long,
    val positionMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is PlaylistNow &&
            other.playlistId == playlistId &&
            other.currentId == currentId &&
            other.playing == playing &&
            other.title == title &&
            other.artwork == artwork &&
            other.positionStep == positionStep &&
            other.durationMs == durationMs &&
            other.name == name &&
            other.feedTitle == feedTitle

    override fun hashCode(): Int = listOf(playlistId, currentId, playing, title, positionStep).hashCode()

    companion object {
        fun of(s: QueueState) =
            PlaylistNow(
                playlistId = s.playlistId.takeIf { s.active },
                name = s.name,
                currentId = s.currentId,
                playing = s.active && (s.isPlaying || s.loading),
                title = s.title,
                feedTitle = s.feedTitle,
                artwork = s.artwork,
                positionStep = s.positionMs / STEP_MS,
                durationMs = s.durationMs,
                positionMs = s.positionMs,
            )
    }
}

/** The audiobook player's state, as coarse. */
private data class AudiobookNow(
    val bookId: String?,
    val playing: Boolean,
    val chapter: Int,
    val chapterTitle: String,
    val positionStep: Long,
    val bookLength: Long,
    val bookMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is AudiobookNow &&
            other.bookId == bookId &&
            other.playing == playing &&
            other.chapter == chapter &&
            other.chapterTitle == chapterTitle &&
            other.positionStep == positionStep &&
            other.bookLength == bookLength

    override fun hashCode(): Int = listOf(bookId, playing, chapter, positionStep).hashCode()

    companion object {
        fun of(s: AudiobookState) =
            AudiobookNow(
                bookId = s.bookId,
                playing = s.active && (s.isPlaying || s.loading),
                chapter = s.chapter,
                chapterTitle = s.currentChapter?.title.orEmpty(),
                positionStep = s.bookMs / STEP_MS,
                bookLength = s.bookLength,
                bookMs = s.bookMs,
            )
    }
}

/** Redraw at most every this much of playing */
private const val STEP_MS = 30_000L

private fun sources(app: FeederApplication): Flow<Sources> {
    val playlistPlayer: PlaylistPlayer by app.di.instance()
    val audiobookPlayer: AudiobookPlayer by app.di.instance()
    val zims = ZimLibrary.get(app)
    if (zims.books.value == null) zims.refresh()
    val libraries =
        combine(
            EbookLibrary.get(app).books,
            AudiobookLibrary.get(app).books,
            zims.books,
        ) { e, a, z -> e.size * 31 + a.size * 17 + (z?.size ?: -1) }
    return combine(
        CentralHistory.get(app).entries,
        playlistPlayer.state.map { PlaylistNow.of(it) }.distinctUntilChanged(),
        audiobookPlayer.state.map { AudiobookNow.of(it) }.distinctUntilChanged(),
        libraries,
    ) { entries, pl, ab, libs -> Sources(entries, pl, ab, libs) }
        .distinctUntilChanged()
}

/** Changes worth redrawing the widget for (after the first). */
internal fun continueWidgetChanges(context: Context): Flow<Unit> = sources(context.applicationContext as FeederApplication).map { }

/** The widget's lines, newest first; types never used are left out. */
internal fun continueWidgetItems(context: Context): Flow<List<ContinueItem>> {
    val app = context.applicationContext as FeederApplication
    return sources(app)
        .conflate()
        .map { ContinueItems(app).build(it) }
        .flowOn(Dispatchers.IO)
}

private class ContinueItems(
    private val app: FeederApplication,
) {
    suspend fun build(src: Sources): List<ContinueItem> {
        val opened = src.entries.filter { it.a == CentralHistory.OPEN }
        return listOfNotNull(
            playlist(src.playlist, opened.firstOrNull { it.pl.isNotEmpty() }),
            audiobook(src.audiobook, opened.firstOrNull { it.t == CentralHistory.AUDIOBOOK }),
            ebook(opened.firstOrNull { it.t == CentralHistory.EBOOK }),
            zim(opened.firstOrNull { it.t == CentralHistory.ZIM }),
        ).sortedByDescending { it.at }
    }

    private suspend fun playlist(
        now: PlaylistNow,
        last: RecentEntry?,
    ): ContinueItem? {
        val live = now.playlistId != null
        if (!live && last == null) return null
        val title: String
        val subtitle: String
        val image: String?
        val progress: Float?
        val left: Long?
        if (live) {
            title = now.title.ifBlank { now.name }
            subtitle = join(now.name, now.feedTitle)
            image = now.artwork
            progress = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else null
            left = if (now.durationMs > 0) (now.durationMs - now.positionMs) / 1000 else null
        } else {
            title = last!!.n.ifBlank { last.pn }
            subtitle = join(last.pn, last.s)
            image = last.img
            val dur = last.dur
            val pos = last.pos
            progress = if (dur != null && dur > 0 && pos != null) (pos.toFloat() / dur).coerceIn(0f, 1f) else last.f?.toFloat()
            left = if (dur != null && pos != null && dur > pos) dur - pos else null
        }
        val playlistId = now.playlistId ?: last!!.pl
        val cover = image?.takeIf { it.isNotBlank() }?.let { loadUrl(it) }
        return item(
            kind = ContinueKind.PLAYLIST,
            id = playlistId,
            itemKey = if (live) "" else last!!.i,
            title = title,
            subtitle = join(subtitle, left?.let(::leftText)),
            cover = cover,
            progress = progress,
            percent = null,
            playing = now.playing,
            loaded = live,
            at = if (now.playing) Long.MAX_VALUE else maxOf(last?.at ?: 0, if (live) System.currentTimeMillis() - 1 else 0),
            uri = "$DEEP_LINK_BASE_URI/playlists?open=${Uri.encode(playlistId)}",
            hue = 20f,
        )
    }

    private fun audiobook(
        now: AudiobookNow,
        last: RecentEntry?,
    ): ContinueItem? {
        val live = now.bookId != null
        if (!live && last == null) return null
        val id = now.bookId ?: last!!.i
        val book = AudiobookLibrary.get(app).get(id)
        val title = book?.title?.ifBlank { null } ?: last?.takeIf { it.i == id }?.n ?: "Audiobook"
        val subtitle: String
        val progress: Float?
        if (live) {
            val chapter = if (now.chapterTitle.isNotBlank()) "Ch. ${now.chapter + 1} · ${now.chapterTitle}" else book?.author.orEmpty()
            val left = if (now.bookLength > 0) (now.bookLength - now.bookMs) / 1000 else null
            subtitle = join(chapter, left?.let(::leftText))
            progress = if (now.bookLength > 0) (now.bookMs.toFloat() / now.bookLength).coerceIn(0f, 1f) else null
        } else {
            val f = last!!.f
            subtitle = join(last.s.ifBlank { book?.author.orEmpty() }, f?.let { percentOf(it) })
            progress = f?.toFloat()
        }
        val lastAt = last?.takeIf { it.i == id }?.at ?: 0
        return item(
            kind = ContinueKind.AUDIOBOOK,
            id = id,
            itemKey = "",
            title = title,
            subtitle = subtitle,
            cover = loadFile(AudiobookLibrary.get(app).coverFile(id)),
            progress = progress,
            percent = null,
            playing = now.playing,
            loaded = live,
            at = if (now.playing) Long.MAX_VALUE else maxOf(lastAt, if (live) System.currentTimeMillis() - 2 else 0),
            uri = "$DEEP_LINK_BASE_URI/audiobook?book=${Uri.encode(id)}",
            hue = 52f,
        )
    }

    private fun ebook(last: RecentEntry?): ContinueItem? {
        last ?: return null
        val book = EbookLibrary.get(app).get(last.i)
        val f = last.f
        return item(
            kind = ContinueKind.EBOOK,
            id = last.i,
            itemKey = "",
            title = book?.title?.ifBlank { null } ?: last.n,
            subtitle = last.s.ifBlank { book?.author.orEmpty() },
            cover = loadFile(EbookLibrary.get(app).coverFile(last.i)),
            progress = f?.toFloat(),
            percent = if (last.d) app.getString(R.string.continue_widget_finished) else f?.let { percentOf(it) },
            playing = false,
            loaded = false,
            at = last.at,
            uri = "$DEEP_LINK_BASE_URI/ebook?book=${Uri.encode(last.i)}",
            hue = 38f,
        )
    }

    private fun zim(last: RecentEntry?): ContinueItem? {
        last ?: return null
        val cut = last.i.indexOf('\n')
        val bookId = if (cut > 0) last.i.substring(0, cut) else last.i
        val path = last.x.ifEmpty { if (cut > 0) last.i.substring(cut + 1) else "" }
        val book =
            ZimLibrary
                .get(app)
                .books.value
                ?.firstOrNull { it.id == bookId }
        val cover =
            book?.let { b ->
                b.icon?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                    ?: ZimSnapshots.get(app).cached(b)
            }
        return item(
            kind = ContinueKind.ZIM,
            id = bookId,
            itemKey = path,
            title = last.n.ifBlank { book?.title ?: bookId },
            subtitle = last.s.ifBlank { book?.title.orEmpty() },
            cover = cover,
            progress = null,
            percent = null,
            playing = false,
            loaded = false,
            at = last.at,
            uri = "$DEEP_LINK_BASE_URI/zim?book=${Uri.encode(bookId)}&path=${Uri.encode(path)}",
            hue = 214f,
        )
    }

    private fun item(
        kind: ContinueKind,
        id: String,
        itemKey: String,
        title: String,
        subtitle: String,
        cover: Bitmap?,
        progress: Float?,
        percent: String?,
        playing: Boolean,
        loaded: Boolean,
        at: Long,
        uri: String,
        hue: Float,
    ) = ContinueItem(
        kind = kind,
        id = id,
        itemKey = itemKey,
        title = title,
        subtitle = subtitle,
        cover = cover?.let { ContinueWidgetArt.cookieCover(it, COVER_PX) },
        progress = progress,
        percent = percent,
        playing = playing,
        loaded = loaded,
        at = at,
        tint = ContinueWidgetArt.tintOf(cover, hue),
        uri = uri,
    )

    private fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

    private fun percentOf(f: Double): String = "${(f * 100).toInt().coerceIn(if (f > 0) 1 else 0, 100)}%"

    private fun leftText(seconds: Long): String {
        val m = ((seconds + 30) / 60).coerceAtLeast(1)
        return if (m < 60) {
            app.getString(R.string.continue_widget_minutes_left, m.toInt())
        } else {
            app.getString(R.string.continue_widget_hours_left, (m / 60).toInt(), (m % 60).toInt())
        }
    }

    private suspend fun loadUrl(url: String): Bitmap? {
        covers.get(url)?.let { return it }
        val request =
            ImageRequest
                .Builder(app)
                .data(url)
                .size(LOAD_PX, LOAD_PX)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
        val bitmap =
            withTimeoutOrNull(3_000L) {
                (app.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            } ?: return null
        covers.put(url, bitmap)
        return bitmap
    }

    private fun loadFile(file: File): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}:${file.lastModified()}"
        covers.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= LOAD_PX && bounds.outHeight / (sample * 2) >= LOAD_PX) sample *= 2
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return null
        covers.put(key, bitmap)
        return bitmap
    }

    companion object {
        /** Covers as drawn in the pill (px) */
        const val COVER_PX = 168
        private const val LOAD_PX = 192
        private val covers = LruCache<String, Bitmap>(12)
    }
}
