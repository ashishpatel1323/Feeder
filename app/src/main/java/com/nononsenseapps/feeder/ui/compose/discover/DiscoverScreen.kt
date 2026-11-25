package com.nononsenseapps.feeder.ui.compose.discover

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nononsenseapps.feeder.ui.compose.home.initials
import com.nononsenseapps.feeder.ui.compose.home.tileColors
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.launch

private val TABS = listOf(DiscoverKind.TEXT to "Websites", DiscoverKind.PODCAST to "Podcasts", DiscoverKind.YOUTUBE to "YouTube")
private val YOUTUBE_IDEAS = listOf("Science explained", "Tech reviews", "History", "Personal finance India", "Cooking", "Startups", "Chess", "Documentaries")

/**
 * Discover (Material 3 list pattern): a search bar, text tabs, one chip row of filters, then
 * compact list rows with one trailing + that opens a folder sheet.
 */
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenFeed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<DiscoverResult?>(null) }
    var topicSheet by rememberSaveable { mutableStateOf(false) }
    val q = state.submitted

    // A new entry in "added" means an add just finished: say where it went, with a way to open it.
    var seenAdded by remember { mutableStateOf(state.added.keys) }
    LaunchedEffect(state.added) {
        val fresh = state.added.filterKeys { it !in seenAdded }
        seenAdded = state.added.keys
        fresh.values.lastOrNull()?.let { added ->
            scope.launch {
                val result =
                    snackbar.showSnackbar(
                        message = if (added.tag.isNotBlank()) "Added to ${added.tag}" else "Added",
                        actionLabel = "Open",
                        withDismissAction = true,
                    )
                if (result == SnackbarResult.ActionPerformed) onOpenFeed(added.feedId)
            }
        }
    }

    fun submit() {
        keyboard?.hide()
        focus.clearFocus()
        viewModel.search()
    }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                // Discover is part of the Feeds tab: Latest | By feed | Discover
                com.nononsenseapps.feeder.ui.compose.navigation.FeedsViewSwitch(
                    view = com.nononsenseapps.feeder.ui.compose.navigation.FeedsView.DISCOVER,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DiscoverSearchBar(
                    query = state.query,
                    placeholder =
                        when (state.tab) {
                            DiscoverKind.TEXT -> "Search sites and blogs, or paste a link"
                            DiscoverKind.PODCAST -> "Search podcasts"
                            DiscoverKind.YOUTUBE -> "Search channels, a link or @handle"
                        },
                    onQuery = viewModel::setQuery,
                    onSearch = ::submit,
                )
                SecondaryTabRow(selectedTabIndex = TABS.indexOfFirst { it.first == state.tab }) {
                    TABS.forEach { (kind, label) ->
                        Tab(
                            selected = state.tab == kind,
                            onClick = { viewModel.setTab(kind) },
                            text = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item("filters") {
                FilterRow(
                    state = state,
                    topics = viewModel.topics,
                    onOpenTopics = { topicSheet = true },
                    onTopic = viewModel::setTopic,
                    onCountry = viewModel::setCountry,
                    onGenre = viewModel::setGenre,
                    onIdea = { viewModel.search(it) },
                )
            }
            item("heading") {
                val heading =
                    when {
                        q.isNotEmpty() -> "Results for “$q”"
                        state.tab == DiscoverKind.TEXT -> {
                            val n =
                                viewModel.topics
                                    .firstOrNull { it.name == state.topic }
                                    ?.feeds
                                    ?.size ?: 0
                            "${topicLabel(state.topic)} · $n feeds"
                        }
                        state.tab == DiscoverKind.PODCAST -> {
                            val genre = PODCAST_GENRES.firstOrNull { it.first == state.genre }?.second?.takeIf { state.genre != 0 }
                            val country = PODCAST_COUNTRIES.firstOrNull { it.first == state.country }?.second ?: state.country.uppercase()
                            "Top ${genre?.let { "$it " } ?: ""}podcasts in $country"
                        }
                        else -> "Find YouTube channels"
                    }
                Column(Modifier.padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, top = FeederTokens.Space.md, bottom = FeederTokens.Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            heading,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (state.loading) {
                            Spacer(Modifier.width(FeederTokens.Space.sm))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (state.tab == DiscoverKind.YOUTUBE && q.isEmpty()) {
                        Text(
                            "Search by name, or paste a channel link or @handle. Each channel becomes a feed of its new videos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = FeederTokens.Space.xs),
                        )
                    }
                    if (state.note.isNotEmpty()) {
                        Text(
                            state.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = FeederTokens.Space.xs),
                        )
                    }
                }
            }
            items(state.results, key = { it.feedUrl }) { r ->
                ResultRow(
                    r = r,
                    followed = isFollowed(r, followed),
                    added = state.added[r.feedUrl],
                    adding = r.feedUrl in state.adding,
                    error = state.failed[r.feedUrl],
                    onAdd = { picking = r },
                    onOpen = onOpenFeed,
                )
            }
            item("source") {
                val source =
                    when (state.tab) {
                        DiscoverKind.TEXT -> "Topic lists come from awesome-rss-feeds (CC0) plus Indian business sites. Search uses Feedly."
                        DiscoverKind.PODCAST -> "Podcast search and charts come from Apple Podcasts."
                        DiscoverKind.YOUTUBE -> ""
                    }
                if (source.isNotEmpty() && state.results.isNotEmpty()) {
                    Text(
                        source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
                    )
                }
            }
        }
    }

    picking?.let { r ->
        FolderSheet(
            title = r.title,
            folders = folders,
            lastFolder = viewModel.lastFolder,
            onPick = { tag ->
                picking = null
                viewModel.add(r, tag)
            },
            onDismiss = { picking = null },
        )
    }
    if (topicSheet) {
        TopicSheet(
            topics = viewModel.topics,
            selected = state.topic,
            onPick = {
                topicSheet = false
                viewModel.setTopic(it)
            },
            onDismiss = { topicSheet = false },
        )
    }
}

/** M3 search bar look: a 56 dp pill, search icon, clear button. The keyboard's search key searches. */
@Composable
private fun DiscoverSearchBar(
    query: String,
    placeholder: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { focusRequester.requestFocus() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(FeederTokens.Space.lg))
            Box(Modifier.weight(1f).padding(vertical = FeederTokens.Space.lg)) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = placeholder },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
            } else {
                Spacer(Modifier.width(FeederTokens.Space.md))
            }
        }
    }
}

/** The one filter row: a dropdown chip (topic, or country + chart) and a few quick picks. */
@Composable
private fun FilterRow(
    state: DiscoverState,
    topics: List<CuratedTopic>,
    onOpenTopics: () -> Unit,
    onTopic: (String) -> Unit,
    onCountry: (String) -> Unit,
    onGenre: (Int) -> Unit,
    onIdea: (String) -> Unit,
) {
    val q = state.submitted
    val names = topics.map { it.name }.toSet()
    LazyRow(
        contentPadding = PaddingValues(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
    ) {
        when {
            state.tab == DiscoverKind.TEXT && q.isEmpty() -> {
                item("topic") {
                    FilterChip(
                        selected = true,
                        onClick = onOpenTopics,
                        label = { Text(topicLabel(state.topic), maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a topic", modifier = Modifier.size(18.dp)) },
                    )
                }
                items(QUICK_TOPICS.filter { it in names && it != state.topic }, key = { "q:$it" }) { t ->
                    FilterChip(selected = false, onClick = { onTopic(t) }, label = { Text(topicLabel(t), maxLines = 1) })
                }
            }
            state.tab == DiscoverKind.PODCAST -> {
                item("country") {
                    MenuChip(
                        label = PODCAST_COUNTRIES.firstOrNull { it.first == state.country }?.second ?: state.country.uppercase(),
                        leading = true,
                        options = PODCAST_COUNTRIES,
                        selected = state.country,
                        onPick = onCountry,
                    )
                }
                if (q.isEmpty()) {
                    item("genre") {
                        MenuChip(
                            label = if (state.genre == 0) "All categories" else PODCAST_GENRES.firstOrNull { it.first == state.genre }?.second ?: "Category",
                            leading = false,
                            options = PODCAST_GENRES.map { (id, name) -> id to if (id == 0) "All categories" else name },
                            selected = state.genre,
                            onPick = onGenre,
                        )
                    }
                }
            }
            state.tab == DiscoverKind.YOUTUBE && q.isEmpty() -> {
                items(YOUTUBE_IDEAS, key = { "y:$it" }) { idea ->
                    AssistChip(onClick = { onIdea(idea) }, label = { Text(idea, maxLines = 1) })
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun <T> MenuChip(
    label: String,
    leading: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = if (leading) ({ Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                    trailingIcon = if (value == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                )
            }
        }
    }
}

/** Square artwork: the result's image or site icon, a letter tile while loading or when there is none. */
@Composable
private fun ResultIcon(
    r: DiscoverResult,
    size: Dp,
) {
    val url = iconUrl(r)
    val small = r.image == null
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(if (size > 48.dp) FeederTokens.Radius.md else 10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        val tile: @Composable () -> Unit = {
            val (bg, fg) = tileColors(r.title)
            Box(Modifier.fillMaxSize().background(Color(bg)), contentAlignment = Alignment.Center) {
                Text(initials(r.title), color = Color(fg), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            }
        }
        if (url == null) {
            tile()
        } else {
            SubcomposeAsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(url)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = if (small) ContentScale.Fit else ContentScale.Crop,
                loading = { tile() },
                error = { tile() },
                modifier = if (small) Modifier.size(size * 0.6f) else Modifier.fillMaxSize(),
            )
        }
    }
}

/** A standard list row: icon, title, one supporting line, and one trailing action. Tapping the row visits the site. */
@Composable
private fun ResultRow(
    r: DiscoverResult,
    followed: Boolean,
    added: Added?,
    adding: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val context = LocalContext.current
    val site = r.site?.takeIf { it.startsWith("http") }
    val supporting =
        when {
            error != null -> "Could not add: $error"
            added != null -> listOf(if (added.tag.isNotBlank()) "Added to ${added.tag}" else "Added", supportingLine(r)).filter { it.isNotBlank() }.joinToString(" · ")
            followed -> listOf("Following", supportingLine(r)).filter { it.isNotBlank() }.joinToString(" · ")
            else -> supportingLine(r)
        }
    ListItem(
        headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            supporting.takeIf { it.isNotBlank() }?.let {
                {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        leadingContent = { ResultIcon(r, if (r.kind == DiscoverKind.PODCAST && r.image != null) 56.dp else 40.dp) },
        trailingContent = {
            when {
                adding -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                added != null ->
                    FilledTonalIconButton(onClick = { onOpen(added.feedId) }) {
                        Icon(Icons.Default.Check, contentDescription = "Open ${r.title}")
                    }
                followed ->
                    FilledTonalIconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.Check, contentDescription = "Already following")
                    }
                else ->
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Add ${r.title} to a folder", tint = MaterialTheme.colorScheme.primary)
                    }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.clickable(enabled = site != null || added != null) {
                when {
                    added != null -> onOpen(added.feedId)
                    site != null -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, site.toUri())) }
                }
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSheet(
    title: String,
    folders: List<String>,
    lastFolder: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val ordered = if (lastFolder in folders) listOf(lastFolder) + folders.filter { it != lastFolder } else folders
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = FeederTokens.Space.lg)) {
            Text("Add to folder", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = FeederTokens.Space.xl))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = FeederTokens.Space.xl, end = FeederTokens.Space.xl, bottom = FeederTokens.Space.sm),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ordered, key = { it }) { f ->
                    SheetRow(
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        text = f,
                        trailing = if (f == lastFolder) "Last used" else null,
                        onClick = { onPick(f) },
                    )
                }
                item("none") { SheetRow(icon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) }, text = "No folder", onClick = { onPick("") }) }
                item("new") {
                    if (!creating) {
                        SheetRow(
                            icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            text = "New folder…",
                            accent = true,
                            onClick = { creating = true },
                        )
                    } else {
                        HorizontalDivider(Modifier.padding(vertical = FeederTokens.Space.sm))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = FeederTokens.Space.xl)) {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                label = { Text("New folder") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onPick(name.trim()) }),
                                modifier = Modifier.weight(1f).focusRequester(focus),
                            )
                            Spacer(Modifier.width(FeederTokens.Space.sm))
                            Button(onClick = { onPick(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    trailing: String? = null,
    accent: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = FeederTokens.Space.xl),
    ) {
        icon()
        Spacer(Modifier.width(FeederTokens.Space.lg))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicSheet(
    topics: List<CuratedTopic>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Topics", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = FeederTokens.Space.xl, vertical = FeederTokens.Space.xs))
        LazyColumn(contentPadding = PaddingValues(bottom = FeederTokens.Space.lg)) {
            topics.groupBy { it.group }.forEach { (group, list) ->
                if (group.isNotBlank()) {
                    item("h:$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = FeederTokens.Space.xl, end = FeederTokens.Space.xl, top = FeederTokens.Space.lg, bottom = FeederTokens.Space.xs),
                        )
                    }
                }
                items(list, key = { "t:${it.name}" }) { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onPick(t.name) }
                                .padding(horizontal = FeederTokens.Space.xl),
                    ) {
                        Text(
                            topicLabel(t.name),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (t.name == selected) FontWeight.SemiBold else null,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${t.feeds.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.name == selected) {
                            Spacer(Modifier.width(FeederTokens.Space.sm))
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
