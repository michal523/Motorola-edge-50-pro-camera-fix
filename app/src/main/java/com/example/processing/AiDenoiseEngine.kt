package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.DenoiseLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * On-Device Computational AI Denoising Engine:
 * 1. Estimates sensor noise sigma dynamically from capture ISO.
 * 2. Separates Y (Luminance) and Cb/Cr (Chrominance).
 * 3. Runs multi-threaded tile Joint-Bilateral Chroma Denoise (eliminates color blotches cleanly).
 * 4. Runs Edge-Preserving Adaptive Bilateral Luma Denoise + Micro-Detail Preservation.
 * 5. In ULTRA_AI_MULTIFRAME mode, aligns and performs Tukey-biweight temporal burst fusion before spatial denoise.
 */
object AiDenoiseEngine {

    suspend fun denoise(
        source: Bitmap,
        level: DenoiseLevel,
        iso: Int,
        burstFrames: List<Bitmap> = emptyList(),
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Bitmap = withContext(Dispatchers.Default) {
        if (level == DenoiseLevel.OFF) return@withContext source

        // Step 1: Optional Multi-Frame Temporal Fusion for Ultra-Night / Deep AI Denoise
        val baseFrame = if (level == DenoiseLevel.ULTRA_AI_MULTIFRAME && burstFrames.size >= 2) {
            onProgress(0.25f, "AI Multi-Frame: dopasowanie ${burstFrames.size} klatek...")
            temporalBurstDenoise(burstFrames)
        } else {
            source
        }

        val w = baseFrame.width
        val h = baseFrame.height
        val srcPixels = IntArray(w * h)
        baseFrame.getPixels(srcPixels, 0, w, 0, 0, w, h)

        onProgress(0.5f, "AI Denoise: separacja YCbCr i estymacja szumu (ISO $iso)...")

        // Convert to floating-point YCbCr planes
        val yPlane = FloatArray(w * h)
        val cbPlane = FloatArray(w * h)
        val crPlane = FloatArray(w * h)

        for (i in srcPixels.indices) {
            val c = srcPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            yPlane[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cbPlane[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            crPlane[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // ISO-calibrated noise model
        val isoFactor = (sqrt((iso.coerceAtLeast(50) / 100f))).coerceIn(0.7f, 6.0f)
        val (lumaRadius, chromaRadius, lumaSigma, chromaSigma, detailBlend) = when (level) {
            DenoiseLevel.OFF -> return@withContext source
            DenoiseLevel.FAST -> Params(1, 2, 10f * isoFactor, 20f * isoFactor, 0.30f)
            DenoiseLevel.BALANCED -> Params(2, 3, 16f * isoFactor, 30f * isoFactor, 0.22f)
            DenoiseLevel.ULTRA_AI_MULTIFRAME -> Params(2, 4, 22f * isoFactor, 40f * isoFactor, 0.18f)
        }

        val outY = FloatArray(w * h)
        val outCb = FloatArray(w * h)
        val outCr = FloatArray(w * h)

        onProgress(0.7f, "AI Denoise: filtracja krawędziowa luminancji i chrominancji...")

        val chunkCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerChunk = (h + chunkCount - 1) / chunkCount

        val lumaSigmaSq2 = 2f * lumaSigma * lumaSigma
        val chromaSigmaSq2 = 2f * chromaSigma * chromaSigma

        (0 until chunkCount).map { chunkIdx ->
            async {
                val startY = chunkIdx * rowsPerChunk
                val endY = minOf(h, startY + rowsPerChunk)

                for (py in startY until endY) {
                    val rowOffset = py * w
                    for (px in 0 until w) {
                        val centerIdx = rowOffset + px
                        val cy = yPlane[centerIdx]
                        val cCb = cbPlane[centerIdx]
                        val cCr = crPlane[centerIdx]

                        // Luma Edge-Preserving Bilateral
                        var sumY = 0f
                        var wSumY = 0f
                        val yMin = maxOf(0, py - lumaRadius)
                        val yMax = minOf(h - 1, py + lumaRadius)
                        val xMin = maxOf(0, px - lumaRadius)
                        val xMax = minOf(w - 1, px + lumaRadius)

                        for (ny in yMin..yMax) {
                            val nRow = ny * w
                            val dy2 = (ny - py) * (ny - py)
                            for (nx in xMin..xMax) {
                                val dx2 = (nx - px) * (nx - px)
                                val nVal = yPlane[nRow + nx]
                                val diff = nVal - cy
                                val weight = exp(-((dx2 + dy2) / 4.5f) - (diff * diff) / lumaSigmaSq2)
                                sumY += nVal * weight
                                wSumY += weight
                            }
                        }
                        val denoisedY = sumY / wSumY.coerceAtLeast(1e-5f)
                        // Micro-detail restoration prevents waxy plastic look
                        outY[centerIdx] = (denoisedY * (1f - detailBlend) + cy * detailBlend).coerceIn(0f, 255f)

                        // Joint-Bilateral Chroma Denoise (guided by Luma edges + Chroma distance)
                        var sumCb = 0f
                        var sumCr = 0f
                        var wSumC = 0f
                        val cyMin = maxOf(0, py - chromaRadius)
                        val cyMax = minOf(h - 1, py + chromaRadius)
                        val cxMin = maxOf(0, px - chromaRadius)
                        val cxMax = minOf(w - 1, px + chromaRadius)

                        for (ny in cyMin..cyMax step 1) {
                            val nRow = ny * w
                            for (nx in cxMin..cxMax step 1) {
                                val nIdx = nRow + nx
                                val dY = yPlane[nIdx] - cy
                                val dCb = cbPlane[nIdx] - cCb
                                val dCr = crPlane[nIdx] - cCr
                                val jointDist = (dY * dY * 0.6f) + (dCb * dCb + dCr * dCr)
                                val weight = exp(-jointDist / chromaSigmaSq2)
                                sumCb += cbPlane[nIdx] * weight
                                sumCr += crPlane[nIdx] * weight
                                wSumC += weight
                            }
                        }
                        outCb[centerIdx] = (sumCb / wSumC.coerceAtLeast(1e-5f)).coerceIn(0f, 255f)
                        outCr[centerIdx] = (sumCr / wSumC.coerceAtLeast(1e-5f)).coerceIn(0f, 255f)
                    }
                }
            }
        }.awaitAll()

        onProgress(0.95f, "Składanie odszumionego obrazu...")
        val outPixels = IntArray(w * h)
        for (i in outPixels.indices) {
            val y = outY[i]
            val cb = outCb[i] - 128f
            val cr = outCr[i] - 128f
            val r = (y + 1.402f * cr).toInt().coerceIn(0, 255)
            val g = (y - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
            val b = (y + 1.772f * cb).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Aligns burst frames using global/tile SAD matching and fuses pixels via robust outlier-rejected averaging.
     */
    private fun temporalBurstDenoise(frames: List<Bitmap>): Bitmap {
        val ref = frames.first()
        val w = ref.width
        val h = ref.height
        val refPixels = IntArray(w * h)
        ref.getPixels(refPixels, 0, w, 0, 0, w, h)

        val accR = FloatArray(w * h)
        val accG = FloatArray(w * h)
        val accB = FloatArray(w * h)
        val accW = FloatArray(w * h)

        for (i in refPixels.indices) {
            val c = refPixels[i]
            accR[i] = ((c shr 16) and 0xFF).toFloat()
            accG[i] = ((c shr 8) and 0xFF).toFloat()
            accB[i] = (c and 0xFF).toFloat()
            accW[i] = 1f
        }

        val buf = IntArray(w * h)
        for (fIdx in 1 until frames.size) {
            val f = frames[fIdx]
            if (f.width != w || f.height != h) continue
            f.getPixels(buf, 0, w, 0, 0, w, h)
            val (dx, dy) = estimateGlobalShift(refPixels, buf, w, h, searchRadius = 10)

            for (y in 0 until h) {
                val sy = (y + dy).coerceIn(0, h - 1)
                val dstRow = y * w
                val srcRow = sy * w
                for (x in 0 until w) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val c = buf[srcRow + sx]
                    val rc = refPixels[dstRow + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val rr = (rc shr 16) and 0xFF
                    val rg = (rc shr 8) and 0xFF
                    val rb = rc and 0xFF

                    // Robust motion ghost rejection (Tukey-like weight)
                    val diff = abs(r - rr) + abs(g - rg) + abs(b - rb)
                    val wPixel = if (diff < 65) 1f else exp(-(diff - 65) / 35f)
                    val idx = dstRow + x
                    accR[idx] += r * wPixel
                    accG[idx] += g * wPixel
                    accB[idx] += b * wPixel
                    accW[idx] += wPixel
                }
            }
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            val inv = 1f / accW[i].coerceAtLeast(1f)
            val r = (accR[i] * inv).toInt().coerceIn(0, 255)
            val g = (accG[i] * inv).toInt().coerceIn(0, 255)
            val b = (accB[i] * inv).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    internal fun estimateGlobalShift(
        ref: IntArray,
        target: IntArray,
        w: Int,
        h: Int,
        searchRadius: Int
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        val cxStart = (w * 0.25f).toInt()
        val cxEnd = (w * 0.75f).toInt()
        val cyStart = (h * 0.25f).toInt()
        val cyEnd = (h * 0.75f).toInt()

        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                var sad = 0L
                for (y in cyStart until cyEnd step 8) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val rRow = y * w
                    val tRow = sy * w
                    for (x in cxStart until cxEnd step 8) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val rc = ref[rRow + x]
                        val tc = target[tRow + sx]
                        val rLum = ((rc shr 16) and 0xFF) + ((rc shr 8) and 0xFF) + (rc and 0xFF)
                        val tLum = ((tc shr 16) and 0xFF) + ((tc shr 8) and 0xFF) + (tc and 0xFF)
                        sad += abs(rLum - tLum)
                    }
                }
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private data class Params(
        val lumaRadius: Int,
        val chromaRadius: Int,
        val lumaSigma: Float,
        val chromaSigma: Float,
        val detailBlend: Float
    )
}
