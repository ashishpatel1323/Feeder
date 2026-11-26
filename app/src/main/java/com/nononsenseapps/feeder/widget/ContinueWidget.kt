package com.nononsenseapps.feeder.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.MainActivity
import kotlinx.coroutines.flow.first

/**
 * Continue: the last playlist, audiobook, ebook and ZIM page as pills, each tinted from its
 * cover, with the cover and a badge for its type. 4×1 shows the newest, 4×2 adds the others as
 * chips, 4×4 stacks all of them. Play/pause and skips drive the players; anywhere else on a
 * pill opens it in the app.
 */
class ContinueWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        ContinueWidgetUpdater.start(context)
        val items = continueWidgetItems(context)
        val first = items.first()
        provideContent {
            val shown by items.collectAsState(initial = first)
            ContinueContent(shown)
        }
    }
}

@Composable
private fun ContinueContent(items: List<ContinueItem>) {
    val size = LocalSize.current
    val context = LocalContext.current
    if (items.isEmpty()) {
        Empty(context)
        return
    }
    val wide = size.width >= 260.dp
    val gap = 6.dp
    // How many full pills fit, alone or above a row of chips for the rest
    val fitAll = ((size.height + gap) / (PILL_HEIGHT + gap)).toInt()
    val fitWithChips = ((size.height - CHIP_HEIGHT) / (PILL_HEIGHT + gap)).toInt()
    when {
        // Short (4×1): the newest only
        fitAll <= 1 && fitWithChips < 1 ->
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Pill(items.first(), minOf(PILL_HEIGHT, size.height), wide)
            }
        // Room for every pill (4×4)
        items.size <= fitAll -> {
            val pill = minOf(PILL_HEIGHT + 8.dp, (size.height - gap * (items.size - 1)) / items.size)
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Spacer(GlanceModifier.height(gap))
                    Pill(item, pill, wide)
                }
            }
        }
        // 4×2: the newest as pills, the rest as chips
        else -> {
            val pills = fitWithChips.coerceIn(1, items.size - 1)
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                items.take(pills).forEachIndexed { i, item ->
                    if (i > 0) Spacer(GlanceModifier.height(gap))
                    Pill(item, PILL_HEIGHT, wide)
                }
                Spacer(GlanceModifier.height(gap))
                Row(GlanceModifier.fillMaxWidth()) {
                    items.drop(pills).forEachIndexed { i, item ->
                        if (i > 0) Spacer(GlanceModifier.width(gap))
                        Chip(item, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

private val PILL_HEIGHT = 68.dp
private val CHIP_HEIGHT = 40.dp

@Composable
private fun Pill(
    item: ContinueItem,
    height: Dp,
    wide: Boolean,
) {
    val t = item.tint
    val context = LocalContext.current
    val cover = (height - 14.dp).coerceIn(36.dp, 56.dp)
    Box(
        GlanceModifier
            .fillMaxWidth()
            .height(height)
            .background(t.background.provider())
            .cornerRadius(height / 2)
            .clickable(openAction(context, item)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            GlanceModifier.fillMaxWidth().padding(start = 7.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(item, cover, badge = (cover * 0.42f).coerceAtLeast(18.dp))
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    item.title,
                    maxLines = 1,
                    style = TextStyle(color = t.onBackground.provider(), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, maxLines = 1, style = TextStyle(color = t.onBackgroundSoft.provider(), fontSize = 12.sp))
                }
                item.progress?.let { p ->
                    Spacer(GlanceModifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = p,
                        modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                        color = t.button.provider(),
                        backgroundColor = t.track.provider(),
                    )
                }
            }
            Spacer(GlanceModifier.width(6.dp))
            val big = (height - 18.dp).coerceIn(36.dp, 50.dp)
            if (item.audio) {
                val small = (big - 12.dp).coerceAtLeast(30.dp)
                if (wide) {
                    RoundButton(Art.back10, R.string.continue_widget_back_10, small, t, seek(item, -10_000))
                    Spacer(GlanceModifier.width(4.dp))
                }
                CookieButton(
                    if (item.playing) Art.pause else Art.play,
                    if (item.playing) R.string.continue_widget_pause else R.string.continue_widget_play,
                    big,
                    t,
                    actionRunCallback<ContinuePlayPauseAction>(
                        actionParametersOf(ContinueKindKey to item.kind.name, ContinueIdKey to item.id, ContinueItemKey to item.itemKey),
                    ),
                )
                if (wide) {
                    Spacer(GlanceModifier.width(4.dp))
                    RoundButton(Art.forward30, R.string.continue_widget_forward_30, small, t, seek(item, 30_000))
                }
            } else {
                item.percent?.takeIf { wide }?.let { pct ->
                    Box(
                        GlanceModifier
                            .height(32.dp)
                            .background(t.tonal.provider())
                            .cornerRadius(16.dp)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(pct, style = TextStyle(color = t.onBackground.provider(), fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    }
                    Spacer(GlanceModifier.width(6.dp))
                }
                CookieButton(
                    if (item.kind == ContinueKind.EBOOK) Art.read else Art.open,
                    R.string.continue_widget_open,
                    big,
                    t,
                    openAction(context, item),
                )
            }
        }
    }
}

@Composable
private fun Chip(
    item: ContinueItem,
    modifier: GlanceModifier = GlanceModifier,
) {
    val t = item.tint
    Box(
        modifier
            .height(CHIP_HEIGHT)
            .background(t.background.provider())
            .cornerRadius(CHIP_HEIGHT / 2)
            .clickable(openAction(LocalContext.current, item)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(GlanceModifier.padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item, 32.dp, badge = 15.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(item.title, maxLines = 1, style = TextStyle(color = t.onBackground.provider(), fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** The cover (or a plain shape with the type's icon) and the type badge in its corner. */
@Composable
private fun Cover(
    item: ContinueItem,
    size: Dp,
    badge: Dp,
) {
    val t = item.tint
    val context = LocalContext.current
    Box(GlanceModifier.size(size + 3.dp)) {
        Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
            val cover = item.cover
            if (cover != null) {
                Image(ImageProvider(cover), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
            } else {
                Image(ImageProvider(Art.cookie), null, GlanceModifier.fillMaxSize(), colorFilter = ColorFilter.tint(t.button.provider()))
                Image(ImageProvider(Art.kind(item.kind)), null, GlanceModifier.size(size * 0.45f), colorFilter = ColorFilter.tint(t.onButton.provider()))
            }
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            // A ring in the pill's color sets the badge off the cover
            Box(
                GlanceModifier.size(badge).background(t.background.provider()).cornerRadius(badge / 2),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    GlanceModifier
                        .size(badge - 4.dp)
                        .background(t.button.provider())
                        .cornerRadius((badge - 4.dp) / 2)
                        .semantics { contentDescription = context.getString(kindName(item.kind)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(Art.kind(item.kind)),
                        contentDescription = null,
                        modifier = GlanceModifier.size((badge - 4.dp) * 0.62f),
                        colorFilter = ColorFilter.tint(t.onButton.provider()),
                    )
                }
            }
        }
    }
}

@Composable
private fun CookieButton(
    icon: Bitmap,
    label: Int,
    size: Dp,
    t: ContinueTint,
    action: Action,
) {
    val context = LocalContext.current
    Box(
        GlanceModifier.size(size).clickable(action).semantics { contentDescription = context.getString(label) },
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(Art.cookie), null, GlanceModifier.fillMaxSize(), colorFilter = ColorFilter.tint(t.button.provider()))
        Image(ImageProvider(icon), null, GlanceModifier.size(size * 0.52f), colorFilter = ColorFilter.tint(t.onButton.provider()))
    }
}

@Composable
private fun RoundButton(
    icon: Bitmap,
    label: Int,
    size: Dp,
    t: ContinueTint,
    action: Action,
) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .size(size)
            .background(t.tonal.provider())
            .cornerRadius(size / 2)
            .clickable(action)
            .semantics { contentDescription = context.getString(label) },
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), null, GlanceModifier.size(size * 0.6f), colorFilter = ColorFilter.tint(t.onBackground.provider()))
    }
}

@Composable
private fun Empty(context: Context) {
    val bg = ColorProvider(day = Color(0xFFEDEDF3), night = Color(0xFF2A2A30))
    val fg = ColorProvider(day = Color(0xFF3A3A44), night = Color(0xFFDADAE2))
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            GlanceModifier
                .fillMaxWidth()
                .height(PILL_HEIGHT)
                .background(bg)
                .cornerRadius(PILL_HEIGHT / 2)
                .padding(horizontal = 20.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(context.getString(R.string.continue_widget_empty), maxLines = 2, style = TextStyle(color = fg, fontSize = 13.sp))
        }
    }
}

private fun openAction(
    context: Context,
    item: ContinueItem,
): Action =
    actionStartActivity(
        Intent(Intent.ACTION_VIEW, item.uri.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

private fun seek(
    item: ContinueItem,
    ms: Long,
): Action = actionRunCallback<ContinueSeekAction>(actionParametersOf(ContinueKindKey to item.kind.name, ContinueIdKey to item.id, ContinueSeekKey to ms))

private fun kindName(kind: ContinueKind): Int =
    when (kind) {
        ContinueKind.PLAYLIST -> R.string.continue_widget_playlist
        ContinueKind.AUDIOBOOK -> R.string.continue_widget_audiobook
        ContinueKind.EBOOK -> R.string.continue_widget_ebook
        ContinueKind.ZIM -> R.string.continue_widget_zim
    }

private fun Pair<Int, Int>.provider(): ColorProvider = ColorProvider(day = Color(first), night = Color(second))

/** The white pictures, drawn once. */
private object Art {
    private const val ICON_PX = 72
    val cookie: Bitmap by lazy { ContinueWidgetArt.cookieMask(144) }
    val play by lazy { ContinueWidgetArt.icon(Icons.Filled.PlayArrow, ICON_PX) }
    val pause by lazy { ContinueWidgetArt.icon(Icons.Filled.Pause, ICON_PX) }
    val back10 by lazy { ContinueWidgetArt.icon(Icons.Filled.Replay10, ICON_PX) }
    val forward30 by lazy { ContinueWidgetArt.icon(Icons.Filled.Forward30, ICON_PX) }
    val read by lazy { ContinueWidgetArt.icon(Icons.Filled.AutoStories, ICON_PX) }
    val open by lazy { ContinueWidgetArt.icon(Icons.AutoMirrored.Filled.OpenInNew, ICON_PX) }
    private val playlist by lazy { ContinueWidgetArt.icon(Icons.AutoMirrored.Filled.QueueMusic, ICON_PX) }
    private val audiobook by lazy { ContinueWidgetArt.icon(Icons.Filled.Headphones, ICON_PX) }
    private val ebook by lazy { ContinueWidgetArt.icon(Icons.AutoMirrored.Filled.MenuBook, ICON_PX) }
    private val zim by lazy { ContinueWidgetArt.icon(Icons.Filled.Public, ICON_PX) }

    fun kind(kind: ContinueKind): Bitmap =
        when (kind) {
            ContinueKind.PLAYLIST -> playlist
            ContinueKind.AUDIOBOOK -> audiobook
            ContinueKind.EBOOK -> ebook
            ContinueKind.ZIM -> zim
        }
}
