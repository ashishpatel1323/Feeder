package com.nononsenseapps.feeder.ui.compose.audiobooks

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.shelf.BookStatus
import com.nononsenseapps.feeder.model.shelf.ShelfMarks
import com.nononsenseapps.feeder.model.shelf.ShelfStatusRules
import com.nononsenseapps.feeder.ui.compose.shelf.CoverPickerDialog
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.audiobooks.AudioPos
import com.nononsenseapps.feeder.model.audiobooks.AudioPositions
import com.nononsenseapps.feeder.model.audiobooks.Audiobook
import com.nononsenseapps.feeder.model.audiobooks.AudiobookLibrary
import com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer
import com.nononsenseapps.feeder.model.audiobooks.AudiobookRules
import com.nononsenseapps.feeder.model.audiobooks.AudiobookState
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.sync2.ThisDevice
import com.nononsenseapps.feeder.sync2.otherDeviceText
import com.nononsenseapps.feeder.ui.compose.components.DeleteRequest
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.DeleteVerb
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.ebooks.DeleteFromDropboxDialog
import com.nononsenseapps.feeder.ui.compose.ebooks.DropboxAccountButton
import com.nononsenseapps.feeder.ui.compose.ebooks.uploadOrExplain
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.history.historyItems
import com.nononsenseapps.feeder.ui.compose.history.rememberHistory
import com.nononsenseapps.feeder.ui.compose.shelf.GeneratedCover
import com.nononsenseapps.feeder.ui.compose.shelf.LibraryListingScreen
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAction
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAdd
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfItem
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfJob
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfQuick
import com.nononsenseapps.feeder.ui.compose.shelf.Where
import com.nononsenseapps.feeder.ui.compose.shelf.rememberShelfPrefs
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

private fun speedText(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.') + "×"

/** "36% listened on Pixel 8, 2h ago" / "Finished" / "Not started" */
@Composable
private fun progressText(
    b: Audiobook,
    pos: AudioPos?,
    thisDeviceId: String,
): String {
    if (pos == null || (pos.i == 0 && pos.ms < 1000)) return stringResource(R.string.audiobook_not_started)
    if (pos.f >= 0.999) return stringResource(R.string.audiobook_finished)
    val what =
        when {
            pos.f > 0 -> stringResource(R.string.audiobook_listened, (pos.f * 100).roundToInt().coerceAtLeast(1))
            b.tracks.size > 1 -> stringResource(R.string.audiobook_file_of, pos.i + 1, b.tracks.size)
            else -> AudiobookRules.clock(pos.ms)
        }
    return otherDeviceText(pos.by, pos.ts, thisDeviceId)?.let { "$what $it" } ?: what
}

@Composable
private fun rememberCovers(
    library: AudiobookLibrary,
    books: List<Audiobook>,
    version: Int,
): Map<String, ImageBitmap> {
    val covers by produceState(emptyMap<String, ImageBitmap>(), books.map { it.id }, version) {
        value =
            withContext(Dispatchers.IO) {
                books.mapNotNull { b -> library.coverFile(b.id).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()?.let { b.id to it } } }.toMap()
            }
    }
    return covers
}

@Composable
private fun Cover(
    cover: ImageBitmap?,
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = FeederTokens.Radius.md,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(title, Icons.Default.Headphones, small = false, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The Audiobooks tab: books on this phone and in Dropbox › Apps › Feeder › Audiobooks, last listened first, as cards or a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobooksScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val di = LocalDI.current
    val nudge: SyncNudge by di.instance()
    val player: AudiobookPlayer by di.instance()
    val library = remember { AudiobookLibrary.get(context) }
    val deleteConfirmer = rememberDeleteConfirmer()

    fun askRemoveDownload(b: Audiobook) =
        deleteConfirmer.ask(
            DeleteRequest(
                title = R.string.confirm_delete_title_remove_from_device,
                itemName = b.title,
                scope = DeleteScope.THIS_DEVICE,
                verb = DeleteVerb.REMOVE,
                extra = R.string.confirm_delete_extra_stays_in_dropbox,
                onConfirm = { library.removeDownload(b.id) },
            ),
        )

    fun askForget(b: Audiobook) =
        deleteConfirmer.ask(
            DeleteRequest(
                title = R.string.confirm_delete_title_forget,
                itemName = b.title,
                scope = DeleteScope.THIS_DEVICE,
                verb = DeleteVerb.REMOVE,
                extra = R.string.confirm_delete_extra_forget,
                onConfirm = { library.forget(b.id) },
            ),
        )
    val positions = remember { AudioPositions.get(context) }
    val thisDeviceId = remember { ThisDevice.get(context).id }
    val dropbox = remember { DropboxClient.get(context) }
    val books by library.books.collectAsState()
    val jobs by library.jobs.collectAsState()
    val refreshing by library.refreshing.collectAsState()
    val adding by library.adding.collectAsState()
    val message by library.messages.collectAsState()
    val pos by positions.all.collectAsState()
    val link by dropbox.link.collectAsState()
    val version by library.version.collectAsState()
    val playing by player.state.collectAsState()
    val prefs = rememberShelfPrefs("audiobooks", defaultCards = true)
    var deleting by remember { mutableStateOf<Audiobook?>(null) }
    val marks: ShelfMarks by di.instance()
    val picked by marks.statuses.collectAsState()
    var coverFor by remember { mutableStateOf<Audiobook?>(null) }
    val coverText = stringResource(R.string.cover_from_internet)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> library.addFiles(uris) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(library::addFolder) }

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
    val covers = rememberCovers(library, books, version)
    val sorted = remember(books) { books.sortedWith(compareByDescending<Audiobook> { it.lastOpenedAt }.thenByDescending { it.addedAt }.thenBy { it.title.lowercase() }) }
    val downloadText = stringResource(R.string.audiobook_download)
    val removeDownloadText = stringResource(R.string.audiobook_remove_download)
    val forgetText = stringResource(R.string.audiobook_forget)
    val playText = stringResource(R.string.audiobook_play)
    val resumeText = stringResource(R.string.audiobook_resume)
    val pauseText = stringResource(R.string.audiobook_pause)
    val filesText = stringResource(R.string.audiobook_files)
    val emptyPhoneText = stringResource(R.string.audiobooks_empty_phone)
    val downloadingText = stringResource(R.string.shelf_downloading)
    val uploadingText = stringResource(R.string.shelf_uploading)
    val uploadText = stringResource(R.string.upload_to_dropbox)
    val resumeUploadText = stringResource(R.string.resume_upload_to_dropbox)
    val deleteDropboxText = stringResource(R.string.delete_from_dropbox)
    val audiobooksWhat = stringResource(R.string.audiobooks_what)
    val items =
        sorted.map { b ->
            val p = pos[b.id]
            val len = AudiobookRules.bookLengthMs(b)
            val job = jobs[b.id]
            val downloaded = library.isDownloaded(b)
            val isPlaying = playing.bookId == b.id && (playing.isPlaying || playing.loading)
            val started = p != null && p.f > 0 && p.f < 0.999
            val actions = mutableListOf<ShelfAction>()
            if (b.dropboxPath != null && !downloaded && !b.onPhone && job == null) actions += ShelfAction(downloadText) { library.download(b.id) }
            if (downloaded && b.dropboxPath != null && !b.onPhone) actions += ShelfAction(removeDownloadText) { askRemoveDownload(b) }
            if (b.dropboxPath == null && job == null) actions += ShelfAction(if (library.hasPendingUpload(b.id)) resumeUploadText else uploadText) { uploadOrExplain(context, link != null, audiobooksWhat) { library.uploadToDropbox(b.id) } }
            if (b.tracks.any { it.uri != null }) actions += ShelfAction(forgetText) { askForget(b) }
            actions += ShelfAction(coverText) { coverFor = b }
            if (b.dropboxPath != null && job == null) actions += ShelfAction(deleteDropboxText) { deleting = b }
            val markKey = ShelfStatusRules.key(ShelfStatusRules.AUDIOBOOK, b.id)
            val activity = maxOf(b.lastOpenedAt, p?.ts ?: 0)
            val status = ShelfStatusRules.effective(ShelfStatusRules.auto(p?.f, opened = b.lastOpenedAt > 0), picked[markKey], activity)
            ShelfItem(
                status = status,
                sortAt = if (status == BookStatus.NEW) b.addedAt else maxOf(activity, picked[markKey]?.ts ?: 0),
                onStatus = { marks.setStatus(markKey, it) },
                key = b.id,
                title = b.title,
                subtitle = b.author,
                detail =
                    listOf(
                        progressText(b, p, thisDeviceId),
                        if (len > 0) {
                            AudiobookRules.lengthText(len)
                        } else if (b.tracks.size > 1) {
                            filesText.format(b.tracks.size)
                        } else {
                            Formatter.formatShortFileSize(context, b.size)
                        },
                    ).joinToString(" · "),
                cover = covers[b.id],
                fallback = Icons.Default.Headphones,
                where =
                    when {
                        b.dropboxPath == null -> Where.DEVICE
                        downloaded || b.onPhone -> Where.BOTH
                        else -> Where.CLOUD
                    },
                size = b.size,
                busy = job?.let { (if (it.up) uploadingText else downloadingText).format(if (it.total > 0) (it.done * 100 / it.total).toInt() else 0) },
                progress = p?.f?.toFloat(),
                quick =
                    ShelfQuick(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "${if (isPlaying) {
                            pauseText
                        } else if (started) {
                            resumeText
                        } else {
                            playText
                        }} ${b.title}",
                    ) { if (isPlaying) player.pause() else player.open(b.id) },
                onOpen = { onOpenBook(b.id) },
                actions = actions,
            )
        }

    LibraryListingScreen(
        title = stringResource(R.string.tab_audiobooks),
        prefs = prefs,
        items = items,
        emptyIcon = Icons.Default.Headphones,
        emptyText = stringResource(R.string.audiobooks_empty),
        emptyDevice = emptyPhoneText,
        addLabel = stringResource(R.string.audiobooks_add),
        addActions =
            listOf(
                ShelfAdd(Icons.Outlined.AudioFile, stringResource(R.string.add_from_phone_files)) { pickFiles.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream")) },
                ShelfAdd(Icons.Outlined.Folder, stringResource(R.string.add_from_phone_folder)) { pickFolder.launch(null) },
            ),
        dropbox = { DropboxAccountButton(what = audiobooksWhat, folder = DropboxClient.AUDIOBOOKS_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) }) },
        modifier = modifier,
        onRefresh = if (link != null) ({ library.refresh() }) else null,
        refreshLabel = stringResource(R.string.audiobooks_refresh),
        refreshing = refreshing,
        loading = refreshing && adding == null,
        jobs = listOfNotNull(adding?.let { ShelfJob("adding", stringResource(R.string.audiobooks_adding, it)) }),
        streams = true,
    )

    coverFor?.let { b ->
        CoverPickerDialog(
            key = ShelfStatusRules.key(ShelfStatusRules.AUDIOBOOK, b.id),
            query = listOf(b.title, b.author).filter { it.isNotBlank() }.joinToString(" "),
            audiobook = true,
            marks = marks,
            onDismiss = { coverFor = null },
        )
    }

    deleting?.let { b ->
        DeleteFromDropboxDialog(
            title = b.title,
            keepsPhoneCopy = library.onPhone(b),
            onDelete = { library.deleteFromDropbox(b.id) },
            onDismiss = { deleting = null },
        )
    }
}

/** One book: cover, chapter, seek bar, controls, Speed and Sleep, and the chapters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookPlayerScreen(
    bookId: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val di = LocalDI.current
    val player: AudiobookPlayer by di.instance()
    val library = remember { AudiobookLibrary.get(context) }
    val books by library.books.collectAsState()
    val version by library.version.collectAsState()
    val book = books.firstOrNull { it.id == bookId }
    val s by player.state.collectAsState()
    val mine = s.bookId == bookId
    val cover = rememberCovers(library, listOfNotNull(book), version)[bookId]

    // Opening the page loads the book where it was left, without playing it yet
    LaunchedEffect(bookId) {
        if (player.state.value.bookId != bookId && !player.state.value.isPlaying) player.open(bookId, play = false)
    }
    LaunchedEffect(Unit) {
        player.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
            )
        },
    ) { padding ->
        if (book == null) {
            Text(stringResource(R.string.audiobook_gone), modifier = Modifier.padding(padding).padding(FeederTokens.Space.lg))
            return@Scaffold
        }
        val chapters = if (mine) s.chapters else remember(book) { AudiobookRules.chaptersOf(book) }
        val listState = rememberLazyListState()
        var showHistory by rememberSaveable { mutableStateOf(false) }
        val history = rememberHistory(ItemHistory.AUDIOBOOK, bookId)
        // Keep the chapter playing in view when it changes (not when the page opens)
        var lastChapter by remember { mutableStateOf(-1) }
        LaunchedEffect(mine, s.chapter) {
            if (!mine || s.chapters.isEmpty() || showHistory) return@LaunchedEffect
            val row = s.chapter + 2
            if (lastChapter >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == row }) listState.animateScrollToItem(row)
            lastChapter = s.chapter
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                NowPlaying(book, cover, s, mine, player)
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = FeederTokens.Space.xl, bottom = FeederTokens.Space.sm)) {
                    SegmentedButton(
                        selected = !showHistory,
                        onClick = { showHistory = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("${stringResource(R.string.audiobook_chapters)} · ${chapters.size}") }
                    SegmentedButton(
                        selected = showHistory,
                        onClick = { showHistory = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.history)) }
                }
            }
            if (showHistory) {
                historyItems(history, R.string.history_empty_audio) { e ->
                    val parts = e.x.split(':')
                    val i = parts.getOrNull(0)?.toIntOrNull()
                    val ms = parts.getOrNull(1)?.toLongOrNull()
                    if (i != null && ms != null) player.open(bookId, play = mine && (s.isPlaying || s.loading), at = i to ms)
                }
            } else {
                itemsIndexed(chapters, key = { _, c -> "${c.i}:${c.startMs}" }) { k, c ->
                    val current = mine && k == s.chapter
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(FeederTokens.Radius.sm))
                                .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable { if (mine) player.jumpToChapter(k) else player.open(bookId, at = c.i to c.startMs) }
                                .padding(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.sm),
                    ) {
                        Text("${k + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                        Text(
                            c.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else null,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (c.lengthMs > 0) Text(AudiobookRules.clock(c.lengthMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlaying(
    book: Audiobook,
    cover: ImageBitmap?,
    s: AudiobookState,
    mine: Boolean,
    player: AudiobookPlayer,
) {
    val context = LocalContext.current
    val positions = remember { AudioPositions.get(context) }
    val pos by positions.all.collectAsState()
    val saved = pos[book.id]
    val playing = mine && (s.isPlaying || s.loading)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.sm)) {
        Cover(cover, book.title, Modifier.widthIn(max = 300.dp).fillMaxWidth(0.8f), corner = 28.dp)
        Spacer(Modifier.height(FeederTokens.Space.lg))
        Text(book.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            (if (mine) s.currentChapter?.title else null) ?: "",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = FeederTokens.Space.md),
        )

        // The chapter's seek bar, wavy while playing
        val len = if (mine) s.chapterLengthMs else 0L
        val max = len.toFloat().coerceAtLeast(1f)
        val slider = remember(s.chapter, max) { SliderState(0f, 0, 0f..max) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(s.inChapterMs, dragging, slider) {
            if (!dragging) slider.value = s.inChapterMs.toFloat().coerceIn(0f, max)
        }
        val shown = if (dragging) slider.value.toLong() else s.inChapterMs
        Slider(
            state = slider,
            onValueChange = {
                dragging = true
                slider.value = it
            },
            onValueChangeFinished = {
                dragging = false
                s.currentChapter?.let { c -> player.seekToBookMs(c.bookMs + slider.value.toLong()) }
            },
            enabled = mine && len > 0,
            thumb = {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 24.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            },
            track = { state ->
                if (playing && !dragging) {
                    LinearWavyProgressIndicator(progress = { state.coercedValueAsFraction }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    LinearProgressIndicator(progress = { state.coercedValueAsFraction }, modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.md),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(AudiobookRules.clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (len > 0) "−" + AudiobookRules.clock(len - shown) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm), modifier = Modifier.padding(top = FeederTokens.Space.sm)) {
            Control(Icons.Default.SkipPrevious, stringResource(R.string.audiobook_prev_chapter), mine) { player.previousChapter() }
            Round(Icons.Default.Replay10, stringResource(R.string.audiobook_back_10), mine) { player.seekBy(-10_000) }
            val label =
                stringResource(
                    if (playing) {
                        R.string.audiobook_pause
                    } else if (saved != null && saved.f > 0) {
                        R.string.audiobook_resume
                    } else {
                        R.string.audiobook_play
                    },
                )
            val corner by animateDpAsState(if (playing) 24.dp else 36.dp, label = "playCorner")
            PlainTooltipBox(tooltip = { Text(label) }) {
                FilledIconButton(
                    onClick = { if (mine) player.toggle() else player.open(book.id) },
                    shape = RoundedCornerShape(corner),
                    modifier = Modifier.size(width = 88.dp, height = 72.dp),
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = label, modifier = Modifier.size(36.dp))
                }
            }
            Round(Icons.Default.Forward30, stringResource(R.string.audiobook_ahead_30), mine) { player.seekBy(30_000) }
            Control(Icons.Default.SkipNext, stringResource(R.string.audiobook_next_chapter), mine && s.chapter < s.chapters.size - 1) { player.nextChapter() }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm), modifier = Modifier.padding(top = FeederTokens.Space.lg)) {
            SpeedButton(if (mine) s.speed else 1f, enabled = mine) { player.setSpeed(it) }
            SleepButton(s, player)
        }
        val bookLen = if (mine) s.bookLength else AudiobookRules.bookLengthMs(book)
        val lines =
            listOfNotNull(
                if (mine && bookLen > 0) {
                    stringResource(R.string.audiobook_left, AudiobookRules.clock(s.bookMs), AudiobookRules.clock(bookLen), AudiobookRules.lengthText(((bookLen - s.bookMs) / s.speed).toLong())) +
                        if (s.speed != 1f) " · ${speedText(s.speed)}" else ""
                } else {
                    null
                },
                if (mine) s.scan?.let { stringResource(R.string.audiobook_reading_chapters, it.first, it.second) } else null,
            )
        if (lines.isNotEmpty()) {
            Text(
                lines.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FeederTokens.Space.md),
                textAlign = TextAlign.Center,
            )
        }
        if (mine && s.error != null) Text(s.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Control(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun Round(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    enabled: Boolean,
    onPick: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(FeederTokens.Space.sm))
            Text("${stringResource(R.string.audiobook_speed)} ${speedText(speed)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { v ->
                DropdownMenuItem(
                    text = { Text(speedText(v)) },
                    leadingIcon = { if (kotlin.math.abs(v - speed) < 0.01f) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onPick(v)
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepButton(
    s: AudiobookState,
    player: AudiobookPlayer,
) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.sleepEndsAt) {
        while (s.sleepEndsAt > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text =
        when {
            s.sleepEndOfChapter -> stringResource(R.string.audiobook_sleep_chapter)
            s.sleepEndsAt > 0 -> AudiobookRules.clock(s.sleepEndsAt - now)
            else -> stringResource(R.string.audiobook_sleep)
        }
    Box {
        FilledTonalButton(onClick = { open = true }) {
            Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(FeederTokens.Space.sm))
            Text(text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (s.sleepEndsAt > 0) {
                DropdownMenuItem(text = { Text(stringResource(R.string.audiobook_sleep_add)) }, onClick = {
                    open = false
                    player.extendSleep()
                })
            }
            SLEEP_MINUTES.forEach { m ->
                DropdownMenuItem(text = { Text(stringResource(R.string.audiobook_sleep_minutes, m)) }, onClick = {
                    open = false
                    player.setSleep(m)
                })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audiobook_sleep_chapter)) },
                leadingIcon = { if (s.sleepEndOfChapter) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = {
                    open = false
                    player.setSleep(0)
                },
            )
            if (s.sleepOn) {
                DropdownMenuItem(text = { Text(stringResource(R.string.audiobook_sleep_off)) }, onClick = {
                    open = false
                    player.cancelSleep()
                })
            }
        }
    }
}

/** The mini player above the bottom bar while an audiobook is open and its page isn't. */
@Composable
fun AudiobookMiniBar(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val di = LocalDI.current
    val player: AudiobookPlayer by di.instance()
    val library = remember { AudiobookLibrary.get(context) }
    val version by library.version.collectAsState()
    val s by player.state.collectAsState()
    val id = s.bookId ?: return
    val book = library.get(id)
    val cover = rememberCovers(library, listOfNotNull(book), version)[id]
    val playing = s.isPlaying || s.loading
    val openLabel = stringResource(R.string.audiobook_open_player)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 3.dp,
        modifier = modifier.padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.sm).fillMaxWidth(),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (s.chapterLengthMs > 0) (s.inChapterMs.toFloat() / s.chapterLengthMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = openLabel) { onOpen(id) }.padding(FeederTokens.Space.sm),
            ) {
                Cover(cover, s.title, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.currentChapter?.title ?: s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(s.title, if (s.chapterLengthMs > 0) "−" + AudiobookRules.clock(s.chapterLengthMs - s.inChapterMs) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Control(Icons.Default.Replay10, stringResource(R.string.audiobook_back_10), true) { player.seekBy(-10_000) }
                val label = stringResource(if (playing) R.string.audiobook_pause else R.string.audiobook_play)
                PlainTooltipBox(tooltip = { Text(label) }) {
                    FilledIconButton(onClick = { player.toggle() }, shape = RoundedCornerShape(if (playing) 16.dp else 24.dp), modifier = Modifier.size(width = 56.dp, height = 48.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = label)
                    }
                }
                Control(Icons.Default.Close, stringResource(R.string.audiobook_close), true) { player.close() }
            }
        }
    }
}
