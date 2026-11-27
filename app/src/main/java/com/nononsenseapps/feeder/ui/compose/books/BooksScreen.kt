package com.nononsenseapps.feeder.ui.compose.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.audiobooks.AudiobooksScreen
import com.nononsenseapps.feeder.ui.compose.ebooks.EbooksScreen
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.zim.ZimLibraryScreen
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Books: Ebooks, Audiobooks, ZIM and Player as swipeable subtabs under one header (search, Dropbox, ⋮).
 * Opens on [askedTab] when a link names one, else on the subtab used last.
 */
@Composable
fun BooksScreen(
    askedTab: BookTab?,
    onOpenEbook: (String) -> Unit,
    onOpenAudiobook: (String) -> Unit,
    onOpenZim: (String) -> Unit,
    onOpenMediaFolder: (String) -> Unit = {},
    onOpenMediaFile: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = remember { BooksHost() }
    val start = remember { askedTab ?: BooksTabPreference.get(context) }
    val pager = rememberPagerState(initialPage = start.ordinal) { BookTab.entries.size }
    val scope = rememberCoroutineScope()

    LaunchedEffect(askedTab) {
        if (askedTab != null && pager.currentPage != askedTab.ordinal) pager.scrollToPage(askedTab.ordinal)
    }
    // Only a switch made here is remembered, not the subtab a link opened
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect {
            BooksTabPreference.set(context, BookTab.entries[it])
            host.query = ""
        }
    }

    val tab = BookTab.entries[pager.currentPage]
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
    ) {
        BooksTopRow(host, tab)
        PrimaryScrollableTabRow(
            selectedTabIndex = pager.currentPage,
            edgePadding = 0.dp,
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.books_subtabs) },
        ) {
            BookTab.entries.forEach { t ->
                val count = host.pages.getValue(t).count
                Tab(
                    selected = pager.currentPage == t.ordinal,
                    onClick = { scope.launch { pager.animateScrollToPage(t.ordinal) } },
                    text = {
                        Text(
                            if (count > 0) "${stringResource(t.label())} $count" else stringResource(t.label()),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        HorizontalPager(
            state = pager,
            // All three stay composed so the tab counts are right and swiping doesn't reload
            beyondViewportPageCount = BookTab.entries.size - 1,
            key = { it },
            modifier = Modifier.weight(1f),
        ) { page ->
            val pageTab = BookTab.entries[page]
            CompositionLocalProvider(LocalBooksPage provides remember(pageTab) { BooksPage(pageTab, host) }) {
                when (pageTab) {
                    BookTab.EBOOKS -> EbooksScreen(onOpenBook = onOpenEbook)
                    BookTab.AUDIOBOOKS -> AudiobooksScreen(onOpenBook = onOpenAudiobook)
                    BookTab.ZIM -> ZimLibraryScreen(onOpenBook = onOpenZim)
                    BookTab.PLAYER ->
                        com.nononsenseapps.feeder.ui.compose.media.MediaScreen(
                            folder = "",
                            onOpenFolder = onOpenMediaFolder,
                            onOpenFile = onOpenMediaFile,
                            onNavigateUp = {},
                        )
                }
            }
        }
    }
}

fun BookTab.label(): Int =
    when (this) {
        BookTab.EBOOKS -> R.string.tab_ebooks
        BookTab.AUDIOBOOKS -> R.string.tab_audiobooks
        BookTab.ZIM -> R.string.tab_zim
        BookTab.PLAYER -> R.string.media_player
    }

private fun BookTab.searchHint(): Int =
    when (this) {
        BookTab.EBOOKS -> R.string.books_search_ebooks
        BookTab.AUDIOBOOKS -> R.string.books_search_audiobooks
        BookTab.ZIM -> R.string.books_search_zim
        BookTab.PLAYER -> R.string.media_search
    }

/** Search pill (with the Dropbox button inside) and ⋮, for the shelf shown now. */
@Composable
private fun BooksTopRow(
    host: BooksHost,
    tab: BookTab,
) {
    val chrome = host.pages.getValue(tab)
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs, top = FeederTokens.Space.sm, bottom = FeederTokens.Space.xs)
                .fillMaxWidth(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(56.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                val hint = stringResource(tab.searchHint())
                BasicTextField(
                    value = host.query,
                    onValueChange = { host.query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.weight(1f).semantics { contentDescription = hint },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (host.query.isEmpty()) {
                                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                if (host.query.isNotEmpty()) {
                    IconButton(onClick = { host.query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.books_search_clear))
                    }
                } else if (chrome.registered && chrome.available) {
                    chrome.dropbox?.invoke()
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.books_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (chrome.available) {
                    chrome.addActions.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.label) },
                            leadingIcon = { Icon(a.icon, null) },
                            onClick = {
                                menu = false
                                a.run()
                            },
                        )
                    }
                    if (chrome.hasRefresh) {
                        DropdownMenuItem(
                            text = { Text(chrome.refreshLabel) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            enabled = !chrome.refreshing,
                            onClick = {
                                menu = false
                                chrome.onRefresh?.invoke()
                            },
                        )
                    }
                }
                chrome.prefs?.let { prefs ->
                    if (chrome.available) HorizontalDivider()
                    Text(
                        stringResource(R.string.books_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shelf_cards)) },
                        leadingIcon = { Icon(Icons.Outlined.GridView, null) },
                        trailingIcon = { if (prefs.cards) Icon(Icons.Default.Check, null) },
                        onClick = {
                            menu = false
                            prefs.updateCards(true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shelf_list)) },
                        leadingIcon = { Icon(Icons.Outlined.ViewAgenda, null) },
                        trailingIcon = { if (!prefs.cards) Icon(Icons.Default.Check, null) },
                        onClick = {
                            menu = false
                            prefs.updateCards(false)
                        },
                    )
                }
            }
        }
    }
}
