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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R

/**
 * The shared shelf of the Ebooks and ZIM tabs (same as the web app's Shelf.tsx): what is on this
 * phone, ready and offline, and what is only in Dropbox, as cards or a list.
 */

/** BOTH = on this phone and in Dropbox; DEVICE = only here; CLOUD = only in Dropbox */
enum class Where { BOTH, DEVICE, CLOUD }

enum class Show { ALL, DEVICE, CLOUD }

enum class CoverShape { BOOK, TILE }

class ShelfAction(
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
    /** [cover] is a picture of the whole page (a ZIM's landing page), so it fills the tile */
    val coverFills: Boolean = false,
    val fallback: ImageVector,
    val where: Where,
    val size: Long,
    /** Downloading or uploading right now, e.g. "Downloading 40%" */
    val busy: String? = null,
    val onOpen: () -> Unit,
    val actions: List<ShelfAction> = emptyList(),
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

    fun updateCards(c: Boolean) {
        cards = c
        prefs.edit { putBoolean("$tab.cards", c) }
    }

    val columns: GridCells get() = if (cards) GridCells.Adaptive(112.dp) else GridCells.Fixed(1)
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
            where == Where.BOTH -> Triple(Icons.Outlined.CheckCircle, stringResource(R.string.shelf_downloaded), if (dark) Color(0xFF1F4A33) to Color(0xFFB9EFCF) else Color(0xFFD7F2E1) to Color(0xFF135C36))
            where == Where.DEVICE -> Triple(Icons.Outlined.CheckCircle, stringResource(R.string.shelf_only_here), if (dark) Color(0xFF4D3A12) to Color(0xFFF7DCA3) else Color(0xFFFDECC8) to Color(0xFF6B4500))
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
    all: Int,
    device: Int,
    cloud: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    shape: CoverShape,
    emptyDevice: String,
) {
    val onPhone = items.filter { it.where != Where.CLOUD }
    val cloud = items.filter { it.where == Where.CLOUD }
    if (prefs.show != Show.CLOUD && onPhone.isNotEmpty()) {
        if (cloud.isNotEmpty()) {
            item(key = "head:phone", span = { GridItemSpan(maxLineSpan) }) { SectionHead(Icons.Outlined.CheckCircle, R.string.shelf_on_phone, R.string.shelf_on_phone_hint) }
        }
        items(onPhone, key = { it.key }) { ShelfEntry(it, prefs.cards, shape) }
    }
    if (prefs.show == Show.DEVICE && onPhone.isEmpty()) {
        item(key = "empty:phone", span = { GridItemSpan(maxLineSpan) }) {
            Text(emptyDevice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
    if (prefs.show != Show.DEVICE && cloud.isNotEmpty()) {
        item(key = "head:cloud", span = { GridItemSpan(maxLineSpan) }) { SectionHead(Icons.Outlined.Cloud, R.string.in_dropbox, R.string.shelf_in_dropbox_hint) }
        items(cloud, key = { it.key }) { ShelfEntry(it, prefs.cards, shape) }
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
    shape: CoverShape,
) {
    var menu by remember { mutableStateOf(false) }
    val more: @Composable () -> Unit = {
        if (item.actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = item.title,
                        modifier = if (cards) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(2.dp) else Modifier,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
    val dim = if (item.where == Where.CLOUD) Modifier.alpha(0.72f) else Modifier
    if (cards) {
        Column(Modifier.clickable(onClick = item.onOpen)) {
            Box {
                Cover(item, shape, small = false, modifier = dim)
                Availability(item.where, item.size, item.busy, Modifier.align(Alignment.BottomStart).padding(6.dp))
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
                }
            },
            leadingContent = { Cover(item, shape, small = true, modifier = dim) },
            trailingContent = { more() },
            modifier = Modifier.clickable(onClick = item.onOpen),
        )
    }
}

@Composable
private fun Cover(
    item: ShelfItem,
    shape: CoverShape,
    small: Boolean,
    modifier: Modifier = Modifier,
) {
    val picture: @Composable (Modifier) -> Unit = { m ->
        when {
            item.cover != null -> Image(item.cover, contentDescription = null, contentScale = if (shape == CoverShape.BOOK) ContentScale.Crop else ContentScale.Fit, modifier = m)
            item.coverUrl != null -> AsyncImage(model = item.coverUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = m)
            else -> Icon(item.fallback, contentDescription = null, modifier = m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (shape == CoverShape.BOOK) {
        val m = if (small) modifier.size(40.dp, 60.dp) else modifier.fillMaxWidth().aspectRatio(2f / 3f)
        if (item.cover != null) {
            picture(m.clip(RoundedCornerShape(if (small) 4.dp else 6.dp)))
        } else {
            Box(
                m.clip(RoundedCornerShape(if (small) 4.dp else 6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(if (small) 8.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (small) {
                    picture(Modifier.size(20.dp))
                } else {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        val m = if (small) modifier.size(44.dp) else modifier.fillMaxWidth().aspectRatio(4f / 3f)
        Box(m.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (item.cover != null && item.coverFills) {
                Image(item.cover, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            } else {
                picture(Modifier.size(if (small) 32.dp else 56.dp).clip(RoundedCornerShape(if (small) 6.dp else 8.dp)))
            }
        }
    }
}
