package com.nononsenseapps.feeder.ui.compose.discover

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nononsenseapps.feeder.ui.compose.home.initials
import com.nononsenseapps.feeder.ui.compose.home.tileColors
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

private val TABS = listOf(DiscoverKind.TEXT to "Text feeds", DiscoverKind.PODCAST to "Podcasts", DiscoverKind.YOUTUBE to "YouTube")
private val YOUTUBE_IDEAS = listOf("Science explained", "Tech reviews", "History", "Personal finance India", "Cooking", "Startups", "Chess", "Documentaries")

private fun DiscoverKind.icon(): ImageVector =
    when (this) {
        DiscoverKind.TEXT -> Icons.AutoMirrored.Filled.Article
        DiscoverKind.PODCAST -> Icons.Default.Headphones
        DiscoverKind.YOUTUBE -> Icons.Default.PlayArrow
    }

/**
 * Discover: find text feeds, podcasts and YouTube channels, and add each to a folder with the
 * visible Add to folder button and its folder picker.
 */
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onNavigateUp: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val q = state.submitted

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SensibleTopAppBar(
                title = "Discover",
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item("tabs") {
                PrimaryTabRow(selectedTabIndex = TABS.indexOfFirst { it.first == state.tab }) {
                    TABS.forEach { (kind, label) ->
                        Tab(
                            selected = state.tab == kind,
                            onClick = { viewModel.setTab(kind) },
                            text = { Text(label, maxLines = 1) },
                            icon = { Icon(kind.icon(), contentDescription = null) },
                        )
                    }
                }
            }
            item("search") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        placeholder = {
                            Text(
                                when (state.tab) {
                                    DiscoverKind.TEXT -> "Sites and blogs, or an address"
                                    DiscoverKind.PODCAST -> "Search podcasts"
                                    DiscoverKind.YOUTUBE -> "Channels, a link or @handle"
                                },
                                maxLines = 1,
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(onSearch = {
                                keyboard?.hide()
                                viewModel.search()
                            }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            keyboard?.hide()
                            viewModel.search()
                        },
                        enabled = state.query.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
            when {
                state.tab == DiscoverKind.TEXT && q.isEmpty() -> {
                    viewModel.topics.groupBy { it.group }.forEach { (group, list) ->
                        item("g:$group") {
                            ChipRow(group, list.map { it.name to "${it.name}  ${it.feeds.size}" }, state.topic, viewModel::setTopic)
                        }
                    }
                }
                state.tab == DiscoverKind.PODCAST -> {
                    item("country") { ChipRow("Country", PODCAST_COUNTRIES, state.country, viewModel::setCountry) }
                    if (q.isEmpty()) {
                        item("genre") { ChipRow("Top charts", PODCAST_GENRES, state.genre, viewModel::setGenre) }
                    }
                }
                state.tab == DiscoverKind.YOUTUBE && q.isEmpty() -> {
                    item("ideas") { ChipRow("Try", YOUTUBE_IDEAS.map { it to it }, null) { viewModel.search(it) } }
                }
                else -> Unit
            }
            item("heading") {
                val heading =
                    when {
                        q.isNotEmpty() -> "Results for “$q”"
                        state.tab == DiscoverKind.TEXT -> state.topic
                        state.tab == DiscoverKind.PODCAST -> {
                            val genre = PODCAST_GENRES.firstOrNull { it.first == state.genre }?.second?.takeIf { state.genre != 0 }
                            val country = PODCAST_COUNTRIES.firstOrNull { it.first == state.country }?.second ?: state.country.uppercase()
                            "Top ${genre?.let { "$it " } ?: ""}podcasts in $country"
                        }
                        else -> "Find YouTube channels"
                    }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                        if (state.loading) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (state.tab == DiscoverKind.YOUTUBE && q.isEmpty()) {
                        Text(
                            "Search by name, or paste a channel link or @handle. Each channel becomes a feed of its new videos.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.note.isNotEmpty()) {
                        Text(state.note, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            items(state.results, key = { it.feedUrl }) { r ->
                ResultCard(
                    r = r,
                    followed = isFollowed(r, followed),
                    added = state.added[r.feedUrl],
                    adding = r.feedUrl in state.adding,
                    error = state.failed[r.feedUrl],
                    folders = folders,
                    lastFolder = viewModel.lastFolder,
                    onAdd = { tag -> viewModel.add(r, tag) },
                    onOpen = onOpenFeed,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item("source") {
                val source =
                    when (state.tab) {
                        DiscoverKind.TEXT -> "Topic lists come from awesome-rss-feeds (CC0) plus Indian business sites. Search uses Feedly."
                        DiscoverKind.PODCAST -> "Podcast search and charts come from Apple Podcasts."
                        DiscoverKind.YOUTUBE -> ""
                    }
                if (source.isNotEmpty()) {
                    Text(source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onPick: (T) -> Unit,
) {
    Column {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.first.toString() }) { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(text, maxLines = 1) })
            }
        }
    }
}

@Composable
private fun Art(r: DiscoverResult) {
    val (bg, fg) = tileColors(r.title)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(bg)),
    ) {
        Text(initials(r.title), color = Color(fg), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1)
        if (r.image != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(r.image)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    r: DiscoverResult,
    followed: Boolean,
    added: Added?,
    adding: Boolean,
    error: String?,
    folders: List<String>,
    lastFolder: String,
    onAdd: (String) -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable(r.feedUrl) { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Art(r)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    r.meta?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    r.description?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                when {
                    added != null -> {
                        Done(if (added.tag.isNotBlank()) "Added to ${added.tag}" else "Added")
                        OutlinedButton(onClick = { onOpen(added.feedId) }) { Text("Open feed") }
                    }
                    followed -> Done("Already following")
                    else ->
                        FilledTonalButton(onClick = { picking = !picking }, enabled = !adding) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (adding) "Adding…" else "Add to folder")
                        }
                }
                r.site?.takeIf { it.startsWith("http") }?.let { site ->
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, site.toUri())) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Visit")
                    }
                }
            }
            error?.let { Text("Could not add: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (picking && added == null && !followed) {
                val ordered = if (lastFolder in folders) listOf(lastFolder) + folders.filter { it != lastFolder } else folders
                Text("Add to", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { f ->
                        FilterChip(
                            selected = f == lastFolder,
                            onClick = {
                                picking = false
                                onAdd(f)
                            },
                            label = { Text(f) },
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                    FilterChip(selected = false, onClick = {
                        picking = false
                        onAdd("")
                    }, label = { Text("No folder") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        singleLine = true,
                        placeholder = { Text("New folder") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(onDone = {
                                if (newFolder.isNotBlank()) {
                                    picking = false
                                    onAdd(newFolder.trim())
                                }
                            }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        picking = false
                        onAdd(newFolder.trim())
                    }, enabled = newFolder.isNotBlank()) { Text("Create and add") }
                }
            }
        }
    }
}

@Composable
private fun Done(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
