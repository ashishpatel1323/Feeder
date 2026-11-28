package com.nononsenseapps.feeder.model.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverBitmapsTest {
    @Test
    fun smallCoversDecodeAtFullSize() {
        assertEquals(1, CoverBitmaps.sampleSize(480, 480))
        assertEquals(1, CoverBitmaps.sampleSize(1400, 900))
    }

    @Test
    fun bigCoversDecodeSmallButNotBelowMaxSide() {
        // 3000 px album art: 3000 / 4 = 750 still >= 720, 3000 / 8 would be too small
        assertEquals(4, CoverBitmaps.sampleSize(3000, 3000))
        assertEquals(2, CoverBitmaps.sampleSize(1600, 2560))
    }
}
