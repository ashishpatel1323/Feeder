package com.nononsenseapps.feeder.ui.compose.highlights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
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
import com.nononsenseapps.feeder.ui.compose.utils.FeederTextToolbar
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
    content: @Composable (ArticleHighlightsState?) -> Unit,
) {
    val di = LocalDI.current
    val store: HighlightStore by di.instance()
    val dao: Sync2Dao by di.instance()
    val context = LocalContext.current
    val itemKey by produceState<String?>(null, articleId) {
        value = if (articleId > 0) dao.getItemKeys(listOf(articleId)).firstOrNull()?.let { "${it.feedUrl}\n${it.guid}" } else null
    }
    val all by store.highlights.collectAsStateWithLifecycle()
    val mine = remember(all, itemKey) { all.filter { it.itemKey == itemKey }.sortedBy { it.start } }

    var pending by remember { mutableStateOf<Pair<String, HighlightAnchor?>?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf<ShareSubject?>(null) }

    val userHighlights = remember(mine) { UserHighlights(mine) { editing = it.id } }
    val currentUser by rememberUpdatedState(userHighlights)

    // Highlight in the text selection menu, for this article only
    val toolbar = LocalTextToolbar.current as? FeederTextToolbar
    val label = stringResource(R.string.highlight_action)
    DisposableEffect(toolbar, itemKey, label) {
        if (toolbar != null && itemKey != null) {
            toolbar.extraAction = label to { text: String -> pending = text to currentUser.locator?.anchorOf(text) }
        }
        onDispose { toolbar?.extraAction = null }
    }

    val article =
        itemKey?.let { HighlightArticle(itemKey = it, title = title, link = link, feedTitle = feedTitle, imageUrl = imageUrl) }

    CompositionLocalProvider(LocalUserHighlights provides userHighlights) {
        content(if (itemKey != null) ArticleHighlightsState(mine.size) { showList = true } else null)
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
                    store.delete(h.id)
                    toast(context, R.string.highlight_deleted)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }

    if (showList) {
        ArticleHighlightsSheet(
            highlights = mine,
            onEdit = {
                showList = false
                editing = it.id
            },
            onShare = { share = it.shareSubject() },
            onShareMany = { hs -> share = combinedSubject(hs) },
            onDelete = {
                store.delete(it.id)
                toast(context, R.string.highlight_deleted)
            },
            onDismiss = { showList = false },
        )
    }

    share?.let { ShareImageDialog(subject = it, onDismiss = { share = null }) }
}
