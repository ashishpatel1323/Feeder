package com.nononsenseapps.feeder.ui.compose.zim

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.shelf.BookStatus
import com.nononsenseapps.feeder.model.shelf.ShelfMarks
import com.nononsenseapps.feeder.model.shelf.ShelfStatusRules
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxFile
import com.nononsenseapps.feeder.model.zim.ZimBook
import com.nononsenseapps.feeder.model.zim.ZimCatalogEntry
import com.nononsenseapps.feeder.model.zim.ZimJob
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.model.zim.ZimNative
import com.nononsenseapps.feeder.model.zim.ZimSnapshots
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.ui.compose.components.ConfirmDeleteDialog
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.DeleteVerb
import com.nononsenseapps.feeder.ui.compose.ebooks.DeleteFromDropboxDialog
import com.nononsenseapps.feeder.ui.compose.ebooks.DropboxAccountButton
import com.nononsenseapps.feeder.ui.compose.ebooks.uploadOrExplain
import com.nononsenseapps.feeder.ui.compose.shelf.CoverFit
import com.nononsenseapps.feeder.ui.compose.shelf.LibraryListingScreen
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAction
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAdd
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfItem
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfJob
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfQuick
import com.nononsenseapps.feeder.ui.compose.shelf.Where
import com.nononsenseapps.feeder.ui.compose.shelf.rememberShelfPrefs
import kotlinx.coroutines.delay
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** The ZIM tab: offline websites (Wikipedia, Stack Exchange, zimit copies…) kept on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZimLibraryScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val library = remember { ZimLibrary.get(context) }
    val books by library.books.collectAsState()
    val jobs by library.jobs.collectAsState()
    val message by library.messages.collectAsState()
    val remote by library.remote.collectAsState()
    val dropbox = remember { DropboxClient.get(context) }
    val link by dropbox.link.collectAsState()
    val di = LocalDI.current
    val nudge: SyncNudge by di.instance()
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ZimBook?>(null) }
    var deletingInDropbox by remember { mutableStateOf(false) }
    var deletingRemote by remember { mutableStateOf<Pair<DropboxFile, Boolean>?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { library.import(it) }
        }
    val shelfPrefs = rememberShelfPrefs("zim", defaultCards = true)
    val marks: ShelfMarks by di.instance()
    val picked by marks.statuses.collectAsState()
    val centralHistory: CentralHistory by di.instance()
    val recent by centralHistory.entries.collectAsState()
    // When each site was last opened, on any device (History syncs)
    val lastOpened =
        remember(recent) {
            recent
                .filter { it.t == CentralHistory.ZIM }
                .groupBy { it.i.substringBefore('\n') }
                .mapValues { (_, es) -> es.maxOf { it.at } }
        }
    val uploadLabel = stringResource(R.string.upload_to_dropbox)
    val resumeUploadLabel = stringResource(R.string.resume_upload_to_dropbox)
    val deleteDropboxLabel = stringResource(R.string.delete_from_dropbox)
    val removeLabel = stringResource(R.string.ebook_remove_from_phone)
    val deleteLabel = stringResource(R.string.zim_delete)
    val downloadLabel = stringResource(R.string.shelf_download)
    val noZimsHere = stringResource(R.string.shelf_no_zims_here)
    val openLabel = stringResource(R.string.shelf_open)
    val downloadOpenLabel = stringResource(R.string.shelf_download_open)
    val zimWhat = stringResource(R.string.zim_books_what)
    val pagesLabel: (Int) -> String = { n -> context.resources.getString(R.string.zim_pages, n) }
    val uploading: (Int) -> String = { p -> context.getString(R.string.shelf_uploading, p) }
    val downloading: (Int) -> String = { p -> context.getString(R.string.shelf_downloading, p) }

    LifecycleResumeEffect(Unit) {
        library.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(link) { library.refreshRemote() }
    // Download progress comes from the system download manager: check it while a download runs
    LaunchedEffect(jobs.any { it.downloadId != null }) {
        while (jobs.any { it.downloadId != null }) {
            delay(1000)
            library.refresh()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            library.consumeMessage()
        }
    }

    // Without libzim (an unsupported CPU) ZIM files can't be read: the screen only says so
    val list = if (ZimNative.available) books else emptyList()
    val onlyInDropbox = remote.orEmpty().filter { f -> list.orEmpty().none { library.sameZim(it, f) } }
    val icons by produceState(emptyMap<String, String?>(), onlyInDropbox.map { it.name }) {
        value = onlyInDropbox.associate { it.name to library.catalogIcon(it.name) }
    }
    val localIcons by produceState(emptyMap<String, ImageBitmap>(), list) {
        value = list.orEmpty().mapNotNull { b -> b.icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }?.let { b.id to it } }.toMap()
    }
    // Books without an icon (most zimit sites) get a picture of their landing page
    val snapshots by produceState(emptyMap<String, ImageBitmap>(), list) {
        val shots = ZimSnapshots.get(context)
        val noIcon = list.orEmpty().filter { it.icon == null }
        value = noIcon.mapNotNull { b -> shots.cached(b)?.let { b.id to it.asImageBitmap() } }.toMap()
        for (b in noIcon) {
            if (b.id in value) continue
            shots.snapshot(library, b)?.let { value = value + (b.id to it.asImageBitmap()) }
        }
    }

    fun pct(j: ZimJob?) = j?.let { if (it.total > 0) (it.done * 100 / it.total).toInt() else 0 }
    val shelfItems =
        list.orEmpty().map { book ->
            val copy = remote?.firstOrNull { library.sameZim(book, it) }
            val up = jobs.firstOrNull { it.key == "up:${book.file.name}" }
            val markKey = ShelfStatusRules.key(ShelfStatusRules.ZIM, book.id)
            val opened = lastOpened[book.id] ?: 0
            // A site has no end, so it never becomes Completed by itself
            val auto = ShelfStatusRules.auto(null, opened = opened > 0 || library.lastPath(book.id) != null, canFinish = false)
            val status = ShelfStatusRules.effective(auto, picked[markKey], opened)
            ShelfItem(
                status = status,
                sortAt = maxOf(opened, picked[markKey]?.ts ?: 0),
                onStatus = { marks.setStatus(markKey, it) },
                key = "local:${book.id}",
                title = book.title,
                subtitle = book.description,
                detail =
                    listOfNotNull(
                        Formatter.formatShortFileSize(context, book.size),
                        book.flavour.ifBlank { null },
                        if (book.articleCount > 0) pagesLabel(book.articleCount) else null,
                        book.date.ifBlank { null },
                    ).joinToString(" · "),
                cover = localIcons[book.id] ?: snapshots[book.id],
                coverFit = if (book.id in localIcons) CoverFit.ICON else CoverFit.FILL,
                fallback = Icons.Outlined.Public,
                where = if (copy != null) Where.BOTH else Where.DEVICE,
                size = book.size,
                busy = pct(up)?.let { uploading(it) },
                quick = ShelfQuick(Icons.AutoMirrored.Outlined.MenuBook, openLabel.format(book.title)) { onOpenBook(book.id) },
                onOpen = { onOpenBook(book.id) },
                actions =
                    buildList {
                        if ((link == null || remote != null) &&
                            copy == null &&
                            up == null
                        ) {
                            add(ShelfAction(if (library.hasPendingUpload(book)) resumeUploadLabel else uploadLabel) { uploadOrExplain(context, link != null, zimWhat) { library.uploadToDropbox(book) } })
                        }
                        if (copy != null) add(ShelfAction(deleteDropboxLabel) { deletingRemote = copy to true })
                        add(
                            ShelfAction(if (copy != null) removeLabel else deleteLabel) {
                                deletingInDropbox = copy != null
                                deleting = book
                            },
                        )
                    },
            )
        } +
            onlyInDropbox.map { f ->
                val down = jobs.firstOrNull { it.label == f.name }
                ShelfItem(
                    status = BookStatus.NEW,
                    key = "dbx:${f.id}",
                    title = f.name.removeSuffix(".zim").replace('_', ' '),
                    detail = Formatter.formatShortFileSize(context, f.size),
                    coverUrl = icons[f.name],
                    coverFit = CoverFit.ICON,
                    fallback = Icons.Outlined.Public,
                    where = Where.CLOUD,
                    size = f.size,
                    busy = pct(down)?.let { downloading(it) },
                    quick =
                        if (down == null) {
                            ShelfQuick(Icons.Outlined.Download, downloadOpenLabel.format(f.name.removeSuffix(".zim").replace('_', ' '))) { library.downloadFromDropbox(f) }
                        } else {
                            null
                        },
                    onOpen = { if (down == null) library.downloadFromDropbox(f) },
                    actions =
                        buildList {
                            if (down == null) add(ShelfAction(downloadLabel) { library.downloadFromDropbox(f) })
                            add(ShelfAction(deleteDropboxLabel) { deletingRemote = f to false })
                        },
                )
            }
    val used = list.orEmpty().sumOf { it.size }
    LibraryListingScreen(
        title = stringResource(R.string.zim_library),
        prefs = shelfPrefs,
        items = shelfItems,
        emptyIcon = Icons.Outlined.Public,
        emptyText = stringResource(R.string.zim_intro) + "\n\n" + stringResource(R.string.zim_empty),
        emptyDevice = noZimsHere,
        addLabel = stringResource(R.string.zim_add),
        addActions =
            listOf(
                ShelfAdd(Icons.Outlined.Explore, stringResource(R.string.zim_get_books)) { catalogOpen = true },
                ShelfAdd(Icons.Outlined.FileOpen, stringResource(R.string.zim_add_file)) { pick.launch(arrayOf("application/octet-stream", "application/x-zim", "*/*")) },
            ),
        dropbox = { DropboxAccountButton(what = zimWhat, folder = DropboxClient.ZIM_FOLDER.removePrefix("/"), onSignedOut = { nudge.changed(false) }) },
        modifier = modifier,
        onRefresh = if (link != null) ({ library.refreshRemote() }) else null,
        loading = list == null,
        jobs =
            jobs
                .filterNot { it.key.startsWith("up:") || onlyInDropbox.any { f -> f.name == it.label } }
                .map { ShelfJob(it.key, it.label, it.done, it.total, onCancel = { library.cancel(it) }) },
        footer =
            if (used > 0) {
                stringResource(R.string.zim_storage, Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, library.dir.usableSpace))
            } else {
                null
            },
        unavailable = if (ZimNative.available) null else stringResource(R.string.zim_unavailable),
    )

    if (catalogOpen) {
        ModalBottomSheet(
            onDismissRequest = { catalogOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            CatalogSheet(
                library = library,
                onDownload = {
                    library.download(it)
                    catalogOpen = false
                },
            )
        }
    }

    deletingRemote?.let { (f, onPhone) ->
        DeleteFromDropboxDialog(
            title = f.name.removeSuffix(".zim").replace('_', ' '),
            keepsPhoneCopy = onPhone,
            onDelete = { library.deleteFromDropbox(f) },
            onDismiss = { deletingRemote = null },
        )
    }

    deleting?.let { book ->
        ConfirmDeleteDialog(
            title = stringResource(if (deletingInDropbox) R.string.confirm_delete_title_remove_from_device else R.string.confirm_delete_title_offline_site),
            itemName = "${book.title} (${Formatter.formatShortFileSize(context, book.size)})",
            scope = DeleteScope.THIS_DEVICE,
            verb = if (deletingInDropbox) DeleteVerb.REMOVE else DeleteVerb.DELETE,
            onConfirm = {
                library.delete(book)
                deleting = null
            },
            onDismiss = { deleting = null },
            extra = stringResource(if (deletingInDropbox) R.string.confirm_delete_extra_stays_in_dropbox else R.string.confirm_delete_extra_only_copy),
        )
    }
}

private val LANGS = listOf("eng" to "English", "" to "All", "hin" to "हिन्दी", "fra" to "Français", "deu" to "Deutsch", "spa" to "Español")

@Composable
private fun CatalogSheet(
    library: ZimLibrary,
    onDownload: (ZimCatalogEntry) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf("eng") }
    var results by remember { mutableStateOf<List<ZimCatalogEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, lang) {
        delay(350)
        error = null
        try {
            results = library.searchCatalog(query, lang)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.zim_get_books), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.zim_catalog_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LANGS.take(4).forEach { (code, label) ->
                FilterChip(selected = lang == code, onClick = { lang = code }, label = { Text(label) })
            }
        }
        Box(Modifier.fillMaxWidth().height(480.dp)) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                results == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                results!!.isEmpty() -> Text(stringResource(R.string.zim_nothing_found))
                else ->
                    LazyColumn {
                        items(results!!, key = { it.id + it.url }) { e ->
                            ListItem(
                                headlineContent = {
                                    Text(listOf(e.title, e.flavour).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Column {
                                        if (e.summary.isNotBlank()) Text(e.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOf(stringResource(R.string.zim_pages, e.articleCount), e.date).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                trailingContent = {
                                    TextButton(onClick = { onDownload(e) }) {
                                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(4.dp))
                                        Text(Formatter.formatShortFileSize(context, e.size))
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }
}
