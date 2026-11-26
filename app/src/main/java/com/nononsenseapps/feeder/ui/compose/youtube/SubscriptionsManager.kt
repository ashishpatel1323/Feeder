package com.nononsenseapps.feeder.ui.compose.youtube

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.YouTubeChannel
import com.nononsenseapps.feeder.ui.compose.editfeed.DeleteFeedDialog
import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/**
 * Flow's subscriptions manager over Feeder's YouTube feeds: search, sort, import (OPML or YouTube
 * Takeout's subscriptions.csv) or add a channel, video and music channels apart, and per channel
 * Subscribed ▾ with move to group, music, and Unsubscribe (deletes the feed).
 */
@Composable
fun SubscriptionsManagerScreen(
    viewModel: YouTubeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.viewState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    var query by rememberSaveable { mutableStateOf("") }
    var music by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var addChannel by rememberSaveable { mutableStateOf(false) }
    var unsubscribe by remember { mutableStateOf<YouTubeChannel?>(null) }
    var move by remember { mutableStateOf<YouTubeChannel?>(null) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.import(uri)
        }
    val shown = state.channelsShown(query, music)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = FeederTokens.Space.xs, vertical = FeederTokens.Space.sm),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.youtube_search_subscriptions_hint)) },
                    singleLine = true,
                    shape = CircleShape,
                    colors =
                        TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.youtube_clear))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.youtube_sort))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ChannelSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(stringResource(s.label())) },
                                trailingIcon = { if (state.sort == s) Icon(Icons.Default.Check, contentDescription = null) },
                                onClick = {
                                    viewModel.setSort(s)
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { importMenu = true }) {
                        Icon(Icons.Outlined.Upload, contentDescription = stringResource(R.string.youtube_import))
                    }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.youtube_add_channel)) },
                            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                            onClick = {
                                importMenu = false
                                addChannel = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.youtube_import_file)) },
                            leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                            onClick = {
                                importMenu = false
                                importer.launch(arrayOf("text/*", "application/xml", "text/xml", "text/x-opml", "text/csv", "text/comma-separated-values", "application/octet-stream"))
                            },
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.xs)) {
                SegmentedButton(
                    selected = !music,
                    onClick = { music = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { SegmentedButtonDefaults.Icon(!music) { Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(18.dp)) } },
                    label = { Text(stringResource(R.string.youtube_video_subscriptions), maxLines = 1) },
                )
                SegmentedButton(
                    selected = music,
                    onClick = { music = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { SegmentedButtonDefaults.Icon(music) { Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(18.dp)) } },
                    label = { Text(stringResource(R.string.youtube_music_subscriptions), maxLines = 1) },
                )
            }
            Text(
                pluralStringResource(R.plurals.youtube_channels, shown.size, shown.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        onMove = { move = channel },
                        onToggleMusic = { viewModel.setMusic(channel.id, !channel.music) },
                        onUnsubscribe = { unsubscribe = channel },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (addChannel) {
        AddChannelDialog(
            onAdd = {
                addChannel = false
                viewModel.addChannel(it)
            },
            onDismiss = { addChannel = false },
        )
    }
    unsubscribe?.let { channel ->
        val stats by produceState<FeedStats?>(null, channel.id) { value = viewModel.statsFor(channel.id) }
        DeleteFeedDialog(
            feedTitle = channel.displayTitle,
            stats = stats,
            onConfirm = {
                unsubscribe = null
                viewModel.unsubscribe(channel.id)
            },
            onDismiss = { unsubscribe = null },
        )
    }
    move?.let { channel ->
        MoveToGroupDialog(
            groups = state.groups,
            current = channel.tag,
            onPick = {
                move = null
                viewModel.moveToGroup(listOf(channel.id), it)
            },
            onDismiss = { move = null },
        )
    }
}

private fun ChannelSort.label(): Int =
    when (this) {
        ChannelSort.NAME -> R.string.youtube_sort_name
        ChannelSort.RECENTLY_ADDED -> R.string.youtube_sort_recent
        ChannelSort.MOST_ACTIVE -> R.string.youtube_sort_active
    }

@Composable
private fun ChannelRow(
    channel: YouTubeChannel,
    onMove: () -> Unit,
    onToggleMusic: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { ChannelAvatar(channel.displayTitle, channel.imageUrl?.toString(), 44.dp) },
        headlineContent = { Text(channel.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = channel.tag.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1) } },
        trailingContent = {
            Box {
                FilledTonalButton(onClick = { menu = true }) {
                    Text(stringResource(R.string.youtube_subscribed))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.youtube_move_to_group)) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            menu = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (channel.music) R.string.youtube_move_to_videos else R.string.youtube_move_to_music)) },
                        leadingIcon = { Icon(if (channel.music) Icons.Outlined.VideoLibrary else Icons.Outlined.LibraryMusic, contentDescription = null) },
                        onClick = {
                            menu = false
                            onToggleMusic()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.youtube_unsubscribe), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            onUnsubscribe()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun AddChannelDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_add_channel)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.youtube_add_channel_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.youtube_subscribe)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun MoveToGroupDialog(
    groups: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_move_to_group)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        GroupChoice(stringResource(R.string.youtube_no_group), current.isBlank()) { onPick("") }
                    }
                    items(groups) { g -> GroupChoice(g, g == current) { onPick(g) } }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.youtube_new_group)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.sm),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(newName) }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.youtube_create_and_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun GroupChoice(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = FeederTokens.Space.md),
    ) {
        Icon(if (selected) Icons.Default.Check else Icons.Outlined.Folder, contentDescription = null)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Edit groups (Flow's pencil): the folders holding YouTube feeds, each to rename, delete or pick
 * channels for, plus a new group. Same folders as Manage feeds.
 */
@Composable
fun GroupsDialog(
    state: YouTubeViewState,
    onSave: (oldName: String?, name: String, feedIds: Set<Long>) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = the list; "" = a new group; else the group being edited
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val edit = editing
    if (edit != null) {
        GroupEditor(
            channels = state.channels,
            oldName = edit.ifEmpty { null },
            onSave = { name, ids ->
                onSave(edit.ifEmpty { null }, name, ids)
                editing = null
            },
            onDismiss = { editing = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_edit_groups)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(state.groups) { g ->
                    val count = state.channels.count { it.tag == g }
                    ListItem(
                        headlineContent = { Text(g) },
                        supportingContent = { Text(pluralStringResource(R.plurals.youtube_channels, count, count)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = g }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.youtube_edit_group, g))
                                }
                                IconButton(onClick = { deleting = g }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.youtube_delete_group, g))
                                }
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.youtube_new_group)) },
                        modifier = Modifier.clickable { editing = "" },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.youtube_done)) } },
    )
    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.youtube_delete_group, g)) },
            text = { Text(stringResource(R.string.youtube_delete_group_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(g)
                    deleting = null
                }) { Text(stringResource(R.string.delete_button_text), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupEditor(
    channels: List<YouTubeChannel>,
    oldName: String?,
    onSave: (String, Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(oldName ?: "") }
    var picked by remember { mutableStateOf(channels.filter { oldName != null && it.tag == oldName }.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (oldName == null) R.string.youtube_new_group else R.string.youtube_edit_group, oldName ?: "")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.youtube_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.youtube_pick_channels),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = FeederTokens.Space.md, bottom = FeederTokens.Space.xs),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(channels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }), key = { it.id }) { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { picked = if (c.id in picked) picked - c.id else picked + c.id },
                        ) {
                            Checkbox(checked = c.id in picked, onCheckedChange = { picked = if (it) picked + c.id else picked - c.id })
                            ChannelAvatar(c.displayTitle, c.imageUrl?.toString(), 28.dp)
                            Surface(color = Color.Transparent, modifier = Modifier.padding(start = FeederTokens.Space.sm)) {
                                Text(c.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, picked) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
