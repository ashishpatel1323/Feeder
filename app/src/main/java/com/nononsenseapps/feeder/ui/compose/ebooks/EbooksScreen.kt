package com.nononsenseapps.feeder.ui.compose.ebooks

import androidx.compose.material.icons.outlined.Folder

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.ebooks.Book
import com.nononsenseapps.feeder.model.ebooks.BookPositions
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.sync2.ThisDevice
import com.nononsenseapps.feeder.sync2.otherDeviceText
import com.nononsenseapps.feeder.ui.compose.shelf.CoverShape
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAction
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfControls
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfItem
import com.nononsenseapps.feeder.ui.compose.shelf.Where
import com.nononsenseapps.feeder.ui.compose.shelf.rememberShelfPrefs
import com.nononsenseapps.feeder.ui.compose.shelf.shelf
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import kotlin.math.roundToInt

/** The Ebooks tab: books from Dropbox (/Ebooks) and this phone, read with the same engine as the web app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbooksScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val di = LocalDI.current
    val nudge: SyncNudge by di.instance()
    val library = remember { EbookLibrary.get(context) }
    val positions = remember { BookPositions.get(context) }
    val thisDeviceId = remember { ThisDevice.get(context).id }
    val dropbox = remember { DropboxClient.get(context) }
    val books by library.books.collectAsState()
    val jobs by library.jobs.collectAsState()
    val message by library.messages.collectAsState()
    val pos by positions.all.collectAsState()
    val link by dropbox.link.collectAsState()
    val coverVersion by library.coverVersion.collectAsState()
    var deleting by remember { mutableStateOf<Book?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { library.import(it) }
        }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(library::addFolder) }
    var addMenu by remember { mutableStateOf(false) }

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

    val shelfPrefs = rememberShelfPrefs("ebooks", defaultCards = true)
    val covers by produceState(emptyMap<String, ImageBitmap>(), books, coverVersion) {
        value =
            withContext(Dispatchers.IO) {
                books.mapNotNull { b -> library.coverFile(b.id).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()?.let { b.id to it } } }.toMap()
            }
    }
    val sorted = remember(books) { books.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }.thenBy { it.title.lowercase() }) }

    val downloadLabel = stringResource(R.string.shelf_download)
    val removeLabel = stringResource(R.string.ebook_remove_from_phone)
    val deleteLabel = stringResource(R.string.ebook_delete)
    val uploadLabel = stringResource(R.string.upload_to_dropbox)
    val forgetLabel = stringResource(R.string.audiobook_forget)
    val deleteDropboxLabel = stringResource(R.string.ebook_delete_dropbox)
    val newLabel = stringResource(R.string.ebook_new)
    val booksWhat = stringResource(R.string.ebooks_what)
    val noBooksHere = stringResource(R.string.shelf_no_books_here)
    val items =
        sorted.map { b ->
            val p = pos[b.id]
            val f = p?.f
            val byOther = p?.let { otherDeviceText(it.by, it.ts, thisDeviceId) }
            val job = jobs.firstOrNull { it.key == "down:${b.id}" || it.key == "up:${b.id}" }
            ShelfItem(
                key = b.id,
                title = b.title,
                subtitle = b.author,
                detail = listOf(f?.let { (if (p.n > 0) "${p.p} of ${p.n} pages" else "${(it * 100).roundToInt()}%") + (byOther?.let { w -> " $w" } ?: "") } ?: newLabel, b.ext.uppercase(), Formatter.formatShortFileSize(context, b.size)).joinToString(" · "),
                cover = covers[b.id],
                fallback = Icons.AutoMirrored.Outlined.MenuBook,
                where = if (!b.local) Where.CLOUD else if (b.dropboxPath != null) Where.BOTH else Where.DEVICE,
                size = b.size,
                busy = job?.let { context.getString(if (it.key.startsWith("up:")) R.string.shelf_uploading else R.string.shelf_downloading, if (it.total > 0) (it.done * 100 / it.total).toInt() else 0) },
                onOpen = { onOpenBook(b.id) },
                actions =
                    buildList {
                        if (!b.local) add(ShelfAction(downloadLabel) { library.download(b.id) })
                        if (b.local && b.dropboxPath == null && jobs.none { it.key == "up:${b.id}" }) add(ShelfAction(uploadLabel) { uploadOrExplain(context, link != null, booksWhat) { library.uploadToDropbox(b.id) } })
                        if (b.local) add(ShelfAction(if (b.uri != null) forgetLabel else if (b.dropboxPath != null) removeLabel else deleteLabel) { library.removeFromDevice(b.id) })
                        if (b.dropboxPath != null) add(ShelfAction(deleteDropboxLabel) { deleting = b })
                    },
            )
        }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SensibleTopAppBar(
                title = stringResource(R.string.ebooks),
                actions = {
                    if (link != null) {
                        IconButton(onClick = { library.refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.in_dropbox))
                        }
                    }
                    DropboxAccountButton(what = booksWhat, folder = DropboxClient.EBOOKS_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) })
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_book)) },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_from_phone_files)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                        onClick = {
                            addMenu = false
                            pick.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/octet-stream", "*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_from_phone_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                        onClick = {
                            addMenu = false
                            pickFolder.launch(null)
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = shelfPrefs.columns,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(jobs.filterNot { it.key.startsWith("down:") || it.key.startsWith("up:") }, key = { "job:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { job ->
                Column(Modifier.fillMaxWidth()) {
                    Text(job.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (job.total > 0) {
                        Text(
                            stringResource(R.string.zim_progress, Formatter.formatShortFileSize(context, job.done), Formatter.formatShortFileSize(context, job.total)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(progress = { (job.done.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (sorted.isEmpty() && jobs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.ebooks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            } else if (sorted.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ShelfControls(shelfPrefs, all = sorted.size, device = sorted.count { it.local }, cloud = sorted.count { !it.local })
                }
                shelf(items, shelfPrefs, CoverShape.BOOK, emptyDevice = noBooksHere)
            }
        }
    }

    deleting?.let { book ->
        DeleteFromDropboxDialog(
            title = book.title,
            keepsPhoneCopy = book.local,
            onDelete = { library.deleteFromDropbox(book.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
fun rememberCover(
    library: EbookLibrary,
    id: String,
    version: Int,
): ImageBitmap? {
    val cover by produceState<ImageBitmap?>(null, id, version) {
        value =
            withContext(Dispatchers.IO) {
                val f = library.coverFile(id)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
            }
    }
    return cover
}
