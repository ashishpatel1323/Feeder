package com.nononsenseapps.feeder.ui.compose.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.kind
import com.nononsenseapps.feeder.model.search.FeedHit
import com.nononsenseapps.feeder.model.search.FolderHit
import com.nononsenseapps.feeder.model.search.HighlightHit
import com.nononsenseapps.feeder.model.search.ItemHit
import com.nononsenseapps.feeder.model.search.Marked
import com.nononsenseapps.feeder.model.search.MatchField
import com.nononsenseapps.feeder.model.search.PlaylistHit
import com.nononsenseapps.feeder.model.search.SearchHit
import com.nononsenseapps.feeder.model.search.SearchScope
import com.nononsenseapps.feeder.model.search.clock
import com.nononsenseapps.feeder.model.search.queryTerms
import com.nononsenseapps.feeder.ui.compose.highlights.background
import com.nononsenseapps.feeder.ui.compose.item.ageText
import com.nononsenseapps.feeder.ui.compose.item.displayName
import com.nononsenseapps.feeder.ui.compose.item.icon
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/** Where search can take you besides its results. */
class GlobalSearchNav(
    val onBack: () -> Unit,
    val onOpenArticle: (Long) -> Unit,
    val onOpenFeed: (Long) -> Unit,
    val onOpenFolder: (String) -> Unit,
    val onOpenPlaylist: (String) -> Unit,
    val onOpenLink: (String) -> Unit,
    val onAddFeed: () -> Unit,
    val onDiscover: () -> Unit,
    val onPlaylists: () -> Unit,
    val onHighlights: () -> Unit,
)

private class Shortcut(
    val label: String,
    val icon: ImageVector,
    val keywords: String,
    val run: () -> Unit,
)

/**
 * Global search (Material 3 search view): a search field on top, filter chips for one type, and
 * results grouped by type. Empty, it offers recent searches, what you were in the middle of, and
 * a few places to go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    viewModel: GlobalSearchViewModel,
    nav: GlobalSearchNav,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val results = state.results
    val hasQuery = queryTerms(query).isNotEmpty()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    val shortcuts =
        listOf(
            Shortcut(stringResource(R.string.search_add_feed), Icons.Default.Add, "add subscribe new rss podcast youtube channel", nav.onAddFeed),
            Shortcut(stringResource(R.string.discover), Icons.Default.Explore, "discover find browse topics", nav.onDiscover),
            Shortcut(stringResource(R.string.playlists), Icons.AutoMirrored.Filled.QueueMusic, "playlists queue listen", nav.onPlaylists),
            Shortcut(stringResource(R.string.search_scope_highlights), Icons.Outlined.BorderColor, "highlights notes quotes", nav.onHighlights),
        )

    val open: (SearchHit) -> Unit = { hit ->
        keyboard?.hide()
        when (hit) {
            is ItemHit -> {
                val order = results.items.filter { scope == SearchScope.ALL || it.matches(scope) }.map { it.item.id }
                viewModel.openingItem(order)
                if (hit.field == MatchField.TRANSCRIPT && hit.atMs != null) viewModel.playFrom(hit)
                nav.onOpenArticle(hit.item.id)
            }

            is HighlightHit -> {
                viewModel.remember()
                val id = viewModel.itemIdOf(hit.highlight)
                when {
                    id != null -> nav.onOpenArticle(id)
                    hit.highlight.itemLink != null -> nav.onOpenLink(hit.highlight.itemLink)
                }
            }

            is FeedHit -> {
                viewModel.remember()
                nav.onOpenFeed(hit.feedId)
            }

            is FolderHit -> {
                viewModel.remember()
                nav.onOpenFolder(hit.tag)
            }

            is PlaylistHit -> {
                viewModel.remember()
                nav.onOpenPlaylist(hit.playlist.id)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.sm),
            ) {
                SearchField(
                    query = query,
                    onQuery = viewModel::setQuery,
                    onBack = nav.onBack,
                    onSearch = {
                        keyboard?.hide()
                        viewModel.remember()
                    },
                    focus = focus,
                )
                if (hasQuery) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                        contentPadding = PaddingValues(horizontal = FeederTokens.Space.xs),
                        modifier = Modifier.padding(top = FeederTokens.Space.sm),
                    ) {
                        items(SearchScope.entries) { s ->
                            val n = results.count(s)
                            FilterChip(
                                selected = scope == s,
                                onClick = { viewModel.setScope(s) },
                                enabled = s == SearchScope.ALL || n > 0 || scope == s,
                                label = { Text("${s.label()}  $n") },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = FeederTokens.Space.xxl),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
        ) {
            if (!hasQuery) {
                if (recent.isNotEmpty()) {
                    groupLabel(R.string.search_recent)
                    item(key = "recent") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                            modifier = Modifier.padding(horizontal = FeederTokens.Space.lg),
                        ) {
                            recent.forEach { r ->
                                val forget = stringResource(R.string.search_forget, r)
                                InputChip(
                                    selected = false,
                                    onClick = { viewModel.setQuery(r) },
                                    label = { Text(r) },
                                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = forget,
                                            modifier =
                                                Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .clickable { viewModel.forget(r) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.inProgress.isNotEmpty()) {
                    groupLabel(R.string.search_jump_back)
                    items(state.inProgress, key = { "resume:${it.item.id}" }) { p ->
                        ResultRow(
                            leading = { ItemThumb(p.item) },
                            title = AnnotatedString(p.item.title),
                            meta =
                                listOfNotNull(
                                    p.item.kind.displayName(),
                                    p.item.feedTitle,
                                    if (p.percent >
                                        0
                                    ) {
                                        stringResource(R.string.search_percent_played, p.percent)
                                    } else {
                                        null
                                    },
                                ).joinToString(" · "),
                            onClick = {
                                viewModel.openingItem(listOf(p.item.id))
                                nav.onOpenArticle(p.item.id)
                            },
                        )
                    }
                }
                groupLabel(R.string.search_go_to)
                items(shortcuts, key = { "go:${it.label}" }) { s ->
                    ResultRow(leading = { PlainIcon(s.icon) }, title = AnnotatedString(s.label), meta = null, onClick = s.run)
                }
                return@LazyColumn
            }

            if (state.loaded && results.count(scope) == 0) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.search_no_matches, query.trim()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(FeederTokens.Space.xl),
                    )
                }
            }

            if (scope != SearchScope.ALL) {
                items(results.inScope(scope), key = { it.key }) { hit -> HitRow(hit, state, open) }
                return@LazyColumn
            }

            val all = results.inScope(SearchScope.ALL)
            val top = all.firstOrNull()
            if (top != null) {
                groupLabel(R.string.search_top_hit)
                item(key = "top:${top.key}") { HitRow(top, state, open) }
            }
            group(R.string.search_scope_articles, results.items.filter { it.kind == ItemKind.ARTICLE && it !== top }, 3, SearchScope.ARTICLE, state, open, viewModel::setScope)
            group(R.string.search_scope_episodes, results.items.filter { it.kind == ItemKind.PODCAST && it !== top }, 3, SearchScope.PODCAST, state, open, viewModel::setScope)
            group(R.string.search_scope_videos, results.items.filter { it.kind == ItemKind.YOUTUBE && it !== top }, 3, SearchScope.YOUTUBE, state, open, viewModel::setScope)
            group(R.string.search_scope_highlights, results.highlights.filter { it !== top }, 2, SearchScope.HIGHLIGHT, state, open, viewModel::setScope)
            group(R.string.search_library_group, results.library.filter { it !== top }, 3, SearchScope.LIBRARY, state, open, viewModel::setScope)
            val terms = results.terms
            val matching = shortcuts.filter { s -> terms.all { "${s.label} ${s.keywords}".lowercase().contains(it) } }
            if (matching.isNotEmpty()) {
                groupLabel(R.string.search_go_to)
                items(matching, key = { "go:${it.label}" }) { s ->
                    ResultRow(leading = { PlainIcon(s.icon) }, title = AnnotatedString(s.label), meta = null, onClick = s.run)
                }
            }
        }
    }
}

private fun ItemHit.matches(scope: SearchScope): Boolean =
    when (scope) {
        SearchScope.ARTICLE -> kind == ItemKind.ARTICLE
        SearchScope.PODCAST -> kind == ItemKind.PODCAST
        SearchScope.YOUTUBE -> kind == ItemKind.YOUTUBE
        else -> true
    }

@Composable
private fun SearchScope.label(): String =
    stringResource(
        when (this) {
            SearchScope.ALL -> R.string.search_scope_all
            SearchScope.ARTICLE -> R.string.search_scope_articles
            SearchScope.PODCAST -> R.string.search_scope_episodes
            SearchScope.YOUTUBE -> R.string.search_scope_videos
            SearchScope.HIGHLIGHT -> R.string.search_scope_highlights
            SearchScope.LIBRARY -> R.string.search_scope_library
        },
    )

private fun LazyListScope.groupLabel(label: Int) {
    item(key = "label:$label") {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, top = FeederTokens.Space.lg, bottom = FeederTokens.Space.xs),
        )
    }
}

private fun LazyListScope.group(
    label: Int,
    hits: List<SearchHit>,
    max: Int,
    scope: SearchScope,
    state: GlobalSearchState,
    open: (SearchHit) -> Unit,
    onScope: (SearchScope) -> Unit,
) {
    if (hits.isEmpty()) return
    item(key = "group:$label") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs, top = FeederTokens.Space.sm),
        ) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hits.size > max) {
                // The scope's full count: the item list itself stops at MAX_ITEM_RESULTS
                val total = maxOf(hits.size, state.results.count(scope))
                TextButton(onClick = { onScope(scope) }) { Text(stringResource(R.string.search_show_all, total)) }
            }
        }
    }
    items(hits.take(max), key = { "${it.key}@$label" }) { hit -> HitRow(hit, state, open) }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focus: FocusRequester,
) {
    val hint = stringResource(R.string.search_hint)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = hint },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear))
                }
            } else {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = FeederTokens.Space.lg))
            }
        }
    }
}

@Composable
private fun HitRow(
    hit: SearchHit,
    state: GlobalSearchState,
    open: (SearchHit) -> Unit,
) {
    when (hit) {
        is ItemHit -> {
            val item = hit.item
            val meta: AnnotatedString =
                when {
                    hit.field == MatchField.TRANSCRIPT && hit.snippet != null ->
                        buildAnnotatedString {
                            withStyleBold { append(clock(hit.atMs ?: 0)) }
                            append("  ")
                            append(hit.snippet.annotated())
                            if (hit.transcriptMatches > 1) append("  +${hit.transcriptMatches - 1}")
                        }
                    hit.field == MatchField.TEXT && hit.snippet != null -> hit.snippet.annotated()
                    else -> AnnotatedString(listOfNotNull(item.kind.displayName(), item.feedTitle, ageText(item.sortTime)).joinToString(" · "))
                }
            val hint = if (hit.field == MatchField.TRANSCRIPT && hit.atMs != null) stringResource(R.string.search_play_from, clock(hit.atMs)) else null
            ResultRow(leading = { ItemThumb(item) }, title = hit.title.annotated(), meta = meta, trailing = hint, onClick = { open(hit) })
        }

        is HighlightHit -> {
            val meta =
                buildAnnotatedString {
                    append(hit.highlight.itemTitle)
                    hit.note?.let {
                        append(" · ")
                        append(it.annotated())
                    }
                }
            ResultRow(
                leading = { HighlightIcon(hit.highlight) },
                title =
                    buildAnnotatedString {
                        append("“")
                        append(hit.text.annotated())
                        append("”")
                    },
                meta = meta,
                titleLines = 2,
                onClick = { open(hit) },
            )
        }

        is FeedHit -> {
            val meta =
                listOfNotNull(
                    stringResource(R.string.search_feed),
                    hit.tag.takeIf { it.isNotBlank() },
                    if (hit.unread >
                        0
                    ) {
                        stringResource(R.string.search_unread, hit.unread)
                    } else {
                        null
                    },
                ).joinToString(" · ")
            ResultRow(
                leading = {
                    if (hit.image != null) RoundImage(hit.image) else PlainIcon(Icons.Default.RssFeed)
                },
                title = hit.title.annotated(),
                meta = AnnotatedString(meta),
                onClick = { open(hit) },
            )
        }

        is FolderHit ->
            ResultRow(leading = {
                PlainIcon(Icons.Default.Folder)
            }, title = hit.title.annotated(), meta = AnnotatedString(stringResource(R.string.search_folder_feeds, hit.feeds)), onClick = { open(hit) })

        is PlaylistHit ->
            ResultRow(
                leading = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(LEADING).clip(RoundedCornerShape(FeederTokens.Radius.sm)).background(Color(hit.playlist.color)),
                    ) { Text(hit.playlist.icon, style = MaterialTheme.typography.titleMedium) }
                },
                title = hit.title.annotated(),
                meta = AnnotatedString(stringResource(if (hit.playlist.smart) R.string.search_smart_playlist else R.string.search_playlist)),
                onClick = { open(hit) },
            )
    }
}

@Composable
private fun ResultRow(
    leading: @Composable () -> Unit,
    title: AnnotatedString,
    meta: Any?,
    onClick: () -> Unit,
    trailing: String? = null,
    titleLines: Int = 1,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            when (meta) {
                is AnnotatedString -> Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                is String -> Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else -> {}
            }
            if (trailing != null) {
                Spacer(Modifier.height(2.dp))
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val LEADING = 48.dp

@Composable
private fun ItemThumb(item: PlaylistCandidate) {
    val image = item.image?.url ?: item.feedImage
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(LEADING).clip(RoundedCornerShape(FeederTokens.Radius.sm)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(item.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        if (image != null) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun RoundImage(url: String) {
    Box(Modifier.size(LEADING).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PlainIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(LEADING).clip(RoundedCornerShape(FeederTokens.Radius.sm)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun HighlightIcon(h: Highlight) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(LEADING).clip(RoundedCornerShape(FeederTokens.Radius.sm)).background(h.highlightColor.background()),
    ) { Icon(Icons.Outlined.BorderColor, contentDescription = null, tint = Color.Black.copy(alpha = 0.75f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Marked.annotated(): AnnotatedString {
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r -> if (r.first >= 0 && r.last < text.length) addStyle(mark, r.first, r.last + 1) }
    }
}

private inline fun AnnotatedString.Builder.withStyleBold(block: AnnotatedString.Builder.() -> Unit) {
    val i = pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
    block()
    pop(i)
}
