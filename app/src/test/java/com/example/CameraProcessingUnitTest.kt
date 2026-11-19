package com.example

import com.example.camera.ColorScience
import com.example.domain.model.LiveExposureEdit
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraProcessingUnitTest {

    @Test
    fun shutterScale_coversUltraShortToUltraLong() {
        val minNs = ColorScience.sliderToShutterNs(0f)
        val maxNs = ColorScience.sliderToShutterNs(1f)
        assertEquals(62_500L, minNs)
        assertEquals(60_000_000_000L, maxNs)
        assertEquals("1/16000", ColorScience.formatShutterNs(minNs))
        assertEquals("60s", ColorScience.formatShutterNs(maxNs))
        assertEquals("1/125", ColorScience.formatShutterNs(8_000_000L))
    }

    @Test
    fun colorMatrix_defaultEditIsIdentityScale() {
        val matrix = ColorScience.buildPreviewColorMatrix(LiveExposureEdit())
        assertEquals(1f, matrix.values[0], 0.01f)
        assertEquals(1f, matrix.values[6], 0.01f)
        assertEquals(1f, matrix.values[12], 0.01f)
        assertEquals(0f, matrix.values[4], 0.01f)
    }
}
