package com.nononsenseapps.feeder.ui.compose.ebooks

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.PlaybackStatus
import com.nononsenseapps.feeder.model.ReadAloudNeighbors
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.ebooks.BookAudio
import com.nononsenseapps.feeder.model.ebooks.BookPositions
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import com.nononsenseapps.feeder.model.ebooks.SectionTexts
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.history.HistoryWhere
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.ui.compose.components.askHighlight
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.highlights.SketchEditorDialog
import com.nononsenseapps.feeder.ui.compose.history.historyItems
import com.nononsenseapps.feeder.ui.compose.history.rememberHistory
import com.nononsenseapps.feeder.ui.compose.history.rememberOpenTracker
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.util.UUID

/** Reader settings, the same fields as the web app's ReaderPrefs (web/src/lib/ebooks/reader.ts). */
@Serializable
data class ReaderPrefs(
    val fontSize: Int = 100,
    val lineHeight: Double = 1.5,
    val theme: String = "auto",
    val flow: String = "paginated",
    val publisherFont: Boolean = true,
)

private val prefsJson = Json { ignoreUnknownKeys = true }

private fun loadPrefs(context: Context): ReaderPrefs =
    runCatching {
        prefsJson.decodeFromString(ReaderPrefs.serializer(), context.getSharedPreferences("ebook_reader", Context.MODE_PRIVATE).getString("prefs", null) ?: "{}")
    }.getOrDefault(ReaderPrefs())

private fun savePrefs(
    context: Context,
    p: ReaderPrefs,
) = context
    .getSharedPreferences("ebook_reader", Context.MODE_PRIVATE)
    .edit()
    .putString("prefs", prefsJson.encodeToString(ReaderPrefs.serializer(), p))
    .apply()

private data class Relocation(
    val cfi: String = "",
    val fraction: Float = 0f,
    val chapter: String = "",
    // Page x of y, the same on every device (web lib/ebooks/nav.ts pageNumbers); 0 = not known
    val page: Int = 0,
    val pages: Int = 0,
    val atStart: Boolean = true,
    val atEnd: Boolean = false,
)

private data class TocEntry(
    val label: String,
    val href: String,
    val depth: Int,
)

private data class Selection(
    val cfi: String,
    val text: String,
)

/** Read aloud for a book uses an id no article has: below zero, from the book id. */
fun ttsIdOf(bookId: String): Long = -1L - (bookId.take(12).toLongOrNull(16) ?: bookId.hashCode().toLong()).and(0x7fff_ffff_ffffL)

private const val HOST = "ebook.feeder"

/**
 * Reads one ebook with foliate-js in a WebView (the page in assets/ebook, built from the web app's
 * code). Same positions, highlights and look as the web app's reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun EbookReaderScreen(
    bookId: String,
    at: String?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HideMainBar()
    val context = LocalContext.current
    val deleteConfirmer = rememberDeleteConfirmer()
    val di = LocalDI.current
    val highlightStore: HighlightStore by di.instance()
    val ttsStateHolder: TTSStateHolder by di.instance()
    val repository: Repository by di.instance()
    val library = remember { EbookLibrary.get(context) }
    val positions = remember { BookPositions.get(context) }
    val books by library.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId }
    val itemKey = EbookLibrary.itemKey(bookId)
    val allHighlights by highlightStore.highlights.collectAsState()
    val highlights = remember(allHighlights, itemKey) { allHighlights.filter { it.itemKey == itemKey && !it.cfi.isNullOrBlank() } }
    val ttsId = remember(bookId) { ttsIdOf(bookId) }
    val bookAudio = remember { BookAudio.get(context) }
    val scope = rememberCoroutineScope()

    /** Book section (spine index) being read aloud, -1 when not known. */
    val speakingSection = remember { mutableIntStateOf(-1) }

    /** Prepared audio of the next chapter took over: mark spoken text once that chapter shows. */
    val indexOnLoad = remember { mutableStateOf(false) }

    var file by remember { mutableStateOf<com.nononsenseapps.feeder.model.ebooks.BookSource?>(null) }
    var progress by remember { mutableStateOf(0L to 0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    var loc by remember { mutableStateOf(Relocation()) }
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var editing by remember { mutableStateOf<Highlight?>(null) }
    // A margin sketch being drawn: for a selection, or an existing sketch (by id)
    var sketchingNew by remember { mutableStateOf<Selection?>(null) }
    var sketchingId by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var prefs by remember { mutableStateOf(loadPrefs(context)) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var sliding by remember { mutableStateOf<Float?>(null) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** Read aloud reached the end of a chapter: keep reading once the next one is shown. */
    val continueNext = remember { mutableStateOf(false) }

    fun js(code: String) = webView?.evaluateJavascript(code, null)

    // History: opened once the first page shows, closed with the reader
    val opening =
        rememberOpenTracker(ItemHistory.EBOOK, bookId) {
            loc.takeIf { it.cfi.isNotBlank() }?.let { HistoryWhere(p = ItemHistory.bookChip(it.fraction.toDouble(), it.page, it.pages), s = it.chapter, f = it.fraction.toDouble(), x = it.cfi) }
        }

    fun q(s: String?) = if (s == null) "null" else JsonPrimitive(s).toString()

    fun highlightsJson() =
        JsonArray(
            highlights.map {
                JsonObject(
                    mapOf("id" to JsonPrimitive(it.id), "cfi" to JsonPrimitive(it.cfi), "color" to JsonPrimitive(it.color)) +
                        (it.sketch?.let { sk -> mapOf("sketch" to JsonPrimitive(sk)) } ?: emptyMap()),
                )
            },
        ).toString()

    LaunchedEffect(bookId) {
        try {
            file = library.ensureLocal(bookId) { done, total -> progress = done to total }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    // The latest values for the bridge, which outlives recompositions
    val currentBook by rememberUpdatedState(book)
    val currentHighlights by rememberUpdatedState(highlights)
    val currentPrefs by rememberUpdatedState(prefs)
    val currentDark by rememberUpdatedState(dark)

    fun startListening() {
        js("feeder.ttsText()")
    }

    /** Asks the page for the text of the chapters after [section], for audio made ahead. */
    fun requestTexts(section: Int) {
        if (section >= 0) js("feeder.sectionTexts($section, ${bookAudio.ahead.value})")
    }

    /** Reading is now at [section]: played audio goes, the chapters after it get prepared. */
    fun readingAt(
        section: Int,
        label: String,
    ) {
        speakingSection.intValue = section
        if (section < 0) return
        scope.launch(Dispatchers.IO) { bookAudio.setPosition(bookId, section, label) }
        requestTexts(section)
    }

    /**
     * Plays the chapter after the one being read from audio made ahead, without waiting for the
     * page (which may be asleep in the background). False when that audio is not ready.
     */
    suspend fun playNextPrepared(): Boolean {
        val current = speakingSection.intValue
        if (current < 0) return false
        val next =
            withContext(Dispatchers.IO) {
                val index = bookAudio.nextSection(bookId, current) ?: return@withContext null
                val text = bookAudio.text(bookId, index) ?: return@withContext null
                bookAudio.prepared(bookId, index, text.text)?.let { text to it }
            } ?: return false
        val (section, prepared) = next
        val b = currentBook
        ttsStateHolder.playPrepared(
            articleId = ttsId,
            text = section.text,
            prepared = prepared,
            useDetectLanguage = repository.useDetectLanguage.value,
            title = b?.title ?: "",
            author = b?.author ?: "",
            feedTitle = section.label,
        )
        indexOnLoad.value = true
        js("feeder.goToSection(${section.index})")
        readingAt(section.index, section.label)
        return true
    }

    val bridge =
        remember(bookId) {
            val main = Handler(Looper.getMainLooper())
            object {
                @JavascriptInterface
                fun onOpened(json: String) =
                    main.post {
                        opened = true
                        val o = runCatching { prefsJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@post
                        toc =
                            o["toc"]?.jsonArray?.map {
                                val t = it.jsonObject
                                TocEntry(t["label"]?.jsonPrimitive?.contentOrNull ?: "", t["href"]?.jsonPrimitive?.contentOrNull ?: "", t["depth"]?.jsonPrimitive?.int ?: 0)
                            } ?: emptyList()
                        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
                        val author = o["author"]?.jsonPrimitive?.contentOrNull ?: ""
                        val cover = o["cover"]?.jsonPrimitive?.contentOrNull
                        val b = currentBook
                        if (b != null && (!b.described || b.title != title.ifBlank { b.title } || b.author != author)) library.describe(bookId, title, author, cover)
                    }

                @JavascriptInterface
                fun onRelocate(json: String) =
                    main.post {
                        val o = runCatching { prefsJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return@post
                        val r =
                            Relocation(
                                cfi = o["cfi"]?.jsonPrimitive?.contentOrNull ?: "",
                                fraction = (o["fraction"]?.jsonPrimitive?.double ?: 0.0).toFloat(),
                                chapter = o["chapter"]?.jsonPrimitive?.contentOrNull ?: "",
                                page = o["page"]?.jsonPrimitive?.intOrNull ?: 0,
                                pages = o["pages"]?.jsonPrimitive?.intOrNull ?: 0,
                                atStart = o["atStart"]?.jsonPrimitive?.boolean ?: false,
                                atEnd = o["atEnd"]?.jsonPrimitive?.boolean ?: false,
                            )
                        if (r == loc) return@post
                        loc = r
                        opening.touch()
                        if (r.cfi.isNotBlank()) positions.save(bookId, r.cfi, r.fraction.toDouble(), r.page, r.pages)
                    }

                @JavascriptInterface
                fun onSelection(json: String) =
                    main.post {
                        val o = runCatching { prefsJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
                        selection =
                            o?.let {
                                val cfi = it["cfi"]?.jsonPrimitive?.contentOrNull
                                val text = it["text"]?.jsonPrimitive?.contentOrNull
                                if (cfi.isNullOrBlank() || text.isNullOrBlank()) null else Selection(cfi, text)
                            }
                        if (selection != null) editing = null
                    }

                @JavascriptInterface
                fun onHighlightTap(cfi: String) =
                    main.post {
                        currentHighlights.firstOrNull { it.cfi == cfi }?.let {
                            selection = null
                            if (it.sketch.isNullOrBlank()) editing = it else sketchingId = it.id
                        }
                    }

                @JavascriptInterface
                fun onTtsText(
                    text: String,
                    offset: Int,
                    section: Int,
                ) = main.post {
                    if (text.isBlank()) return@post
                    val b = currentBook
                    val detect = repository.useDetectLanguage.value
                    val start = offset.coerceIn(0, maxOf(0, text.length - 1))
                    val chapter = loc.chapter
                    bookAudio.setLanguage(ttsStateHolder.language.value, detect)
                    scope.launch {
                        // This chapter's audio may already be made (it was ahead before)
                        val prepared = if (section >= 0) withContext(Dispatchers.IO) { bookAudio.prepared(bookId, section, text) } else null
                        if (prepared != null) {
                            ttsStateHolder.playPrepared(ttsId, text, prepared, detect, b?.title ?: "", b?.author ?: "", chapter, start)
                        } else {
                            ttsStateHolder.tts(
                                textArray = listOf(AnnotatedString(text)),
                                useDetectLanguage = detect,
                                articleId = ttsId,
                                title = b?.title ?: "",
                                author = b?.author ?: "",
                                feedTitle = chapter,
                                startOffset = start,
                            )
                        }
                        readingAt(section, chapter)
                    }
                }

                @JavascriptInterface
                fun onSectionTexts(json: String) {
                    val got = runCatching { prefsJson.decodeFromString(SectionTexts.serializer(), json) }.getOrNull() ?: return
                    scope.launch(Dispatchers.IO) {
                        if (got.last >= 0) bookAudio.setLastSection(bookId, got.last)
                        if (got.texts.isNotEmpty()) bookAudio.saveTexts(bookId, got.texts)
                    }
                }

                @JavascriptInterface
                fun onSectionLoaded() =
                    main.post {
                        if (indexOnLoad.value) {
                            indexOnLoad.value = false
                            js("feeder.ttsIndex()")
                        } else if (continueNext.value) {
                            continueNext.value = false
                            js("feeder.ttsText()")
                        }
                    }

                @JavascriptInterface
                fun onTap(zone: String) = Unit

                @JavascriptInterface
                fun onError(message: String) = main.post { error = message }
            }
        }

    // Chapters are read aloud one after another, like articles in a list
    DisposableEffect(bookId) {
        val previous = ttsStateHolder.articleNavigator
        ttsStateHolder.articleNavigator =
            object : TTSStateHolder.ArticleNavigator {
                override fun hasPrevious(): Boolean = !loc.atStart

                override fun hasNext(): Boolean = !loc.atEnd

                override fun previous() {
                    continueNext.value = true
                    js("feeder.prevSection()")
                }

                override fun next() {
                    continueNext.value = true
                    js("feeder.nextSection()")
                }

                override fun onArticleFinished(articleId: Long) {
                    if (articleId != ttsId) return
                    scope.launch {
                        if (playNextPrepared()) return@launch
                        val last = speakingSection.intValue >= 0 && withContext(Dispatchers.IO) { bookAudio.isLastSection(bookId, speakingSection.intValue) }
                        if (loc.atEnd || last) {
                            // Read to the end: the book's audio is no longer needed
                            withContext(Dispatchers.IO) { bookAudio.finished(bookId) }
                            return@launch
                        }
                        continueNext.value = true
                        js("feeder.nextSection()")
                    }
                }
            }
        onDispose {
            ttsStateHolder.articleNavigator = previous
            if (ttsStateHolder.progress.value.articleId == ttsId) ttsStateHolder.stop()
        }
    }

    // The sentence and word being spoken, marked on the page
    val ttsProgress by ttsStateHolder.progress.collectAsState()
    val ttsState by ttsStateHolder.ttsState.collectAsState()
    val reading = ttsProgress.articleId == ttsId && ttsState != PlaybackStatus.STOPPED
    LaunchedEffect(ttsProgress.segment, ttsProgress.word, reading) {
        if (!reading) {
            js("feeder.ttsClear()")
            return@LaunchedEffect
        }
        val s = ttsProgress.segment ?: return@LaunchedEffect
        val w = ttsProgress.word
        js("feeder.ttsMark(${s.start},${s.end},${w?.start ?: 0},${w?.end ?: 0})")
    }

    LaunchedEffect(highlights, opened) { if (opened) js("feeder.setHighlights(${q(highlightsJson())})") }
    LaunchedEffect(prefs, dark, opened) {
        savePrefs(context, prefs)
        if (opened) js("feeder.setPrefs(${q(prefsJson.encodeToString(ReaderPrefs.serializer(), prefs))}, $dark)")
    }
    LaunchedEffect(at, opened) { if (opened && !at.isNullOrBlank()) js("feeder.goTo(${q(at)})") }

    val audioState by bookAudio.state.collectAsState()
    val audioAhead by bookAudio.ahead.collectAsState()
    val audioDeletePlayed by bookAudio.deletePlayed.collectAsState()

    val playerVisible = remember { MutableTransitionState(false) }
    playerVisible.targetState = reading

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (loc.chapter.isNotBlank()) {
                            Text(loc.chapter, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back)) }
                },
                actions = {
                    IconButton(onClick = { sheet = "toc" }, enabled = opened) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.ebook_contents))
                    }
                    IconButton(onClick = { sheet = "highlights" }, enabled = opened) {
                        Icon(Icons.Outlined.Highlight, contentDescription = stringResource(R.string.ebook_highlights))
                    }
                    IconButton(onClick = { sheet = "look" }) {
                        Icon(Icons.Outlined.FormatSize, contentDescription = stringResource(R.string.ebook_look))
                    }
                    IconButton(onClick = { if (reading) ttsStateHolder.play() else startListening() }, enabled = opened) {
                        Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.ebook_listen))
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                when {
                    selection != null || editing != null ->
                        HighlightBar(
                            text = selection?.text ?: editing!!.text,
                            existing = editing,
                            onSave = { color, note ->
                                val e = editing
                                if (e != null) {
                                    highlightStore.update(e.id, color, note)
                                    editing = e.copy(color = color.id, note = note.trim())
                                } else {
                                    val s = selection ?: return@HighlightBar
                                    highlightStore.add(
                                        Highlight(
                                            id = UUID.randomUUID().toString(),
                                            itemKey = itemKey,
                                            itemTitle = book?.title ?: "",
                                            itemLink = null,
                                            feedTitle = book?.author ?: "",
                                            imageUrl = null,
                                            text = s.text,
                                            color = color.id,
                                            note = note.trim(),
                                            createdAt = System.currentTimeMillis(),
                                            cfi = s.cfi,
                                        ),
                                    )
                                    js("feeder.clearSelection()")
                                    selection = null
                                }
                            },
                            onCopy = {
                                val text = selection?.text ?: editing?.text ?: ""
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("quote", text))
                                Toast.makeText(context, R.string.ebook_copy, Toast.LENGTH_SHORT).show()
                            },
                            onShare = {
                                val text = selection?.text ?: editing?.text ?: ""
                                val quote = "“$text”\n— ${listOf(book?.title, book?.author).filter { !it.isNullOrBlank() }.joinToString(", ")}"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote), null))
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
                                if (selection != null) js("feeder.clearSelection()")
                                selection = null
                                editing = null
                            },
                            onSketch =
                                selection?.let { sel ->
                                    {
                                        sketchingNew = sel
                                        js("feeder.clearSelection()")
                                        selection = null
                                    }
                                },
                        )

                    else ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = sliding ?: loc.fraction,
                                onValueChange = { sliding = it },
                                onValueChangeFinished = {
                                    sliding?.let { js("feeder.goToFraction($it)") }
                                    sliding = null
                                },
                                enabled = opened,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            // "45 / 312 · 14%" (pages hidden while dragging, where only the % is known)
                            val pct = "${((sliding ?: loc.fraction) * 100).toInt()}%"
                            Text(if (sliding == null && loc.pages > 0) "${loc.page} / ${loc.pages} · $pct" else pct, style = MaterialTheme.typography.bodySmall)
                        }
                }
                HideableCompactTTSPlayer(
                    visibleState = playerVisible,
                    currentlyPlaying = ttsState == PlaybackStatus.PLAYING,
                    onPlay = { ttsStateHolder.play() },
                    onPause = { ttsStateHolder.pause() },
                    onStop = { ttsStateHolder.stop() },
                    onSkipNext = { ttsStateHolder.skipNext() },
                    languages = ImmutableHolder(ttsStateHolder.availableLanguages.collectAsState().value),
                    onSelectLanguage = {
                        ttsStateHolder.setLanguage(it)
                        bookAudio.setLanguage(it, repository.useDetectLanguage.value)
                    },
                    progress = ttsStateHolder.progress,
                    onSeekTo = { ttsStateHolder.seekTo(it) },
                    onGoToSpokenText = { },
                    displayedArticleId = ttsId,
                    statusLine = offlineAudioStatusLine(audioState, bookId, audioAhead) { sheet = "audio" },
                    onSelectSpeed = { ttsStateHolder.setSpeed(it) },
                    diagnostics = { ttsStateHolder.diagnostics() },
                    neighbors =
                        ReadAloudNeighbors(
                            currentTitle = loc.chapter,
                            previousId = if (loc.atStart) null else ttsId,
                            nextId = if (loc.atEnd) null else ttsId,
                        ),
                    onSkipArticle = { next ->
                        continueNext.value = true
                        ttsStateHolder.stop()
                        js(if (next) "feeder.nextSection()" else "feeder.prevSection()")
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val f = file
            if (f != null && error == null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView.setWebContentsDebuggingEnabled(com.nononsenseapps.feeder.BuildConfig.DEBUG)
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            addJavascriptInterface(bridge, "FeederBook")
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): WebResourceResponse? {
                                        val url = request.url
                                        if (url.host != HOST) return null
                                        val path = url.path.orEmpty()
                                        return try {
                                            if (path.startsWith("/book/")) {
                                                WebResourceResponse("application/octet-stream", null, f.open())
                                            } else {
                                                val name = path.removePrefix("/").ifBlank { "index.html" }
                                                val mime =
                                                    when (name.substringAfterLast('.')) {
                                                        "html" -> "text/html"
                                                        "js", "mjs" -> "text/javascript"
                                                        "css" -> "text/css"
                                                        else -> "application/octet-stream"
                                                    }
                                                WebResourceResponse(mime, "utf-8", ctx.assets.open("ebook/$name"))
                                            }
                                        } catch (e: Exception) {
                                            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), "".byteInputStream())
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        // Book pages load in iframes from blob: addresses; only links the reader taps leave the app
                                        if (!request.isForMainFrame || request.url.host == HOST || request.url.scheme !in setOf("http", "https", "mailto")) return false
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                        return true
                                    }

                                    override fun onPageFinished(
                                        view: WebView,
                                        url: String?,
                                    ) {
                                        val b = currentBook
                                        val start = at?.takeIf { it.isNotBlank() } ?: positions.get(bookId)?.cfi
                                        val name = "book.${b?.ext ?: f.ext}"
                                        view.evaluateJavascript(
                                            "feeder.open(${q("https://$HOST/book/$name")}, ${q(name)}, ${q(start)}, " +
                                                "${q(prefsJson.encodeToString(ReaderPrefs.serializer(), currentPrefs))}, $currentDark, ${q(highlightsJson())})",
                                            null,
                                        )
                                    }
                                }
                            loadUrl("https://$HOST/index.html")
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
            if (error != null) {
                Text(error!!, modifier = Modifier.align(Alignment.Center).padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            } else if (!opened) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (done, total) = progress
                    if (total > 0) {
                        Text(
                            stringResource(
                                R.string.ebook_downloading,
                                android.text.format.Formatter
                                    .formatShortFileSize(context, done),
                                android.text.format.Formatter
                                    .formatShortFileSize(context, total),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.width(240.dp))
                    } else {
                        Text(stringResource(R.string.ebook_opening))
                    }
                }
            }
        }
    }

    sketchingNew?.let { sel ->
        SketchEditorDialog(
            quote = sel.text,
            initial = null,
            initialColor = HighlightColor.PINK,
            onSave = { sketch, color ->
                highlightStore.add(
                    Highlight(
                        id = UUID.randomUUID().toString(),
                        itemKey = itemKey,
                        itemTitle = book?.title ?: "",
                        itemLink = null,
                        feedTitle = book?.author ?: "",
                        imageUrl = null,
                        text = sel.text,
                        color = color.id,
                        createdAt = System.currentTimeMillis(),
                        cfi = sel.cfi,
                        sketch = sketch,
                    ),
                )
                Toast.makeText(context, R.string.sketch_added, Toast.LENGTH_SHORT).show()
                sketchingNew = null
            },
            onDelete = null,
            onDismiss = { sketchingNew = null },
        )
    }
    sketchingId?.let { id ->
        val h = highlights.firstOrNull { it.id == id }
        if (h == null) {
            sketchingId = null
        } else {
            SketchEditorDialog(
                quote = h.text,
                initial = h.sketch,
                initialColor = h.highlightColor,
                onSave = { sketch, color ->
                    highlightStore.updateSketch(h.id, sketch, color)
                    sketchingId = null
                },
                onDelete = {
                    deleteConfirmer.askHighlight(h.text) {
                        highlightStore.delete(h.id)
                        sketchingId = null
                    }
                },
                onDismiss = { sketchingId = null },
            )
        }
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet != "look")) {
            when (sheet) {
                "audio" ->
                    OfflineAudioSheet(
                        state = audioState,
                        ahead = audioAhead,
                        deletePlayed = audioDeletePlayed,
                        onSetAhead = { n -> scope.launch(Dispatchers.IO) { bookAudio.setAhead(n) } },
                        onSetAutoDelete = { d -> scope.launch(Dispatchers.IO) { bookAudio.setDeletePlayed(d) } },
                        onDismiss = { sheet = null },
                    )
                "toc" ->
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { SheetTitle(stringResource(R.string.ebook_contents)) }
                                TextButton(onClick = { sheet = "history" }, modifier = Modifier.padding(end = 8.dp)) {
                                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.history))
                                }
                            }
                        }
                        items(toc) { t ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        t.label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color =
                                            if (t.label ==
                                                loc.chapter
                                            ) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Unspecified
                                            },
                                    )
                                },
                                modifier =
                                    Modifier.padding(start = (t.depth * 16).dp).clickable {
                                        js("feeder.goTo(${q(t.href)})")
                                        sheet = null
                                    },
                            )
                        }
                    }

                "highlights" ->
                    LazyColumn(Modifier.fillMaxWidth()) {
                        item { SheetTitle(stringResource(R.string.ebook_highlights)) }
                        if (highlights.isEmpty()) {
                            item { Text(stringResource(R.string.ebook_no_highlights), modifier = Modifier.padding(16.dp)) }
                        }
                        items(highlights.sortedBy { it.createdAt }, key = { it.id }) { h ->
                            ListItem(
                                leadingContent = { Box(Modifier.size(width = 4.dp, height = 36.dp).background(Color(h.highlightColor.ink))) },
                                headlineContent = { Text(h.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (h.note.isNotBlank()) ({ Text(h.note) }) else null,
                                modifier =
                                    Modifier.clickable {
                                        js("feeder.goTo(${q(h.cfi)})")
                                        sheet = null
                                    },
                            )
                        }
                    }

                "history" -> {
                    val days = rememberHistory(ItemHistory.EBOOK, bookId)
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        item { SheetTitle(stringResource(R.string.history)) }
                        historyItems(days, R.string.history_empty_read) { e ->
                            js("feeder.goTo(${q(e.x)})")
                            sheet = null
                        }
                    }
                }

                else -> LookSheet(prefs = prefs, onChange = { prefs = it })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
internal fun HighlightBar(
    text: String,
    existing: Highlight?,
    onSave: (HighlightColor, String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    /** Draw a margin sketch for the selected text instead (new selections only). */
    onSketch: (() -> Unit)? = null,
) {
    var color by remember(existing?.id, text) { mutableStateOf(existing?.highlightColor ?: HighlightColor.YELLOW) }
    var note by remember(existing?.id, text) { mutableStateOf(existing?.note ?: "") }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HighlightColor.entries.forEach { c ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(Color(c.swatch), CircleShape)
                            .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable {
                                color = c
                                if (existing != null) onSave(c, note)
                            },
                    )
                }
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) }
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, placeholder = { Text(stringResource(R.string.ebook_note_hint)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (existing == null) {
                    androidx.compose.material3.Button(onClick = { onSave(color, note) }) {
                        Icon(Icons.Outlined.Highlight, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ebook_highlight))
                    }
                    if (onSketch != null) {
                        OutlinedButton(onClick = onSketch) {
                            Icon(Icons.Outlined.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sketch_action))
                        }
                    }
                } else if (note.trim() != existing.note) {
                    androidx.compose.material3.Button(onClick = { onSave(color, note) }) { Text(stringResource(R.string.ebook_save_note)) }
                }
                OutlinedButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.ebook_copy), modifier = Modifier.size(18.dp)) }
                OutlinedButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.ebook_share), modifier = Modifier.size(18.dp)) }
                if (existing != null) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ebook_delete)) }
                }
            }
        }
    }
}

@Composable
private fun LookSheet(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
) {
    var lineHeight by remember { mutableFloatStateOf(prefs.lineHeight.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetTitle(stringResource(R.string.ebook_look))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ebook_text_size), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onChange(prefs.copy(fontSize = maxOf(70, prefs.fontSize - 10))) }) { Text("A−") }
            Text("${prefs.fontSize}%", modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedButton(onClick = { onChange(prefs.copy(fontSize = minOf(220, prefs.fontSize + 10))) }) { Text("A+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ebook_line_spacing), modifier = Modifier.weight(1f))
            Slider(
                value = lineHeight,
                onValueChange = { lineHeight = it },
                onValueChangeFinished = { onChange(prefs.copy(lineHeight = (Math.round(lineHeight * 10) / 10.0))) },
                valueRange = 1.2f..2f,
                modifier = Modifier.width(180.dp),
            )
        }
        Text(stringResource(R.string.ebook_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to R.string.ebook_theme_app, "light" to R.string.ebook_theme_light, "sepia" to R.string.ebook_theme_sepia, "dark" to R.string.ebook_theme_dark).forEach { (id, label) ->
                FilterChip(selected = prefs.theme == id, onClick = { onChange(prefs.copy(theme = id)) }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.ebook_pages))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = prefs.flow == "paginated", onClick = { onChange(prefs.copy(flow = "paginated")) }, label = { Text(stringResource(R.string.ebook_turn_pages)) })
            FilterChip(selected = prefs.flow == "scrolled", onClick = { onChange(prefs.copy(flow = "scrolled")) }, label = { Text(stringResource(R.string.ebook_scroll)) })
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(prefs.copy(publisherFont = !prefs.publisherFont)) }) {
            Checkbox(checked = prefs.publisherFont, onCheckedChange = { onChange(prefs.copy(publisherFont = it)) })
            Text(stringResource(R.string.ebook_publisher_font))
        }
    }
}
