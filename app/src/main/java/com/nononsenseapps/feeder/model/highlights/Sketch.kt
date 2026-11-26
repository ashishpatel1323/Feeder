package com.nononsenseapps.feeder.model.highlights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A margin sketch: hand-drawn strokes tied to a passage, shown as a strip after the paragraph.
 * Stored in [Highlight.sketch] as the same JSON as the web app's (web/src/lib/sketch.ts):
 * { h, s: [{ c, w, p: [x, y, x, y, …] }] }, coordinates in 1/1000 of the drawing's width.
 */
data class Sketch(
    val h: Float,
    val strokes: List<SketchStroke>,
)

data class SketchStroke(
    val color: HighlightColor,
    /** Width in 1/1000 of the drawing's width. */
    val w: Float,
    /** x, y, x, y, … */
    val p: FloatArray,
)

const val SKETCH_W = 1000f

/** Ink colors on the paper strip: light theme, dark theme. The highlight swatches are too pale for strokes. */
fun HighlightColor.sketchInk(dark: Boolean): Long =
    when (this) {
        HighlightColor.YELLOW -> if (dark) 0xFFFFC857 else 0xFFB26A00
        HighlightColor.GREEN -> if (dark) 0xFF7ED492 else 0xFF2E7D32
        HighlightColor.BLUE -> if (dark) 0xFF8AB4FF else 0xFF1A56C4
        HighlightColor.PINK -> if (dark) 0xFFF48FB1 else 0xFFC2185B
    }

object SketchCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): Sketch? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            val strokes =
                (o["s"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val st = el as? JsonObject ?: return@mapNotNull null
                    val pts = (st["p"] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull?.toFloat() ?: 0f }?.toFloatArray() ?: return@mapNotNull null
                    if (pts.size < 2) return@mapNotNull null
                    SketchStroke(
                        color = HighlightColor.of(st["c"]?.jsonPrimitive?.content).let { if (st["c"] == null) HighlightColor.BLUE else it },
                        w =
                            st["w"]
                                ?.jsonPrimitive
                                ?.doubleOrNull
                                ?.toFloat()
                                ?.takeIf { it > 0 } ?: 3f,
                        p = pts,
                    )
                }
            if (strokes.isEmpty()) return null
            val h =
                o["h"]
                    ?.jsonPrimitive
                    ?.doubleOrNull
                    ?.toFloat()
                    ?.takeIf { it > 0 } ?: heightOf(strokes)
            Sketch(h, strokes)
        }.getOrNull()
    }

    private fun heightOf(strokes: List<SketchStroke>): Float {
        var max = 0f
        for (st in strokes) for (i in 1 until st.p.size step 2) max = maxOf(max, st.p[i] + st.w)
        return max
    }

    /** The JSON for [Highlight.sketch]: cropped to the strokes (with a little room) and rounded, like the web app. */
    fun encode(strokes: List<SketchStroke>): String {
        val pad = 24f
        var minY = Float.MAX_VALUE
        for (st in strokes) for (i in 1 until st.p.size step 2) minY = minOf(minY, st.p[i] - st.w)
        val shift = if (minY == Float.MAX_VALUE) 0f else maxOf(0f, minY - pad)
        val moved =
            strokes.map { st ->
                st.copy(
                    w = (st.w * 10).roundToInt() / 10f,
                    p = FloatArray(st.p.size) { i -> (if (i % 2 == 1) st.p[i] - shift else st.p[i]).roundToInt().toFloat() },
                )
            }
        val h = maxOf(80, (heightOf(moved) + pad).roundToInt())
        return buildJsonObject {
            put("h", h)
            put(
                "s",
                buildJsonArray {
                    for (st in moved) {
                        add(
                            buildJsonObject {
                                put("c", st.color.id)
                                put("w", st.w)
                                put("p", buildJsonArray { st.p.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.roundToInt())) } })
                            },
                        )
                    }
                },
            )
        }.toString()
    }
}
