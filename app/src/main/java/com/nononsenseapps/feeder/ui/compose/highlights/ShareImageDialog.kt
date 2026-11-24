package com.nononsenseapps.feeder.ui.compose.highlights

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.edit
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.allQuotesText
import com.nononsenseapps.feeder.model.highlights.quoteText
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** What a share image shows: one highlight, or several of one article in reading order. */
data class ShareSubject(
    val text: String,
    val note: String,
    val color: HighlightColor,
    val itemTitle: String,
    val feedTitle: String,
    val itemLink: String?,
    val imageUrl: String?,
    /** Several highlights in one image (text, note); their texts and notes are joined in [text] and [note]. */
    val quotes: List<Pair<String, String>> = emptyList(),
) {
    val many: Boolean get() = quotes.size > 1

    fun caption(withNote: Boolean): String =
        if (many) {
            allQuotesText(
                quotes.map { (t, n) ->
                    Highlight(id = "", itemKey = "", itemTitle = itemTitle, itemLink = itemLink, feedTitle = feedTitle, text = t, note = n, createdAt = 0)
                },
                withNote,
            )
        } else {
            quoteText(text, itemTitle, feedTitle, itemLink, if (withNote) note else "")
        }
}

fun Highlight.shareSubject(): ShareSubject = ShareSubject(text, note, highlightColor, itemTitle, feedTitle, itemLink, imageUrl)

/** One share for several highlights of the same article. */
fun combinedSubject(hs: List<Highlight>): ShareSubject? {
    val first = hs.firstOrNull() ?: return null
    if (hs.size == 1) return first.shareSubject()
    return ShareSubject(
        text = hs.joinToString("\n") { it.text },
        note = hs.map { it.note }.filter { it.isNotBlank() }.joinToString("\n"),
        color = first.highlightColor,
        itemTitle = first.itemTitle,
        feedTitle = first.feedTitle,
        itemLink = first.itemLink,
        imageUrl = hs.firstNotNullOfOrNull { it.imageUrl },
        quotes = hs.map { it.text to it.note },
    )
}

private sealed interface Bg {
    data object Article : Bg

    data object Custom : Bg

    data class Gradient(
        val g: ShareGradient,
    ) : Bg
}

private const val PREFS = "share_image"

/**
 * Share a highlight as an image, Strava style: the article's picture, your own photo or a color,
 * with the highlighted text on it. Four layouts, story or post size. The share sheet gets the
 * image with the quote and link as its text (also put on the clipboard, since many apps drop it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareImageDialog(
    subject: ShareSubject,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var layout by remember { mutableStateOf(enumValueOr(prefs.getString("layout", null), ShareLayout.PHOTO)) }
    var size by remember { mutableStateOf(enumValueOr(prefs.getString("size", null), ShareSize.STORY)) }
    var gradient by remember { mutableStateOf(enumValueOr(prefs.getString("gradient", null), ShareGradient.GREEN)) }
    var showMark by remember { mutableStateOf(prefs.getBoolean("mark", true)) }
    var showNote by remember { mutableStateOf(subject.note.isNotBlank()) }
    var articleImage by remember { mutableStateOf<Bitmap?>(null) }
    var articleState by remember { mutableStateOf(if (subject.imageUrl != null) "loading" else "none") }
    var customImage by remember { mutableStateOf<Bitmap?>(null) }
    var bg by remember { mutableStateOf<Bg>(if (subject.imageUrl != null) Bg.Article else Bg.Gradient(gradient)) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(layout, size, gradient, showMark) {
        prefs.edit {
            putString("layout", layout.name)
            putString("size", size.name)
            putString("gradient", gradient.name)
            putBoolean("mark", showMark)
        }
    }

    LaunchedEffect(subject.imageUrl) {
        val url = subject.imageUrl ?: return@LaunchedEffect
        val img = loadBitmap(context, url)
        articleImage = img
        articleState = if (img != null) "ok" else "none"
        if (img == null && bg == Bg.Article) bg = Bg.Gradient(gradient)
    }

    val image =
        when {
            layout == ShareLayout.COLOR -> null
            bg == Bg.Article -> articleImage
            bg == Bg.Custom -> customImage
            else -> null
        }
    val activeGradient = (bg as? Bg.Gradient)?.g ?: gradient
    val content = ShareContent(subject.text, subject.note, subject.itemTitle, subject.feedTitle, subject.color)
    val options = ShareOptions(layout, size, image, activeGradient, showNote, showMark)

    LaunchedEffect(content, options) {
        preview = withContext(Dispatchers.Default) { ShareImageRenderer.render(content, options) }
    }

    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    val img = decodeUri(context, uri)
                    if (img != null) {
                        customImage = img
                        bg = Bg.Custom
                        if (layout == ShareLayout.COLOR) layout = ShareLayout.PHOTO
                    }
                }
            }
        }

    fun step(d: Int) {
        val all = ShareLayout.entries
        layout = all[(layout.ordinal + d + all.size) % all.size]
    }

    fun pickBg(b: Bg) {
        bg = b
        if (b is Bg.Gradient) {
            gradient = b.g
        } else if (layout == ShareLayout.COLOR) {
            layout = ShareLayout.PHOTO
        }
    }

    val caption = subject.caption(showNote)

    fun run(fn: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                fn()
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = FeederTokens.Space.xs)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.highlight_close))
                    }
                    Text(
                        if (subject.many) stringResource(R.string.share_highlights, subject.quotes.size) else stringResource(R.string.share_highlight),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
                ) {
                    // The image, with arrows and swipes to step through the layouts
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { step(-1) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.share_previous_layout))
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .heightIn(max = 460.dp)
                                    .aspectRatio(size.w.toFloat() / size.h)
                                    .clip(RoundedCornerShape(FeederTokens.Radius.md))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .pointerInput(Unit) {
                                        var total = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { total = 0f },
                                            onDragEnd = { if (abs(total) > 50) step(if (total < 0) 1 else -1) },
                                        ) { _, dx -> total += dx }
                                    },
                        ) {
                            preview?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = subject.text,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        IconButton(onClick = { step(1) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.share_next_layout))
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        ShareLayout.entries.forEach { l ->
                            FilterChip(selected = layout == l, onClick = { layout = l }, label = { Text(stringResource(l.label)) })
                        }
                    }

                    Text(stringResource(R.string.share_background), style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        if (subject.imageUrl != null) {
                            BgTile(
                                label = stringResource(if (articleState == "loading") R.string.share_bg_loading else R.string.share_bg_article),
                                selected = bg == Bg.Article,
                                enabled = articleState == "ok",
                                image = articleImage,
                                onClick = { pickBg(Bg.Article) },
                            )
                        }
                        customImage?.let { img ->
                            BgTile(stringResource(R.string.share_bg_photo), bg == Bg.Custom, true, image = img, onClick = { pickBg(Bg.Custom) })
                        }
                        ShareGradient.entries.forEach { g ->
                            BgTile(
                                label = stringResource(g.label),
                                selected = bg == Bg.Gradient(g),
                                enabled = true,
                                brush = Brush.linearGradient(listOf(Color(g.stops[0]), Color(g.stops[2]))),
                                onClick = { pickBg(Bg.Gradient(g)) },
                            )
                        }
                        BgTile(
                            label = stringResource(R.string.share_bg_pick),
                            selected = false,
                            enabled = true,
                            onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            add = true,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                        verticalArrangement = Arrangement.Center,
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShareSize.entries.forEach { s ->
                            FilterChip(selected = size == s, onClick = { size = s }, label = { Text(stringResource(s.label)) })
                        }
                        CheckRow(
                            label = stringResource(if (subject.many) R.string.share_include_notes else R.string.share_include_note),
                            checked = showNote,
                            enabled = subject.note.isNotBlank(),
                            onChange = { showNote = it },
                        )
                        CheckRow(stringResource(R.string.share_feeder_mark), showMark, true) { showMark = it }
                    }
                }

                // One primary action; the others are outlined
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                    modifier = Modifier.padding(FeederTokens.Space.lg),
                ) {
                    Button(enabled = !busy && preview != null, onClick = {
                        run {
                            val bmp = withContext(Dispatchers.Default) { ShareImageRenderer.render(content, options) }
                            shareImage(context, bmp, caption)
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(R.string.share))
                    }
                    OutlinedButton(enabled = !busy && preview != null, onClick = {
                        run {
                            val bmp = withContext(Dispatchers.Default) { ShareImageRenderer.render(content, options) }
                            val ok = saveImage(context, bmp)
                            Toast.makeText(context, if (ok) R.string.share_image_saved else R.string.share_image_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(R.string.share_save_image))
                    }
                    OutlinedButton(onClick = { copyText(context, caption, context.getString(R.string.highlight_copied)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(R.string.highlight_copy_text))
                    }
                }
            }
        }
    }
}

@Composable
private fun BgTile(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    image: Bitmap? = null,
    brush: Brush? = null,
    add: Boolean = false,
) {
    val shape = RoundedCornerShape(FeederTokens.Radius.sm)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(shape)
                    .then(if (brush != null) Modifier.background(brush) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    .border(
                        if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape,
                    ).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                    .semantics { contentDescription = label },
        ) {
            if (image != null) {
                Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (add) Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(64.dp).padding(top = FeederTokens.Space.xs),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(FeederTokens.Space.sm))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = FeederTokens.Space.sm),
        )
    }
}

private inline fun <reified E : Enum<E>> enumValueOr(
    name: String?,
    default: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: default

private suspend fun loadBitmap(
    context: Context,
    url: String,
): Bitmap? =
    try {
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    } catch (e: Exception) {
        null
    }

private suspend fun decodeUri(
    context: Context,
    uri: Uri,
): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Big enough for a 1080 px wide image
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2400) decoder.setTargetSampleSize((longest / 2400).coerceAtLeast(1))
            }
        } catch (e: Exception) {
            null
        }
    }

fun copyText(
    context: Context,
    text: String,
    done: String,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Feeder", text))
    Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
}

private suspend fun shareImage(
    context: Context,
    bitmap: Bitmap,
    caption: String,
) {
    val file =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            // Old shares are not needed once a new one is made
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "feeder-highlight-${System.currentTimeMillis()}.png").also { f ->
                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.shareimages", file)
    // The caption also goes on the clipboard: many apps drop the text when sharing an image
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Feeder", caption))
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private suspend fun saveImage(
    context: Context,
    bitmap: Bitmap,
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "feeder-highlight-${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Feeder")
                }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } != null
        } catch (e: Exception) {
            false
        }
    }

/** "3 highlights" */
@Composable
fun highlightsCount(n: Int): String = pluralStringResource(R.plurals.highlights_count, n, n)
