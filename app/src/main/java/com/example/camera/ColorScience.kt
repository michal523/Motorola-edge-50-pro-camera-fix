package com.example.camera

import android.hardware.camera2.params.RggbChannelVector
import androidx.compose.ui.graphics.ColorMatrix
import com.example.domain.model.LiveExposureEdit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object ColorScience {

    // Full unrestricted shutter scale from 1/16000s (62,500 ns) to 60s (60,000,000,000 ns)
    val SHUTTER_PRESETS_NS: List<Long> = listOf(
        62_500L,          // 1/16000s (giga krótki)
        125_000L,         // 1/8000s
        250_000L,         // 1/4000s
        500_000L,         // 1/2000s
        1_000_000L,       // 1/1000s
        2_000_000L,       // 1/500s
        4_000_000L,       // 1/250s
        8_000_000L,       // 1/125s
        16_666_667L,      // 1/60s
        33_333_333L,      // 1/30s
        66_666_667L,      // 1/15s
        125_000_000L,     // 1/8s
        250_000_000L,     // 1/4s
        500_000_000L,     // 1/2s
        1_000_000_000L,   // 1s
        2_000_000_000L,   // 2s
        4_000_000_000L,   // 4s
        8_000_000_000L,   // 8s
        15_000_000_000L,  // 15s
        30_000_000_000L,  // 30s
        60_000_000_000L   // 60s (bardzo długi)
    )

    fun formatShutterNs(ns: Long): String {
        return when {
            ns >= 1_000_000_000L -> {
                val sec = ns / 1_000_000_000.0
                if (sec >= 10.0) "${sec.roundToInt()}s"
                else String.format("%.1fs", sec)
            }
            ns >= 250_000_000L -> {
                val denom = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
                "1/${denom}s"
            }
            else -> {
                val denom = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
                "1/$denom"
            }
        }
    }

    fun sliderToShutterNs(fraction: Float): Long {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0.001f) return 62_500L
        if (clamped >= 0.999f) return 60_000_000_000L
        val minLog = ln(62_500.0)
        val maxLog = ln(60_000_000_000.0)
        return kotlin.math.exp(minLog + clamped * (maxLog - minLog)).toLong()
            .coerceIn(62_500L, 60_000_000_000L)
    }

    fun shutterNsToSlider(ns: Long): Float {
        val minLog = ln(62_500.0)
        val maxLog = ln(60_000_000_000.0)
        val cur = ln(ns.coerceIn(62_500L, 60_000_000_000L).toDouble())
        return ((cur - minLog) / (maxLog - minLog)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Converts Kelvin (2000K..10000K) and Green-Magenta Tint (-50..+50)
     * into Camera2 RggbChannelVector gains normalized for sensor color correction.
     */
    fun kelvinToRggbGains(kelvin: Int, tint: Int): RggbChannelVector {
        val temp = (kelvin.coerceIn(2000, 10000)) / 100.0

        val r = if (temp <= 66.0) {
            255.0
        } else {
            (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        val g = if (temp <= 66.0) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        val b = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        // Camera2 gains compensate for illuminant (inverse of light source color)
        val invR = (255.0 / r.coerceAtLeast(35.0)).toFloat()
        val invG = (255.0 / g.coerceAtLeast(35.0)).toFloat()
        val invB = (255.0 / b.coerceAtLeast(35.0)).toFloat()

        // Normalize so green channel sits around ~1.0 baseline, then apply Tint
        val norm = invG.coerceAtLeast(0.01f)
        val tintScale = 1.0f + (tint.coerceIn(-50, 50) / 100f) * 0.45f
        val rGain = (invR / norm * 1.45f).coerceIn(0.6f, 4.0f)
        val gGain = (1.0f / tintScale).coerceIn(0.65f, 1.8f)
        val bGain = (invB / norm * 1.45f).coerceIn(0.6f, 4.0f)

        return RggbChannelVector(rGain, gGain, gGain, bGain)
    }

    /**
     * Builds a real-time 4x5 ColorMatrix for Compose GPU preview & fast live exposure preview.
     */
    fun buildPreviewColorMatrix(edit: LiveExposureEdit): ColorMatrix {
        val exposureScale = 2f.pow(edit.evOffset)
        val contrast = edit.contrast
        val contrastTranslate = 128f * (1f - contrast)
        val shadowLift = edit.shadows * 26f
        val highlightCompress = -edit.highlights * 18f
        val baseOffset = contrastTranslate + shadowLift + highlightCompress

        val sat = edit.saturation
        val invSat = 1f - sat
        val rLum = 0.2126f * invSat
        val gLum = 0.7152f * invSat
        val bLum = 0.0722f * invSat

        val m00 = (rLum + sat) * contrast * exposureScale
        val m01 = gLum * contrast * exposureScale
        val m02 = bLum * contrast * exposureScale

        val m10 = rLum * contrast * exposureScale
        val m11 = (gLum + sat) * contrast * exposureScale
        val m12 = bLum * contrast * exposureScale

        val m20 = rLum * contrast * exposureScale
        val m21 = gLum * contrast * exposureScale
        val m22 = (bLum + sat) * contrast * exposureScale

        return ColorMatrix(
            floatArrayOf(
                m00, m01, m02, 0f, baseOffset,
                m10, m11, m12, 0f, baseOffset,
                m20, m21, m22, 0f, baseOffset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
