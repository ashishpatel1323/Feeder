package com.nononsenseapps.feeder.ui.compose.highlights

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.InkData
import com.nononsenseapps.feeder.model.highlights.SketchStroke
import com.nononsenseapps.feeder.model.highlights.sketchInk
import com.nononsenseapps.feeder.model.html.LinearText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * Handwriting on the article page (Kobo style), the reader side. Each paragraph tells where it is
 * and how it is laid out ([register]); a note keeps the layout signature of the paragraph it was
 * written on and is drawn back only while that paragraph is laid out the same way (width, text
 * size, line height, font, text). Otherwise a [✎] marker shows where it is. Units are dp.
 */
class ArticleInk(
    val pen: PenState,
    /** A note was finished: paragraph index (in the reader's text elements), its words in it, the drawing. */
    val onNote: (paragraph: Int, start: Int, end: Int, data: InkData) -> Unit,
    val onErase: (id: String, data: InkData?) -> Unit,
    val onMark: (id: String) -> Unit,
) {
    class Para(
        var coords: LayoutCoordinates? = null,
        var layout: TextLayoutResult? = null,
        var sig: String = "",
    )

    /** The paragraphs on screen, by identity. */
    val paras = IdentityHashMap<LinearText, Para>()

    /** The notes drawn as ink now, with their paragraph: to erase from. */
    val shown = HashMap<String, Pair<LinearText, InkData>>()

    fun register(
        text: LinearText,
        coords: LayoutCoordinates? = null,
        layout: TextLayoutResult? = null,
        sig: String? = null,
    ) {
        val p = paras.getOrPut(text) { Para() }
        coords?.let { p.coords = it }
        layout?.let { p.layout = it }
        sig?.let { p.sig = it }
    }
}

@Suppress("ktlint:compose:compositionlocal-allowlist")
val LocalArticleInk = compositionLocalOf<ArticleInk?> { null }

/** A note anchored in one paragraph: its id, drawing and the characters it covers there. */
data class ParagraphInk(
    val id: String,
    val data: InkData,
    val range: IntRange,
)

@Suppress("ktlint:compose:compositionlocal-allowlist")
val LocalParagraphInks = compositionLocalOf<Map<LinearText, List<ParagraphInk>>> { emptyMap() }

/** What has to be the same for ink to land on the same words. */
fun paragraphSig(
    layout: TextLayoutResult,
    density: Float,
    fontName: String,
): String {
    val style = layout.layoutInput.style
    return listOf(
        Math.round(layout.size.width / density),
        style.fontSize.value,
        style.lineHeight.value,
        style.fontFamily?.toString() ?: "default",
        fontName,
        layout.layoutInput.text.length,
    ).joinToString("|")
}

internal fun DrawScope.drawInkStrokes(
    strokes: List<SketchStroke>,
    dark: Boolean,
    scale: Float,
) {
    for (st in strokes) {
        val path = Path()
        val p = st.p
        if (p.size < 2) continue
        path.moveTo(p[0] * scale, p[1] * scale)
        if (p.size < 4) {
            path.lineTo(p[0] * scale + 0.01f, p[1] * scale)
        } else {
            var i = 2
            while (i < p.size - 2) {
                path.quadraticTo(p[i] * scale, p[i + 1] * scale, (p[i] + p[i + 2]) / 2 * scale, (p[i + 1] + p[i + 3]) / 2 * scale)
                i += 2
            }
            path.lineTo(p[p.size - 2] * scale, p[p.size - 1] * scale)
        }
        drawPath(path, Color(st.color.sketchInk(dark)), style = Stroke(width = st.w * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Lets a stylus write on the article (any pointer while Draw on page is on), drawing the strokes
 * live and finishing a note a moment after the pen lifts. The pen writes even when a palm or
 * finger touched first, and never scrolls the page. The pen's eraser end, its side button
 * held down (S Pen), or the eraser in the pen bar, removes strokes from notes on the page.
 */
fun Modifier.articleInkCapture(ink: ArticleInk?): Modifier =
    if (ink == null) {
        this
    } else {
        composed {
            val scope = rememberCoroutineScope()
            val pending = remember { mutableStateListOf<SketchStroke>() }
            val live = remember { mutableStateOf<SketchStroke?>(null) }
            val container = remember { arrayOfNulls<LayoutCoordinates>(1) }
            val job = remember { arrayOfNulls<Job>(1) }
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

            fun paraAt(point: Offset): Pair<LinearText, Rect>? {
                val box = container[0] ?: return null
                var best: Pair<LinearText, Rect>? = null
                var bestDist = Float.MAX_VALUE
                for ((t, p) in ink.paras) {
                    val c = p.coords ?: continue
                    if (!c.isAttached) continue
                    val topLeft = box.localPositionOf(c, Offset.Zero)
                    val r =
                        Rect(
                            topLeft,
                            androidx.compose.ui.geometry
                                .Size(c.size.width.toFloat(), c.size.height.toFloat()),
                        )
                    val d =
                        when {
                            point.y < r.top -> r.top - point.y
                            point.y > r.bottom -> point.y - r.bottom
                            else -> 0f
                        }
                    if (d < bestDist) {
                        bestDist = d
                        best = t to r
                    }
                }
                return best
            }

            fun finish(density: Float) {
                job[0]?.cancel()
                val strokes = pending.toList()
                pending.clear()
                if (strokes.isEmpty()) return
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (st in strokes) {
                    var i = 0
                    while (i < st.p.size - 1) {
                        minX = minOf(minX, st.p[i] - st.w)
                        maxX = maxOf(maxX, st.p[i] + st.w)
                        minY = minOf(minY, st.p[i + 1] - st.w)
                        maxY = maxOf(maxY, st.p[i + 1] + st.w)
                        i += 2
                    }
                }
                // Points are in container px here
                val (text, rect) = paraAt(Offset(minX, minY)) ?: return
                val para = ink.paras[text] ?: return
                val layout = para.layout ?: return
                val local = Offset(minX - rect.left, minY - rect.top)
                val localEnd = Offset((maxX - rect.left).coerceIn(0f, rect.width), (maxY - rect.top).coerceIn(0f, rect.height))
                var s = layout.getOffsetForPosition(Offset(local.x.coerceIn(0f, rect.width), local.y.coerceIn(0f, rect.height)))
                var e = layout.getOffsetForPosition(localEnd)
                if (e < s) s = e.also { e = s }
                val len = layout.layoutInput.text.length
                if (e <= s ||
                    layout.layoutInput.text
                        .substring(s, e)
                        .isBlank()
                ) {
                    e = minOf(len, s + 24)
                }
                if (e <= s) return
                // Whole words: the note is about words, not the letters the pen started on
                s = layout.getWordBoundary(s.coerceIn(0, maxOf(0, len - 1))).start.coerceAtMost(s)
                e = layout.getWordBoundary((e - 1).coerceIn(0, maxOf(0, len - 1))).end.coerceAtLeast(e)
                val paragraphs = LocalParagraphIndex.of(ink, text)
                val data =
                    InkData(
                        sig = para.sig,
                        ox = local.x / density,
                        oy = local.y / density,
                        w = (maxX - minX) / density,
                        h = (maxY - minY) / density,
                        strokes = strokes.map { st -> st.copy(w = st.w / density, p = FloatArray(st.p.size) { i -> (if (i % 2 == 0) st.p[i] - minX else st.p[i] - minY) / density }) },
                    )
                ink.onNote(paragraphs, s, e, data)
            }

            fun eraseAt(
                point: Offset,
                density: Float,
            ) {
                pending.removeAll { st -> near(st.p, point.x, point.y, 10f * density + st.w) }
                val box = container[0] ?: return
                for ((id, pair) in ink.shown.toList()) {
                    val (text, data) = pair
                    val c = ink.paras[text]?.coords ?: continue
                    if (!c.isAttached) continue
                    val origin = box.localPositionOf(c, Offset.Zero)
                    val x = (point.x - origin.x) / density - data.ox
                    val y = (point.y - origin.y) / density - data.oy
                    if (x < -12 || y < -12 || x > data.w + 12 || y > data.h + 12) continue
                    val left = data.strokes.filterNot { near(it.p, x, y, 10f + it.w) }
                    if (left.size != data.strokes.size) {
                        val next = if (left.isEmpty()) null else data.copy(strokes = left)
                        if (next == null) ink.shown.remove(id) else ink.shown[id] = text to next
                        ink.onErase(id, next)
                    }
                }
            }

            onGloballyPositioned { container[0] = it }
                .drawWithContent {
                    drawContent()
                    val all = live.value?.let { pending + it } ?: pending
                    if (all.isNotEmpty()) drawInkStrokes(all, dark, 1f)
                }.pointerInput(ink) {
                    // Where and when a pen last hovered: some pens report their touch as a finger
                    // on some devices, but a hover is always reported as a stylus.
                    var hoverAt = Offset.Unspecified
                    var hoverTime = 0L
                    val hoverReach = 48f * density

                    fun isPen(c: PointerInputChange) =
                        c.type == PointerType.Stylus ||
                            c.type == PointerType.Eraser ||
                            (
                                hoverAt.isSpecified &&
                                    c.uptimeMillis - hoverTime < 400 &&
                                    (c.position - hoverAt).getDistance() < hoverReach
                            )

                    // Draws (or erases) with one pointer until it lifts. Everything else on the
                    // page meanwhile (a palm, other fingers) is held, so the page can't scroll,
                    // turn or open a link under the pen.
                    suspend fun AwaitPointerEventScope.penStroke(
                        down: PointerInputChange,
                        stylus: Boolean,
                    ) {
                        job[0]?.cancel()

                        fun erasingNow() = ink.pen.erasing || down.type == PointerType.Eraser || (stylus && StylusButton.pressed)
                        var erase = erasingNow()

                        fun width(pressure: Float) = if (stylus && pressure > 0f) 2.2f * density * (0.5f + pressure) else 2.2f * density
                        if (erase) {
                            eraseAt(down.position, density)
                        } else {
                            live.value = SketchStroke(ink.pen.color, width(down.pressure), floatArrayOf(down.position.x, down.position.y))
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            event.changes.forEach { it.consume() }
                            if (change == null) break
                            val points = change.historical.map { it.position } + change.position
                            // The side button pressed or let go mid-stroke: switch between writing and erasing
                            val nowErase = erasingNow()
                            if (nowErase != erase) {
                                erase = nowErase
                                if (erase) {
                                    live.value?.let { pending += it }
                                    live.value = null
                                } else {
                                    live.value = SketchStroke(ink.pen.color, width(change.pressure), floatArrayOf(change.position.x, change.position.y))
                                }
                            }
                            if (erase) {
                                points.forEach { eraseAt(it, density) }
                            } else {
                                live.value?.let { st ->
                                    val list = st.p.toMutableList()
                                    for (pt in points) {
                                        if (hypot(pt.x - list[list.size - 2], pt.y - list[list.size - 1]) >= density) {
                                            list += pt.x
                                            list += pt.y
                                        }
                                    }
                                    val w = if (stylus) st.w * 0.85f + width(change.pressure) * 0.15f else st.w
                                    live.value = st.copy(p = list.toFloatArray(), w = w)
                                }
                            }
                            if (!change.pressed) break
                        }
                        live.value?.let { pending += it }
                        live.value = null
                        if (pending.isNotEmpty()) {
                            val d = density
                            job[0] =
                                scope.launch {
                                    delay(1100)
                                    finish(d)
                                }
                        }
                    }

                    awaitEachGesture {
                        // Fingers and palms on the page, by pointer: where each went down
                        val fingers = HashMap<PointerId, Offset>()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (c in event.changes) {
                                if (!c.pressed && (c.type == PointerType.Stylus || c.type == PointerType.Eraser)) {
                                    hoverAt = c.position
                                    hoverTime = c.uptimeMillis
                                }
                            }
                            // Any pointer can be the pen, not only the first: a palm resting on
                            // the screen is often down before the pen touches.
                            val down =
                                event.changes.firstOrNull { c ->
                                    c.changedToDownIgnoreConsumed() && (isPen(c) || ink.pen.drawAll)
                                }
                            if (down != null) {
                                fingers.remove(down.id)
                                event.changes.forEach { it.consume() }
                                penStroke(down, isPen(down))
                                continue
                            }
                            for (c in event.changes) {
                                if (c.changedToDownIgnoreConsumed()) fingers[c.id] = c.position
                                if (!c.pressed) fingers.remove(c.id)
                            }
                            // A finger scrolling the page while writing: the note is done. A
                            // resting palm (not moving) leaves it open.
                            if (pending.isNotEmpty() &&
                                event.changes.any { c ->
                                    c.pressed && fingers[c.id]?.let { (c.position - it).getDistance() > viewConfiguration.touchSlop } == true
                                }
                            ) {
                                finish(density)
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
        }
    }

/** Which of the reader's text elements a paragraph is (set by the article view). */
object LocalParagraphIndex {
    private val index = java.util.WeakHashMap<ArticleInk, IdentityHashMap<LinearText, Int>>()

    fun set(
        ink: ArticleInk,
        texts: List<LinearText>,
    ) {
        index[ink] = IdentityHashMap<LinearText, Int>().apply { texts.forEachIndexed { i, t -> put(t, i) } }
    }

    fun of(
        ink: ArticleInk,
        text: LinearText,
    ): Int = index[ink]?.get(text) ?: -1
}

private fun near(
    p: FloatArray,
    x: Float,
    y: Float,
    r: Float,
): Boolean {
    var i = 0
    while (i < p.size - 1) {
        if (hypot(p[i] - x, p[i + 1] - y) < r) return true
        i += 2
    }
    return false
}

/** Pen color for a new note, as a highlight color. */
fun PenState.noteColor(): HighlightColor = color

/** Draws a paragraph's notes: ink where its layout matches, a [✎] marker where it doesn't. */
internal fun DrawScope.drawParagraphInks(
    inks: List<ParagraphInk>,
    sig: String,
    layout: TextLayoutResult?,
    dark: Boolean,
    markerColor: Color,
    shown: (ParagraphInk, Boolean) -> Unit,
) {
    for (k in inks) {
        if (k.data.sig == sig) {
            translate(k.data.ox * density, k.data.oy * density) { drawInkStrokes(k.data.strokes, dark, density) }
            shown(k, true)
        } else {
            shown(k, false)
            val l = layout ?: continue
            val at = k.range.first.coerceIn(0, maxOf(0, l.layoutInput.text.length - 1))
            val box = l.getBoundingBox(at)
            markerRect(box, density).let { r ->
                drawRoundRect(
                    markerColor,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(3 * density),
                    style = Stroke(1.5f * density),
                )
                // A small pen: a slanted line with a tip
                val p = Path()
                p.moveTo(r.left + r.width * 0.3f, r.bottom - r.height * 0.25f)
                p.lineTo(r.right - r.width * 0.25f, r.top + r.height * 0.25f)
                drawPath(p, markerColor, style = Stroke(2f * density, cap = StrokeCap.Round))
            }
        }
    }
}

/** Where the [✎] marker of a note is drawn: a small box just above the start of its words. */
internal fun markerRect(
    box: Rect,
    density: Float,
): Rect {
    val h = 14f * density
    return Rect(box.left - 2 * density, box.top - h * 0.15f, box.left - 2 * density + h * 1.3f, box.top - h * 0.15f + h)
}
