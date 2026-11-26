package com.nononsenseapps.feeder.model.highlights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchCodecTest {
    @Test
    fun cropsRoundsAndReadsBack() {
        val raw = SketchCodec.encode(listOf(SketchStroke(HighlightColor.BLUE, 3.24f, floatArrayOf(10.4f, 300.6f, 20f, 320f))))
        val sk = SketchCodec.parse(raw)
        assertNotNull(sk)
        assertEquals(HighlightColor.BLUE, sk!!.strokes[0].color)
        assertEquals(3.2f, sk.strokes[0].w, 0.001f)
        assertTrue(sk.strokes[0].p[1] < 40f)
        assertTrue(sk.h >= 80f)
    }

    @Test
    fun readsTheWebAppsJson() {
        val sk = SketchCodec.parse("""{"h":120,"s":[{"c":"pink","w":3,"p":[1,2,3,4]}]}""")
        assertEquals(120f, sk!!.h)
        assertEquals(HighlightColor.PINK, sk.strokes[0].color)
        assertEquals(4, sk.strokes[0].p.size)
    }

    @Test
    fun rejectsBadInput() {
        assertNull(SketchCodec.parse("nope"))
        assertNull(SketchCodec.parse("""{"s":[]}"""))
        assertNull(SketchCodec.parse(null))
    }
}
