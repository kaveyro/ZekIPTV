package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatcherTest {

    private val uhd60 = DisplayModeSpec(1, 3840, 2160, 60f)
    private val uhd50 = DisplayModeSpec(2, 3840, 2160, 50f)
    private val uhd24 = DisplayModeSpec(3, 3840, 2160, 24f)
    private val uhd23976 = DisplayModeSpec(4, 3840, 2160, 23.976f)
    private val uhd5994 = DisplayModeSpec(5, 3840, 2160, 59.94f)
    private val fhd50 = DisplayModeSpec(6, 1920, 1080, 50f)
    private val modes = listOf(uhd60, uhd50, uhd24, uhd23976, uhd5994, fhd50)

    @Test
    fun `snaps measured frame rates to standard values`() {
        assertEquals(25f, snapFrameRate(25.1f))
        assertEquals(23.976f, snapFrameRate(23.98f))
        assertEquals(59.94f, snapFrameRate(59.9f))
        assertNull(snapFrameRate(37f))
        assertNull(snapFrameRate(0f))
    }

    @Test
    fun `european tv at 25 or 50 fps switches to 50 hz in same resolution`() {
        assertEquals(2, pickDisplayMode(modes, uhd60, 25f))
        assertEquals(2, pickDisplayMode(modes, uhd60, 50f))
    }

    @Test
    fun `film prefers exact 23976 over 24 hz`() {
        assertEquals(4, pickDisplayMode(modes, uhd60, 23.976f))
        assertEquals(3, pickDisplayMode(modes, uhd60, 24f))
    }

    @Test
    fun `ntsc content uses 5994 not 60`() {
        assertEquals(5, pickDisplayMode(modes, uhd60, 29.97f))
    }

    @Test
    fun `no switch when current mode already fits or nothing fits`() {
        assertNull(pickDisplayMode(modes, uhd50, 25f))
        assertNull(pickDisplayMode(modes, uhd60, 30f))
        assertNull(pickDisplayMode(listOf(uhd60), uhd60, 25f))
    }

    @Test
    fun `estimator measures frame rate from timestamps`() {
        val estimator = FrameRateEstimator(samplesNeeded = 10)
        var result: Float? = null
        for (i in 0..10) result = estimator.onFrame(i * 40_000L) ?: result // 25 fps
        assertEquals(25f, result!!, 0.01f)
    }
}
