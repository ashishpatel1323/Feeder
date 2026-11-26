package com.nononsenseapps.feeder.ui.compose.managefeeds

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.PREF_VAL_OPEN_WITH_BROWSER
import com.nononsenseapps.feeder.archmodel.PREF_VAL_OPEN_WITH_CUSTOM_TAB
import com.nononsenseapps.feeder.archmodel.PREF_VAL_OPEN_WITH_READER
import com.nononsenseapps.feeder.archmodel.PREF_VAL_OPEN_WITH_WEBVIEW
import com.nononsenseapps.feeder.db.room.OPEN_ARTICLE_WITH_APPLICATION_DEFAULT
import com.nononsenseapps.feeder.model.opml.exportOpml
import com.nononsenseapps.feeder.model.opml.importOpml
import com.nononsenseapps.feeder.ui.compose.components.ConfirmDeleteDialog
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.editfeed.Bars
import com.nononsenseapps.feeder.ui.compose.editfeed.DeleteFeedDialog
import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStatsPanel
import com.nononsenseapps.feeder.ui.compose.editfeed.formatRate
import com.nononsenseapps.feeder.ui.compose.editfeed.timeAgo
import com.nononsenseapps.feeder.ui.compose.home.initials
import com.nononsenseapps.feeder.ui.compose.home.tileColors
import com.nononsenseapps.feeder.ui.compose.item.ItemActionButton
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.time.LocalDate
import java.time.LocalTime

/** Opens-in choices, as on the feed settings page. */
private val OPENERS =
    listOf(
        OPEN_ARTICLE_WITH_APPLICATION_DEFAULT to R.string.manage_feeds_default,
        PREF_VAL_OPEN_WITH_READER to R.string.open_in_reader,
        PREF_VAL_OPEN_WITH_WEBVIEW to R.string.open_in_webview,
        PREF_VAL_OPEN_WITH_BROWSER to R.string.open_in_default_browser,
        PREF_VAL_OPEN_WITH_CUSTOM_TAB to R.string.open_in_custom_tab,
    )

private fun openerLabel(value: String): Int = OPENERS.firstOrNull { it.first == value }?.second ?: R.string.manage_feeds_default

private fun ManageSort.label(): Int =
    when (this) {
        ManageSort.NAME -> R.string.manage_sort_name
        ManageSort.MOST_ACTIVE -> R.string.manage_sort_most_active
        ManageSort.LEAST_ACTIVE -> R.string.manage_sort_least_active
        ManageSort.MOST_UNREAD -> R.string.manage_sort_most_unread
        ManageSort.READ_LEAST -> R.string.manage_sort_read_least
        ManageSort.STALE -> R.string.manage_sort_stale
    }

/** What a dialog on the page is about. */
private sealed interface ManageDialog {
    data class RenameFeed(
        val id: Long,
    ) : ManageDialog

    data class DeleteFeeds(
        val ids: List<Long>,
    ) : ManageDialog

    data class RenameFolder(
        val tag: String,
    ) : ManageDialog

    data class DeleteFolder(
        val tag: String,
    ) : ManageDialog

    /** New folder for these feeds (from a folder picker). */
    data class NewFolder(
        val ids: List<Long>,
    ) : ManageDialog
}

/**
 * Manage feeds: every feed grouped by folder, the Android version of the web app's page
 * (web/src/components/ManageFeeds.tsx). Rows show the name, activity and unread count; tapping
 * one opens a sheet with its settings, folder and stats. Long press selects feeds to move or
 * delete together.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManageFeedsScreen(
    viewModel: ManageFeedsViewModel,
    onNavigateUp: () -> Unit,
    onAddFeed: () -> Unit,
    onShowArticles: (Long) -> Unit,
    onEditFeed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.viewState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun say(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    var searchOpen by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetFeed by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<ManageDialog?>(null) }
    val selecting = state.selected.isNotEmpty()

    BackHandler(enabled = selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !selecting && searchOpen) {
        searchOpen = false
        viewModel.setQuery("")
    }

    val di = LocalDI.current
    val opmlExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
            if (uri != null) {
                val appScope: ApplicationCoroutineScope by di.instance()
                appScope.launch { exportOpml(di, uri) }
            }
        }
    val opmlImporter =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val appScope: ApplicationCoroutineScope by di.instance()
                appScope.launch { importOpml(di, uri) }
            }
        }

    val moved: (Int, String) -> Unit = { n, tag ->
        val name = tag.ifBlank { context.getString(R.string.manage_feeds_no_folder) }
        say(if (n > 0) context.resources.getQuantityString(R.plurals.manage_feeds_moved, n, n, name) else context.getString(R.string.manage_feeds_already_there))
    }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SensibleTopAppBar(
                title = stringResource(R.string.manage_feeds),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOpen) viewModel.setQuery("")
                        searchOpen = !searchOpen
                    }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.manage_feeds_search))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.open_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_feeds_expand_all)) },
                                leadingIcon = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
                                enabled = !state.filtering && state.closed.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    viewModel.expandAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_feeds_collapse_all)) },
                                leadingIcon = { Icon(Icons.Default.UnfoldLess, contentDescription = null) },
                                enabled = !state.filtering && state.folders.any { it.tag !in state.closed },
                                onClick = {
                                    menuOpen = false
                                    viewModel.collapseAll()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_feeds_from_opml)) },
                                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    try {
                                        opmlImporter.launch(arrayOf("text/xml", "text/x-opml", "application/xml", "application/octet-stream", "*/*"))
                                    } catch (_: Exception) {
                                        say(context.getString(R.string.failed_to_import_OPML))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_feeds_to_opml)) },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    try {
                                        opmlExporter.launch("feeder-export-${LocalDate.now()}-${LocalTime.now().toSecondOfDay()}.opml")
                                    } catch (_: Exception) {
                                        say(context.getString(R.string.failed_to_export_OPML))
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = onAddFeed,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_feed)) },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    count = state.selected.size,
                    tags = state.allTags,
                    onMove = { tag -> viewModel.moveTo(state.selected, tag) { n -> moved(n, tag) } },
                    onNewFolder = { dialog = ManageDialog.NewFolder(state.selected.toList()) },
                    onDelete = { dialog = ManageDialog.DeleteFeeds(state.selected.toList()) },
                    onSelectAll = viewModel::selectAllVisible,
                    onCancel = viewModel::clearSelection,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Outside the list, so it shows even when the list is scrolled
            if (searchOpen) SearchField(state.query, viewModel::setQuery)
            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "tools") { Toolbar(state, viewModel::setSort) }

                if (state.loaded) {
                    for (folder in state.folders) {
                        val closed = state.isClosed(folder.tag)
                        stickyHeader(key = "folder:${folder.tag}") {
                            FolderHeader(
                                folder = folder,
                                closed = closed,
                                canToggle = !state.filtering,
                                onToggle = { viewModel.toggleFolder(folder.tag) },
                                onRename = { dialog = ManageDialog.RenameFolder(folder.tag) },
                                onDelete = { dialog = ManageDialog.DeleteFolder(folder.tag) },
                            )
                        }
                        if (!closed) {
                            items(folder.shown, key = { it.id }) { feed ->
                                FeedRow(
                                    feed = feed,
                                    selected = feed.id in state.selected,
                                    onClick = { if (selecting) viewModel.toggleSelected(feed.id) else sheetFeed = feed.id },
                                    onToggleSelect = { viewModel.toggleSelected(feed.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                    val message =
                        when {
                            state.feeds.isEmpty() -> R.string.manage_feeds_empty
                            state.folders.isEmpty() && state.query.isNotBlank() -> R.string.manage_feeds_nothing_matches
                            state.folders.isEmpty() && state.sort == ManageSort.STALE -> R.string.manage_feeds_nothing_stale
                            else -> null
                        }
                    if (message != null) {
                        item(key = "message") {
                            Text(
                                stringResource(message),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(FeederTokens.Space.xl),
                            )
                        }
                    }
                }
            }
        }
    }

    val open = sheetFeed?.let { state.feed(it) }
    if (open != null) {
        FeedSheet(
            feed = open,
            tags = state.allTags,
            onDismiss = { sheetFeed = null },
            onShowArticles = { onShowArticles(open.id) },
            onEditFeed = {
                sheetFeed = null
                onEditFeed(open.id)
            },
            onRename = { dialog = ManageDialog.RenameFeed(open.id) },
            onDelete = { dialog = ManageDialog.DeleteFeeds(listOf(open.id)) },
            onOpensWith = { viewModel.setOpensWith(open.id, it) },
            onFullText = { viewModel.setFullText(open.id, it) },
            onMove = { tag -> viewModel.moveTo(listOf(open.id), tag) { n -> moved(n, tag) } },
            onNewFolder = { dialog = ManageDialog.NewFolder(listOf(open.id)) },
        )
    } else if (sheetFeed != null && state.loaded) {
        // Deleted, from here or another device
        sheetFeed = null
    }

    when (val d = dialog) {
        null -> Unit
        is ManageDialog.RenameFeed -> {
            val feed = state.feed(d.id)
            if (feed == null) {
                dialog = null
            } else {
                NameDialog(
                    title = stringResource(R.string.manage_feeds_rename),
                    label = stringResource(R.string.manage_feeds_feed_name),
                    initial = feed.title,
                    onDismiss = { dialog = null },
                    onSave = {
                        dialog = null
                        viewModel.rename(d.id, it)
                    },
                )
            }
        }
        is ManageDialog.DeleteFeeds -> {
            val feeds = d.ids.mapNotNull { state.feed(it) }
            if (feeds.isEmpty()) {
                dialog = null
            } else {
                val done: (Int) -> Unit = { n ->
                    say(context.resources.getQuantityString(R.plurals.manage_feeds_deleted, n, n))
                }
                val single = feeds.singleOrNull()
                if (single != null) {
                    DeleteFeedDialog(
                        feedTitle = single.title,
                        stats = single.stats,
                        onConfirm = {
                            dialog = null
                            sheetFeed = null
                            viewModel.delete(listOf(single.id), done)
                        },
                        onDismiss = { dialog = null },
                    )
                } else {
                    DeleteManyDialog(
                        feeds = feeds,
                        onConfirm = {
                            dialog = null
                            viewModel.delete(feeds.map { it.id }, done)
                        },
                        onDismiss = { dialog = null },
                    )
                }
            }
        }
        is ManageDialog.RenameFolder ->
            NameDialog(
                title = stringResource(R.string.manage_feeds_rename_folder),
                label = stringResource(R.string.manage_feeds_folder_name),
                initial = d.tag,
                onDismiss = { dialog = null },
                onSave = {
                    dialog = null
                    viewModel.renameFolder(d.tag, it)
                },
            )
        is ManageDialog.DeleteFolder -> {
            val count = state.feeds.count { it.tag == d.tag }
            DeleteFolderDialog(
                tag = d.tag,
                count = count,
                onKeep = {
                    dialog = null
                    viewModel.deleteFolder(d.tag, deleteFeeds = false)
                },
                onDeleteFeeds = {
                    dialog = null
                    viewModel.deleteFolder(d.tag, deleteFeeds = true)
                },
                onDismiss = { dialog = null },
            )
        }
        is ManageDialog.NewFolder ->
            NameDialog(
                title = stringResource(R.string.manage_feeds_new_folder).trimEnd('…'),
                label = stringResource(R.string.manage_feeds_folder_name),
                initial = "",
                onDismiss = { dialog = null },
                onSave = { name ->
                    dialog = null
                    viewModel.moveTo(d.ids, name) { n -> moved(n, name.trim()) }
                },
            )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.manage_feeds_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.manage_feeds_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        shape = RoundedCornerShape(FeederTokens.Radius.xl),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.xs)
                .focusRequester(focus),
    )
}

/** One line: the Sort chip with its menu, and how many folders and feeds there are. */
@Composable
private fun Toolbar(
    state: ManageFeedsViewState,
    onSort: (ManageSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FeederTokens.Space.lg)
                .horizontalScroll(rememberScrollState()),
    ) {
        Box {
            FilterChip(
                selected = state.sort != ManageSort.NAME,
                onClick = { open = true },
                label = { Text(stringResource(R.string.manage_feeds_sort, stringResource(state.sort.label()))) },
                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (sort in ManageSort.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(sort.label())) },
                        trailingIcon = { if (sort == state.sort) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = {
                            open = false
                            onSort(sort)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(FeederTokens.Space.md))
        val folders = state.allTags.size
        Text(
            stringResource(
                R.string.manage_feeds_summary,
                pluralStringResource(R.plurals.manage_feeds_folders, folders, folders),
                pluralStringResource(R.plurals.manage_feeds_feeds, state.feeds.size, state.feeds.size),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderHeader(
    folder: ManagedFolder,
    closed: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = folder.tag.ifBlank { stringResource(R.string.manage_feeds_no_folder) }
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        enabled = canToggle,
                        onClickLabel =
                            stringResource(
                                if (closed) R.string.manage_feeds_expand_folder else R.string.manage_feeds_collapse_folder,
                                name,
                            ),
                        onClick = onToggle,
                    ).padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs),
        ) {
            Icon(
                if (closed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = if (canToggle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(FeederTokens.Space.sm))
            Icon(
                if (folder.tag.isBlank()) Icons.Outlined.FolderOff else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(FeederTokens.Space.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.manage_feeds_feeds, folder.all.size, folder.all.size) +
                        if (folder.unread > 0) " · " + stringResource(R.string.manage_feeds_unread, folder.unread) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (folder.tag.isNotBlank()) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.manage_feeds_folder_menu, name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_feeds_rename_folder)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_feeds_delete_folder)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            colors =
                                MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error,
                                ),
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

/** A feed row: picture, name, activity and unread count, and a 30-day spark line. No buttons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(
    feed: ManagedFeed,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectLabel = stringResource(R.string.manage_feeds_select)
    val notes =
        listOfNotNull(
            stringResource(R.string.manage_feeds_full_article).takeIf { feed.fullTextByDefault },
            feed.openArticlesWith
                .takeIf { it != OPEN_ARTICLE_WITH_APPLICATION_DEFAULT }
                ?.let { stringResource(R.string.manage_feeds_opens_in_value, stringResource(openerLabel(it))) },
        )
    ListItem(
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        leadingContent = { FeedAvatar(feed, selected = selected, size = 40) },
        headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            // One meta line: activity, unread, then any setting that is not the default
            val meta =
                listOfNotNull(
                    formatRate(feed.stats.rate),
                    stringResource(R.string.manage_feeds_unread, feed.stats.unread).takeIf { feed.stats.unread > 0 },
                ) + notes
            Text(meta.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Bars(values = feed.stats.perDay, gap = 1.dp, modifier = Modifier.width(56.dp).height(20.dp))
        },
        modifier =
            modifier
                .semantics {
                    this.selected = selected
                    customActions = listOf(CustomAccessibilityAction(selectLabel) { onToggleSelect().let { true } })
                }.combinedClickable(onClick = onClick, onLongClick = onToggleSelect),
    )
}

@Composable
private fun FeedAvatar(
    feed: ManagedFeed,
    size: Int,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(FeederTokens.Radius.sm)
    if (selected) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        return
    }
    val (bg, fg) = tileColors(feed.title)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size.dp)
                .clip(shape)
                .background(Color(bg))
                .clearAndSetSemantics { },
    ) {
        Text(initials(feed.title), color = Color(fg), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        if (!feed.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(feed.imageUrl)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A feed's quick settings and stats; everything else is a tap away in All feed settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSheet(
    feed: ManagedFeed,
    tags: List<String>,
    onDismiss: () -> Unit,
    onShowArticles: () -> Unit,
    onEditFeed: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpensWith: (String) -> Unit,
    onFullText: (Boolean) -> Unit,
    onMove: (String) -> Unit,
    onNewFolder: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FeederTokens.Space.lg)
                    .navigationBarsPadding()
                    .padding(bottom = FeederTokens.Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedAvatar(feed, size = 48)
                Spacer(Modifier.width(FeederTokens.Space.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(feed.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        feed.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.manage_feeds_feed_menu, feed.title))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_feeds_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.manage_feeds_all_settings)) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = {
                                menu = false
                                onEditFeed()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_feed)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            colors =
                                MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error,
                                ),
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            FilledTonalButton(onClick = onShowArticles, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(FeederTokens.Space.sm))
                Text(
                    if (feed.stats.unread > 0) {
                        stringResource(R.string.feed_stats_show_articles_unread, feed.stats.unread)
                    } else {
                        stringResource(R.string.feed_stats_show_articles)
                    },
                )
            }

            Text(stringResource(R.string.manage_feeds_settings), style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(FeederTokens.Radius.md), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    PickerRow(
                        label = stringResource(R.string.manage_feeds_opens_in),
                        value = stringResource(openerLabel(feed.openArticlesWith)),
                    ) { close ->
                        for ((value, label) in OPENERS) {
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                trailingIcon = { if (value == feed.openArticlesWith) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = {
                                    close()
                                    onOpensWith(value)
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.manage_feeds_full_article)) },
                        supportingContent = { Text(stringResource(R.string.manage_feeds_full_article_hint)) },
                        trailingContent = { Switch(checked = feed.fullTextByDefault, onCheckedChange = null) },
                        modifier = Modifier.clickable { onFullText(!feed.fullTextByDefault) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    PickerRow(
                        label = stringResource(R.string.manage_feeds_folder),
                        value = feed.tag.ifBlank { stringResource(R.string.manage_feeds_no_folder) },
                    ) { close ->
                        FolderMenuItems(
                            tags = tags,
                            current = feed.tag,
                            onPick = {
                                close()
                                onMove(it)
                            },
                            onNewFolder = {
                                close()
                                onNewFolder()
                            },
                        )
                    }
                }
            }

            Text(stringResource(R.string.feed_stats_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.feed_stats_checked, timeAgo(feed.lastSync, stringResource(R.string.feed_stats_never))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FeedStatsPanel(feed.stats)
        }
    }
}

/** A setting row whose value opens a menu. */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(label) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.clickable { open = true },
        )
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

@Composable
private fun ColumnScope.FolderMenuItems(
    tags: List<String>,
    current: String?,
    onPick: (String) -> Unit,
    onNewFolder: () -> Unit,
) {
    for (tag in tags + "") {
        DropdownMenuItem(
            text = { Text(tag.ifBlank { stringResource(R.string.manage_feeds_no_folder) }) },
            leadingIcon = { Icon(if (tag.isBlank()) Icons.Outlined.FolderOff else Icons.Default.Folder, contentDescription = null) },
            trailingIcon = { if (tag == current) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = { onPick(tag) },
        )
    }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.manage_feeds_new_folder)) },
        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
        onClick = onNewFolder,
    )
}

/** Under the list while feeds are selected; every action is a labeled button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    count: Int,
    tags: List<String>,
    onMove: (String) -> Unit,
    onNewFolder: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.item_action_cancel))
                }
                Text(stringResource(R.string.n_selected, count), style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            ) {
                var moveMenu by remember { mutableStateOf(false) }
                Box {
                    ItemActionButton(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.manage_feeds_move_to), { moveMenu = true }, primary = true)
                    DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                        FolderMenuItems(
                            tags = tags,
                            current = null,
                            onPick = {
                                moveMenu = false
                                onMove(it)
                            },
                            onNewFolder = {
                                moveMenu = false
                                onNewFolder()
                            },
                        )
                    }
                }
                ItemActionButton(Icons.Outlined.Delete, stringResource(R.string.delete_button_text), onDelete, danger = true)
                ItemActionButton(Icons.Default.SelectAll, stringResource(R.string.manage_feeds_select_all), onSelectAll)
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSave(value) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.manage_feeds_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteManyDialog(
    feeds: List<ManagedFeed>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = feeds.sumOf { it.stats.stored }
    val saved = feeds.sumOf { it.stats.saved }
    val names = feeds.take(5).joinToString(", ") { it.title } + if (feeds.size > 5) ", …" else ""
    val extra =
        buildString {
            append(pluralStringResource(R.plurals.manage_feeds_delete_selected_articles, stored, stored))
            if (saved > 0) {
                append(" ")
                append(pluralStringResource(R.plurals.delete_feed_confirm_saved, saved, saved))
            }
        }
    ConfirmDeleteDialog(
        title = stringResource(R.string.confirm_delete_title_feeds),
        itemName = pluralStringResource(R.plurals.confirm_delete_n_feeds, feeds.size, feeds.size) + ": " + names,
        scope = DeleteScope.ALL_DEVICES,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extra = extra,
    )
}

@Composable
private fun DeleteFolderDialog(
    tag: String,
    count: Int,
    onKeep: () -> Unit,
    onDeleteFeeds: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.manage_feeds_delete_folder_title, tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                Text(pluralStringResource(R.plurals.manage_feeds_delete_folder_text, count, count))
                Text(stringResource(R.string.manage_feeds_delete_folder_sync))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.manage_feeds_keep_feeds)) }
                TextButton(onClick = onDeleteFeeds, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(pluralStringResource(R.plurals.manage_feeds_delete_feeds_too, count, count))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
