package com.nononsenseapps.feeder.ui.compose.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.PLAYLIST_COLORS
import com.nononsenseapps.feeder.model.playlists.PLAYLIST_ICONS
import com.nononsenseapps.feeder.model.playlists.PlayStatus
import com.nononsenseapps.feeder.model.playlists.Playlist
import com.nononsenseapps.feeder.model.playlists.QueueState
import com.nononsenseapps.feeder.model.playlists.SmartRules
import com.nononsenseapps.feeder.model.playlists.describe
import com.nononsenseapps.feeder.model.playlists.formatTotal
import com.nononsenseapps.feeder.model.playlists.isDownloaded
import com.nononsenseapps.feeder.model.playlists.itemSeconds
import com.nononsenseapps.feeder.model.playlists.items
import com.nononsenseapps.feeder.model.playlists.kind
import com.nononsenseapps.feeder.model.playlists.label
import com.nononsenseapps.feeder.model.playlists.matches
import com.nononsenseapps.feeder.model.playlists.totalSeconds
import com.nononsenseapps.feeder.model.playlists.withinLabel
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.ui.compose.components.ConfirmDeleteDialog
import com.nononsenseapps.feeder.ui.compose.components.DeleteRequest
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.DeleteVerb
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.item.ItemActionButton
import com.nononsenseapps.feeder.ui.compose.item.ItemRow
import com.nononsenseapps.feeder.ui.compose.item.ItemRowActions
import com.nononsenseapps.feeder.ui.compose.item.ItemRowModel
import com.nononsenseapps.feeder.ui.compose.item.ageText
import com.nononsenseapps.feeder.ui.compose.item.rememberItemCommands
import com.nononsenseapps.feeder.ui.compose.item.rememberItemProgress
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchTopBar
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    viewModel: PlaylistsViewModel,
    onNavigateUp: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.viewState.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val openId by viewModel.openId.collectAsStateWithLifecycle()
    val open = openId?.let { id -> state.playlists.firstOrNull { it.id == id } }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var confirmDelete by remember { mutableStateOf<Playlist?>(null) }
    var carMode by remember { mutableStateOf(false) }
    val flipId by viewModel.flipId.collectAsStateWithLifecycle()
    val flipItems by viewModel.flipItems.collectAsStateWithLifecycle()
    val briefs by viewModel.briefs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { m ->
            scope.launch {
                val r = snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
            }
        }
    }

    BackHandler(enabled = open != null) { viewModel.open(null) }

    if (flipId != null) {
        HideMainBar()
        // Flip view takes the whole screen, as it does for a feed, until the user leaves it
        PlaylistFlip(
            items = flipItems,
            onOpenArticle = { id ->
                viewModel.openingArticle(flipId.orEmpty(), viewModel.flipOrder())
                onOpenArticle(id)
            },
            onExit = { viewModel.flip(null) },
            snackbar = snackbar,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (open == null) {
                // The list of playlists is a tab: the global search bar on top
                GlobalSearchTopBar(
                    actions = {
                        NewPlaylistAction(
                            onNewSmart = { editing = viewModel.newPlaylistDraft(true) },
                            onNew = { editing = viewModel.newPlaylistDraft(false) },
                        )
                    },
                )
            } else {
                SensibleTopAppBar(
                    title = open.name,
                    navigationIcon = {
                        IconButton(onClick = { viewModel.open(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                        }
                    },
                    actions = {
                        run {
                            val items = remember(open, state) { open.items(state.candidates, state.ctx, state.byId) }
                            val toDownload = items.filter { it.kind != ItemKind.ARTICLE && !it.isDownloaded(state.ctx) }
                            val toPrepare = remember(open, state, queue.index, queue.playlistId) { viewModel.nextArticlesToPrepare(open) }
                            // Compressed speech: about 6 kB a second
                            val prepareSize =
                                remember(toPrepare) {
                                    // Speech runs at about 150 words a minute
                                    val seconds = toPrepare.sumOf { (if (it.fullTextByDefault && it.wordCountFull > 0) it.wordCountFull else it.wordCount).toLong() * 2 / 5 }
                                    if (seconds > 0) {
                                        android.text.format.Formatter
                                            .formatShortFileSize(context, seconds * 6_000)
                                    } else {
                                        ""
                                    }
                                }
                            val brief = briefs[open.id]?.v
                            val briefCount = remember(open, state) { viewModel.briefArticles(open).size }
                            PlaylistTopBarActions(
                                smart = open.smart,
                                canFlip = items.isNotEmpty(),
                                onFlip = { viewModel.flip(open.id) },
                                onEdit = { editing = open },
                                downloadCount = toDownload.size,
                                onDownload = {
                                    val n = viewModel.downloadAll(toDownload)
                                    scope.launch { snackbar.showSnackbar("Downloading $n items") }
                                },
                                onDelete = { confirmDelete = open },
                                prepareCount = toPrepare.size,
                                prepareSize = prepareSize,
                                briefLabel =
                                    when {
                                        briefCount == 0 || brief?.isPending() == true -> null
                                        brief?.s == com.nononsenseapps.feeder.model.briefs.Brief.DONE -> stringResource(R.string.playlist_brief_new)
                                        else -> stringResource(R.string.playlist_brief)
                                    },
                                onBrief = {
                                    val n = viewModel.askBrief(open)
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.playlist_brief_asked, n)) }
                                },
                                onPrepare = {
                                    val n = viewModel.prepareArticles(open)
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            if (n > 0) context.getString(R.string.playlist_prepare_started, n) else context.getString(R.string.playlist_prepare_nothing),
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (queue.active) {
                NowPlayingBar(queue, viewModel, onExpand = { carMode = true })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (open == null) {
                PlaylistIndex(state, queue, onOpen = viewModel::open, onPlay = { viewModel.play(it) })
            } else {
                PlaylistDetail(open, state, queue, viewModel, onEdit = { editing = open }, onOpenArticle = onOpenArticle, snackbar = snackbar)
            }
        }
    }

    editing?.let { draft ->
        PlaylistEditor(
            initial = draft,
            isNew = state.playlists.none { it.id == draft.id },
            state = state,
            onDismiss = { editing = null },
            onSave = { pl, isNew ->
                viewModel.save(pl)
                editing = null
                if (isNew) viewModel.open(pl.id)
            },
        )
    }
    confirmDelete?.let { pl ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.confirm_delete_title_playlist),
            itemName = pl.name,
            scope = DeleteScope.ALL_DEVICES,
            onConfirm = {
                val before = state.playlists
                viewModel.delete(pl.id)
                confirmDelete = null
                scope.launch {
                    if (snackbar.showSnackbar("Deleted ${pl.name}", actionLabel = "Undo") == SnackbarResult.ActionPerformed) viewModel.restore(before)
                }
            },
            onDismiss = { confirmDelete = null },
            extra = stringResource(R.string.confirm_delete_extra_playlist),
        )
    }
    if (carMode && queue.active) {
        HideMainBar()
        CarMode(queue, state, viewModel, onClose = { carMode = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = FeederTokens.Space.lg, top = FeederTokens.Space.lg, bottom = FeederTokens.Space.sm),
    )
}

@Composable
private fun PlaylistIndex(
    state: PlaylistsViewState,
    queue: QueueState,
    onOpen: (String) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.playlists_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SectionLabel(stringResource(R.string.playlists_smart_section))
        }
        val smart = state.playlists.filter { it.smart }
        smart.forEach { pl -> item(key = pl.id) { PlaylistRow(pl, state, queue, onOpen, onPlay) } }
        item { SectionLabel(stringResource(R.string.playlists_manual_section)) }
        val manual = state.playlists.filter { !it.smart }
        if (manual.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.playlists_manual_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        manual.forEach { pl -> item(key = pl.id) { PlaylistRow(pl, state, queue, onOpen, onPlay) } }
    }
}

@Composable
private fun PlaylistRow(
    pl: Playlist,
    state: PlaylistsViewState,
    queue: QueueState,
    onOpen: (String) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    val items = remember(pl, state) { pl.items(state.candidates, state.ctx, state.byId) }
    val total = remember(items, state.ctx) { items.totalSeconds(state.ctx).first }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(pl.id) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        PlaylistTile(pl.icon, pl.color, 48, images = remember(items) { items.playlistArt() })
        Column(Modifier.weight(1f)) {
            Text(pl.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta =
                buildString {
                    append(stringResource(if (pl.smart) R.string.playlist_smart else R.string.playlist_manual))
                    append(" · ${items.size} items")
                    if (total > 0) append(" · ${formatTotal(total)}")
                    if (queue.playlistId == pl.id && queue.active) append(" · playing")
                }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = { onPlay(pl) }, enabled = items.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.playlist_play))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistDetail(
    pl: Playlist,
    state: PlaylistsViewState,
    queue: QueueState,
    viewModel: PlaylistsViewModel,
    onEdit: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    snackbar: SnackbarHostState? = null,
) {
    val items = remember(pl, state) { pl.items(state.candidates, state.ctx, state.byId) }
    val commands = rememberItemCommands()
    val deleteConfirmer = rememberDeleteConfirmer()
    val (total, unknown) = remember(items, state.ctx) { items.totalSeconds(state.ctx) }
    val feedTitles = remember(state.feeds) { state.feeds.toMap() }
    val playingId = if (queue.playlistId == pl.id) queue.currentId else null
    val addToPlaylist = rememberAddToPlaylist()
    val offline by viewModel.offlineAudio.collectAsStateWithLifecycle()
    val briefs by viewModel.briefs.collectAsStateWithLifecycle()
    val briefSpeech by viewModel.briefSpeech.collectAsStateWithLifecycle()
    // Brief articles are keyed "feedUrl\nguid", as in sync
    val idsByKey = remember(state.candidates) { state.candidates.associate { "${it.feedUrl}\n${it.guid}" to it.id } }
    // Swiping in the reader goes through the playlist as shown here
    val openArticle: (Long) -> Unit = { id ->
        viewModel.openingArticle(pl.id, items.map { it.id })
        onOpenArticle(id)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                PlaylistTile(pl.icon, pl.color, 56, images = remember(items) { items.playlistArt() })
                Column {
                    Text(
                        stringResource(if (pl.smart) R.string.playlist_smart else R.string.playlist_manual),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            append("${items.size} items")
                            if (total > 0) append(" · ${formatTotal(total)}")
                            if (unknown > 0) append(" · $unknown of unknown length")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Button(onClick = { viewModel.play(pl) }, enabled = items.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.playlist_play_all), fontWeight = FontWeight.Bold)
                }
                val shuffleLabel = stringResource(R.string.playlist_shuffle)
                PlainTooltipBox(tooltip = { Text(shuffleLabel) }) {
                    FilledTonalIconButton(onClick = { viewModel.play(pl, shuffle = true) }, enabled = items.size > 1) {
                        Icon(Icons.Default.Shuffle, contentDescription = shuffleLabel)
                    }
                }
            }
            if (pl.smart) {
                // Rules and order on one line of chips, as the feed list's filters are
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    RulesChip(rules = pl.rules.describe { feedTitles[it] ?: "?" }, onClick = onEdit)
                    SortMenuChip(current = pl.sort, onSelect = { viewModel.save(pl.copy(sort = it)) })
                }
            } else {
                Text(
                    stringResource(R.string.playlist_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            briefs[pl.id]?.let { b ->
                BriefCard(
                    brief = b.v,
                    localId = { key -> idsByKey[key] },
                    onOpen = openArticle,
                    onAgain = { viewModel.askBrief(pl) },
                    onSync = viewModel::syncForBrief,
                    speaking = briefSpeech == viewModel.briefSpeechId(pl.id, b.ts),
                    onListen = { viewModel.listenBrief(pl.id, b.v, b.ts) },
                )
            }
            if (offline.playlistId == pl.id && offline.ids.isNotEmpty()) {
                OfflineArticlesCard(
                    state = offline,
                    onCancel = viewModel::cancelPreparing,
                    onRemove = viewModel::removePrepared,
                )
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
        }
        if (items.isEmpty()) {
            item {
                Text(
                    stringResource(if (pl.smart) R.string.playlist_empty_smart else R.string.playlist_empty_manual),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        itemsIndexed(items, key = { _, it -> it.id }) { index, it ->
            val kind = it.kind
            val pos = state.ctx.positions[it.id]
            // Episodes from the podcast player's positions, videos from YouTube's as well
            val progress = rememberItemProgress(it.id, if (kind == ItemKind.YOUTUBE) VideoPositions.articleKey(it.feedUrl, it.guid) else "")
            ItemRow(
                model =
                    ItemRowModel(
                        id = it.id,
                        title = it.title,
                        feedTitle = it.feedTitle,
                        age = ageText(it.sortTime),
                        kind = kind,
                        lengthSeconds =
                            itemSeconds(
                                kind = kind,
                                wordCount = if (it.fullTextByDefault && it.wordCountFull > 0) it.wordCountFull else it.wordCount,
                                enclosureDuration = it.enclosureDuration,
                                knownLength = state.ctx.knownLengths[it.id],
                                positionLength = pos?.second,
                            ),
                        read = it.read,
                        saved = it.bookmarked,
                        image = it.image?.url ?: it.feedImage,
                        progress = progress,
                        offlineAudio =
                            when {
                                it.id in offline.ready -> 1f
                                it.id == offline.preparingId -> offline.progress
                                else -> null
                            },
                    ),
                actions =
                    ItemRowActions(
                        // Episodes and videos play here, in this playlist's order; articles open
                        onPrimary = { if (kind == ItemKind.PODCAST) viewModel.play(pl, start = index) else openArticle(it.id) },
                        onToggleSave = { commands.setSaved(it.id, !it.bookmarked) },
                        onPlaylist = { addToPlaylist(it.id, it.title) },
                        onToggleRead = { commands.setRead(it.id, !it.read) },
                        onDelete = { commands.delete(it.id, snackbar) },
                    ),
                onOpen = { openArticle(it.id) },
                highlighted = it.id == playingId,
                primaryLabel = if (it.id == playingId) stringResource(R.string.item_now_playing) else null,
                extras = {
                    if (!pl.smart) {
                        ItemActionButton(
                            icon = Icons.Default.KeyboardArrowUp,
                            label = stringResource(R.string.item_action_move_up),
                            onClick = { viewModel.move(pl.id, it.id, index - 1) },
                            enabled = index > 0,
                        )
                        ItemActionButton(
                            icon = Icons.Default.KeyboardArrowDown,
                            label = stringResource(R.string.item_action_move_down),
                            onClick = { viewModel.move(pl.id, it.id, index + 1) },
                            enabled = index < items.size - 1,
                        )
                        ItemActionButton(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.item_action_remove),
                            onClick = {
                                deleteConfirmer.ask(
                                    DeleteRequest(
                                        title = R.string.confirm_delete_title_remove_from_playlist,
                                        itemName = it.title,
                                        scope = DeleteScope.ALL_DEVICES,
                                        verb = DeleteVerb.REMOVE,
                                        extra = R.string.confirm_delete_extra_playlist_entry,
                                        onConfirm = { viewModel.remove(pl.id, it.id) },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

/** The shared type badge (Article / Podcast / Video), as every item view shows it. */
@Composable
fun KindTag(kind: ItemKind) {
    com.nononsenseapps.feeder.ui.compose.item
        .TypeBadge(kind)
}

private fun QueueState.fraction(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private val AUDIO_SPEEDS = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private fun nextSpeed(current: Float): Float = AUDIO_SPEEDS.firstOrNull { it > current + 0.01f } ?: AUDIO_SPEEDS.first()

private fun speedLabel(speed: Float): String = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×"

@Composable
fun NowPlayingBar(
    queue: QueueState,
    viewModel: PlaylistsViewModel,
    onExpand: () -> Unit,
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(progress = { queue.fraction() }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(if (queue.kind == ItemKind.ARTICLE) Icons.AutoMirrored.Filled.Article else Icons.Default.Headphones, contentDescription = null)
                    queue.artwork?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(queue.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${queue.kind?.label ?: ""} · ${queue.name} · ${queue.index + 1} of ${queue.ids.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = viewModel::toggle) {
                    Icon(if (queue.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (queue.isPlaying) "Pause" else "Play", modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = viewModel::next, enabled = queue.hasNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.queue_next))
                }
                IconButton(onClick = onExpand) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = stringResource(R.string.queue_car_mode))
                }
            }
        }
    }
}

/**
 * Car mode: big buttons, few words, and what comes next. While an article is read aloud its text
 * fills the rest of the screen, following the spoken sentence; on a phone on its side the
 * controls sit on the left and the text on the right.
 */
@Composable
private fun CarMode(
    queue: QueueState,
    state: PlaylistsViewState,
    viewModel: PlaylistsViewModel,
    onClose: () -> Unit,
) {
    val isArticle = queue.kind == ItemKind.ARTICLE
    var showQueue by remember { mutableStateOf(false) }
    val speech by viewModel.readAloudProgress.collectAsStateWithLifecycle()
    val config = LocalConfiguration.current
    val sideBySide = config.screenWidthDp > config.screenHeightDp && config.screenHeightDp < 500
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val header: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${queue.name} · ${queue.index + 1} of ${queue.ids.size}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.queue_exit_car_mode))
                    }
                }
                // Compact while the article text is on screen, so the text gets the room
                val tile = if (isArticle) 56 else 110
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(tile.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(if (isArticle) Icons.AutoMirrored.Filled.Article else Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size((tile * 0.45).dp))
                        queue.artwork?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column {
                        if (!isArticle) {
                            Text(
                                (queue.kind?.label ?: "").uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            queue.title,
                            fontSize = if (isArticle) 19.sp else 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = if (isArticle) 23.sp else 26.sp,
                            maxLines = if (isArticle) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(queue.feedTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
            val transport: @Composable () -> Unit = {
                Column {
                    LinearProgressIndicator(progress = { queue.fraction() }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(clock(queue.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (queue.durationMs > 0) Text("−" + clock(queue.durationMs - queue.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // Side by side the controls get half the width, so the buttons are smaller
                    val small = if (sideBySide) 48 else 64
                    val big = if (sideBySide) 68 else 96
                    BigButton(Icons.Default.SkipPrevious, stringResource(R.string.queue_previous), small) { viewModel.previous() }
                    BigButton(Icons.Default.Replay10, if (isArticle) stringResource(R.string.queue_back) else "10 s", small) { viewModel.seekBy(-10_000) }
                    BigButton(if (queue.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, big, primary = true) { viewModel.toggle() }
                    BigButton(Icons.Default.Forward10, if (isArticle) stringResource(R.string.queue_ahead) else "10 s", small) { viewModel.seekBy(10_000) }
                    BigButton(Icons.Default.SkipNext, stringResource(R.string.queue_next), small, enabled = queue.hasNext) { viewModel.next() }
                }
                val nextTitle = queue.ids.getOrNull(queue.index + 1)?.let { state.byId[it]?.title }
                Text(
                    nextTitle?.let { stringResource(R.string.queue_next_up, it) } ?: stringResource(R.string.queue_last_item),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isArticle) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    val speed = if (isArticle) state.ctx.speed else queue.audioSpeed
                    OutlinedButton(onClick = { viewModel.setSpeed(nextSpeed(speed), isArticle) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("${stringResource(R.string.queue_speed)} ${speedLabel(speed)}", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                    OutlinedButton(onClick = { showQueue = !showQueue }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                    OutlinedButton(onClick = {
                        viewModel.stop()
                        onClose()
                    }, modifier = Modifier.height(52.dp)) { Text(stringResource(R.string.queue_stop), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            // Below the controls: the queue when asked for, else the article being read
            val below: @Composable (Modifier) -> Unit = { mod ->
                if (showQueue) {
                    CarQueueList(queue, state, viewModel, mod)
                } else if (isArticle) {
                    SpokenArticleText(progress = speech, articleId = queue.currentId, modifier = mod)
                }
            }
            if (sideBySide) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        header()
                        transport()
                    }
                    if (showQueue || isArticle) below(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    header()
                    transport()
                    if (showQueue || isArticle) below(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The queue in car mode: tap to jump, × to take an item out. */
@Composable
private fun CarQueueList(
    queue: QueueState,
    state: PlaylistsViewState,
    viewModel: PlaylistsViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        itemsIndexed(queue.ids) { i, id ->
            val it = state.byId[id] ?: return@itemsIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (i == queue.index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { viewModel.jump(i) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                KindTag(it.kind)
                Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (i != queue.index) {
                    IconButton(onClick = { viewModel.removeFromQueue(i) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Take out of the queue", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(
    icon: ImageVector,
    label: String?,
    size: Int,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled, onClick = onClick),
        ) {
            Icon(
                icon,
                contentDescription = label ?: "Play or pause",
                tint =
                    when {
                        primary -> MaterialTheme.colorScheme.onPrimary
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                modifier = Modifier.size((size * 0.5).dp),
            )
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Create or change a playlist: name and look, and for smart ones the rules, with a live count. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaylistEditor(
    initial: Playlist,
    isNew: Boolean,
    state: PlaylistsViewState,
    onDismiss: () -> Unit,
    onSave: (Playlist, Boolean) -> Unit,
) {
    var pl by remember(initial.id) { mutableStateOf(initial) }
    var showFeeds by remember { mutableStateOf(initial.rules.feedIds.isNotEmpty()) }
    val r = pl.rules

    fun rules(fn: (SmartRules) -> SmartRules) {
        pl = pl.copy(rules = fn(pl.rules))
    }
    val matching = remember(pl.rules, state) { if (pl.smart) state.candidates.count { it.matches(pl.rules, state.ctx) } else pl.itemIds.size }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    isNew && pl.smart -> stringResource(R.string.playlist_new_smart)
                    isNew -> stringResource(R.string.playlist_new)
                    pl.smart -> stringResource(R.string.playlist_edit_rules)
                    else -> stringResource(R.string.playlist_rename)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (pl.smart) Text(stringResource(R.string.playlist_rules_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaylistTile(pl.icon, pl.color, 44)
                OutlinedTextField(
                    value = pl.name,
                    onValueChange = { pl = pl.copy(name = it) },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Field(stringResource(R.string.playlist_look)) {
                PLAYLIST_ICONS.forEach { ic ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(if (pl.icon == ic) 2.dp else 1.dp, if (pl.icon == ic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { pl = pl.copy(icon = ic) },
                    ) { Text(ic, fontSize = 18.sp) }
                }
                PLAYLIST_COLORS.forEach { c ->
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (pl.color == c) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { pl = pl.copy(color = c) },
                    )
                }
            }
            if (pl.smart) {
                Field(stringResource(R.string.playlist_rule_feeds)) {
                    Chip(stringResource(R.string.playlist_rule_all), r.folders.isEmpty() && r.feedIds.isEmpty()) { rules { it.copy(folders = emptyList(), feedIds = emptyList()) } }
                    state.folders.forEach { f -> Chip(f, f in r.folders) { rules { it.copy(folders = it.folders.toggle(f)) } } }
                    Chip(if (r.feedIds.isEmpty()) "Pick feeds…" else "${r.feedIds.size} feeds picked", showFeeds || r.feedIds.isNotEmpty()) { showFeeds = !showFeeds }
                }
                if (showFeeds) {
                    Column {
                        state.feeds.forEach { (id, title) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { rules { it.copy(feedIds = it.feedIds.toggle(id)) } }) {
                                Checkbox(checked = id in r.feedIds, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Field(stringResource(R.string.playlist_rule_type)) {
                    ItemKind.entries.forEach { k -> Chip(if (k == ItemKind.ARTICLE) "Article (read aloud)" else k.label, k in r.kinds) { rules { it.copy(kinds = it.kinds.toggle(k)) } } }
                }
                Field(stringResource(R.string.playlist_rule_status)) {
                    PlayStatus.entries.forEach { s -> Chip(s.label, s in r.statuses) { rules { it.copy(statuses = it.statuses.toggle(s)) } } }
                    Chip(stringResource(R.string.playlist_rule_saved), r.savedOnly) { rules { it.copy(savedOnly = !it.savedOnly) } }
                    Chip(stringResource(R.string.playlist_rule_downloaded), r.downloadedOnly) { rules { it.copy(downloadedOnly = !it.downloadedOnly) } }
                }
                Field(stringResource(R.string.playlist_rule_published)) {
                    listOf(1, 2, 7, 14, 30, null).forEach { d -> Chip(withinLabel(d), r.withinDays == d) { rules { it.copy(withinDays = d) } } }
                }
                Field(stringResource(R.string.playlist_rule_length)) {
                    listOf<Triple<String, Int?, Int?>>(
                        Triple("Any length", null, null),
                        Triple("Under 10 min", null, 10),
                        Triple("Under 20 min", null, 20),
                        Triple("Under 40 min", null, 40),
                        Triple("Under 1 hour", null, 60),
                        Triple("Over 20 min", 20, null),
                        Triple("Over 1 hour", 60, null),
                    ).forEach { (label, min, max) -> Chip(label, r.minMinutes == min && r.maxMinutes == max) { rules { it.copy(minMinutes = min, maxMinutes = max) } } }
                }
                Text(stringResource(R.string.playlist_rule_length_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.playlist_cancel)) }
                Button(
                    onClick = { onSave(pl.copy(name = pl.name.trim().ifBlank { if (pl.smart) "Smart playlist" else "My playlist" }), isNew) },
                    modifier = Modifier.weight(2f),
                ) { Text(stringResource(R.string.playlist_save) + if (pl.smart) " · $matching items" else "") }
            }
        }
    }
}

private fun <T> List<T>.toggle(v: T): List<T> = if (v in this) this - v else this + v

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Field(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** "Getting 20 articles ready · 7 of 20" with Cancel, then "20 articles ready offline · 58 MB" with Remove. */
@Composable
private fun OfflineArticlesCard(
    state: com.nononsenseapps.feeder.model.playlists.PlaylistAudioState,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val size =
        android.text.format.Formatter
            .formatShortFileSize(context, state.bytes)
    val total = state.ids.size
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.running) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        state.running -> stringResource(R.string.playlist_preparing, total, state.done)
                        state.readyInBatch > 0 -> stringResource(R.string.playlist_prepared, state.readyInBatch, size)
                        else -> stringResource(R.string.playlist_prepared_none)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.running) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                } else if (state.readyInBatch > 0) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.playlist_prepare_remove)) }
                }
            }
            if (state.running) {
                val within = if (state.preparingId != null) state.progress else 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { ((state.done + within) / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp),
                )
            }
            Text(
                when {
                    !state.running -> stringResource(R.string.playlist_prepared_hint)
                    state.bytes > 0 -> stringResource(R.string.playlist_preparing_hint, size)
                    else -> stringResource(R.string.playlist_preparing_hint_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The first items' pictures (or their feed's), for a playlist's collage tile. */
internal fun List<com.nononsenseapps.feeder.db.room.PlaylistCandidate>.playlistArt(): List<String> =
    asSequence()
        .mapNotNull { it.image?.url?.takeIf { u -> u.isNotBlank() } ?: it.feedImage?.takeIf { u -> u.isNotBlank() } }
        .distinct()
        .take(4)
        .toList()
