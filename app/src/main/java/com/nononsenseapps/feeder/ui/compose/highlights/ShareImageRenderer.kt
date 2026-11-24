package com.nononsenseapps.feeder.ui.compose.highlights

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the quote sits on the picture. The same four layouts as the web app. */
enum class ShareLayout(
    @StringRes val label: Int,
) {
    PHOTO(R.string.share_layout_photo),
    BLUR(R.string.share_layout_blur),
    SPLIT(R.string.share_layout_split),
    COLOR(R.string.share_layout_color),
}

enum class ShareSize(
    val w: Int,
    val h: Int,
    @StringRes val label: Int,
) {
    STORY(1080, 1920, R.string.share_size_story),
    POST(1080, 1350, R.string.share_size_post),
}

/** Plain backgrounds, also used when there's no picture. */
enum class ShareGradient(
    @StringRes val label: Int,
    val stops: List<Long>,
) {
    GREEN(R.string.share_gradient_green, listOf(0xFF164F36, 0xFF1F6F4A, 0xFF2F8C60)),
    BLUE(R.string.share_gradient_blue, listOf(0xFF1D2B64, 0xFF2F45A0, 0xFF4F6BD8)),
    SUNSET(R.string.share_gradient_sunset, listOf(0xFFFF7A1A, 0xFFF2551D, 0xFFC2185B)),
    NIGHT(R.string.share_gradient_night, listOf(0xFF111412, 0xFF1F2622, 0xFF2C332E)),
}

data class ShareContent(
    val text: String,
    val note: String,
    val title: String,
    val source: String,
    val color: HighlightColor,
)

data class ShareOptions(
    val layout: ShareLayout,
    val size: ShareSize,
    /** A picture behind the text, or null for the gradient. */
    val image: Bitmap?,
    val gradient: ShareGradient,
    val showNote: Boolean,
    val showMark: Boolean,
)

/**
 * Draws a highlight share image: a picture (or gradient) and the highlighted text, with the
 * article's title and source. No reading stats. A port of the web app's shareImage.ts.
 */
object ShareImageRenderer {
    private val SERIF = Typeface.SERIF
    private val SANS = Typeface.SANS_SERIF

    private class Fitted(
        val lines: List<String>,
        val px: Float,
        val lh: Float,
        val paint: Paint,
    )

    private fun paint(
        family: Typeface,
        weight: Int,
        italic: Boolean,
        px: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(family, weight, italic)
            textSize = px
        }

    private fun wrap(
        p: Paint,
        text: String,
        maxWidth: Float,
    ): List<String> {
        val lines = mutableListOf<String>()
        text.split(Regex("\n+")).forEachIndexed { i, para ->
            // A blank line between paragraphs (several highlights in one image)
            if (i > 0 && lines.isNotEmpty()) lines += ""
            var line = ""
            for (word in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(next) <= maxWidth || line.isEmpty()) {
                    line = next
                } else {
                    lines += line
                    line = word
                }
            }
            if (line.isNotEmpty()) lines += line
        }
        return lines
    }

    /** The biggest font size (down to [minPx]) at which the text fits; cut with … if it never does. */
    private fun fitText(
        text: String,
        font: (Float) -> Paint,
        width: Float,
        height: Float,
        maxPx: Int,
        minPx: Int,
        lineHeight: Float = 1.32f,
    ): Fitted {
        var px = maxPx
        while (px >= minPx) {
            val p = font(px.toFloat())
            val lines = wrap(p, text, width)
            if (lines.size * px * lineHeight <= height) return Fitted(lines, px.toFloat(), px * lineHeight, p)
            px -= 2
        }
        val p = font(minPx.toFloat())
        val lh = minPx * lineHeight
        val all = wrap(p, text, width)
        val lines = all.take(max(1, floor(height / lh).toInt())).toMutableList()
        if (lines.size < all.size) {
            var last = lines.last()
            while (last.isNotEmpty() && p.measureText("$last…") > width) last = last.replace(Regex("\\s*\\S+$"), "")
            lines[lines.lastIndex] = "$last…"
        }
        return Fitted(lines, minPx.toFloat(), lh, p)
    }

    private fun drawCover(
        c: Canvas,
        img: Bitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val scale = max(w / img.width, h / img.height)
        val sw = w / scale
        val sh = h / scale
        val sx = (img.width - sw) / 2
        val sy = (img.height - sh) / 2
        c.drawBitmap(
            img,
            Rect(sx.roundToInt(), sy.roundToInt(), (sx + sw).roundToInt(), (sy + sh).roundToInt()),
            RectF(x, y, x + w, y + h),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

    /** Blur by shrinking the picture a lot, then stretching it back. */
    private fun drawBlurred(
        c: Canvas,
        img: Bitmap,
        w: Float,
        h: Float,
    ) {
        val sw = 36
        val sh = (36 * h / w).roundToInt().coerceAtLeast(1)
        val small = createBitmap(sw, sh)
        drawCover(Canvas(small), img, 0f, 0f, sw.toFloat(), sh.toFloat())
        // Two steps up, so the stretch is smooth rather than blocky
        val mid = small.scale(sw * 4, sh * 4, filter = true)
        c.drawBitmap(mid, Rect(0, 0, mid.width, mid.height), RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun fillGradient(
        c: Canvas,
        g: ShareGradient,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val p =
            Paint().apply {
                shader =
                    LinearGradient(
                        x,
                        y,
                        x + w,
                        y + h,
                        g.stops.map { it.toInt() }.toIntArray(),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        c.drawRect(x, y, x + w, y + h, p)
    }

    private fun drawMark(
        c: Canvas,
        x: Float,
        y: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val p =
            paint(SANS, 800, false, 30f).apply {
                letterSpacing = 0.2f
                this.color = color
                alpha = (0.85f * 255).roundToInt()
                textAlign = align
            }
        // Top of the text at y, like the canvas "top" baseline
        c.drawText("FEEDER", x, y - p.ascent(), p)
    }

    /** Title (bold, up to two lines) with the source under it, drawn upwards from [bottom]. Returns its height. */
    private fun drawSource(
        c: Canvas,
        s: ShareContent,
        x: Float,
        bottom: Float,
        width: Float,
        color: Int,
        muted: Int,
    ): Float {
        if (s.source.isNotBlank()) {
            val p = paint(SANS, 400, false, 32f).apply { this.color = muted }
            c.drawText(ellipsize(p, s.source, width), x, bottom, p)
        }
        val tp = paint(SANS, 700, false, 38f).apply { this.color = color }
        val lines = wrap(tp, s.title, width).take(2)
        var y = bottom - if (s.source.isNotBlank()) 48 else 0
        for (i in lines.indices.reversed()) {
            c.drawText(ellipsize(tp, lines[i], width), x, y, tp)
            y -= 48
        }
        return bottom - y
    }

    private fun ellipsize(
        p: Paint,
        text: String,
        width: Float,
    ): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** The lines from [top]; [stroke] puts a highlighter stroke behind the lower part of each line. */
    private fun drawLines(
        c: Canvas,
        t: Fitted,
        x: Float,
        top: Float,
        fill: Int,
        stroke: Int? = null,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val p = Paint(t.paint).apply { textAlign = align }
        t.lines.forEachIndexed { i, line ->
            val base = top + i * t.lh + t.px
            if (stroke != null && line.isNotEmpty()) {
                val w = p.measureText(line)
                val left = if (align == Paint.Align.CENTER) x - w / 2 else x
                val sp =
                    Paint().apply {
                        color = stroke
                        alpha = (0.6f * 255).roundToInt()
                    }
                c.drawRect(left - 4, base - t.px * 0.32f, left + w + 4, base - t.px * 0.32f + t.px * 0.42f, sp)
            }
            p.color = fill
            c.drawText(line, x, base, p)
        }
    }

    private fun argb(
        a: Float,
        rgb: Int,
    ): Int = ((a * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

    fun render(
        s: ShareContent,
        o: ShareOptions,
    ): Bitmap {
        val wi = o.size.w
        val hi = o.size.h
        val w = wi.toFloat()
        val h = hi.toFloat()
        val bitmap = createBitmap(wi, hi)
        val c = Canvas(bitmap)
        val pad = 88f
        val ink = s.color.ink.toInt()
        val white = 0xFFFFFFFF.toInt()
        val quote = s.text.split("\n").joinToString("\n") { "“$it”" }
        val note = if (o.showNote) s.note else ""

        when (o.layout) {
            ShareLayout.PHOTO -> {
                if (o.image != null) drawCover(c, o.image, 0f, 0f, w, h) else fillGradient(c, o.gradient, 0f, 0f, w, h)
                val fade =
                    Paint().apply {
                        shader =
                            LinearGradient(
                                0f,
                                h * 0.25f,
                                0f,
                                h,
                                intArrayOf(argb(0f, 0), argb(0.62f, 0), argb(0.85f, 0)),
                                floatArrayOf(0f, 0.55f, 1f),
                                Shader.TileMode.CLAMP,
                            )
                    }
                c.drawRect(0f, 0f, w, h, fade)
                if (o.showMark) drawMark(c, pad, pad, white)
                val srcH = drawSource(c, s, pad, h - pad, w - pad * 2, white, argb(0.8f, white))
                var bottom = h - pad - srcH - 40
                if (note.isNotBlank()) {
                    val n = fitText(note, { paint(SANS, 400, true, it) }, w - pad * 2, 200f, 36, 28, 1.35f)
                    bottom -= n.lines.size * n.lh
                    drawLines(c, n, pad, bottom, argb(0.88f, white))
                    bottom -= 32
                }
                val t = fitText(quote, { paint(SERIF, 700, false, it) }, w - pad * 2, bottom - h * 0.2f, 76, 30)
                val top = bottom - t.lines.size * t.lh
                drawLines(c, t, pad, top, white, ink)
            }

            ShareLayout.BLUR -> {
                if (o.image != null) drawBlurred(c, o.image, w, h) else fillGradient(c, o.gradient, 0f, 0f, w, h)
                c.drawRect(0f, 0f, w, h, Paint().apply { color = argb(0.12f, 0) })
                val cardX = 72f
                val cardW = w - cardX * 2
                val inner = cardW - 120
                val t = fitText(s.text, { paint(SERIF, 600, false, it) }, inner - 28, h * 0.55f, 64, 26, 1.4f)
                val n = if (note.isNotBlank()) fitText(note, { paint(SANS, 400, true, it) }, inner, 220f, 34, 26, 1.35f) else null
                val titleLines = min(2, wrap(paint(SANS, 700, false, 38f), s.title, inner).size)
                val srcH = titleLines * 48f + if (s.source.isNotBlank()) 48 else 0
                val quoteH = t.lines.size * t.lh
                val cardH = 60 + quoteH + (n?.let { 36 + it.lines.size * it.lh } ?: 0f) + 48 + srcH + 50
                val cardY = max(140f, (h - cardH) / 2)
                val card =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = argb(0.93f, white)
                        setShadowLayer(60f, 0f, 20f, argb(0.28f, 0))
                    }
                c.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + cardH), 44f, 44f, card)
                val qx = cardX + 60
                var y = cardY + 60
                c.drawRect(qx, y + 6, qx + 10, y + quoteH - 6, Paint().apply { color = ink })
                drawLines(c, t, qx + 38, y - t.lh * 0.12f, 0xFF1B1D1C.toInt())
                y += quoteH
                if (n != null) {
                    y += 36
                    drawLines(c, n, qx, y - n.lh * 0.15f, 0xFF4F5A52.toInt())
                    y += n.lines.size * n.lh
                }
                drawSource(c, s, qx, y + 48 + srcH - 6, inner, 0xFF1B1D1C.toInt(), 0xFF4F5A52.toInt())
                if (o.showMark) drawMark(c, w / 2, h - pad - 30, white, Paint.Align.CENTER)
            }

            ShareLayout.SPLIT -> {
                val topH = (h * 0.42f).roundToInt().toFloat()
                c.drawColor(0xFFFFFDF5.toInt())
                if (o.image != null) drawCover(c, o.image, 0f, 0f, w, topH) else fillGradient(c, o.gradient, 0f, 0f, w, topH)
                val qm = paint(SERIF, 700, false, 150f).apply { color = ink }
                c.drawText("“", pad - 6, topH + 40 - qm.ascent(), qm)
                val dark = 0xFF1D1D1B.toInt()
                val muted = 0xFF6C6A60.toInt()
                val srcH = drawSource(c, s, pad, h - pad, w - pad * 2 - if (o.showMark) 200 else 0, dark, muted)
                if (o.showMark) drawMark(c, w - pad, h - pad - 26, dark, Paint.Align.RIGHT)
                val top = topH + 170
                var avail = h - pad - srcH - 60 - top
                var n: Fitted? = null
                if (note.isNotBlank()) {
                    n = fitText(note, { paint(SANS, 400, true, it) }, w - pad * 2, 200f, 34, 26, 1.35f)
                    avail -= n.lines.size * n.lh + 32
                }
                val t = fitText(s.text, { paint(SERIF, 500, false, it) }, w - pad * 2, avail, 62, 26, 1.42f)
                drawLines(c, t, pad, top, dark)
                if (n != null) drawLines(c, n, pad, top + t.lines.size * t.lh + 32, muted)
            }

            ShareLayout.COLOR -> {
                fillGradient(c, o.gradient, 0f, 0f, w, h)
                if (o.showMark) drawMark(c, pad, pad, white)
                val srcH = drawSource(c, s, pad, h - pad, w - pad * 2, white, argb(0.8f, white))
                val room = h - pad * 2 - 120 - srcH - 80
                val n = if (note.isNotBlank()) fitText(note, { paint(SANS, 400, true, it) }, w - pad * 2, 200f, 36, 28, 1.35f) else null
                val noteH = n?.let { it.lines.size * it.lh + 36 } ?: 0f
                val t = fitText(quote, { paint(SANS, 700, false, it) }, w - pad * 2, room - noteH, 80, 30, 1.3f)
                val blockH = t.lines.size * t.lh + noteH
                val top = pad + 120 + (room - blockH) / 2
                drawLines(c, t, pad, top, white)
                if (n != null) drawLines(c, n, pad, top + t.lines.size * t.lh + 36, argb(0.88f, white))
            }
        }
        return bitmap
    }
}
