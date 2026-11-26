package com.nononsenseapps.feeder.ui.compose.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.DateBucket
import com.nononsenseapps.feeder.archmodel.DateBucketBounds
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.history.CentralHistoryRecorder
import com.nononsenseapps.feeder.model.history.RecentEntry
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.ui.compose.feed.dateHeaderText
import com.nononsenseapps.feeder.ui.compose.feed.rememberGroupHeaderLabels
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Where History's rows lead. */
class CentralHistoryNav(
    val openArticle: (itemId: Long) -> Unit,
    val openVideo: (itemId: Long, videoId: String) -> Unit,
    val openBook: (bookId: String, cfi: String?) -> Unit,
    val openAudiobook: (bookId: String) -> Unit,
    val openZim: (bookId: String) -> Unit,
    val openFeed: (feedId: Long) -> Unit = {},
)

/** The chips of History. */
enum class RecentFilter(
    val label: Int,
) {
    ALL(R.string.central_history_filter_all),
    ARTICLE(R.string.central_history_filter_articles),
    YOUTUBE(R.string.central_history_filter_youtube),
    PODCAST(R.string.central_history_filter_podcasts),
    PLAYLIST(R.string.central_history_filter_playlists),
    AUDIOBOOK(R.string.central_history_filter_audiobooks),
    EBOOK(R.string.central_history_filter_ebooks),
    ZIM(R.string.central_history_filter_zim),
    MARKS(R.string.central_history_filter_marks),
    FEED(R.string.central_history_filter_feeds),
    ;

    fun matches(e: RecentEntry): Boolean =
        when (this) {
            ALL -> true
            PLAYLIST -> e.pl.isNotEmpty()
            MARKS -> e.t == CentralHistory.HIGHLIGHT || e.t == CentralHistory.NOTE
            ARTICLE -> e.t == CentralHistory.ARTICLE
            YOUTUBE -> e.t == CentralHistory.YOUTUBE
            PODCAST -> e.t == CentralHistory.PODCAST
            AUDIOBOOK -> e.t == CentralHistory.AUDIOBOOK
            EBOOK -> e.t == CentralHistory.EBOOK
            ZIM -> e.t == CentralHistory.ZIM
            FEED -> e.t == CentralHistory.FEED
        }
}

/**
 * History: everything opened (articles, videos, episodes, audiobooks, ebooks, ZIM pages) and
 * highlights, notes and feeds made or deleted, on every device, in Mail-style date groups.
 * [onNavigateUp] null = it is a bottom bar tab (no Back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CentralHistoryScreen(
    onNavigateUp: (() -> Unit)?,
    nav: CentralHistoryNav,
    modifier: Modifier = Modifier,
) {
    val di = LocalDI.current
    val context = LocalContext.current
    val history: CentralHistory by di.instance()
    val recorder: CentralHistoryRecorder by di.instance()
    val repository: Repository by di.instance()
    val highlightStore: HighlightStore by di.instance()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entries by history.entries.collectAsState()
    var filter by rememberSaveable { mutableStateOf(RecentFilter.ALL) }
    var menu by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf<RecentEntry?>(null) }
    var askResubscribe by remember { mutableStateOf<RecentEntry?>(null) }
    val notHere = stringResource(R.string.central_history_not_here)
    val labels = rememberGroupHeaderLabels()
    val locale = Locale.getDefault()
    val shown = remember(entries, filter) { entries.filter { filter.matches(it) } }
    val groups =
        remember(shown) {
            val bounds = DateBucketBounds.current(locale)
            val zone = ZoneId.systemDefault()
            shown.groupBy { bounds.keyOf(Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate()) }.toList()
        }

    fun open(e: RecentEntry) {
        scope.launch {
            val missing = { scope.launch { snackbar.showSnackbar(notHere) } }
            if (e.a == CentralHistory.DELETE) {
                if (e.t == CentralHistory.FEED) askResubscribe = e
                return@launch
            }
            when (e.t) {
                CentralHistory.ARTICLE, CentralHistory.PODCAST -> {
                    val id = withContext(Dispatchers.IO) { recorder.itemIdOf(e.i) } ?: return@launch run { missing() }
                    nav.openArticle(id)
                }
                CentralHistory.YOUTUBE -> {
                    val id = withContext(Dispatchers.IO) { recorder.itemIdOf(e.i) }
                    when {
                        id != null && e.x.isNotEmpty() -> nav.openVideo(id, e.x)
                        id != null -> nav.openArticle(id)
                        e.x.isNotEmpty() -> nav.openVideo(0, e.x)
                        else -> missing()
                    }
                }
                CentralHistory.AUDIOBOOK -> nav.openAudiobook(e.i)
                CentralHistory.EBOOK -> nav.openBook(e.i, e.x.ifEmpty { null })
                CentralHistory.ZIM -> {
                    val cut = e.i.indexOf('\n')
                    val book = if (cut > 0) e.i.substring(0, cut) else e.i
                    val path = e.x.ifEmpty { if (cut > 0) e.i.substring(cut + 1) else "" }
                    // The reader opens where the book was left
                    if (path.isNotEmpty()) ZimLibrary.get(context).rememberPath(book, path)
                    nav.openZim(book)
                }
                CentralHistory.HIGHLIGHT, CentralHistory.NOTE -> {
                    val h = highlightStore.get(e.i)
                    val itemKey = h?.itemKey ?: e.x
                    val bookId = EbookLibrary.bookIdOfItem(itemKey)
                    val zimPage = ZimLibrary.pageOfHighlight(itemKey)
                    when {
                        bookId != null -> nav.openBook(bookId, h?.cfi)
                        zimPage != null -> {
                            val library = ZimLibrary.get(context)
                            library.rememberPath(zimPage.first, zimPage.second)
                            if (h != null) library.pendingHighlight = h.id
                            nav.openZim(zimPage.first)
                        }
                        else -> {
                            val id = withContext(Dispatchers.IO) { recorder.itemIdOf(itemKey) } ?: return@launch run { missing() }
                            nav.openArticle(id)
                        }
                    }
                }
                CentralHistory.FEED -> {
                    val feed = withContext(Dispatchers.IO) { runCatching { repository.getFeed(URL(e.i)) }.getOrNull() } ?: return@launch run { missing() }
                    nav.openFeed(feed.id)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_history)) },
                navigationIcon = {
                    if (onNavigateUp != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.central_history_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.central_history_clear)) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    askClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.xs),
            ) {
                RecentFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(FeederTokens.Space.xl), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md)) {
                        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Text(
                            stringResource(if (entries.isEmpty()) R.string.central_history_empty else R.string.central_history_empty_filter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for ((key, list) in groups) {
                        item(key = "h:$key") {
                            Text(
                                dateHeaderText(key, labels, locale),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .semantics { heading() }
                                        .padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, top = FeederTokens.Space.lg, bottom = FeederTokens.Space.xs),
                            )
                        }
                        items(list, key = { it.key }) { e ->
                            val recent = key.bucket == DateBucket.TODAY || key.bucket == DateBucket.YESTERDAY
                            SwipeToRemove(onRemove = { askRemove = e }) {
                                RecentRow(e, timeOnly = recent, onOpen = { open(e) }, onRemove = { askRemove = e })
                            }
                        }
                    }
                }
            }
        }
    }

    askRemove?.let { e ->
        AlertDialog(
            onDismissRequest = { askRemove = null },
            title = { Text(stringResource(R.string.central_history_remove_title, rowTitle(e))) },
            text = { Text(stringResource(R.string.central_history_remove_body, stringResource(thingOf(e.t)))) },
            confirmButton = {
                TextButton(onClick = {
                    history.remove(e.key)
                    askRemove = null
                }) { Text(stringResource(R.string.central_history_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { askRemove = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.central_history_clear_title)) },
            text = { Text(stringResource(R.string.central_history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    history.clear()
                    askClear = false
                }) { Text(stringResource(R.string.central_history_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    askResubscribe?.let { e ->
        AlertDialog(
            onDismissRequest = { askResubscribe = null },
            title = { Text(stringResource(R.string.central_history_resubscribe_title, e.n.ifBlank { e.i })) },
            text = { Text(stringResource(R.string.central_history_resubscribe_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askResubscribe = null
                    scope.launch(Dispatchers.IO) {
                        val url = runCatching { URL(e.i) }.getOrNull() ?: return@launch
                        val id = repository.getFeed(url)?.id ?: repository.saveFeed(Feed(url = url, title = e.n, whenModified = Instant.now()))
                        recorder.feedSubscribed(e.i, e.n, e.img)
                        runOnceRssSync(di = di, feedId = id, triggeredByUser = true)
                    }
                }) { Text(stringResource(R.string.central_history_resubscribe)) }
            },
            dismissButton = { TextButton(onClick = { askResubscribe = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** A swipe either way asks to remove the row from history; the row springs back meanwhile. */
@Composable
private fun SwipeToRemove(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = FeederTokens.Space.xl),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
        onDismiss = {
            onRemove()
            scope.launch { state.reset() }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

@Composable
private fun rowTitle(e: RecentEntry): String {
    val n = e.n.ifBlank { stringResource(R.string.central_history_untitled) }
    val delete = e.a == CentralHistory.DELETE
    return when (e.t) {
        CentralHistory.HIGHLIGHT -> stringResource(if (delete) R.string.central_history_highlight_deleted else R.string.central_history_highlighted, n)
        CentralHistory.NOTE -> stringResource(if (delete) R.string.central_history_note_deleted else R.string.central_history_noted, n)
        CentralHistory.FEED -> stringResource(if (delete) R.string.central_history_feed_deleted else R.string.central_history_subscribed, n)
        else -> n
    }
}

private fun thingOf(t: String): Int =
    when (t) {
        CentralHistory.YOUTUBE -> R.string.central_history_thing_youtube
        CentralHistory.PODCAST -> R.string.central_history_thing_podcast
        CentralHistory.AUDIOBOOK -> R.string.central_history_thing_audiobook
        CentralHistory.EBOOK -> R.string.central_history_thing_ebook
        CentralHistory.ZIM -> R.string.central_history_thing_zim
        CentralHistory.HIGHLIGHT -> R.string.central_history_thing_highlight
        CentralHistory.NOTE -> R.string.central_history_thing_note
        CentralHistory.FEED -> R.string.central_history_thing_feed
        else -> R.string.central_history_thing_article
    }

private fun iconOf(t: String): ImageVector =
    when (t) {
        CentralHistory.YOUTUBE -> Icons.Outlined.SmartDisplay
        CentralHistory.PODCAST -> Icons.Outlined.Podcasts
        CentralHistory.AUDIOBOOK -> Icons.Outlined.Headphones
        CentralHistory.EBOOK -> Icons.AutoMirrored.Outlined.MenuBook
        CentralHistory.ZIM -> Icons.Outlined.Public
        CentralHistory.HIGHLIGHT -> Icons.Outlined.BorderColor
        CentralHistory.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
        CentralHistory.FEED -> Icons.Outlined.RssFeed
        else -> Icons.AutoMirrored.Outlined.Article
    }

/** "Read", "Watched", "12:03 / 40:00", "42%", or null (same as the web app's progressText). */
@Composable
private fun progressText(e: RecentEntry): String? {
    if (e.a != CentralHistory.OPEN) return null
    return when {
        e.t == CentralHistory.ARTICLE -> if (e.d) stringResource(R.string.central_history_read) else null
        e.t == CentralHistory.ZIM -> null
        e.d ->
            stringResource(
                when (e.t) {
                    CentralHistory.YOUTUBE -> R.string.central_history_watched
                    CentralHistory.PODCAST -> R.string.central_history_played
                    else -> R.string.central_history_finished
                },
            )
        (e.t == CentralHistory.YOUTUBE || e.t == CentralHistory.PODCAST) && (e.pos ?: 0) > 0 -> {
            val pos = VideoPositions.formatClock((e.pos ?: 0).toInt())
            e.dur?.let { "$pos / ${VideoPositions.formatClock(it.toInt())}" } ?: pos
        }
        (e.f ?: 0.0) > 0 -> "${maxOf(1, Math.round((e.f ?: 0.0) * 100).toInt())}%"
        else -> null
    }
}

@Composable
private fun RecentRow(
    e: RecentEntry,
    timeOnly: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val removeLabel = stringResource(R.string.central_history_remove)
    val time =
        remember(e.at, timeOnly) {
            if (timeOnly) {
                DateFormat.getTimeFormat(context).format(Date(e.at))
            } else {
                DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()))
            }
        }
    val meta =
        listOfNotNull(
            progressText(e),
            e.s.ifBlank { null },
            e.pn.ifBlank { null },
            time,
            e.dn.ifBlank { null },
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.lg),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(removeLabel) {
                                onRemove()
                                true
                            },
                        )
                }.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.md),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(FeederTokens.Radius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                iconOf(e.t),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
            if (e.img.isNotEmpty()) {
                AsyncImage(model = e.img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            val f = e.f
            if (e.a == CentralHistory.OPEN && f != null && f > 0 && !e.d) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                ) {
                    Box(Modifier.fillMaxWidth(f.toFloat().coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs)) {
            Text(
                rowTitle(e),
                style = MaterialTheme.typography.titleMedium,
                color = if (e.a == CentralHistory.DELETE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (e.q.isNotEmpty()) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                    val swatch = if (e.t == CentralHistory.HIGHLIGHT || e.c.isNotEmpty()) Color(HighlightColor.of(e.c).swatch) else MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.width(3.dp).fillMaxHeight().background(swatch, RoundedCornerShape(FeederTokens.Radius.xs)))
                    Text(
                        e.q,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
