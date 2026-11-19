package com.example.processing

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import kotlin.math.abs

/**
 * Real-time YUV_420_888 Live Analyzer (~15 fps):
 * - Computes 64-bin Luma + RGB Histograms & highlight/shadow clipping %
 * - Computes Sobel Edge Focus Peaking overlay Bitmap
 * - Computes Zebra highlight warning overlay
 */
class LiveFrameAnalyzer {

    data class LiveAnalysisResult(
        val lumaBins: FloatArray,
        val redBins: FloatArray,
        val greenBins: FloatArray,
        val blueBins: FloatArray,
        val highlightClipPct: Float,
        val shadowClipPct: Float,
        val peakingOverlay: Bitmap?
    )

    private var lastAnalyzedMs = 0L

    fun analyzeIfReady(
        image: Image,
        sensorOrientation: Int,
        isFront: Boolean,
        computeRgb: Boolean,
        computePeaking: Boolean,
        peakingColorArgb: Int,
        peakingSensitivity: Float,
        zebraEnabled: Boolean
    ): LiveAnalysisResult? {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedMs < 65L) return null // ~15 fps budget
        lastAnalyzedMs = now

        val w = image.width
        val h = image.height
        if (w < 16 || h < 16) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val lumaCounts = IntArray(64)
        val rCounts = IntArray(64)
        val gCounts = IntArray(64)
        val bCounts = IntArray(64)

        var totalSamples = 0
        var highlightClips = 0
        var shadowClips = 0

        val step = 4
        for (y in 0 until h step step) {
            val yRow = y * yRowStride
            val uvRow = (y / 2) * uvRowStride
            for (x in 0 until w step step) {
                val yVal = yBuf.get(yRow + x * yPixelStride).toInt() and 0xFF
                val bin = (yVal * 63) / 255
                lumaCounts[bin]++
                if (yVal >= 246) highlightClips++
                if (yVal <= 8) shadowClips++

                if (computeRgb) {
                    val uvIdx = uvRow + (x / 2) * uvPixelStride
                    val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                    val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128
                    val r = (yVal + (1.370705f * v)).toInt().coerceIn(0, 255)
                    val g = (yVal - (0.337633f * u) - (0.698001f * v)).toInt().coerceIn(0, 255)
                    val b = (yVal + (1.732446f * u)).toInt().coerceIn(0, 255)
                    rCounts[(r * 63) / 255]++
                    gCounts[(g * 63) / 255]++
                    bCounts[(b * 63) / 255]++
                }
                totalSamples++
            }
        }

        val normLuma = normalizeBins(lumaCounts)
        val normR = if (computeRgb) normalizeBins(rCounts) else normLuma
        val normG = if (computeRgb) normalizeBins(gCounts) else normLuma
        val normB = if (computeRgb) normalizeBins(bCounts) else normLuma

        var overlayBmp: Bitmap? = null
        if (computePeaking || zebraEnabled) {
            val ds = 2 // 320x240 overlay for ultra-low latency
            val ow = w / ds
            val oh = h / ds
            val pixels = IntArray(ow * oh)
            // Sensitivity 0..1 maps threshold 180..35
            val gradThreshold = (180f - peakingSensitivity.coerceIn(0f, 1f) * 145f).toInt()
            val zebraStripeColor = 0xCCFF9E2C.toInt()

            for (oy in 1 until oh - 1) {
                val sy = oy * ds
                val rowPrev = (sy - ds) * yRowStride
                val rowCur = sy * yRowStride
                val rowNext = (sy + ds) * yRowStride
                for (ox in 1 until ow - 1) {
                    val sx = ox * ds
                    val c = yBuf.get(rowCur + sx * yPixelStride).toInt() and 0xFF

                    var outColor = 0
                    if (zebraEnabled && c >= 244 && ((ox + oy) % 6 < 3)) {
                        outColor = zebraStripeColor
                    }

                    if (computePeaking) {
                        val tl = yBuf.get(rowPrev + (sx - ds) * yPixelStride).toInt() and 0xFF
                        val t = yBuf.get(rowPrev + sx * yPixelStride).toInt() and 0xFF
                        val tr = yBuf.get(rowPrev + (sx + ds) * yPixelStride).toInt() and 0xFF
                        val l = yBuf.get(rowCur + (sx - ds) * yPixelStride).toInt() and 0xFF
                        val r = yBuf.get(rowCur + (sx + ds) * yPixelStride).toInt() and 0xFF
                        val bl = yBuf.get(rowNext + (sx - ds) * yPixelStride).toInt() and 0xFF
                        val b = yBuf.get(rowNext + sx * yPixelStride).toInt() and 0xFF
                        val br = yBuf.get(rowNext + (sx + ds) * yPixelStride).toInt() and 0xFF

                        val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                        val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                        val mag = (abs(gx) + abs(gy)) / 2
                        if (mag > gradThreshold) {
                            outColor = peakingColorArgb
                        }
                    }
                    pixels[oy * ow + ox] = outColor
                }
            }

            val rawOverlay = Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply {
                postRotate(sensorOrientation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            overlayBmp = Bitmap.createBitmap(rawOverlay, 0, 0, ow, oh, matrix, false)
            if (overlayBmp != rawOverlay) rawOverlay.recycle()
        }

        val totalF = totalSamples.coerceAtLeast(1).toFloat()
        return LiveAnalysisResult(
            lumaBins = normLuma,
            redBins = normR,
            greenBins = normG,
            blueBins = normB,
            highlightClipPct = (highlightClips / totalF) * 100f,
            shadowClipPct = (shadowClips / totalF) * 100f,
            peakingOverlay = overlayBmp
        )
    }

    private fun normalizeBins(counts: IntArray): FloatArray {
        val maxVal = (counts.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        return FloatArray(counts.size) { i -> (counts[i] / maxVal).coerceIn(0f, 1f) }
    }
}
