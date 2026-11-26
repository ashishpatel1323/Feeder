package com.nononsenseapps.feeder.ui.compose.zim

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.PlaybackStatus
import com.nononsenseapps.feeder.model.ReadAloudNeighbors
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.history.HistoryWhere
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.model.zim.ZimLibrary
import com.nononsenseapps.feeder.model.zim.ZimWebContent
import com.nononsenseapps.feeder.ui.compose.components.askHighlight
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.ebooks.HighlightBar
import com.nononsenseapps.feeder.ui.compose.ebooks.ttsIdOf
import com.nononsenseapps.feeder.ui.compose.history.HistorySheet
import com.nononsenseapps.feeder.ui.compose.history.rememberOpenTracker
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.util.UUID

/** Text selected on a page, anchored like an article highlight (see web/src/lib/zim/marks.ts). */
private data class ZimSelection(
    val text: String,
    val start: Int,
    val prefix: String,
)

private val bridgeJson = Json { ignoreUnknownKeys = true }

/**
 * Reads one ZIM book in a WebView, served from the file on the phone; search by title and full text.
 * Pages can be highlighted and read aloud: a script built from the web app's code
 * (assets/zim/feeder-zim.js, see web/src/zim-android) is added to each page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ZimReaderScreen(
    bookId: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HideMainBar()
    val context = LocalContext.current
    val deleteConfirmer = rememberDeleteConfirmer()
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

    val di = LocalDI.current
    val highlightStore: HighlightStore by di.instance()
    val ttsStateHolder: TTSStateHolder by di.instance()
    val repository: Repository by di.instance()
    val recentHistory: com.nononsenseapps.feeder.model.history.CentralHistoryRecorder by di.instance()
    /** The page shown, as its path in the book; null until a page of the book has loaded. */
    var pagePath by remember { mutableStateOf<String?>(null) }
    /** Bumped each time a page finishes loading and has the script. */
    var pageLoads by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<ZimSelection?>(null) }
    var editing by remember { mutableStateOf<Highlight?>(null) }
    var highlightsSheet by remember { mutableStateOf(false) }
    var historySheet by remember { mutableStateOf(false) }
    val allHighlights by highlightStore.highlights.collectAsState()
    val bookPrefix = remember(bookId) { ZimLibrary.highlightKey(bookId, "") }
    val bookHighlights = remember(allHighlights, bookPrefix) { allHighlights.filter { it.itemKey.startsWith(bookPrefix) } }
    val itemKey = pagePath?.let { ZimLibrary.highlightKey(bookId, it) }
    val pageHighlights = remember(bookHighlights, itemKey) { bookHighlights.filter { it.itemKey == itemKey } }
    val ttsId = remember(itemKey) { ttsIdOf(itemKey ?: "zim:$bookId") }
    val script = remember { runCatching { context.assets.open("zim/feeder-zim.js").bufferedReader().use { it.readText() } }.getOrNull() }

    fun js(code: String) = webView?.evaluateJavascript(code, null)

    // History: opened once the first page shows, closed with the reader
    val opening =
        rememberOpenTracker(ItemHistory.ZIM, bookId) {
            pagePath?.let { HistoryWhere(p = pageTitle.ifBlank { it.ifBlank { bookTitle } }, x = it) }
        }

    fun q(s: String?) = if (s == null) "null" else JsonPrimitive(s).toString()

    fun marksJson(list: List<Highlight>) =
        JsonArray(
            list.map {
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(it.id),
                        "text" to JsonPrimitive(it.text),
                        "start" to JsonPrimitive(it.start),
                        "prefix" to JsonPrimitive(it.prefix),
                        "color" to JsonPrimitive(it.color),
                    ),
                )
            },
        ).toString()

    // The latest values for the bridge, which outlives recompositions
    val currentPageHighlights by rememberUpdatedState(pageHighlights)
    val currentPageTitle by rememberUpdatedState(pageTitle)
    val currentBookTitle by rememberUpdatedState(bookTitle)
    val currentTtsId by rememberUpdatedState(ttsId)
    val bridge =
        remember(bookId) {
            val main = Handler(Looper.getMainLooper())
            object {
                @JavascriptInterface
                fun onSelection(json: String) =
                    main.post {
                        val o = runCatching { bridgeJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
                        selection =
                            o?.let {
                                val text = it["text"]?.jsonPrimitive?.contentOrNull
                                if (text.isNullOrBlank()) {
                                    null
                                } else {
                                    ZimSelection(text, it["start"]?.jsonPrimitive?.intOrNull ?: 0, it["prefix"]?.jsonPrimitive?.contentOrNull ?: "")
                                }
                            }
                        if (selection != null) editing = null
                    }

                @JavascriptInterface
                fun onHighlightTap(id: String) =
                    main.post {
                        currentPageHighlights.firstOrNull { it.id == id }?.let {
                            selection = null
                            editing = it
                        }
                    }

                @JavascriptInterface
                fun onTtsText(
                    text: String,
                    offset: Int,
                ) = main.post {
                    if (text.isBlank()) return@post
                    ttsStateHolder.tts(
                        textArray = listOf(AnnotatedString(text)),
                        useDetectLanguage = repository.useDetectLanguage.value,
                        articleId = currentTtsId,
                        title = currentPageTitle.ifBlank { currentBookTitle },
                        author = "",
                        feedTitle = currentBookTitle,
                        startOffset = offset.coerceIn(0, maxOf(0, text.length - 1)),
                    )
                }
            }
        }

    // Read aloud: the sentence and word being spoken, marked on the page
    val ttsProgress by ttsStateHolder.progress.collectAsState()
    val ttsState by ttsStateHolder.ttsState.collectAsState()
    val reading = ttsProgress.articleId == ttsId && ttsState != PlaybackStatus.STOPPED
    LaunchedEffect(ttsProgress.segment, ttsProgress.word, reading, pageLoads) {
        if (!reading) {
            js("window.feederZim&&feederZim.ttsClear()")
            return@LaunchedEffect
        }
        val seg = ttsProgress.segment ?: return@LaunchedEffect
        val w = ttsProgress.word
        js("window.feederZim&&feederZim.ttsMark(${seg.start},${seg.end},${w?.start ?: 0},${w?.end ?: 0})")
    }
    LaunchedEffect(pageHighlights, pageLoads) { if (pageLoads > 0) js("window.feederZim&&feederZim.setHighlights(${q(marksJson(pageHighlights))})") }
    DisposableEffect(bookId) {
        onDispose {
            if (ttsStateHolder.progress.value.articleId == ttsId) ttsStateHolder.stop()
        }
    }
    val playerVisible = remember { MutableTransitionState(false) }
    playerVisible.targetState = reading

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

    fun openHighlight(h: Highlight) {
        val (_, path) = ZimLibrary.pageOfHighlight(h.itemKey) ?: return
        highlightsSheet = false
        if (path == pagePath) {
            js("window.feederZim&&feederZim.scrollToHighlight(${q(h.id)})")
        } else {
            library.pendingHighlight = h.id
            open(path)
        }
    }

    fun quote(text: String) = "“$text”\n— ${listOf(pageTitle, bookTitle).filter { it.isNotBlank() }.distinct().joinToString(", ")}"

    BackHandler {
        when {
            selection != null || editing != null -> {
                if (selection != null) js("window.feederZim&&feederZim.clearSelection()")
                selection = null
                editing = null
            }
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
                        IconButton(onClick = { highlightsSheet = true }) {
                            Icon(Icons.Outlined.Highlight, contentDescription = stringResource(R.string.ebook_highlights))
                        }
                        IconButton(onClick = { if (reading) ttsStateHolder.play() else js("window.feederZim&&feederZim.ttsText()") }, enabled = pagePath != null) {
                            Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.ebook_listen))
                        }
                        IconButton(onClick = { mainPath?.let { open(it) } }) {
                            Icon(Icons.Outlined.Home, contentDescription = stringResource(R.string.zim_main_page))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (selection != null || editing != null) {
                    HighlightBar(
                        text = selection?.text ?: editing!!.text,
                        existing = editing,
                        onSave = { color, note ->
                            val e = editing
                            if (e != null) {
                                highlightStore.update(e.id, color, note)
                                editing = e.copy(color = color.id, note = note.trim())
                            } else {
                                val sel = selection ?: return@HighlightBar
                                val key = itemKey ?: return@HighlightBar
                                highlightStore.add(
                                    Highlight(
                                        id = UUID.randomUUID().toString(),
                                        itemKey = key,
                                        itemTitle = pageTitle.ifBlank { bookTitle },
                                        itemLink = null,
                                        feedTitle = bookTitle,
                                        imageUrl = null,
                                        text = sel.text,
                                        start = sel.start,
                                        prefix = sel.prefix,
                                        color = color.id,
                                        note = note.trim(),
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                js("window.feederZim&&feederZim.clearSelection()")
                                selection = null
                            }
                        },
                        onCopy = {
                            val text = selection?.text ?: editing?.text ?: ""
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("quote", quote(text)))
                            Toast.makeText(context, R.string.ebook_copy, Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val text = selection?.text ?: editing?.text ?: ""
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote(text)), null))
                        },
                        onDelete = {
                            editing?.let { h ->
                                deleteConfirmer.askHighlight(h.text) {
                                    highlightStore.delete(h.id)
                                    editing = null
                                }
                            }
                        },
                        onClose = {
                            if (selection != null) js("window.feederZim&&feederZim.clearSelection()")
                            selection = null
                            editing = null
                        },
                    )
                }
                HideableCompactTTSPlayer(
                    visibleState = playerVisible,
                    currentlyPlaying = ttsState == PlaybackStatus.PLAYING,
                    onPlay = { ttsStateHolder.play() },
                    onPause = { ttsStateHolder.pause() },
                    onStop = { ttsStateHolder.stop() },
                    onSkipNext = { ttsStateHolder.skipNext() },
                    languages = ImmutableHolder(ttsStateHolder.availableLanguages.collectAsState().value),
                    onSelectLanguage = { ttsStateHolder.setLanguage(it) },
                    progress = ttsStateHolder.progress,
                    onSeekTo = { ttsStateHolder.seekTo(it) },
                    onGoToSpokenText = { js("window.feederZim&&feederZim.ttsFollow()") },
                    displayedArticleId = ttsId,
                    onSelectSpeed = { ttsStateHolder.setSpeed(it) },
                    diagnostics = { ttsStateHolder.diagnostics() },
                    neighbors = ReadAloudNeighbors(currentTitle = pageTitle.ifBlank { bookTitle }),
                )
            }
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
                                addJavascriptInterface(bridge, "FeederZim")
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
                                            selection = null
                                            editing = null
                                            // A new page: stop reading the old one
                                            if (ttsStateHolder.progress.value.articleId == currentTtsId && ttsStateHolder.ttsState.value != PlaybackStatus.STOPPED) ttsStateHolder.stop()
                                        }

                                        override fun onPageFinished(
                                            view: WebView,
                                            url: String,
                                        ) {
                                            loading = false
                                            pageTitle = view.title?.takeUnless { it.contains(ZimWebContent.HOST) }.orEmpty()
                                            val at = ZimWebContent.parse(url.toUri())?.takeIf { it.first == bookId }
                                            at?.let { (_, path) -> if (path.isNotEmpty()) library.rememberPath(bookId, path) }
                                            pagePath = at?.second
                                            if (at != null) opening.touch()
                                            // History: every page shown is its own entry
                                            at?.let { (_, path) -> recentHistory.zimPage(bookId, path, pageTitle, currentBookTitle) }
                                            if (at != null && script != null) {
                                                view.evaluateJavascript(script, null)
                                                library.pendingHighlight?.let {
                                                    library.pendingHighlight = null
                                                    view.evaluateJavascript("window.feederZim&&feederZim.scrollToHighlight(${q(it)})", null)
                                                }
                                                pageLoads++
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

    if (highlightsSheet) {
        ZimHighlightsSheet(
            highlights = bookHighlights,
            bookTitle = bookTitle,
            onOpen = { openHighlight(it) },
            onHistory = {
                highlightsSheet = false
                historySheet = true
            },
            onDismiss = { highlightsSheet = false },
        )
    }
    if (historySheet) {
        HistorySheet(ItemHistory.ZIM, bookId, onJump = { open(it.x) }, onDismiss = { historySheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZimHighlightsSheet(
    highlights: List<Highlight>,
    bookTitle: String,
    onOpen: (Highlight) -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ebook_highlights),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    TextButton(onClick = onHistory, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.history))
                    }
                }
            }
            if (highlights.isEmpty()) {
                item { Text(stringResource(R.string.zim_no_highlights), modifier = Modifier.padding(16.dp)) }
            }
            items(highlights.sortedWith(compareBy({ it.itemTitle }, { it.start })), key = { it.id }) { h ->
                ListItem(
                    leadingContent = { Box(Modifier.size(width = 4.dp, height = 36.dp).background(Color(h.highlightColor.ink))) },
                    overlineContent = if (h.itemTitle.isNotBlank() && h.itemTitle != bookTitle) ({ Text(h.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }) else null,
                    headlineContent = { Text(h.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (h.note.isNotBlank()) ({ Text(h.note) }) else null,
                    modifier = Modifier.clickable { onOpen(h) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
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
