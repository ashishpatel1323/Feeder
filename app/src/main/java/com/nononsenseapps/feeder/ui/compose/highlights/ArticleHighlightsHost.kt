package com.nononsenseapps.feeder.ui.compose.highlights

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.Sync2Dao
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightAnchor
import com.nononsenseapps.feeder.model.highlights.HighlightArticle
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.highlights.newHighlight
import com.nononsenseapps.feeder.model.highlights.quoteText
import com.nononsenseapps.feeder.ui.compose.components.askHighlight
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** What the article screen shows about its highlights: how many, and how to open the list. */
data class ArticleHighlightsState(
    val count: Int,
    val onShowList: () -> Unit,
)

/**
 * Highlights for the open article: adds Highlight to the text selection menu, marks the saved
 * highlights in the text, and shows the highlight sheet, the article's list and share images.
 */
@Composable
fun ArticleHighlightsHost(
    articleId: Long,
    title: String,
    link: String?,
    feedTitle: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable (ArticleHighlightsState?) -> Unit,
) {
    val di = LocalDI.current
    val store: HighlightStore by di.instance()
    val dao: Sync2Dao by di.instance()
    val context = LocalContext.current
    val deleteConfirmer = rememberDeleteConfirmer()
    val itemKey by produceState<String?>(null, articleId) {
        value = if (articleId > 0) dao.getItemKeys(listOf(articleId)).firstOrNull()?.let { "${it.feedUrl}\n${it.guid}" } else null
    }
    val all by store.highlights.collectAsStateWithLifecycle()
    val mine = remember(all, itemKey) { all.filter { it.itemKey == itemKey }.sortedBy { it.start } }

    var pending by remember { mutableStateOf<Pair<String, HighlightAnchor?>?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    // A margin sketch being drawn: for a selection (text + place) or an existing sketch (id)
    var sketchingNew by remember { mutableStateOf<Pair<String, HighlightAnchor?>?>(null) }
    var sketchingId by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf<ShareSubject?>(null) }

    val userHighlights =
        remember(mine) {
            UserHighlights(mine) {
                if (it.sketch.isNullOrBlank()) editing = it.id else sketchingId = it.id
            }
        }
    val currentUser by rememberUpdatedState(userHighlights)

    // Highlight in the text selection menu, for this article only. Compose doesn't hand out the
    // selected text, so Highlight runs the menu's own Copy into a clipboard that catches it
    // (the real clipboard is left alone).
    val label = stringResource(R.string.highlight_action)
    val sketchLabel = stringResource(R.string.sketch_action)
    val selectAllLabel = stringResource(android.R.string.selectAll)
    val copyItem = remember { arrayOfNulls<TextContextMenuItem>(1) }
    val realClipboard = LocalClipboard.current
    val clipboard = remember(realClipboard, context) { CatchingClipboard(realClipboard, context) }
    val scope = rememberCoroutineScope()
    val menuModifier =
        if (itemKey == null) {
            Modifier
        } else {
            Modifier
                .appendTextContextMenuComponents {
                    // Copy is found by the filter below. Appends run before filters.
                    item(key = HighlightMenuKey, label = label) {
                        val copy = copyItem[0]
                        if (copy != null) {
                            clipboard.catchNext(scope) { text -> pending = text to currentUser.locator?.anchorOf(text) }
                            copy.onClick(this)
                        }
                        close()
                    }
                    item(key = SketchMenuKey, label = sketchLabel) {
                        val copy = copyItem[0]
                        if (copy != null) {
                            clipboard.catchNext(scope) { text -> sketchingNew = text to currentUser.locator?.anchorOf(text) }
                            copy.onClick(this)
                        }
                        close()
                    }
                    for (moved in MovedMenuItems.startMenu()) {
                        val itemLabel = if (moved.key == TextContextMenuKeys.SelectAllKey) selectAllLabel else moved.label
                        item(key = MovedMenuKey(moved.key), label = itemLabel, leadingIcon = moved.leadingIcon) {
                            MovedMenuItems.current(moved.key)?.onClick?.invoke(this)
                        }
                    }
                }.filterTextContextMenuComponents { component ->
                    val item = component as? TextContextMenuItem
                    if (item?.key == TextContextMenuKeys.CopyKey) copyItem[0] = item
                    item == null || !MovedMenuItems.filter(item)
                }
        }

    val article =
        itemKey?.let { HighlightArticle(itemKey = it, title = title, link = link, feedTitle = feedTitle, imageUrl = imageUrl) }

    CompositionLocalProvider(LocalUserHighlights provides userHighlights) {
        Box(modifier.then(menuModifier)) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                content(if (itemKey != null) ArticleHighlightsState(mine.size) { showList = true } else null)
            }
        }
    }

    fun create(
        text: String,
        anchor: HighlightAnchor?,
        color: com.nononsenseapps.feeder.model.highlights.HighlightColor,
        note: String,
    ): Highlight? {
        val a = article ?: return null
        // Text outside the article body (the title) is still kept, it just isn't marked
        val h = newHighlight(a, anchor ?: HighlightAnchor(text.trim(), 0, ""), color, note)
        store.add(h)
        return h
    }

    pending?.let { (text, anchor) ->
        HighlightSheet(
            text = text.trim(),
            existing = null,
            onSave = { color, note ->
                create(text, anchor, color, note)
                toast(context, R.string.highlight_added)
                pending = null
            },
            onShare = { color, note ->
                // Sharing a selection saves it as a highlight too
                share = create(text, anchor, color, note)?.shareSubject()
                pending = null
            },
            onCopy = { note ->
                copyText(context, quoteText(text.trim(), title, feedTitle, link, note), context.getString(R.string.highlight_copied))
            },
            onDelete = {},
            onDismiss = { pending = null },
        )
    }

    editing?.let { id ->
        val h = mine.firstOrNull { it.id == id }
        if (h == null) {
            editing = null
        } else {
            HighlightSheet(
                text = h.text,
                existing = h,
                onSave = { color, note -> store.update(h.id, color, note) },
                onShare = { color, note ->
                    if (color != h.highlightColor || note.trim() != h.note) store.update(h.id, color, note)
                    share = h.copy(color = color.id, note = note.trim()).shareSubject()
                    editing = null
                },
                onCopy = { note ->
                    copyText(context, quoteText(h.text, h.itemTitle, h.feedTitle, h.itemLink, note), context.getString(R.string.highlight_copied))
                },
                onDelete = {
                    deleteConfirmer.askHighlight(h.text) {
                        store.delete(h.id)
                        toast(context, R.string.highlight_deleted)
                        editing = null
                    }
                },
                onDismiss = { editing = null },
            )
        }
    }

    sketchingNew?.let { (text, anchor) ->
        SketchEditorDialog(
            quote = text.trim(),
            initial = null,
            initialColor = com.nononsenseapps.feeder.model.highlights.HighlightColor.PINK,
            onSave = { sketch, color ->
                article?.let { a ->
                    store.add(newHighlight(a, anchor ?: HighlightAnchor(text.trim(), 0, ""), color, "").copy(sketch = sketch))
                    toast(context, R.string.sketch_added)
                }
                sketchingNew = null
            },
            onDelete = null,
            onDismiss = { sketchingNew = null },
        )
    }

    sketchingId?.let { id ->
        val h = mine.firstOrNull { it.id == id }
        if (h == null) {
            sketchingId = null
        } else {
            SketchEditorDialog(
                quote = h.text,
                initial = h.sketch,
                initialColor = h.highlightColor,
                onSave = { sketch, color ->
                    store.updateSketch(h.id, sketch, color)
                    sketchingId = null
                },
                onDelete = {
                    deleteConfirmer.askHighlight(h.text) {
                        store.delete(h.id)
                        toast(context, R.string.sketch_deleted)
                        sketchingId = null
                    }
                },
                onDismiss = { sketchingId = null },
            )
        }
    }

    if (showList) {
        // Reading order in the text shown (each device counts offsets in its own text)
        val locator = userHighlights.locator
        val inOrder = remember(mine, locator) { mine.sortedBy { locator?.positionOf(it) ?: (Int.MAX_VALUE - 1) } }
        ArticleHighlightsSheet(
            highlights = inOrder,
            onEdit = {
                showList = false
                if (it.sketch.isNullOrBlank()) editing = it.id else sketchingId = it.id
            },
            onShare = { share = it.shareSubject() },
            onShareMany = { hs -> share = combinedSubject(hs) },
            onDelete = { h ->
                deleteConfirmer.askHighlight(h.text) {
                    store.delete(h.id)
                    toast(context, R.string.highlight_deleted)
                }
            },
            onDismiss = { showList = false },
        )
    }

    share?.let { ShareImageDialog(subject = it, onDismiss = { share = null }) }
}

private object HighlightMenuKey

private object SketchMenuKey

private data class MovedMenuKey(
    val key: Any,
)

/**
 * The selection toolbar shows only a few items before its overflow (⋮), in the order they come.
 * Other apps' text actions (Translate, Ask Gemini, Read aloud) and Select all come before items
 * added here, so with a few such apps installed Highlight lands in the overflow, out of sight.
 *
 * Items can't be reordered in place, and a menu's appends run before its filters. So the filter
 * removes those items and the next menu adds copies of them after Highlight. The first menu after
 * the app starts keeps the original order. A copy runs the item of the menu it is in.
 */
private object MovedMenuItems {
    private val latest = LinkedHashMap<Any, TextContextMenuItem>()
    private var seen = mutableSetOf<Any>()
    private var readded = emptySet<Any>()

    /** A menu is being made: the items to add after Highlight, as the last menu had them. */
    fun startMenu(): List<TextContextMenuItem> {
        if (seen.isNotEmpty()) latest.keys.retainAll(seen)
        seen = mutableSetOf()
        readded = latest.keys.toSet()
        return latest.values.toList()
    }

    /** True when [item] is one that was added after Highlight, so the original is left out. */
    fun filter(item: TextContextMenuItem): Boolean {
        val key = item.key
        if (key != TextContextMenuKeys.SelectAllKey && key !is ProcessTextKey) return false
        seen += key
        latest[key] = item
        return key in readded
    }

    fun current(key: Any): TextContextMenuItem? = latest[key]?.takeIf { key in seen }
}

/**
 * The real clipboard, except that the next copy after [catchNext] goes to its callback instead:
 * how the Highlight menu item gets the selected text.
 */
private class CatchingClipboard(
    private val real: Clipboard,
    private val context: Context,
) : Clipboard {
    @Volatile
    private var onCopy: ((String) -> Unit)? = null

    fun catchNext(
        scope: CoroutineScope,
        callback: (String) -> Unit,
    ) {
        onCopy = callback
        // Copy runs right away; never catch a later copy the reader makes
        scope.launch {
            delay(2_000)
            if (onCopy === callback) onCopy = null
        }
    }

    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val callback = onCopy
        if (callback == null) {
            real.setClipEntry(clipEntry)
            return
        }
        onCopy = null
        val text =
            clipEntry
                ?.clipData
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
        if (!text.isNullOrBlank()) callback(text)
    }

    override val nativeClipboard: NativeClipboard
        get() = real.nativeClipboard
}
