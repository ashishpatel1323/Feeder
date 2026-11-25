package com.nononsenseapps.feeder.ui.compose.zim

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.model.zim.ZimBook
import com.nononsenseapps.feeder.model.zim.ZimCatalogEntry
import com.nononsenseapps.feeder.model.zim.ZimJob
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.model.zim.ZimNative
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.feeder.ui.compose.ebooks.DropboxCard
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import kotlinx.coroutines.delay

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
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { library.import(it) }
        }

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

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SensibleTopAppBar(
                title = stringResource(R.string.zim_library),
                actions = {
                    if (ZimNative.available) {
                        IconButton(onClick = { catalogOpen = true }) {
                            Icon(Icons.Outlined.Explore, contentDescription = stringResource(R.string.zim_get_books))
                        }
                        IconButton(onClick = { pick.launch(arrayOf("application/octet-stream", "application/x-zim", "*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = stringResource(R.string.zim_add_file))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!ZimNative.available) {
            Text(
                stringResource(R.string.zim_unavailable),
                modifier = Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.zim_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { DropboxCard(what = stringResource(R.string.zim_books_what), onSignedOut = { nudge.changed(false) }) }
            items(jobs, key = { "job:${it.key}" }) { job -> JobRow(job, onCancel = { library.cancel(job) }) }
            val list = books
            val onlyInDropbox = remote.orEmpty().filter { f -> list.orEmpty().none { library.sameZim(it, f) } }
            if (list != null && list.isEmpty() && jobs.isEmpty() && onlyInDropbox.isEmpty()) {
                item { EmptyLibrary(onGet = { catalogOpen = true }, onAdd = { pick.launch(arrayOf("*/*")) }) }
            }
            items(list.orEmpty(), key = { it.id }) { book ->
                val inDropbox = remote?.any { library.sameZim(book, it) }
                BookRow(
                    book,
                    onOpen = { onOpenBook(book.id) },
                    onDelete = { deleting = book },
                    inDropbox = if (link == null) null else inDropbox,
                    onUpload = { library.uploadToDropbox(book) },
                )
            }
            if (onlyInDropbox.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.in_dropbox),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(onlyInDropbox, key = { "dbx:${it.id}" }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name.removeSuffix(".zim").replace('_', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.not_on_phone_yet, Formatter.formatShortFileSize(context, f.size))) },
                        leadingContent = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(40.dp)) },
                        trailingContent = {
                            TextButton(onClick = { library.downloadFromDropbox(f) }, enabled = jobs.none { it.label == f.name }) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(stringResource(R.string.download))
                            }
                        },
                    )
                }
            }
            item {
                val used = list.orEmpty().sumOf { it.size }
                if (used > 0) {
                    Text(
                        stringResource(
                            R.string.zim_storage,
                            Formatter.formatShortFileSize(context, used),
                            Formatter.formatShortFileSize(context, library.dir.usableSpace),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

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

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.zim_delete_title, book.title)) },
            text = { Text(stringResource(R.string.zim_delete_text, Formatter.formatShortFileSize(context, book.size))) },
            confirmButton = {
                TextButton(onClick = {
                    library.delete(book)
                    deleting = null
                }) { Text(stringResource(R.string.zim_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun BookIcon(icon: ByteArray?) {
    val bitmap = remember(icon) { icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
    } else {
        Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun BookRow(
    book: ZimBook,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    inDropbox: Boolean?,
    onUpload: () -> Unit,
) {
    val context = LocalContext.current
    val meta =
        listOfNotNull(
            Formatter.formatShortFileSize(context, book.size),
            book.flavour.ifBlank { null },
            if (book.articleCount > 0) stringResource(R.string.zim_pages, book.articleCount) else null,
            book.date.ifBlank { null },
        ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (book.description.isNotBlank()) Text(book.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = { BookIcon(book.icon) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (inDropbox) {
                    true -> Icon(Icons.Outlined.Cloud, contentDescription = stringResource(R.string.in_dropbox), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                    false ->
                        IconButton(onClick = onUpload) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.upload_to_dropbox))
                        }
                    null -> Unit
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.zim_delete_title, book.title))
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun JobRow(
    job: ZimJob,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(job.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    if (job.total > 0) {
                        stringResource(
                            R.string.zim_progress,
                            Formatter.formatShortFileSize(context, job.done),
                            Formatter.formatShortFileSize(context, job.total),
                        )
                    } else {
                        Formatter.formatShortFileSize(context, job.done)
                    },
                )
                Spacer(Modifier.height(4.dp))
                if (job.total > 0) {
                    LinearProgressIndicator(progress = { (job.done.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(40.dp)) },
        trailingContent = {
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.zim_stop)) }
        },
    )
}

@Composable
private fun EmptyLibrary(
    onGet: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.zim_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onGet, label = { Text(stringResource(R.string.zim_get_books)) }, leadingIcon = { Icon(Icons.Outlined.Explore, null) })
            AssistChip(onClick = onAdd, label = { Text(stringResource(R.string.zim_add_file)) }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) })
        }
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
