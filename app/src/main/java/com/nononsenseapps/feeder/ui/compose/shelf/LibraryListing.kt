package com.nononsenseapps.feeder.ui.compose.shelf

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.books.LocalBooksPage
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.navigation.LocalMainNav
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

/** One way to add to a library, in the top bar's + menu and as a chip in the empty state. */
class ShelfAdd(
    val icon: ImageVector,
    val label: String,
    val run: () -> Unit,
)

/** Something being added or fetched that isn't a shelf item yet (an import, a catalog download). */
class ShelfJob(
    val key: String,
    val label: String,
    /** Bytes so far; 0 = not known yet */
    val done: Long = 0,
    /** Bytes in all; 0 = unknown, the bar just moves */
    val total: Long = 0,
    /** Stop it (only where it can be stopped) */
    val onCancel: (() -> Unit)? = null,
)

/**
 * The one listing screen of the Audiobooks, Ebooks and ZIM tabs: top bar (title, refresh, Dropbox
 * account, + add menu), jobs, then the Cards | List switch, the All / On this phone / In Dropbox
 * chips and the shelf, or the empty state. Same as the web app's LibraryListing in Shelf.tsx.
 *
 * Inside Books ([LocalBooksPage]) there is no top bar: Add, Check Dropbox, the Dropbox button and
 * Cards | List go up to the Books header, and its search filters the shelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryListingScreen(
    title: String,
    prefs: ShelfPrefs,
    items: List<ShelfItem>,
    emptyIcon: ImageVector,
    emptyText: String,
    emptyDevice: String,
    addLabel: String,
    addActions: List<ShelfAdd>,
    dropbox: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Check Dropbox again; null hides the button (signed out) */
    onRefresh: (() -> Unit)? = null,
    refreshLabel: String = stringResource(R.string.shelf_check_dropbox),
    refreshing: Boolean = false,
    /** Nothing to show yet because the library is still being read */
    loading: Boolean = false,
    jobs: List<ShelfJob> = emptyList(),
    /** What is in Dropbox plays from there right away (audiobooks), so it isn't dimmed */
    streams: Boolean = false,
    footer: String? = null,
    /** Shown instead of everything when this phone can't open the files at all */
    unavailable: String? = null,
) {
    var addMenu by remember { mutableStateOf(false) }
    val page = LocalBooksPage.current
    if (page != null) {
        val chrome = page.chrome
        // Plain fields first, then the state the header reads, so it never sees a stale callback
        SideEffect {
            chrome.onRefresh = onRefresh
            chrome.addActions = addActions
            chrome.dropbox = dropbox
            chrome.count = items.size
            chrome.available = unavailable == null
            chrome.hasRefresh = onRefresh != null
            chrome.refreshing = refreshing
            chrome.refreshLabel = refreshLabel
            chrome.addLabels = addActions.map { it.label }
            chrome.prefs = prefs
            chrome.registered = true
        }
    }
    val query = page?.host?.query?.trim().orEmpty()
    val shown =
        if (query.isEmpty()) {
            items
        } else {
            items.filter { it.title.contains(query, ignoreCase = true) || it.subtitle?.contains(query, ignoreCase = true) == true }
        }
    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (page == null) {
                ListingTopBar(title, onRefresh, refreshLabel, refreshing, unavailable, dropbox, addActions, addLabel, addMenu) { addMenu = it }
            }
        },
    ) { padding ->
        ListingBody(padding, prefs, items, shown, query, emptyIcon, emptyText, emptyDevice, addActions, loading, jobs, streams, footer, unavailable, showLayout = page == null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListingTopBar(
    title: String,
    onRefresh: (() -> Unit)?,
    refreshLabel: String,
    refreshing: Boolean,
    unavailable: String?,
    dropbox: @Composable () -> Unit,
    addActions: List<ShelfAdd>,
    addLabel: String,
    addMenu: Boolean,
    setAddMenu: (Boolean) -> Unit,
) {
            SensibleTopAppBar(
                title = title,
                actions = {
                    if (onRefresh != null && unavailable == null) {
                        PlainTooltipBox(tooltip = { Text(refreshLabel) }) {
                            IconButton(onClick = onRefresh, enabled = !refreshing) {
                                Icon(Icons.Outlined.Refresh, contentDescription = refreshLabel)
                            }
                        }
                    }
                    dropbox()
                    if (unavailable == null && addActions.isNotEmpty()) {
                        Box {
                            PlainTooltipBox(tooltip = { Text(addLabel) }) {
                                IconButton(onClick = { if (addActions.size == 1) addActions[0].run() else setAddMenu(true) }) {
                                    Icon(Icons.Outlined.Add, contentDescription = addLabel)
                                }
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { setAddMenu(false) }) {
                                addActions.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.label) },
                                        leadingIcon = { Icon(a.icon, null) },
                                        onClick = {
                                            setAddMenu(false)
                                            a.run()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
}

@Composable
private fun ListingBody(
    padding: PaddingValues,
    prefs: ShelfPrefs,
    items: List<ShelfItem>,
    shown: List<ShelfItem>,
    query: String,
    emptyIcon: ImageVector,
    emptyText: String,
    emptyDevice: String,
    addActions: List<ShelfAdd>,
    loading: Boolean,
    jobs: List<ShelfJob>,
    streams: Boolean,
    footer: String?,
    unavailable: String?,
    showLayout: Boolean,
) {
        if (unavailable != null) {
            Text(
                unavailable,
                modifier = Modifier.padding(padding).padding(FeederTokens.Space.xl),
                style = MaterialTheme.typography.bodyLarge,
            )
            return
        }
        LazyVerticalGrid(
            columns = prefs.columns,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, top = if (showLayout) 0.dp else FeederTokens.Space.sm, bottom = FeederTokens.Space.xl),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(if (prefs.cards) 18.dp else 0.dp),
        ) {
            items(jobs, key = { "job:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { JobRow(it) }
            if (loading && items.isEmpty()) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (items.isEmpty() && jobs.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(emptyIcon, emptyText, addActions) }
            } else if (items.isNotEmpty() && shown.isEmpty()) {
                item(key = "nomatch", span = { GridItemSpan(maxLineSpan) }) { NoMatch(query) }
            } else if (items.isNotEmpty()) {
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { ShelfControls(prefs, shown, showLayout = showLayout) }
                shelf(shown, prefs, emptyDevice, streams)
            }
            footer?.let {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = FeederTokens.Space.lg))
                }
            }
        }
}

/** The Books search found nothing on this shelf: offer search everywhere (inside books too). */
@Composable
private fun NoMatch(query: String) {
    val nav = LocalMainNav.current
    Column(
        Modifier.fillMaxWidth().padding(FeederTokens.Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
    ) {
        Text(
            stringResource(R.string.books_no_match, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AssistChip(onClick = nav.openSearch, label = { Text(stringResource(R.string.books_search_inside)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
    }
}

@Composable
private fun JobRow(job: ShelfJob) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(job.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (job.done > 0) {
                    Text(
                        if (job.total > 0) {
                            stringResource(R.string.zim_progress, Formatter.formatShortFileSize(context, job.done), Formatter.formatShortFileSize(context, job.total))
                        } else {
                            Formatter.formatShortFileSize(context, job.done)
                        },
                    )
                }
                Spacer(Modifier.height(FeederTokens.Space.xs))
                if (job.total > 0) {
                    LinearProgressIndicator(progress = { (job.done.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(40.dp)) },
        trailingContent =
            job.onCancel?.let { cancel ->
                { IconButton(onClick = cancel) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.zim_stop)) } }
            },
    )
}

@Composable
private fun EmptyLibrary(
    icon: ImageVector,
    text: String,
    addActions: List<ShelfAdd>,
) {
    Column(
        Modifier.fillMaxWidth().padding(FeederTokens.Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm, Alignment.CenterHorizontally)) {
            addActions.forEach { a ->
                AssistChip(onClick = a.run, label = { Text(a.label) }, leadingIcon = { Icon(a.icon, null) })
            }
        }
    }
}
