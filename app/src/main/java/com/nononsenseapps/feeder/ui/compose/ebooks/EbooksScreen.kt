package com.nononsenseapps.feeder.ui.compose.ebooks

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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

    val sorted = remember(books) { books.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }.thenBy { it.title.lowercase() }) }

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
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pick.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-mobipocket-ebook", "application/octet-stream", "*/*")) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_book)) },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DropboxCard(what = stringResource(R.string.ebooks_what), onSignedOut = { nudge.changed(false) }, horizontalPadding = 0.dp)
            }
            items(jobs, key = { "job:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { job ->
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
            }
            items(sorted, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    fraction = pos[book.id]?.f,
                    coverVersion = coverVersion,
                    library = library,
                    onOpen = { onOpenBook(book.id) },
                    onRemoveFromPhone = { library.removeFromDevice(book.id) },
                    onDelete = { deleting = book },
                )
            }
        }
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ebook_delete_title, book.title)) },
            text = { Text(stringResource(R.string.ebook_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    library.deleteEverywhere(book.id)
                    deleting = null
                }) { Text(stringResource(R.string.ebook_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
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

@Composable
private fun BookCard(
    book: Book,
    fraction: Double?,
    coverVersion: Int,
    library: EbookLibrary,
    onOpen: () -> Unit,
    onRemoveFromPhone: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val cover = rememberCover(library, book.id, coverVersion)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clickable(onClick = onOpen)) {
        Box {
            if (cover != null) {
                Image(
                    cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = book.title,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(2.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (book.local) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (book.dropboxPath != null) R.string.ebook_remove_from_phone else R.string.ebook_delete)) },
                            onClick = {
                                menu = false
                                onRemoveFromPhone()
                            },
                        )
                    }
                    if (book.dropboxPath != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ebook_delete_dropbox)) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("${book.ext.uppercase()} · ${Formatter.formatShortFileSize(context, book.size)}", style = MaterialTheme.typography.bodySmall) },
                        onClick = { menu = false },
                        enabled = false,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) {
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val where =
            listOfNotNull(
                fraction?.let { "${(it * 100).roundToInt()}%" } ?: stringResource(R.string.ebook_new),
                if (!book.local) stringResource(R.string.ebook_in_dropbox) else null,
                if (book.dropboxPath == null) stringResource(R.string.ebook_phone_only) else null,
            ).joinToString(" · ")
        Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
