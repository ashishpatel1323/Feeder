package com.nononsenseapps.feeder.ui.compose.ebooks

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.shelf.BookStatus
import com.nononsenseapps.feeder.model.shelf.ShelfMarks
import com.nononsenseapps.feeder.model.shelf.ShelfStatusRules
import com.nononsenseapps.feeder.ui.compose.shelf.CoverPickerDialog
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.ebooks.Book
import com.nononsenseapps.feeder.model.ebooks.BookPositions
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.sync2.ThisDevice
import com.nononsenseapps.feeder.sync2.otherDeviceText
import com.nononsenseapps.feeder.ui.compose.components.DeleteRequest
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.DeleteVerb
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.shelf.CoverFit
import com.nononsenseapps.feeder.ui.compose.shelf.LibraryListingScreen
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAction
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAdd
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfItem
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfJob
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfQuick
import com.nononsenseapps.feeder.ui.compose.shelf.Where
import com.nononsenseapps.feeder.ui.compose.shelf.rememberShelfPrefs
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
    val deleteConfirmer = rememberDeleteConfirmer()

    fun askRemove(b: Book) =
        deleteConfirmer.ask(
            DeleteRequest(
                title =
                    when {
                        b.uri != null -> R.string.confirm_delete_title_forget
                        b.dropboxPath != null -> R.string.confirm_delete_title_remove_from_device
                        else -> R.string.confirm_delete_title_book
                    },
                itemName = b.title,
                scope = DeleteScope.THIS_DEVICE,
                verb = if (b.dropboxPath != null || b.uri != null) DeleteVerb.REMOVE else DeleteVerb.DELETE,
                extra =
                    when {
                        b.uri != null -> R.string.confirm_delete_extra_forget
                        b.dropboxPath != null -> R.string.confirm_delete_extra_stays_in_dropbox
                        else -> R.string.confirm_delete_extra_only_copy
                    },
                onConfirm = { library.removeFromDevice(b.id) },
            ),
        )
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
    val marks: ShelfMarks by di.instance()
    val picked by marks.statuses.collectAsState()
    var coverFor by remember { mutableStateOf<Book?>(null) }
    val coverLabel = stringResource(R.string.cover_from_internet)
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
    val openLabel = stringResource(R.string.shelf_open)
    val downloadOpenLabel = stringResource(R.string.shelf_download_open)
    val items =
        sorted.map { b ->
            val p = pos[b.id]
            val f = p?.f
            val byOther = p?.let { otherDeviceText(it.by, it.ts, thisDeviceId) }
            val job = jobs.firstOrNull { it.key == "down:${b.id}" || it.key == "up:${b.id}" }
            val markKey = ShelfStatusRules.key(ShelfStatusRules.EBOOK, b.id)
            val activity = maxOf(b.lastOpenedAt, p?.ts ?: 0)
            val auto = ShelfStatusRules.auto(f, opened = b.lastOpenedAt > 0)
            val status = ShelfStatusRules.effective(auto, picked[markKey], activity)
            ShelfItem(
                status = status,
                sortAt = if (status == BookStatus.NEW) b.addedAt else maxOf(activity, picked[markKey]?.ts ?: 0),
                onStatus = { marks.setStatus(markKey, it) },
                key = b.id,
                title = b.title,
                subtitle = b.author,
                detail =
                    listOf(
                        f?.let {
                            (
                                if (p.n >
                                    0
                                ) {
                                    "${p.p} of ${p.n} pages"
                                } else {
                                    "${(it * 100).roundToInt()}%"
                                }
                            ) + (byOther?.let { w -> " $w" } ?: "")
                        } ?: newLabel,
                        b.ext.uppercase(),
                        Formatter.formatShortFileSize(context, b.size),
                    ).joinToString(" · "),
                cover = covers[b.id],
                coverFit = CoverFit.FIT,
                fallback = Icons.AutoMirrored.Outlined.MenuBook,
                where =
                    if (!b.local) {
                        Where.CLOUD
                    } else if (b.dropboxPath != null) {
                        Where.BOTH
                    } else {
                        Where.DEVICE
                    },
                progress = f?.toFloat(),
                quick =
                    if (b.local) {
                        ShelfQuick(Icons.AutoMirrored.Outlined.MenuBook, openLabel.format(b.title)) { onOpenBook(b.id) }
                    } else {
                        ShelfQuick(Icons.Outlined.Download, downloadOpenLabel.format(b.title)) { onOpenBook(b.id) }
                    },
                size = b.size,
                busy =
                    job?.let {
                        context.getString(
                            if (it.key.startsWith("up:")) R.string.shelf_uploading else R.string.shelf_downloading,
                            if (it.total >
                                0
                            ) {
                                (it.done * 100 / it.total).toInt()
                            } else {
                                0
                            },
                        )
                    },
                onOpen = { onOpenBook(b.id) },
                actions =
                    buildList {
                        if (!b.local) add(ShelfAction(downloadLabel) { library.download(b.id) })
                        if (b.local &&
                            b.dropboxPath == null &&
                            jobs.none { it.key == "up:${b.id}" }
                        ) {
                            add(ShelfAction(uploadLabel) { uploadOrExplain(context, link != null, booksWhat) { library.uploadToDropbox(b.id) } })
                        }
                        if (b.local) {
                            add(
                                ShelfAction(
                                    if (b.uri != null) {
                                        forgetLabel
                                    } else if (b.dropboxPath != null) {
                                        removeLabel
                                    } else {
                                        deleteLabel
                                    },
                                ) { askRemove(b) },
                            )
                        }
                        add(ShelfAction(coverLabel) { coverFor = b })
                        if (b.dropboxPath != null) add(ShelfAction(deleteDropboxLabel) { deleting = b })
                    },
            )
        }

    LibraryListingScreen(
        title = stringResource(R.string.ebooks),
        prefs = shelfPrefs,
        items = items,
        emptyIcon = Icons.AutoMirrored.Outlined.MenuBook,
        emptyText = stringResource(R.string.ebooks_empty),
        emptyDevice = noBooksHere,
        addLabel = stringResource(R.string.add_book),
        addActions =
            listOf(
                ShelfAdd(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.add_from_phone_files)) {
                    pick.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/octet-stream", "*/*"))
                },
                ShelfAdd(Icons.Outlined.Folder, stringResource(R.string.add_from_phone_folder)) { pickFolder.launch(null) },
            ),
        dropbox = { DropboxAccountButton(what = booksWhat, folder = DropboxClient.EBOOKS_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) }) },
        modifier = modifier,
        onRefresh = if (link != null) ({ library.refresh() }) else null,
        jobs =
            jobs
                .filterNot { it.key.startsWith("down:") || it.key.startsWith("up:") }
                .map { ShelfJob(it.key, it.label, it.done, it.total) },
    )

    coverFor?.let { b ->
        CoverPickerDialog(
            key = ShelfStatusRules.key(ShelfStatusRules.EBOOK, b.id),
            query = listOf(b.title, b.author).filter { it.isNotBlank() }.joinToString(" "),
            audiobook = false,
            marks = marks,
            onDismiss = { coverFor = null },
        )
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
