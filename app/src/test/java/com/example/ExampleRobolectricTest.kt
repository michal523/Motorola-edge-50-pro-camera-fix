package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DenoiseLevel
import com.example.domain.model.MotionBlurStyle
import com.example.processing.AiDenoiseEngine
import com.example.processing.ComputationalModesEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Kadr RAW", appName)
    }

    @Test
    fun `ai denoise preserves bitmap dimensions`() = runTest {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.DKGRAY)
        val out = AiDenoiseEngine.denoise(bmp, DenoiseLevel.BALANCED, iso = 1600)
        assertEquals(64, out.width)
        assertEquals(64, out.height)
    }

    @Test
    fun `super resolution doubles crop dimensions`() = runTest {
        val f1 = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val f2 = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val out = ComputationalModesEngine.processSuperResolution(listOf(f1, f2), zoomRatio = 1.0f)
        assertEquals(640, out.width)
        assertEquals(480, out.height)
    }

    @Test
    fun `motion blur blends multiple frames cleanly`() = runTest {
        val f1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 100, 100)) }
        val f2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }
        val out = ComputationalModesEngine.processMotionBlur(listOf(f1, f2), MotionBlurStyle.SILKY_WATER)
        assertEquals(100, out.width)
        val px = out.getPixel(50, 50)
        assertEquals(150, Color.red(px))
    }

    @Test
    fun `kelvin to rggb gains shifts warm and cool channels`() {
        val warm = com.example.camera.ColorScience.kelvinToRggbGains(2500, 0)
        val cool = com.example.camera.ColorScience.kelvinToRggbGains(9000, 0)
        org.junit.Assert.assertTrue(warm.blue > warm.red)
        org.junit.Assert.assertTrue(cool.red > cool.blue)
    }
}
