package com.nononsenseapps.feeder.ui.compose.highlights

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.db.room.Sync2Dao
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.highlights.allQuotesText
import com.nononsenseapps.feeder.model.highlights.quoteText
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

private sealed interface HlFilter {
    data object All : HlFilter

    data object Notes : HlFilter

    data class Color(
        val c: HighlightColor,
    ) : HlFilter
}

/** Every highlight, newest first, grouped by article. Same as the web app's Highlights page. */
@Composable
fun HighlightsScreen(
    onNavigateUp: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBook: (bookId: String, cfi: String?) -> Unit = { _, _ -> },
) {
    val di = LocalDI.current
    val store: HighlightStore by di.instance()
    val dao: Sync2Dao by di.instance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by store.highlights.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<HlFilter>(HlFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var share by remember { mutableStateOf<ShareSubject?>(null) }
    val copied = stringResource(R.string.highlight_copied)

    val shown =
        remember(all, filter, query) {
            val q = query.trim().lowercase()
            all
                .filter {
                    when (val f = filter) {
                        HlFilter.All -> true
                        HlFilter.Notes -> it.note.isNotBlank()
                        is HlFilter.Color -> it.highlightColor == f.c
                    }
                }.filter { q.isEmpty() || "${it.text}\n${it.note}\n${it.itemTitle}\n${it.feedTitle}".lowercase().contains(q) }
                .sortedByDescending { it.createdAt }
        }
    val groups = remember(shown) { shown.groupBy { it.itemKey }.values.toList() }

    fun open(h: Highlight) {
        EbookLibrary.bookIdOfItem(h.itemKey)?.let { bookId ->
            if (EbookLibrary.get(context).get(bookId) != null) onOpenBook(bookId, h.cfi) else toast(context, R.string.ebook_not_found)
            return
        }
        scope.launch {
            val cut = h.itemKey.indexOf('\n')
            val id = if (cut > 0) dao.getItemId(h.itemKey.substring(0, cut), h.itemKey.substring(cut + 1)) else null
            when {
                id != null -> onOpenArticle(id)
                !h.itemLink.isNullOrBlank() ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.itemLink))) }
                else -> toast(context, R.string.highlight_article_gone)
            }
        }
    }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SensibleTopAppBar(
                title = stringResource(R.string.highlights),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = FeederTokens.Space.lg, end = FeederTokens.Space.lg, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = filter == HlFilter.All,
                            onClick = { filter = HlFilter.All },
                            label = { Text(stringResource(R.string.highlights_all, all.size)) },
                        )
                        HighlightColor.entries.forEach { c ->
                            val f = HlFilter.Color(c)
                            val colorName = stringResource(c.label)
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .background(c.background(), CircleShape),
                                    )
                                },
                                label = { Text("${all.count { it.highlightColor == c }}") },
                                modifier =
                                    Modifier.semantics(mergeDescendants = true) {
                                        contentDescription = "$colorName ${all.count { it.highlightColor == c }}"
                                    },
                            )
                        }
                        FilterChip(
                            selected = filter == HlFilter.Notes,
                            onClick = { filter = HlFilter.Notes },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text(stringResource(R.string.highlights_with_notes)) },
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.highlights_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (all.isEmpty()) R.string.highlights_empty else R.string.highlights_no_match),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = FeederTokens.Space.xl),
                    )
                }
            }
            groups.forEach { group ->
                val first = group.first()
                item(key = "head:${first.itemKey}") {
                    val inOrder = group.sortedBy { it.start }
                    val copiedMany = pluralStringResource(R.plurals.highlights_copied_many, group.size, group.size)
                    Column(modifier = Modifier.padding(top = FeederTokens.Space.lg)) {
                        Column(modifier = Modifier.fillMaxWidth().clickable { open(first) }.padding(vertical = FeederTokens.Space.xs)) {
                            Text(
                                first.itemTitle.ifBlank { "—" },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (first.feedTitle.isNotBlank()) {
                                Text(first.feedTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (group.size > 1) {
                            Row {
                                TextButton(onClick = { share = combinedSubject(inOrder) }) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(FeederTokens.Space.sm))
                                    Text(stringResource(R.string.highlights_all_image))
                                }
                                TextButton(onClick = { copyText(context, allQuotesText(inOrder), copiedMany) }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(FeederTokens.Space.sm))
                                    Text(stringResource(R.string.highlights_all_text))
                                }
                            }
                        }
                    }
                }
                items(group, key = { it.id }) { h ->
                    HighlightCard(
                        h = h,
                        showDate = true,
                        onOpen = { open(h) },
                        onShare = { share = h.shareSubject() },
                        onCopy = { copyText(context, quoteText(h.text, h.itemTitle, h.feedTitle, h.itemLink, h.note), copied) },
                        onDelete = {
                            store.delete(h.id)
                            toast(context, R.string.highlight_deleted)
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(FeederTokens.Space.xl)) }
        }
    }

    share?.let { ShareImageDialog(subject = it, onDismiss = { share = null }) }
}
