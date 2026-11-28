package com.nononsenseapps.feeder.ui.compose.highlights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.SKETCH_W
import com.nononsenseapps.feeder.model.highlights.SketchCodec
import com.nononsenseapps.feeder.model.highlights.SketchStroke
import com.nononsenseapps.feeder.model.highlights.sketchInk
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlin.math.hypot

private const val BASE_W = 3.2f

@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The paper behind sketches: warm white, or a dark warm grey in the dark theme (same as the web app). */
@Composable
fun sketchPaper(): Color = if (isDarkSurface()) Color(0xFF2A2B24) else Color(0xFFFFFDF6)

/** A margin sketch as a strip after its paragraph. Tapping it opens it to edit or delete. */
@Composable
fun SketchStrip(
    highlight: Highlight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = remember(highlight.sketch) { SketchCodec.parse(highlight.sketch) } ?: return
    val dark = isDarkSurface()
    val edge = Color(highlight.highlightColor.sketchInk(dark))
    val label = stringResource(R.string.sketch)
    Row(
        modifier =
            modifier
                .padding(top = FeederTokens.Space.sm, bottom = FeederTokens.Space.md)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(sketchPaper())
                .clickable(onClickLabel = stringResource(R.string.sketch_edit), onClick = onClick)
                .semantics { contentDescription = label }
                .drawBehind { drawRect(edge, size = Size(3.dp.toPx(), size.height)) },
    ) {
        Canvas(
            Modifier
                .padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.xs)
                .fillMaxWidth()
                .aspectRatio(SKETCH_W / sketch.h),
        ) {
            drawStrokes(sketch.strokes, dark)
        }
    }
}

private fun DrawScope.drawStrokes(
    strokes: List<SketchStroke>,
    dark: Boolean,
) {
    val k = size.width / SKETCH_W
    for (st in strokes) {
        drawPath(
            path = strokePath(st.p, k),
            color = Color(st.color.sketchInk(dark)),
            style = Stroke(width = st.w * k, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** A path through the points, smoothed with quadratic curves between midpoints (as on the web). */
private fun strokePath(
    p: FloatArray,
    k: Float,
): Path {
    val path = Path()
    if (p.size < 2) return path
    path.moveTo(p[0] * k, p[1] * k)
    if (p.size < 4) {
        path.lineTo(p[0] * k + 0.01f, p[1] * k)
        return path
    }
    var i = 2
    while (i < p.size - 2) {
        val mx = (p[i] + p[i + 2]) / 2
        val my = (p[i + 1] + p[i + 3]) / 2
        path.quadraticTo(p[i] * k, p[i + 1] * k, mx * k, my * k)
        i += 2
    }
    path.lineTo(p[p.size - 2] * k, p[p.size - 1] * k)
    return path
}

private val EraserIcon: ImageVector by lazy {
    ImageVector
        .Builder(name = "Eraser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData =
                addPathNodes(
                    "M16.24 3.56l4.95 4.94c.78.79.78 2.05 0 2.84L12 20.53a4.008 4.008 0 0 1-5.66 0L2.81 17c-.78-.79-.78-2.05 0-2.84l10.6-10.6c.79-.78 2.05-.78 2.83 0M4.22 15.58l3.54 3.53c.78.79 2.04.79 2.83 0l3.53-3.53-4.95-4.95-4.95 4.95Z",
                ),
            fill = SolidColor(Color.Black),
        ).build()
}

/**
 * Full-screen canvas for drawing a margin sketch: the passage on top, paper, and a pen bar (pen,
 * eraser, the four colors, undo). A stylus draws with pressure; its eraser end erases; fingers draw too.
 * Coordinates are kept in 1/1000 of the canvas width, so the sketch fits any screen (see Sketch.kt).
 */
@Composable
fun SketchEditorDialog(
    quote: String,
    initial: String?,
    initialColor: HighlightColor,
    onSave: (sketch: String, color: HighlightColor) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val strokes = remember(initial) { mutableStateListOf<SketchStroke>().apply { SketchCodec.parse(initial)?.strokes?.let { addAll(it) } } }
    var color by remember { mutableStateOf(initialColor) }
    var erasing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<SketchStroke?>(null) }
    val dark = isDarkSurface()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                modifier = Modifier.safeDrawingPadding().padding(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(android.R.string.cancel)) }
                    Text(stringResource(R.string.sketch), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (onDelete != null) {
                        TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Spacer(Modifier.width(FeederTokens.Space.xs))
                            Text(stringResource(R.string.sketch_delete))
                        }
                    }
                    Button(
                        onClick = {
                            if (strokes.isEmpty()) {
                                if (onDelete != null) onDelete() else onDismiss()
                            } else {
                                onSave(SketchCodec.encode(strokes.toList()), strokes.last().color)
                            }
                        },
                        enabled = strokes.isNotEmpty() || onDelete != null,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.xs))
                        Text(stringResource(R.string.sketch_save))
                    }
                }
                Text(
                    quote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = FeederTokens.Space.sm),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(sketchPaper())
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val k = SKETCH_W / size.width
                                val erase =
                                    erasing || down.type == PointerType.Eraser

                                fun eraseAt(o: Offset) {
                                    val x = o.x * k
                                    val y = o.y * k
                                    strokes.removeAll { st ->
                                        var hit = false
                                        var i = 0
                                        while (i < st.p.size - 1) {
                                            if (hypot(st.p[i] - x, st.p[i + 1] - y) < 14 + st.w) {
                                                hit = true
                                                break
                                            }
                                            i += 2
                                        }
                                        hit
                                    }
                                }
                                val stylus = down.type == PointerType.Stylus

                                fun width(pressure: Float) = if (stylus && pressure > 0f) BASE_W * (0.45f + pressure * 1.1f) else BASE_W
                                down.consume()
                                if (erase) {
                                    eraseAt(down.position)
                                } else {
                                    live = SketchStroke(color, width(down.pressure), floatArrayOf(down.position.x * k, down.position.y * k))
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val points = change.historical.map { it.position } + change.position
                                    if (erase) {
                                        points.forEach(::eraseAt)
                                    } else {
                                        live?.let { st ->
                                            val list = st.p.toMutableList()
                                            for (pt in points) {
                                                val x = pt.x * k
                                                val y = pt.y * k
                                                if (hypot(x - list[list.size - 2], y - list[list.size - 1]) >= 1.5f) {
                                                    list += x
                                                    list += y
                                                }
                                            }
                                            val w = if (stylus) st.w * 0.85f + width(change.pressure) * 0.15f else st.w
                                            live = st.copy(p = list.toFloatArray(), w = w)
                                        }
                                    }
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                live?.let { strokes += it }
                                live = null
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawStrokes(strokes, dark)
                        live?.let { drawStrokes(listOf(it), dark) }
                    }
                    if (strokes.isEmpty() && live == null) {
                        Text(
                            stringResource(R.string.sketch_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                // Pen bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.xs),
                    ) {
                        FilledTonalIconToggleButton(checked = !erasing, onCheckedChange = { erasing = false }) {
                            Icon(Icons.Outlined.Draw, contentDescription = stringResource(R.string.sketch_pen))
                        }
                        FilledTonalIconToggleButton(checked = erasing, onCheckedChange = { erasing = true }) {
                            Icon(EraserIcon, contentDescription = stringResource(R.string.sketch_eraser))
                        }
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        for (c in HighlightColor.entries) {
                            val selected = c == color
                            Box(
                                Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(30.dp)
                                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(4.dp) else Modifier)
                                    .clip(CircleShape)
                                    .background(Color(c.sketchInk(dark)))
                                    .clickable(onClickLabel = c.id) {
                                        color = c
                                        erasing = false
                                    },
                            )
                        }
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(R.string.sketch_undo))
                        }
                    }
                }
            }
        }
    }
}

/** Pen color, eraser and Draw on page, for writing on a page (Kobo style). */
class PenState(
    initialColor: HighlightColor,
) {
    var color by mutableStateOf(initialColor)
    var erasing by mutableStateOf(false)
    var drawAll by mutableStateOf(false)

    /** For the page scripts (web/src/lib/pageink.ts). */
    fun json(): String = """{"drawAll":$drawAll,"color":"${color.id}","erasing":$erasing}"""
}

@Composable
fun rememberPenState(): PenState = remember { PenState(HighlightColor.PINK) }

/** The floating pen bar shown while Draw on page is on. */
@Composable
fun PenBar(
    pen: PenState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkSurface()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.xs)) {
            FilledTonalIconToggleButton(checked = !pen.erasing, onCheckedChange = { pen.erasing = false }) {
                Icon(Icons.Outlined.Draw, contentDescription = stringResource(R.string.sketch_pen))
            }
            FilledTonalIconToggleButton(checked = pen.erasing, onCheckedChange = { pen.erasing = true }) {
                Icon(EraserIcon, contentDescription = stringResource(R.string.sketch_eraser))
            }
            for (c in HighlightColor.entries) {
                val selected = c == pen.color && !pen.erasing
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(28.dp)
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(4.dp) else Modifier)
                        .clip(CircleShape)
                        .background(Color(c.sketchInk(dark)))
                        .clickable(onClickLabel = c.id) {
                            pen.color = c
                            pen.erasing = false
                        },
                )
            }
            Spacer(Modifier.width(FeederTokens.Space.xs))
            Button(onClick = onDone) { Text(stringResource(R.string.pen_done)) }
        }
    }
}

/** A handwritten note written with other reading settings: as it was written, with its words. */
@Composable
fun InkPreviewDialog(
    note: Highlight,
    canSwitch: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val data = remember(note.ink) { com.nononsenseapps.feeder.model.highlights.InkCodec.parse(note.ink) }
    val dark = isDarkSurface()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                Text(stringResource(R.string.ink_why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("“${note.text}”", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (data != null) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(sketchPaper()).padding(FeederTokens.Space.sm)) {
                        Canvas(Modifier.fillMaxWidth().aspectRatio((data.w + 8f) / (data.h + 8f))) {
                            val k = size.width / (data.w + 8f)
                            for (st in data.strokes) {
                                val shifted = FloatArray(st.p.size) { i -> st.p[i] + 4f }
                                drawPath(strokePath(shifted, k), Color(st.color.sketchInk(dark)), style = Stroke(width = st.w * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (canSwitch) TextButton(onClick = onSwitch) { Text(stringResource(R.string.ink_switch)) } else TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.sketch_delete)) }
        },
    )
}
