package com.nononsenseapps.feeder.model.highlights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkCodecTest {
    @Test
    fun readsTheWebAppsJsonAndBack() {
        val raw = """{"v":1,"sig":"604|18px|29.7px|Georgia|192","ox":47.8,"oy":-0.2,"w":238.4,"h":20.1,"s":[{"c":"pink","w":2.2,"p":[0,1,10.3,2]}],"prefs":{"textSize":18}}"""
        val d = InkCodec.parse(raw)!!
        assertEquals("604|18px|29.7px|Georgia|192", d.sig)
        assertEquals(HighlightColor.PINK, d.strokes[0].color)
        assertEquals("18", d.prefs["textSize"].toString())
        val again = InkCodec.parse(InkCodec.encode(d))!!
        assertEquals(d.sig, again.sig)
        assertEquals(10.3f, again.strokes[0].p[2], 0.01f)
    }

    @Test
    fun rejectsBadInput() {
        assertNull(InkCodec.parse("nope"))
        assertNull(InkCodec.parse("""{"sig":"x","s":[]}"""))
    }
}
