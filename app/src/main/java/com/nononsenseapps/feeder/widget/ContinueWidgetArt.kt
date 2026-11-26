package com.nononsenseapps.feeder.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Continue widget's pictures. A widget can't clip to a shape or draw an icon vector itself,
 * so the scalloped ("cookie") covers and buttons and the icons are drawn here as bitmaps; the
 * widget tints the white ones, so they follow light and dark mode.
 */
internal object ContinueWidgetArt {
    /** A scalloped circle of [lobes] waves, as a path in a [size] square. */
    fun cookie(
        size: Float,
        lobes: Int,
        depth: Float,
    ): Path {
        val path = Path()
        val r = size / 2f
        val steps = 240
        for (k in 0..steps) {
            val a = 2 * PI * k / steps
            val rr = r * (1 - depth + depth * cos(lobes * a)).toFloat()
            val x = r + rr * cos(a).toFloat()
            val y = r + rr * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** A white scalloped shape, to tint (the big play button). */
    fun cookieMask(px: Int): Bitmap {
        val b = createBitmap(px, px)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(b).drawPath(cookie(px.toFloat(), 9, 0.07f), paint)
        return b
    }

    /** A cover cut to the scalloped shape, cropped to fill it. */
    fun cookieCover(
        cover: Bitmap,
        px: Int,
    ): Bitmap {
        val b = createBitmap(px, px)
        val scale = maxOf(px.toFloat() / cover.width, px.toFloat() / cover.height)
        val m =
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((px - cover.width * scale) / 2f, (px - cover.height * scale) / 2f)
            }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            }
        Canvas(b).drawPath(cookie(px.toFloat(), 12, 0.05f), paint)
        return b
    }

    /** An icon in white, to tint. */
    fun icon(
        vector: ImageVector,
        px: Int,
    ): Bitmap {
        val b = createBitmap(px, px)
        val canvas = Canvas(b)
        canvas.scale(px / vector.viewportWidth, px / vector.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun draw(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorGroup -> draw(node)
                    is VectorPath -> {
                        val p = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                        p.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                        canvas.drawPath(p, paint)
                    }
                }
            }
        }
        draw(vector.root)
        return b
    }

    /**
     * The colors of a pill, from its cover: the cover's most common vivid hue, made light for
     * day and dark for night (as Android's own media widget does). [fallbackHue] when the cover
     * is grey or missing.
     */
    fun tintOf(
        cover: Bitmap?,
        fallbackHue: Float,
    ): ContinueTint {
        var hue = fallbackHue
        var sat = 0.45f
        if (cover != null) {
            val small = cover.scale(24, 24, filter = true)
            val buckets = FloatArray(36)
            val sats = FloatArray(36)
            val hsl = FloatArray(3)
            for (y in 0 until small.height) {
                for (x in 0 until small.width) {
                    ColorUtils.colorToHSL(small.getPixel(x, y), hsl)
                    // Grey, near black and near white pixels say nothing about the color
                    if (hsl[1] < 0.2f || hsl[2] < 0.12f || hsl[2] > 0.92f) continue
                    val k = (hsl[0] / 10f).toInt().coerceIn(0, 35)
                    val w = hsl[1] * (1f - kotlin.math.abs(hsl[2] - 0.5f))
                    buckets[k] += w
                    sats[k] += hsl[1] * w
                }
            }
            if (small !== cover) small.recycle()
            val best = buckets.indices.maxBy { buckets[it] }
            if (buckets[best] > 4f) {
                hue = best * 10f + 5f
                sat = (sats[best] / buckets[best]).coerceIn(0.3f, 0.85f)
            } else {
                sat = 0.12f
            }
        }

        fun c(
            s: Float,
            l: Float,
        ) = ColorUtils.HSLToColor(floatArrayOf(hue, s.coerceIn(0f, 1f), l))
        return ContinueTint(
            background = c(sat, 0.88f) to c(sat * 0.45f, 0.17f),
            onBackground = c(sat * 0.6f, 0.14f) to c(sat * 0.35f, 0.92f),
            onBackgroundSoft = c(sat * 0.4f, 0.34f) to c(sat * 0.3f, 0.74f),
            button = c(sat, 0.33f) to c(sat * 0.8f, 0.78f),
            onButton = Color.WHITE to c(sat, 0.14f),
            tonal = c(sat * 0.9f, 0.80f) to c(sat * 0.45f, 0.27f),
            track = c(sat * 0.6f, 0.75f) to c(sat * 0.35f, 0.33f),
        )
    }
}

/** A pill's colors, each as (day, night). */
data class ContinueTint(
    val background: Pair<Int, Int>,
    val onBackground: Pair<Int, Int>,
    val onBackgroundSoft: Pair<Int, Int>,
    val button: Pair<Int, Int>,
    val onButton: Pair<Int, Int>,
    val tonal: Pair<Int, Int>,
    val track: Pair<Int, Int>,
)
