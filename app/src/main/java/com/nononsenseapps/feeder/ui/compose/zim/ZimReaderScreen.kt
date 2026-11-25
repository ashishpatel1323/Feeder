package com.nononsenseapps.feeder.ui.compose.zim

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.model.zim.ZimWebContent
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Reads one ZIM book in a WebView, served from the file on the phone; search by title and full text. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZimReaderScreen(
    bookId: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HideMainBar()
    val context = LocalContext.current
    val library = remember { ZimLibrary.get(context) }
    var bookTitle by remember { mutableStateOf(bookId) }
    var pageTitle by remember { mutableStateOf("") }
    var mainPath by remember { mutableStateOf<String?>(null) }
    var startPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var titleHits by remember { mutableStateOf<List<ZimWebContent.Hit>>(emptyList()) }
    var textHits by remember { mutableStateOf<List<ZimWebContent.Hit>>(emptyList()) }
    var hasFulltext by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(bookId) {
        withContext(Dispatchers.IO) {
            try {
                val archive = library.archive(bookId)
                bookTitle =
                    try {
                        archive.getMetadata("Title").ifBlank { bookId }
                    } catch (_: Exception) {
                        bookId
                    }
                hasFulltext = archive.hasFulltextIndex()
                val main = ZimWebContent.mainPath(archive) ?: ""
                // Open where you left off, if that page is still in the book
                startPath = library.lastPath(bookId)?.takeIf { archive.hasEntryByPath(it) } ?: main
                mainPath = main
            } catch (e: Exception) {
                error = context.getString(R.string.zim_book_missing)
            }
        }
    }

    LaunchedEffect(query, searching) {
        if (!searching || query.isBlank()) {
            titleHits = emptyList()
            textHits = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        withContext(Dispatchers.IO) {
            val archive = library.archive(bookId)
            titleHits = runCatching { ZimWebContent.suggest(archive, query) }.getOrDefault(emptyList())
            textHits =
                runCatching { ZimWebContent.search(archive, query) }
                    .getOrDefault(emptyList())
                    .filter { hit -> titleHits.none { it.path == hit.path } }
        }
    }

    fun open(path: String) {
        searching = false
        query = ""
        webView?.loadUrl(ZimWebContent.url(bookId, path))
    }

    BackHandler {
        when {
            searching -> searching = false
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onNavigateUp()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (searching) searching = false else onNavigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.zim_search_in, bookTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { (titleHits + textHits).firstOrNull()?.let { open(it.path) } }),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        androidx.compose.foundation.layout.Column {
                            Text(pageTitle.ifBlank { bookTitle }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (pageTitle.isNotBlank() && pageTitle != bookTitle) {
                                Text(
                                    bookTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.zim_clear)) }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.zim_search_in, bookTitle))
                        }
                        IconButton(onClick = { mainPath?.let { open(it) } }) {
                            Icon(Icons.Outlined.Home, contentDescription = stringResource(R.string.zim_main_page))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val start = mainPath
            when {
                error != null -> Text(error!!, modifier = Modifier.padding(24.dp))
                start != null ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                // Pages come only from the book (see shouldInterceptRequest), never from files
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun shouldInterceptRequest(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): WebResourceResponse? {
                                            if (request.url.host == ZimWebContent.HOST) return ZimWebContent.respond(library, request)
                                            // Anything outside the book stays offline
                                            return WebResourceResponse("text/plain", "utf-8", 403, "Offline", emptyMap(), "".byteInputStream())
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            if (request.url.host == ZimWebContent.HOST) return false
                                            // Links out of the book open in the browser
                                            try {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                            } catch (_: ActivityNotFoundException) {
                                            }
                                            return true
                                        }

                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String,
                                            favicon: Bitmap?,
                                        ) {
                                            loading = true
                                        }

                                        override fun onPageFinished(
                                            view: WebView,
                                            url: String,
                                        ) {
                                            loading = false
                                            pageTitle = view.title?.takeUnless { it.contains(ZimWebContent.HOST) }.orEmpty()
                                            ZimWebContent.parse(url.toUri())?.let { (book, path) ->
                                                if (book == bookId && path.isNotEmpty()) library.rememberPath(bookId, path)
                                            }
                                        }
                                    }
                                loadUrl(ZimWebContent.url(bookId, startPath.ifEmpty { start }))
                                webView = this
                            }
                        },
                    )
            }
            if (loading && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching && query.isNotBlank()) {
                SearchResults(
                    titleHits = titleHits,
                    textHits = textHits,
                    hasFulltext = hasFulltext,
                    onOpen = { open(it) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    titleHits: List<ZimWebContent.Hit>,
    textHits: List<ZimWebContent.Hit>,
    hasFulltext: Boolean,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (titleHits.isEmpty() && textHits.isEmpty()) {
            item { Text(stringResource(R.string.zim_nothing_found), modifier = Modifier.padding(16.dp)) }
        }
        items(titleHits, key = { "t:${it.path}" }) { hit ->
            ListItem(headlineContent = { Text(hit.title) }, modifier = Modifier.clickable { onOpen(hit.path) })
        }
        if (textHits.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    stringResource(R.string.zim_in_text),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
        } else if (!hasFulltext && titleHits.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.zim_no_fulltext),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(textHits, key = { "f:${it.path}" }) { hit ->
            ListItem(
                headlineContent = { Text(hit.title) },
                supportingContent = hit.snippet?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
                modifier = Modifier.clickable { onOpen(hit.path) },
            )
        }
    }
}
