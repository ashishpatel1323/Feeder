package com.nononsenseapps.feeder.ui.compose.home

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.audiobooks.AudiobookLibrary
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.history.CentralHistoryRecorder
import com.nononsenseapps.feeder.model.history.RecentEntry
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.items
import com.nononsenseapps.feeder.model.shelf.CoverBitmaps
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.ui.compose.history.CentralHistoryNav
import com.nononsenseapps.feeder.ui.compose.playlists.PlaylistsViewModel
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.LocalPaperMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** Where Home's tiles go. */
class HomeShelvesNav(
    val recent: CentralHistoryNav,
    val openPlaylist: (id: String) -> Unit,
    val openSettings: () -> Unit,
)

/** What "Jump back in" shows: things you open, not highlights, notes or feed changes. */
private val OPENABLE =
    setOf(
        CentralHistory.ARTICLE,
        CentralHistory.PODCAST,
        CentralHistory.YOUTUBE,
        CentralHistory.AUDIOBOOK,
        CentralHistory.EBOOK,
        CentralHistory.ZIM,
        CentralHistory.MEDIA,
    )

private const val JUMP_BACK_COUNT = 6
private const val SHELF_COUNT = 12

/**
 * Home (redesign, YouTube Music style): "Jump back in" with what you had open on any device, then
 * one shelf per playlist with its next items. Each shelf title opens the whole playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeShelvesScreen(
    playlists: PlaylistsViewModel,
    nav: HomeShelvesNav,
    modifier: Modifier = Modifier,
) {
    val di = LocalDI.current
    val context = LocalContext.current
    val history: CentralHistory by di.instance()
    val recorder: CentralHistoryRecorder by di.instance()
    val scope = rememberCoroutineScope()
    val entries by history.entries.collectAsState()
    val state by playlists.viewState.collectAsState()
    val recent =
        remember(entries) {
            entries
                .filter { it.a == CentralHistory.OPEN && it.t in OPENABLE && !it.d }
                .distinctBy { it.key }
                .take(JUMP_BACK_COUNT)
        }
    val shelves: List<Pair<Playlist, List<PlaylistCandidate>>> =
        remember(state) {
            state.playlists
                .map { it to it.items(state.candidates, state.ctx, state.byId).take(SHELF_COUNT) }
                .filter { it.second.isNotEmpty() }
        }

    fun open(e: RecentEntry) {
        scope.launch { openRecent(context, e, nav.recent, recorder) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_home)) },
                actions = {
                    IconButton(onClick = nav.openSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = FeederTokens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xl),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (recent.isNotEmpty()) {
                item(key = "jump") {
                    Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm), modifier = Modifier.padding(horizontal = FeederTokens.Space.lg)) {
                        ShelfTitle(stringResource(R.string.shelf_jump_back_in), null)
                        recent.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                                pair.forEach { e -> RecentTile(e, Modifier.weight(1f)) { open(e) } }
                                if (pair.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            shelves.forEach { (pl, items) ->
                item(key = "pl:${pl.id}") {
                    Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                        ShelfTitle(
                            pl.name,
                            onShowAll = { nav.openPlaylist(pl.id) },
                            modifier = Modifier.padding(horizontal = FeederTokens.Space.lg),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = FeederTokens.Space.lg),
                            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                        ) {
                            items(items, key = { it.id }) { c -> ItemTile(c) { nav.recent.openArticle(c.id) } }
                        }
                    }
                }
            }
            if (recent.isEmpty() && shelves.isEmpty() && state.loaded) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.shelves_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(FeederTokens.Space.xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfTitle(
    title: String,
    onShowAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onShowAll != null) {
            TextButton(onClick = onShowAll) { Text(stringResource(R.string.shelf_show_all)) }
        }
    }
}

/** A compact two-per-row tile, like Spotify's recents: art, title, progress line. */
@Composable
private fun RecentTile(
    e: RecentEntry,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val paper = LocalPaperMode.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(56.dp)
                .clip(RoundedCornerShape(FeederTokens.Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // Paper has no tonal fills: the tile gets an outline instead
                .then(if (paper) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(FeederTokens.Radius.sm)) else Modifier)
                .clickable(onClick = onClick),
    ) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val bookCover = rememberLocalBookCover(e)
            if (e.img.isNotEmpty()) {
                AsyncImage(model = e.img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (bookCover != null) {
                Image(bookCover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = FeederTokens.Space.sm)) {
            Text(
                e.n.ifBlank { e.s },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val f = e.f
            if (f != null && f > 0) {
                Box(
                    Modifier
                        .padding(top = FeederTokens.Space.xs)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(Modifier.fillMaxWidth(f.toFloat().coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** The cover of a book on this phone, for an ebook or audiobook entry (they have no picture link). */
@Composable
private fun rememberLocalBookCover(e: RecentEntry): ImageBitmap? {
    val context = LocalContext.current
    val file =
        remember(e.t, e.i, e.img) {
            when {
                e.img.isNotEmpty() || e.i.isEmpty() -> null
                e.t == CentralHistory.EBOOK -> EbookLibrary.get(context).coverFile(e.i)
                e.t == CentralHistory.AUDIOBOOK -> AudiobookLibrary.get(context).coverFile(e.i)
                else -> null
            }
        } ?: return null
    // A cover picked or found later changes the library's version: look again then
    val version by remember(e.t) {
        when (e.t) {
            CentralHistory.EBOOK -> EbookLibrary.get(context).coverVersion
            else -> AudiobookLibrary.get(context).version
        }
    }.collectAsState()
    val cover by produceState(remember(file) { CoverBitmaps.peek(file) }, file, version) {
        value = withContext(Dispatchers.IO) { CoverBitmaps.load(file) }
    }
    return cover
}

/** A shelf tile: square art (16:9 for videos), two-line title, feed name. */
@Composable
private fun ItemTile(
    c: PlaylistCandidate,
    onClick: () -> Unit,
) {
    val video = c.link?.contains("youtube.com/watch") == true || c.feedUrl.contains("youtube.com/feeds")
    val width = if (video) 200.dp else 140.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
        modifier = Modifier.width(width).clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (video) 16f / 9f else 1f)
                .clip(RoundedCornerShape(FeederTokens.Radius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val img = c.image?.url ?: c.feedImage
            if (!img.isNullOrBlank()) {
                AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            c.feedTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens a History entry the way the History screen does (the cases Home shows). */
private suspend fun openRecent(
    context: Context,
    e: RecentEntry,
    nav: CentralHistoryNav,
    recorder: CentralHistoryRecorder,
) {
    when (e.t) {
        CentralHistory.ARTICLE, CentralHistory.PODCAST -> {
            val id = withContext(Dispatchers.IO) { recorder.itemIdOf(e.i) } ?: return
            nav.openArticle(id)
        }
        CentralHistory.YOUTUBE -> {
            val id = withContext(Dispatchers.IO) { recorder.itemIdOf(e.i) }
            when {
                id != null && e.x.isNotEmpty() -> nav.openVideo(id, e.x)
                id != null -> nav.openArticle(id)
                e.x.isNotEmpty() -> nav.openVideo(0, e.x)
            }
        }
        CentralHistory.AUDIOBOOK -> nav.openAudiobook(e.i)
        CentralHistory.MEDIA -> nav.openMedia(e.i)
        CentralHistory.EBOOK -> nav.openBook(e.i, e.x.ifEmpty { null })
        CentralHistory.ZIM -> {
            val cut = e.i.indexOf('\n')
            val book = if (cut > 0) e.i.substring(0, cut) else e.i
            val path = e.x.ifEmpty { if (cut > 0) e.i.substring(cut + 1) else "" }
            if (path.isNotEmpty()) ZimLibrary.get(context).rememberPath(book, path)
            nav.openZim(book)
        }
    }
}
