package com.nononsenseapps.feeder.ui.compose.shelf

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.shelf.BookStatus
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/**
 * The shared shelf of the Audiobooks, Ebooks and ZIM tabs (same as the web app's Shelf.tsx): what is
 * on this phone, ready and offline, and what is only in Dropbox, as cards or a list. The whole screen
 * around it (top bar, jobs, empty state) is [LibraryListingScreen], so the three tabs look the same.
 *
 * [Where]: BOTH = on this phone and in Dropbox; DEVICE = only here; CLOUD = only in Dropbox.
 */
enum class Where { BOTH, DEVICE, CLOUD }

enum class Show { ALL, DEVICE, CLOUD }

/**
 * How a picture sits in the square cover every tab uses: FILL crops it to the square (audiobook art,
 * a ZIM's landing page), FIT shows all of it (a book cover), ICON shows it small in the middle (a
 * ZIM's site icon).
 */
enum class CoverFit { FILL, FIT, ICON }

class ShelfAction(
    val label: String,
    val run: () -> Unit,
)

class ShelfQuick(
    val icon: ImageVector,
    val label: String,
    val run: () -> Unit,
)

class ShelfItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val detail: String? = null,
    /** A picture from the phone (a book cover, a ZIM's own icon) */
    val cover: ImageBitmap? = null,
    /** A picture to load (the Kiwix catalog's icon for a ZIM only in Dropbox) */
    val coverUrl: String? = null,
    val coverFit: CoverFit = CoverFit.FILL,
    val fallback: ImageVector,
    val where: Where,
    val size: Long,
    /** Downloading or uploading right now, e.g. "Downloading 40%" */
    val busy: String? = null,
    /** 0–1 through it (listened), drawn as a bar along the cover */
    val progress: Float? = null,
    /** The main action right on the cover: an audiobook's Play, a book's Open */
    val quick: ShelfQuick? = null,
    val onOpen: () -> Unit,
    val actions: List<ShelfAction> = emptyList(),
    /** Current, Not started yet or Completed: the shelf groups by it when items have one */
    val status: BookStatus? = null,
    /** Order inside its group, newest first (last read or played, added, finished) */
    val sortAt: Long = 0,
    /** Picks the status by hand, from the item's ⋮ menu */
    val onStatus: ((BookStatus) -> Unit)? = null,
)

/** What to show and how, remembered per tab ("ebooks", "zim"). */
@Stable
class ShelfPrefs(
    private val context: Context,
    private val tab: String,
    defaultCards: Boolean,
) {
    private val prefs get() = context.getSharedPreferences("shelf", Context.MODE_PRIVATE)
    var show by mutableStateOf(runCatching { Show.valueOf(prefs.getString("$tab.show", null) ?: "ALL") }.getOrDefault(Show.ALL))
        private set
    var cards by mutableStateOf(prefs.getBoolean("$tab.cards", defaultCards))
        private set

    fun updateShow(s: Show) {
        show = s
        prefs.edit { putString("$tab.show", s.name) }
    }

    /** Whether the Completed group is open (it starts closed, so finished books don't push the rest down) */
    var doneOpen by mutableStateOf(prefs.getBoolean("$tab.doneOpen", false))
        private set

    fun updateDoneOpen(open: Boolean) {
        doneOpen = open
        prefs.edit { putBoolean("$tab.doneOpen", open) }
    }

    fun updateCards(c: Boolean) {
        cards = c
        prefs.edit { putBoolean("$tab.cards", c) }
    }

    val columns: GridCells get() = if (cards) GridCells.Adaptive(150.dp) else GridCells.Fixed(1)
}

@Composable
fun rememberShelfPrefs(
    tab: String,
    defaultCards: Boolean,
): ShelfPrefs {
    val context = LocalContext.current
    return remember(tab) { ShelfPrefs(context.applicationContext, tab, defaultCards) }
}

/** Where an item is, in words and an icon. */
@Composable
fun Availability(
    where: Where,
    size: Long,
    busy: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (icon, text, colors) =
        when {
            busy != null -> Triple(Icons.Outlined.Download, busy, if (dark) Color(0xFF1D3657) to Color(0xFFC4DBFB) else Color(0xFFDBE8FB) to Color(0xFF1C4A8A))
            where == Where.BOTH ->
                Triple(
                    Icons.Outlined.CheckCircle,
                    stringResource(R.string.shelf_downloaded),
                    if (dark) {
                        Color(0xFF1F4A33) to Color(0xFFB9EFCF)
                    } else {
                        Color(0xFFD7F2E1) to
                            Color(0xFF135C36)
                    },
                )
            where == Where.DEVICE ->
                Triple(
                    Icons.Outlined.CheckCircle,
                    stringResource(R.string.shelf_only_here),
                    if (dark) {
                        Color(0xFF4D3A12) to Color(0xFFF7DCA3)
                    } else {
                        Color(0xFFFDECC8) to
                            Color(0xFF6B4500)
                    },
                )
            else ->
                Triple(
                    Icons.Outlined.Cloud,
                    stringResource(R.string.shelf_in_dropbox_size, Formatter.formatShortFileSize(context, size)),
                    if (dark) Color(0xFF1D3657) to Color(0xFFC4DBFB) else Color(0xFFDBE8FB) to Color(0xFF1C4A8A),
                )
        }
    Surface(modifier = modifier, color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Filter chips (All / On this phone / In Dropbox) and the Cards | List switch. */
@Composable
fun ShelfControls(
    prefs: ShelfPrefs,
    items: List<ShelfItem>,
    modifier: Modifier = Modifier,
    /** The Cards | List switch; Books has it in its ⋮ menu instead */
    showLayout: Boolean = true,
) {
    val all = items.size
    val cloud = items.count { it.where == Where.CLOUD }
    val device = all - cloud
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLayout) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = prefs.cards,
                    onClick = { prefs.updateCards(true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { SegmentedButtonDefaults.Icon(prefs.cards) { Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp)) } },
                    label = { Text(stringResource(R.string.shelf_cards)) },
                )
                SegmentedButton(
                    selected = !prefs.cards,
                    onClick = { prefs.updateCards(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { SegmentedButtonDefaults.Icon(!prefs.cards) { Icon(Icons.Outlined.ViewAgenda, null, Modifier.size(18.dp)) } },
                    label = { Text(stringResource(R.string.shelf_list)) },
                )
            }
        }
        if (cloud > 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = prefs.show == Show.ALL, onClick = { prefs.updateShow(Show.ALL) }, label = { Text(stringResource(R.string.shelf_all, all)) })
                FilterChip(
                    selected = prefs.show == Show.DEVICE,
                    onClick = { prefs.updateShow(Show.DEVICE) },
                    label = { Text(stringResource(R.string.shelf_on_phone_count, device)) },
                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = prefs.show == Show.CLOUD,
                    onClick = { prefs.updateShow(Show.CLOUD) },
                    label = { Text(stringResource(R.string.shelf_in_dropbox_count, cloud)) },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** The "On this phone" and "In Dropbox" sections, as cards or rows, inside a LazyVerticalGrid using [ShelfPrefs.columns]. */
fun LazyGridScope.shelf(
    items: List<ShelfItem>,
    prefs: ShelfPrefs,
    emptyDevice: String,
    /** What is in Dropbox plays from there right away (audiobooks), so it isn't dimmed */
    streams: Boolean = false,
) {
    if (items.any { it.status != null }) {
        statusShelf(items, prefs, emptyDevice, streams)
        return
    }
    val onPhone = items.filter { it.where != Where.CLOUD }
    val cloud = items.filter { it.where == Where.CLOUD }
    // The chips are hidden while nothing is only in Dropbox: a filter chosen before (say In Dropbox,
    // then its last book was deleted) must not keep hiding everything with no chip to undo it
    val show = if (cloud.isEmpty()) Show.ALL else prefs.show
    if (show != Show.CLOUD && onPhone.isNotEmpty()) {
        if (cloud.isNotEmpty()) {
            item(key = "head:phone", span = {
                GridItemSpan(maxLineSpan)
            }) { SectionHead(Icons.Outlined.CheckCircle, R.string.shelf_on_phone, if (streams) R.string.shelf_on_phone_hint_play else R.string.shelf_on_phone_hint) }
        }
        items(onPhone, key = { it.key }) { ShelfEntry(it, prefs.cards, streams) }
    }
    if (show == Show.DEVICE && onPhone.isEmpty()) {
        item(key = "empty:phone", span = { GridItemSpan(maxLineSpan) }) {
            Text(emptyDevice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
    if (show != Show.DEVICE && cloud.isNotEmpty()) {
        item(
            key = "head:cloud",
            span = { GridItemSpan(maxLineSpan) },
        ) { SectionHead(Icons.Outlined.Cloud, R.string.in_dropbox, if (streams) R.string.shelf_streams_hint else R.string.shelf_in_dropbox_hint) }
        items(cloud, key = { it.key }) { ShelfEntry(it, prefs.cards, streams) }
    }
}

/**
 * The shelf grouped like the Voice app: Current, Not started yet, Completed (closed until tapped).
 * Where a book is (phone or Dropbox) is the chips' filter and each cover's badge.
 */
private fun LazyGridScope.statusShelf(
    items: List<ShelfItem>,
    prefs: ShelfPrefs,
    emptyDevice: String,
    streams: Boolean,
) {
    val anyCloud = items.any { it.where == Where.CLOUD }
    val show = if (!anyCloud) Show.ALL else prefs.show
    val shown =
        when (show) {
            Show.ALL -> items
            Show.DEVICE -> items.filter { it.where != Where.CLOUD }
            Show.CLOUD -> items.filter { it.where == Where.CLOUD }
        }
    if (shown.isEmpty() && show == Show.DEVICE) {
        item(key = "empty:phone", span = { GridItemSpan(maxLineSpan) }) {
            Text(emptyDevice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        return
    }
    BookStatus.entries.forEach { status ->
        val group = shown.filter { (it.status ?: BookStatus.NEW) == status }.sortedByDescending { it.sortAt }
        if (group.isEmpty()) return@forEach
        val open = status != BookStatus.DONE || prefs.doneOpen
        item(key = "head:${status.code}", span = { GridItemSpan(maxLineSpan) }) {
            StatusHead(status, group.size, collapsible = status == BookStatus.DONE, open = open) { prefs.updateDoneOpen(!prefs.doneOpen) }
        }
        if (open) items(group, key = { it.key }) { ShelfEntry(it, prefs.cards, streams) }
    }
}

fun BookStatus.label(): Int =
    when (this) {
        BookStatus.CURRENT -> R.string.shelf_status_current
        BookStatus.NEW -> R.string.shelf_status_new
        BookStatus.DONE -> R.string.shelf_status_done
    }

fun BookStatus.icon(): ImageVector =
    when (this) {
        BookStatus.CURRENT -> Icons.Outlined.AutoStories
        BookStatus.NEW -> Icons.Outlined.HourglassEmpty
        BookStatus.DONE -> Icons.Outlined.Check
    }

/** "Current  2", as in Voice: a title and a count, Completed with an arrow to open or close it. */
@Composable
private fun StatusHead(
    status: BookStatus,
    count: Int,
    collapsible: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(status.label())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (collapsible) Modifier.clickable(onClickLabel = label, onClick = onToggle) else Modifier)
                .padding(top = 16.dp, bottom = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
        if (collapsible) {
            Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
    }
}

/** Not started yet · Current · Completed, as three toggle tiles at the top of a book's ⋮ menu (as in Voice). */
@Composable
private fun StatusPicker(
    current: BookStatus,
    onPick: (BookStatus) -> Unit,
) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(BookStatus.NEW, BookStatus.CURRENT, BookStatus.DONE).forEach { st ->
            val on = st == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .width(84.dp)
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onPick(st) }
                        .semantics { selected = on }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Icon(st.icon(), contentDescription = null, tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(st.label()),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHead(
    icon: ImageVector,
    title: Int,
    hint: Int,
) {
    Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShelfEntry(
    item: ShelfItem,
    cards: Boolean,
    streams: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val more: @Composable () -> Unit = {
        if (item.actions.isNotEmpty() || item.onStatus != null) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = item.title,
                        modifier = if (cards) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(2.dp) else Modifier,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    item.onStatus?.let { pick ->
                        StatusPicker(item.status ?: BookStatus.NEW) {
                            menu = false
                            pick(it)
                        }
                        if (item.actions.isNotEmpty()) HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                    item.actions.forEach { a ->
                        DropdownMenuItem(text = { Text(a.label) }, onClick = {
                            menu = false
                            a.run()
                        })
                    }
                }
            }
        }
    }
    val quick: @Composable (Boolean) -> Unit = { onCover ->
        item.quick?.let { q ->
            if (onCover) {
                IconButton(onClick = q.run, modifier = Modifier.size(40.dp)) {
                    Icon(
                        q.icon,
                        contentDescription = q.label,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(50)).padding(6.dp),
                    )
                }
            } else {
                FilledTonalIconButton(onClick = q.run, modifier = Modifier.size(40.dp)) { Icon(q.icon, contentDescription = q.label) }
            }
        }
    }
    val progress = item.progress?.takeIf { it > 0f && it < 0.999f }
    val dim = if (item.where == Where.CLOUD && !streams) Modifier.alpha(0.72f) else Modifier
    if (cards) {
        Column(Modifier.clickable(onClick = item.onOpen)) {
            Box {
                Cover(item, small = false, modifier = dim)
                Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    Availability(item.where, item.size, item.busy)
                    if (progress != null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
                Box(Modifier.align(Alignment.TopStart).padding(2.dp)) { quick(true) }
                Box(Modifier.align(Alignment.TopEnd)) { more() }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
    } else {
        ListItem(
            headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Availability(item.where, item.size, item.busy)
                        item.detail?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f), gapSize = 0.dp, drawStopIndicator = {})
                }
            },
            leadingContent = { Cover(item, small = true, modifier = dim) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    quick(false)
                    more()
                }
            },
            modifier = Modifier.clickable(onClick = item.onOpen),
        )
    }
}

/** The same square cover on every tab: the picture as [ShelfItem.coverFit] says, or one made from the title. */
@Composable
private fun Cover(
    item: ShelfItem,
    small: Boolean,
    modifier: Modifier = Modifier,
) {
    val corner = if (small) FeederTokens.Radius.sm else FeederTokens.Radius.md
    val m = (if (small) modifier.size(48.dp) else modifier.fillMaxWidth()).aspectRatio(1f).clip(RoundedCornerShape(corner))
    if (item.cover == null && item.coverUrl == null) {
        GeneratedCover(item.title, item.fallback, small, m)
        return
    }
    val picture: @Composable (Modifier, ContentScale) -> Unit = { pm, scale ->
        if (item.cover != null) {
            Image(item.cover, contentDescription = null, contentScale = scale, alignment = Alignment.TopCenter, modifier = pm)
        } else {
            AsyncImage(model = item.coverUrl, contentDescription = null, contentScale = scale, alignment = Alignment.TopCenter, modifier = pm)
        }
    }
    Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when (item.coverFit) {
            CoverFit.FILL -> picture(Modifier.fillMaxSize(), ContentScale.Crop)
            CoverFit.FIT -> picture(Modifier.fillMaxSize().padding(if (small) 4.dp else FeederTokens.Space.md), ContentScale.Fit)
            CoverFit.ICON ->
                picture(
                    Modifier.size(if (small) 32.dp else 64.dp).clip(RoundedCornerShape(if (small) 6.dp else FeederTokens.Radius.sm)),
                    ContentScale.Fit,
                )
        }
    }
}

/** A cover made from the title when there is no picture: the same colours for the same title. */
@Composable
fun GeneratedCover(
    title: String,
    icon: ImageVector,
    small: Boolean,
    modifier: Modifier = Modifier,
) {
    val hue = (title.fold(0L) { h, c -> (h * 31 + c.code) and 0xffffffffL } % 360).toFloat()
    val brush =
        Brush.linearGradient(
            listOf(Color.hsl(hue, 0.42f, 0.46f), Color.hsl((hue + 40f) % 360f, 0.46f, 0.26f)),
        )
    Box(modifier.background(brush).padding(if (small) 0.dp else 12.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 22.dp else 28.dp))
            if (!small) {
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
