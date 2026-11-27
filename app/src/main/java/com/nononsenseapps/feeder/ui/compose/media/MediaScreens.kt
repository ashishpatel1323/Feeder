package com.nononsenseapps.feeder.ui.compose.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.text.format.Formatter
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.audiobooks.AudioPos
import com.nononsenseapps.feeder.model.audiobooks.AudioPositions
import com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer
import com.nononsenseapps.feeder.model.audiobooks.AudiobookRules
import com.nononsenseapps.feeder.model.media.MediaFile
import com.nononsenseapps.feeder.model.media.MediaLibrary
import com.nononsenseapps.feeder.model.media.MediaRules
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.sync2.ThisDevice
import com.nononsenseapps.feeder.sync2.otherDeviceText
import com.nononsenseapps.feeder.ui.compose.audiobooks.Round
import com.nononsenseapps.feeder.ui.compose.audiobooks.SleepButton
import com.nononsenseapps.feeder.ui.compose.audiobooks.SpeedButton
import com.nononsenseapps.feeder.ui.compose.books.LocalBooksPage
import com.nononsenseapps.feeder.ui.compose.components.DeleteRequest
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.DeleteVerb
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.ebooks.DeleteFromDropboxDialog
import com.nononsenseapps.feeder.ui.compose.ebooks.DropboxAccountButton
import com.nononsenseapps.feeder.ui.compose.ebooks.uploadOrExplain
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.shelf.GeneratedCover
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAction
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAdd
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfControls
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfItem
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfQuick
import com.nononsenseapps.feeder.ui.compose.shelf.Where
import com.nononsenseapps.feeder.ui.compose.shelf.rememberShelfPrefs
import com.nononsenseapps.feeder.ui.compose.shelf.shelf
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import kotlin.math.roundToInt

/*
 * Library > Player: any audio or video file on this phone or in Dropbox › Apps › Feeder › Media,
 * with Dropbox's folders as folders, and one file's screen (the video, or the sound's controls,
 * then what plays next in its folder). The web app's PlayerPage.tsx is the same.
 */

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** "42% watched on Pixel 8, 2 h ago" / "Watched" / "" */
@Composable
private fun progressText(
    m: MediaFile,
    pos: AudioPos?,
    thisDeviceId: String,
): String {
    if (pos == null || pos.ms < 1000) return ""
    if (pos.f >= 0.99) return stringResource(if (m.isVideo) R.string.media_watched else R.string.media_played)
    val what =
        if (pos.f > 0) {
            stringResource(if (m.isVideo) R.string.media_watched_pct else R.string.media_played_pct, (pos.f * 100).roundToInt().coerceAtLeast(1))
        } else {
            AudiobookRules.clock(pos.ms)
        }
    return otherDeviceText(pos.by, pos.ts, thisDeviceId)?.let { "$what $it" } ?: what
}

private fun where(
    library: MediaLibrary,
    m: MediaFile,
): Where =
    when {
        m.dropboxPath == null -> Where.DEVICE
        library.onPhone(m) -> Where.BOTH
        else -> Where.CLOUD
    }

/** The ⋮ actions of a file: download, remove the download, upload, take a phone file out, delete from Dropbox. */
@Composable
private fun rememberFileActions(
    library: MediaLibrary,
    signedIn: Boolean,
    onDeleteFromDropbox: (MediaFile) -> Unit,
): (MediaFile) -> List<ShelfAction> {
    val context = LocalContext.current
    val deleteConfirmer = rememberDeleteConfirmer()
    val jobs by library.jobs.collectAsState()
    val downloadText = stringResource(R.string.audiobook_download)
    val removeDownloadText = stringResource(R.string.audiobook_remove_download)
    val forgetText = stringResource(R.string.media_remove_from_feeder)
    val uploadText = stringResource(R.string.upload_to_dropbox)
    val deleteDropboxText = stringResource(R.string.delete_from_dropbox)
    val deleteText = stringResource(R.string.media_delete)
    val what = stringResource(R.string.media_what)
    return { m ->
        val busy = jobs.containsKey(m.id)
        val downloaded = library.localFile(m) != null
        buildList {
            if (m.dropboxPath != null && !downloaded && m.uri == null && !busy) add(ShelfAction(downloadText) { library.download(m.id) })
            if (downloaded && m.dropboxPath != null && m.uri == null) {
                add(
                    ShelfAction(removeDownloadText) {
                        deleteConfirmer.ask(
                            DeleteRequest(
                                title = R.string.confirm_delete_title_remove_from_device,
                                itemName = m.title,
                                scope = DeleteScope.THIS_DEVICE,
                                verb = DeleteVerb.REMOVE,
                                extra = R.string.confirm_delete_extra_stays_in_dropbox,
                                onConfirm = { library.removeDownload(m.id) },
                            ),
                        )
                    },
                )
            }
            if (m.dropboxPath == null && !busy) add(ShelfAction(uploadText) { uploadOrExplain(context, signedIn, what) { library.uploadToDropbox(m.id) } })
            if (m.uri == null && m.dropboxPath == null && downloaded) {
                add(
                    ShelfAction(deleteText) {
                        deleteConfirmer.ask(
                            DeleteRequest(
                                title = R.string.media_confirm_delete_title,
                                itemName = m.title,
                                scope = DeleteScope.THIS_DEVICE,
                                extra = R.string.media_confirm_delete_only_copy,
                                onConfirm = { library.deleteFromPhone(m.id) },
                            ),
                        )
                    },
                )
            }
            if (m.uri != null) {
                add(
                    ShelfAction(forgetText) {
                        deleteConfirmer.ask(
                            DeleteRequest(
                                title = R.string.media_confirm_forget_title,
                                itemName = m.title,
                                scope = DeleteScope.THIS_DEVICE,
                                verb = DeleteVerb.REMOVE,
                                extra = R.string.confirm_delete_extra_forget,
                                onConfirm = { library.forget(m.id) },
                            ),
                        )
                    },
                )
            }
            if (m.dropboxPath != null && !busy) add(ShelfAction(deleteDropboxText) { onDeleteFromDropbox(m) })
        }
    }
}

/** The Player list: [folder] "" is the top; folders first, then the folder's files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
    folder: String,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val di = LocalDI.current
    val nudge: SyncNudge by di.instance()
    val player: AudiobookPlayer by di.instance()
    val library = remember { MediaLibrary.get(context) }
    val positions = remember { AudioPositions.get(context) }
    val thisDeviceId = remember { ThisDevice.get(context).id }
    val dropbox = remember { DropboxClient.get(context) }
    val all by library.items.collectAsState()
    val jobs by library.jobs.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val adding by library.adding.collectAsState()
    val message by library.messages.collectAsState()
    library.version.collectAsState().value
    val pos by positions.all.collectAsState()
    val link by dropbox.link.collectAsState()
    val s by player.state.collectAsState()
    val prefs = rememberShelfPrefs("player", defaultCards = false)
    var deleting by remember { mutableStateOf<MediaFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> library.addFiles(uris) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(library::addFolder) }
    val actionsFor = rememberFileActions(library, link != null) { deleting = it }

    LifecycleResumeEffect(Unit) {
        library.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(link) { library.refresh() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            library.consumeMessage()
        }
    }

    // Books' Player subtab: Add, Check Dropbox and the Dropbox button go up to the Books header, whose search filters
    val page = LocalBooksPage.current
    val query =
        page
            ?.host
            ?.query
            ?.trim()
            .orEmpty()
    val what = stringResource(R.string.media_what)
    val filesLabel = stringResource(R.string.add_from_phone_files)
    val folderLabel = stringResource(R.string.add_from_phone_folder)
    val refreshLabel = stringResource(R.string.media_refresh)
    if (page != null) {
        val chrome = page.chrome
        SideEffect {
            chrome.onRefresh = if (link != null) ({ library.refresh() }) else null
            chrome.addActions =
                listOf(
                    ShelfAdd(Icons.Outlined.AudioFile, filesLabel) { pickFiles.launch(arrayOf("audio/*", "video/*", "application/x-subrip", "text/vtt", "application/octet-stream")) },
                    ShelfAdd(Icons.Outlined.Folder, folderLabel) { pickFolder.launch(null) },
                )
            chrome.dropbox = { DropboxAccountButton(what = what, folder = DropboxClient.MEDIA_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) }) }
            chrome.count = all.size
            chrome.available = true
            chrome.hasRefresh = link != null
            chrome.refreshing = refreshing
            chrome.refreshLabel = refreshLabel
            chrome.addLabels = listOf(filesLabel, folderLabel)
            chrome.prefs = null
            chrome.registered = true
        }
    }
    val (allFolders, folderFiles) = remember(all, folder) { MediaRules.folderView(all, folder) }
    // A search looks through every folder
    val files =
        if (query.isEmpty()) {
            folderFiles
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true) }.sortedBy { it.name.lowercase() }
        }
    val folders = if (query.isEmpty()) allFolders else allFolders.filter { it.name.contains(query, ignoreCase = true) }
    val under = remember(all, folder) { MediaRules.filesUnder(all, folder) }
    val downloadingText = stringResource(R.string.shelf_downloading)
    val uploadingText = stringResource(R.string.shelf_uploading)
    val playText = stringResource(R.string.audiobook_play)
    val pauseText = stringResource(R.string.audiobook_pause)
    val emptyPhoneText = stringResource(R.string.media_empty_phone)

    fun play(
        m: MediaFile,
        list: List<MediaFile>,
    ) {
        val k = list.indexOfFirst { it.id == m.id }
        player.openMedia(m.id, if (k >= 0) list.drop(k + 1).map { it.id } else emptyList())
    }

    val items =
        files.map { m ->
            val p = pos[m.id]
            val job = jobs[m.id]
            val isPlaying = s.media && s.bookId == m.id && (s.isPlaying || s.loading)
            ShelfItem(
                key = m.id,
                title = m.title,
                subtitle = if (query.isNotEmpty()) m.folder.replace("/", " › ") else null,
                detail =
                    listOf(
                        progressText(m, p, thisDeviceId),
                        m.duration?.let { AudiobookRules.lengthText((it * 1000).toLong()) }.orEmpty(),
                        if (m.dropboxPath == null || library.onPhone(m)) Formatter.formatShortFileSize(context, m.size) else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                fallback = if (m.isVideo) Icons.Outlined.Movie else Icons.Default.MusicNote,
                where = where(library, m),
                size = m.size,
                busy = job?.let { (if (it.up) uploadingText else downloadingText).format(if (it.total > 0) (it.done * 100 / it.total).toInt() else 0) },
                progress = p?.f?.toFloat(),
                quick =
                    ShelfQuick(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (isPlaying) pauseText else playText} ${m.title}") {
                        if (isPlaying) {
                            player.pause()
                        } else {
                            play(m, files)
                            if (m.isVideo) onOpenFile(m.id)
                        }
                    },
                onOpen = { onOpenFile(m.id) },
                actions = actionsFor(m),
            )
        }

    val title = if (folder.isEmpty()) stringResource(R.string.media_player) else folder.substringAfterLast('/')
    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (page == null) {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(if (folder.isEmpty()) R.string.go_back else R.string.media_up_folder))
                        }
                    },
                    actions = {
                        DropboxAccountButton(what = stringResource(R.string.media_what), folder = DropboxClient.MEDIA_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.media_add)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_from_phone_files)) },
                                    leadingIcon = { Icon(Icons.Outlined.AudioFile, null) },
                                    onClick = {
                                        menu = false
                                        pickFiles.launch(arrayOf("audio/*", "video/*", "application/x-subrip", "text/vtt", "application/octet-stream"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_from_phone_folder)) },
                                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                    onClick = {
                                        menu = false
                                        pickFolder.launch(null)
                                    },
                                )
                                if (link != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.media_refresh)) },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                        enabled = !refreshing,
                                        onClick = {
                                            menu = false
                                            library.refresh()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = FeederTokens.Space.xl),
        ) {
            if (folder.isNotEmpty()) {
                item(key = "crumbs", span = { GridItemSpan(maxLineSpan) }) { Breadcrumbs(folder, onOpenFolder) }
            }
            adding?.let { a ->
                item(key = "adding", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.media_adding, a), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            if (all.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                        Icon(Icons.Outlined.Movie, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.media_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                            OutlinedButton(onClick = { pickFiles.launch(arrayOf("audio/*", "video/*", "application/octet-stream")) }) { Text(stringResource(R.string.add_from_phone_files)) }
                            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.add_from_phone_folder)) }
                        }
                    }
                }
                return@LazyVerticalGrid
            }
            if (under.isNotEmpty() && query.isEmpty()) {
                item(key = "playall", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Button(onClick = {
                            player.openMedia(under.first().id, under.drop(1).map { it.id })
                            if (under.first().isVideo) onOpenFile(under.first().id)
                        }) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.media_play_all))
                        }
                        val shuffleLabel = stringResource(R.string.media_shuffle)
                        PlainTooltipBox(tooltip = { Text(shuffleLabel) }) {
                            IconButton(onClick = {
                                val order = under.shuffled()
                                player.openMedia(order.first().id, order.drop(1).map { it.id })
                                if (order.first().isVideo) onOpenFile(order.first().id)
                            }) { Icon(Icons.Default.Shuffle, contentDescription = shuffleLabel) }
                        }
                        Text(pluralStringResource(R.plurals.media_files, under.size, under.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(folders, key = { "folder:${it.path}" }) { f ->
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${pluralStringResource(R.plurals.media_files, f.count, f.count)} · ${Formatter.formatShortFileSize(context, f.size)}") },
                    leadingContent = {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(FeederTokens.Radius.md)).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { onOpenFolder(f.path) },
                )
            }
            if (items.isNotEmpty()) {
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                    ShelfControls(prefs, items, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), showLayout = false)
                }
                shelf(items, prefs, emptyPhoneText, streams = true)
            }
        }
    }

    deleting?.let { m ->
        DeleteFromDropboxDialog(
            title = m.title,
            keepsPhoneCopy = library.onPhone(m),
            onDelete = {
                if (!library.onPhone(m) && player.state.value.bookId == m.id) player.close()
                library.deleteFromDropbox(m.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Breadcrumbs(
    folder: String,
    onOpenFolder: (String) -> Unit,
) {
    val parts = folder.split('/')
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    ) {
        TextButton(onClick = { onOpenFolder("") }) { Text(stringResource(R.string.media_player)) }
        parts.forEachIndexed { k, name ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (k == parts.size - 1) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
            } else {
                TextButton(onClick = { onOpenFolder(parts.take(k + 1).joinToString("/")) }) { Text(name) }
            }
        }
    }
}

/** Whether the activity shows only this screen's video, in a small window. */
@Composable
private fun rememberInPip(activity: ComponentActivity?): Boolean {
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/** One file: the video (or the sound's controls), Speed, Sleep and Next, then what plays next in its folder. */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    id: String,
    onNavigateUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val di = LocalDI.current
    val player: AudiobookPlayer by di.instance()
    val library = remember { MediaLibrary.get(context) }
    val positions = remember { AudioPositions.get(context) }
    val thisDeviceId = remember { ThisDevice.get(context).id }
    val dropbox = remember { DropboxClient.get(context) }
    val link by dropbox.link.collectAsState()
    val all by library.items.collectAsState()
    library.version.collectAsState().value
    val pos by positions.all.collectAsState()
    val s by player.state.collectAsState()
    val m = all.firstOrNull { it.id == id }
    val mine = s.media && s.bookId == id
    val playing = mine && (s.isPlaying || s.loading)
    var deleting by remember { mutableStateOf<MediaFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }
    val actionsFor = rememberFileActions(library, link != null) { deleting = it }
    val inPip = rememberInPip(activity)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The files after this one in its folder, in play order
    val next =
        remember(all, m) {
            if (m == null) {
                emptyList()
            } else {
                val siblings = MediaRules.filesUnder(all, m.folder).filter { it.folder == m.folder }
                siblings.drop(siblings.indexOfFirst { it.id == id } + 1)
            }
        }

    // Opening the screen loads the file where it was left; a video starts, unless something else plays
    LaunchedEffect(id, m != null) {
        if (m == null || (player.state.value.media && player.state.value.bookId == id)) return@LaunchedEffect
        val busy = player.state.value.isPlaying || player.state.value.loading
        if (!busy) player.openMedia(id, next.map { it.id }, play = m.isVideo)
    }
    LaunchedEffect(Unit) {
        player.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // The next file started from here: follow it
    val onOpenNow by rememberUpdatedState(onOpenFile)
    LaunchedEffect(s.bookId) {
        val now = s.bookId
        if (s.media && now != null && now != id && next.any { it.id == now }) onOpenNow(now)
    }

    val video = m?.isVideo == true
    // Leaving the app while a video plays shrinks it to a small window (Android 12+)
    DisposableEffect(activity, video, mine, playing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null && video && mine) {
            activity.setPictureInPictureParams(
                PictureInPictureParams
                    .Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(playing)
                    .build(),
            )
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }

    if (m == null) {
        Scaffold(modifier = modifier, topBar = {
            TopAppBar(title = {}, navigationIcon = {
                IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back)) }
            })
        }) { padding ->
            Text(stringResource(R.string.media_gone), modifier = Modifier.padding(padding).padding(16.dp))
        }
        return
    }

    val videoView: @Composable (Modifier, Boolean) -> Unit = { mod, isFull ->
        Box(mod.background(Color.Black), contentAlignment = Alignment.Center) {
            if (mine) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            setShowSubtitleButton(true)
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setFullscreenButtonClickListener { full = it }
                        }
                    },
                    update = {
                        it.player = player.exoPlayer
                        it.keepScreenOn = playing
                        it.useController = !inPip
                        it.setFullscreenButtonState(isFull)
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                IconButton(onClick = { player.openMedia(id, next.map { it.id }) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.audiobook_play), tint = Color.White, modifier = Modifier.size(56.dp))
                }
            }
        }
    }

    // In the small window, or full screen (turned sideways, or the full screen button): the video alone
    if (video && (inPip || full || landscape)) {
        if (full) FullscreenWindowEffect(activity) { full = false }
        videoView(Modifier.fillMaxSize(), full || landscape)
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back)) }
                },
                actions = {
                    val actions = actionsFor(m)
                    if (actions.isNotEmpty() || video) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = m.title) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (video && mine && activity != null) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.media_pip)) }, onClick = {
                                        menu = false
                                        runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
                                    })
                                }
                                actions.forEach { a ->
                                    DropdownMenuItem(text = { Text(a.label) }, onClick = {
                                        menu = false
                                        a.run()
                                    })
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "now") {
                Column(horizontalAlignment = if (video) Alignment.Start else Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (video) {
                        videoView(Modifier.fillMaxWidth().aspectRatio(16f / 9f), false)
                    } else {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .widthIn(max = 280.dp)
                                .fillMaxWidth(0.7f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(28.dp)),
                        ) { GeneratedCover(m.title, Icons.Default.MusicNote, small = false, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = if (video) Alignment.Start else Alignment.CenterHorizontally) {
                        Text(
                            m.title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = if (video) TextAlign.Start else TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        val len = if (mine) s.bookLength else ((m.duration ?: 0.0) * 1000).toLong()
                        Text(
                            listOf(
                                m.folder.replace("/", " › "),
                                if (len > 0) AudiobookRules.lengthText(len) else "",
                                progressText(m, pos[m.id], thisDeviceId),
                                stringResource(
                                    when (where(library, m)) {
                                        Where.DEVICE -> R.string.media_only_phone
                                        Where.BOTH -> R.string.media_phone_and_dropbox
                                        Where.CLOUD -> R.string.media_in_dropbox
                                    },
                                ),
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = if (video) TextAlign.Start else TextAlign.Center,
                        )
                        if (!video) AudioControls(s, mine, playing, len, pos[m.id], player) { player.openMedia(id, next.map { it.id }) }
                        Row(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm), modifier = Modifier.padding(top = FeederTokens.Space.lg)) {
                            SpeedButton(if (mine) s.speed else 1f, enabled = mine) { player.setSpeed(it) }
                            SleepButton(s, player)
                            if (next.isNotEmpty()) {
                                FilledTonalButton(onClick = {
                                    player.openMedia(next.first().id, next.drop(1).map { it.id })
                                    onOpenFile(next.first().id)
                                }) {
                                    Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(FeederTokens.Space.sm))
                                    Text(stringResource(R.string.media_next))
                                }
                            }
                        }
                        if (mine && s.error != null) Text(s.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (next.isNotEmpty()) {
                item(key = "nexthead") {
                    Text(
                        stringResource(R.string.media_up_next, m.folder.substringAfterLast('/').ifEmpty { stringResource(R.string.media_player) }),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(next.take(50), key = { "next:${it.id}" }) { x ->
                    ListItem(
                        headlineContent = { Text(x.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = x.duration?.let { d -> { Text(AudiobookRules.lengthText((d * 1000).toLong())) } },
                        leadingContent = {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(FeederTokens.Radius.md))) {
                                GeneratedCover(x.title, if (x.isVideo) Icons.Outlined.Movie else Icons.Default.MusicNote, small = true, modifier = Modifier.fillMaxSize())
                            }
                        },
                        modifier =
                            Modifier.clickable {
                                val k = next.indexOf(x)
                                player.openMedia(x.id, next.drop(k + 1).map { it.id })
                                onOpenFile(x.id)
                            },
                    )
                }
            }
        }
    }

    deleting?.let { d ->
        DeleteFromDropboxDialog(
            title = d.title,
            keepsPhoneCopy = library.onPhone(d),
            onDelete = {
                if (!library.onPhone(d) && player.state.value.bookId == d.id) player.close()
                library.deleteFromDropbox(d.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** Landscape without system bars while the full screen button is on; Back leaves it. */
@Composable
private fun FullscreenWindowEffect(
    activity: Activity?,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** A sound's seek bar and controls (back 10 s, play, ahead 30 s). */
@Composable
private fun AudioControls(
    s: com.nononsenseapps.feeder.model.audiobooks.AudiobookState,
    mine: Boolean,
    playing: Boolean,
    len: Long,
    saved: AudioPos?,
    player: AudiobookPlayer,
    onStart: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val at = if (mine) s.ms else 0L
    val shown = if (dragging) value.toLong() else at
    Slider(
        value = shown.toFloat().coerceIn(0f, len.toFloat().coerceAtLeast(1f)),
        onValueChange = {
            dragging = true
            value = it
        },
        onValueChangeFinished = {
            dragging = false
            player.seekToBookMs(value.toLong())
        },
        valueRange = 0f..len.toFloat().coerceAtLeast(1f),
        enabled = mine && len > 0,
        modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.md),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(AudiobookRules.clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(if (len > 0) "−" + AudiobookRules.clock(len - shown) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md), modifier = Modifier.padding(top = FeederTokens.Space.sm)) {
        Round(Icons.Default.Replay10, stringResource(R.string.audiobook_back_10), mine) { player.seekBy(-10_000) }
        val label =
            stringResource(
                if (playing) {
                    R.string.audiobook_pause
                } else if (saved != null && saved.f > 0 && saved.f < 0.99) {
                    R.string.audiobook_resume
                } else {
                    R.string.audiobook_play
                },
            )
        PlainTooltipBox(tooltip = { Text(label) }) {
            FilledIconButton(
                onClick = { if (mine) player.toggle() else onStart() },
                shape = RoundedCornerShape(if (playing) 24.dp else 36.dp),
                modifier = Modifier.size(width = 88.dp, height = 72.dp),
            ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = label, modifier = Modifier.size(36.dp)) }
        }
        Round(Icons.Default.Forward30, stringResource(R.string.audiobook_ahead_30), mine) { player.seekBy(30_000) }
    }
}
