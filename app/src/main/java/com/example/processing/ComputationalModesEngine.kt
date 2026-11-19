package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.LiveExposureEdit
import com.example.domain.model.MotionBlurStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow

/**
 * Computational Photography Engine:
 * 1. Daylight Motion Blur / ND Filter Simulator (Silky Water, Light Trails, Dynamic Panning)
 * 2. Handheld Hand-Tremor Sub-Pixel Super-Resolution (2x Drizzle Shift-and-Add + Deconvolution)
 * 3. Real-time Exposure Parameter Baking (EV, Shadows, Highlights, Contrast, Saturation, Sharpness)
 */
object ComputationalModesEngine {

    /**
     * Blends N rapid frames captured at daylight-safe shutter speed to simulate an ND filter
     * or ultra-long exposure without blowing out highlights.
     */
    suspend fun processMotionBlur(
        frames: List<Bitmap>,
        style: MotionBlurStyle,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw IllegalArgumentException("Brak klatek do rozmycia ruchu")
        if (frames.size == 1) return@withContext frames.first()

        val w = frames.first().width
        val h = frames.first().height
        val count = frames.size

        val accR = FloatArray(w * h)
        val accG = FloatArray(w * h)
        val accB = FloatArray(w * h)
        val buf = IntArray(w * h)

        frames.forEachIndexed { idx, bmp ->
            if (bmp.width != w || bmp.height != h) return@forEachIndexed
            onProgress(0.2f + 0.7f * (idx.toFloat() / count), "Składanie klatki ${idx + 1}/$count...")
            bmp.getPixels(buf, 0, w, 0, 0, w, h)

            when (style) {
                MotionBlurStyle.SILKY_WATER -> {
                    for (i in buf.indices) {
                        val c = buf[i]
                        accR[i] += ((c shr 16) and 0xFF)
                        accG[i] += ((c shr 8) and 0xFF)
                        accB[i] += (c and 0xFF)
                    }
                }
                MotionBlurStyle.LIGHT_TRAILS -> {
                    for (i in buf.indices) {
                        val c = buf[i]
                        val r = ((c shr 16) and 0xFF).toFloat()
                        val g = ((c shr 8) and 0xFF).toFloat()
                        val b = (c and 0xFF).toFloat()
                        val newLum = 0.299f * r + 0.587f * g + 0.114f * b
                        val oldLum = 0.299f * accR[i] + 0.587f * accG[i] + 0.114f * accB[i]
                        if (idx == 0 || newLum > oldLum) {
                            accR[i] = r
                            accG[i] = g
                            accB[i] = b
                        }
                    }
                }
                MotionBlurStyle.DYNAMIC_PANNING -> {
                    // Anchor frame (center) gets higher weight; surrounding frames create velocity trail
                    val centerIdx = count / 2
                    val dist = abs(idx - centerIdx)
                    val weight = if (idx == centerIdx) 3.0f else (1.0f / (1f + dist * 0.35f))
                    for (i in buf.indices) {
                        val c = buf[i]
                        accR[i] += ((c shr 16) and 0xFF) * weight
                        accG[i] += ((c shr 8) and 0xFF) * weight
                        accB[i] += (c and 0xFF) * weight
                    }
                }
            }
        }

        val norm = when (style) {
            MotionBlurStyle.SILKY_WATER -> 1f / count.toFloat()
            MotionBlurStyle.LIGHT_TRAILS -> 1f
            MotionBlurStyle.DYNAMIC_PANNING -> {
                val centerIdx = count / 2
                var totalW = 0f
                for (idx in 0 until count) {
                    val dist = abs(idx - centerIdx)
                    totalW += if (idx == centerIdx) 3.0f else (1.0f / (1f + dist * 0.35f))
                }
                1f / totalW.coerceAtLeast(1f)
            }
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            val r = (accR[i] * norm).toInt().coerceIn(0, 255)
            val g = (accG[i] * norm).toInt().coerceIn(0, 255)
            val b = (accB[i] * norm).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        onProgress(1f, "Gotowe!")
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Handheld Multi-Frame Super-Resolution (2x Drizzle Shift-and-Add):
     * Uses natural hand tremor across burst frames to reconstruct sub-pixel detail
     * beyond optical telephoto/digital zoom limits.
     */
    suspend fun processSuperResolution(
        frames: List<Bitmap>,
        zoomRatio: Float,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw IllegalArgumentException("Brak klatek do Super-Rozdzielczości")
        if (frames.size == 1) return@withContext frames.first()

        onProgress(0.15f, "Analiza ostrości klatek bazowych...")
        // 1. Score sharpness via Laplacian energy to pick sharpest anchor frame
        val anchorIdx = frames.indices.maxByOrNull { computeSharpnessScore(frames[it]) } ?: 0
        val anchor = frames[anchorIdx]
        val srcW = anchor.width
        val srcH = anchor.height

        // Crop center region if digital zoom > 1.2x so Drizzle reconstructs zoomed detail at high res
        val effectiveCropScale = if (zoomRatio > 1.2f) (1f / zoomRatio.coerceIn(1f, 4f)) else 1f
        val cropW = ((srcW * effectiveCropScale).toInt()).coerceAtLeast(240)
        val cropH = ((srcH * effectiveCropScale).toInt()).coerceAtLeast(240)
        val cropX = (srcW - cropW) / 2
        val cropY = (srcH - cropH) / 2

        val upW = cropW * 2
        val upH = cropH * 2

        val accR = FloatArray(upW * upH)
        val accG = FloatArray(upW * upH)
        val accB = FloatArray(upW * upH)
        val accW = FloatArray(upW * upH)

        val anchorCrop = Bitmap.createBitmap(anchor, cropX, cropY, cropW, cropH)
        val anchorPixels = IntArray(cropW * cropH)
        anchorCrop.getPixels(anchorPixels, 0, cropW, 0, 0, cropW, cropH)
        if (anchorCrop != anchor) anchorCrop.recycle()

        // Seed 2x canvas with bilinear anchor splat
        splatSubPixelFrame(anchorPixels, cropW, cropH, 0f, 0f, 1.4f, accR, accG, accB, accW, upW, upH)

        // 2. Align every other frame with sub-pixel precision (hand-tremor shifts) and Drizzle-accumulate
        val buf = IntArray(cropW * cropH)
        frames.forEachIndexed { idx, bmp ->
            if (idx == anchorIdx) return@forEachIndexed
            if (bmp.width != srcW || bmp.height != srcH) return@forEachIndexed
            onProgress(
                0.25f + 0.55f * (idx.toFloat() / frames.size),
                "Sub-pikselowe składanie drgań ręki (${idx + 1}/${frames.size})..."
            )
            val cropped = Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH)
            cropped.getPixels(buf, 0, cropW, 0, 0, cropW, cropH)
            if (cropped != bmp) cropped.recycle()

            val (subDx, subDy) = estimateSubPixelShift(anchorPixels, buf, cropW, cropH)
            splatSubPixelFrame(buf, cropW, cropH, subDx, subDy, 1.0f, accR, accG, accB, accW, upW, upH)
        }

        onProgress(0.88f, "Dekonwolucja i wyostrzanie krawędzi Super-Res...")
        val merged = IntArray(upW * upH)
        for (i in merged.indices) {
            val inv = 1f / accW[i].coerceAtLeast(1e-4f)
            val r = (accR[i] * inv).toInt().coerceIn(0, 255)
            val g = (accG[i] * inv).toInt().coerceIn(0, 255)
            val b = (accB[i] * inv).toInt().coerceIn(0, 255)
            merged[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Mild deconvolution unsharp pass to recover crisp optical micro-contrast
        val sharpened = applyUnsharpDeconvolution(merged, upW, upH, amount = 0.45f)
        onProgress(1f, "Super-Rozdzielczość gotowa!")
        Bitmap.createBitmap(sharpened, upW, upH, Bitmap.Config.ARGB_8888)
    }

    private fun splatSubPixelFrame(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        shiftX: Float,
        shiftY: Float,
        frameWeight: Float,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        accW: FloatArray,
        upW: Int,
        upH: Int
    ) {
        val sx2 = shiftX * 2f
        val sy2 = shiftY * 2f
        for (y in 0 until srcH) {
            val row = y * srcW
            val baseUy = y * 2f + sy2
            val uy0 = baseUy.toInt().coerceIn(0, upH - 1)
            val uy1 = (uy0 + 1).coerceIn(0, upH - 1)
            val fy = (baseUy - uy0).coerceIn(0f, 1f)

            for (x in 0 until srcW) {
                val c = pixels[row + x]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()

                val baseUx = x * 2f + sx2
                val ux0 = baseUx.toInt().coerceIn(0, upW - 1)
                val ux1 = (ux0 + 1).coerceIn(0, upW - 1)
                val fx = (baseUx - ux0).coerceIn(0f, 1f)

                val w00 = (1f - fx) * (1f - fy) * frameWeight
                val w10 = fx * (1f - fy) * frameWeight
                val w01 = (1f - fx) * fy * frameWeight
                val w11 = fx * fy * frameWeight

                val i00 = uy0 * upW + ux0
                val i10 = uy0 * upW + ux1
                val i01 = uy1 * upW + ux0
                val i11 = uy1 * upW + ux1

                accR[i00] += r * w00; accG[i00] += g * w00; accB[i00] += b * w00; accW[i00] += w00
                accR[i10] += r * w10; accG[i10] += g * w10; accB[i10] += b * w10; accW[i10] += w10
                accR[i01] += r * w01; accG[i01] += g * w01; accB[i01] += b * w01; accW[i01] += w01
                accR[i11] += r * w11; accG[i11] += g * w11; accB[i11] += b * w11; accW[i11] += w11
            }
        }
    }

    private fun estimateSubPixelShift(ref: IntArray, target: IntArray, w: Int, h: Int): Pair<Float, Float> {
        val (intDx, intDy) = AiDenoiseEngine.estimateGlobalShift(ref, target, w, h, searchRadius = 8)
        // Sub-pixel refinement around (intDx, intDy) at [-1, 0, +1]
        val sadXNeg = regionSad(ref, target, w, h, intDx - 1, intDy)
        val sadCenter = regionSad(ref, target, w, h, intDx, intDy)
        val sadXPos = regionSad(ref, target, w, h, intDx + 1, intDy)

        val sadYNeg = regionSad(ref, target, w, h, intDx, intDy - 1)
        val sadYPos = regionSad(ref, target, w, h, intDx, intDy + 1)

        val subX = parabolicOffset(sadXNeg, sadCenter, sadXPos)
        val subY = parabolicOffset(sadYNeg, sadCenter, sadYPos)
        return (intDx + subX) to (intDy + subY)
    }

    private fun parabolicOffset(left: Long, center: Long, right: Long): Float {
        val denom = 2.0 * (left - 2.0 * center + right)
        if (abs(denom) < 1e-3) return 0f
        return ((left - right) / denom).toFloat().coerceIn(-0.5f, 0.5f)
    }

    private fun regionSad(ref: IntArray, target: IntArray, w: Int, h: Int, dx: Int, dy: Int): Long {
        var sad = 0L
        val x0 = (w * 0.2f).toInt()
        val x1 = (w * 0.8f).toInt()
        val y0 = (h * 0.2f).toInt()
        val y1 = (h * 0.8f).toInt()
        for (y in y0 until y1 step 6) {
            val sy = (y + dy).coerceIn(0, h - 1)
            val rRow = y * w
            val tRow = sy * w
            for (x in x0 until x1 step 6) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val rc = ref[rRow + x]
                val tc = target[tRow + sx]
                val rL = ((rc shr 16) and 0xFF) + ((rc shr 8) and 0xFF) + (rc and 0xFF)
                val tL = ((tc shr 16) and 0xFF) + ((tc shr 8) and 0xFF) + (tc and 0xFF)
                sad += abs(rL - tL)
            }
        }
        return sad
    }

    private fun computeSharpnessScore(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        val cx = w / 4
        val cy = h / 4
        val cw = (w / 2).coerceAtLeast(16)
        val ch = (h / 2).coerceAtLeast(16)
        val pixels = IntArray(cw * ch)
        bmp.getPixels(pixels, 0, cw, cx, cy, cw, ch)
        var energy = 0L
        for (y in 1 until ch - 1 step 2) {
            for (x in 1 until cw - 1 step 2) {
                val c = (pixels[y * cw + x] shr 8) and 0xFF
                val r = (pixels[y * cw + x + 1] shr 8) and 0xFF
                val d = (pixels[(y + 1) * cw + x] shr 8) and 0xFF
                energy += abs(c - r) + abs(c - d)
            }
        }
        return energy
    }

    private fun applyUnsharpDeconvolution(src: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = src.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val t = src[idx - w]
                val b = src[idx + w]
                val l = src[idx - 1]
                val r = src[idx + 1]

                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF

                val blurR = (((t shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((r shr 16) and 0xFF)) * 0.25f
                val blurG = (((t shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((r shr 8) and 0xFF)) * 0.25f
                val blurB = ((t and 0xFF) + (b and 0xFF) + (l and 0xFF) + (r and 0xFF)) * 0.25f

                val nr = (cr + (cr - blurR) * amount).toInt().coerceIn(0, 255)
                val ng = (cg + (cg - blurG) * amount).toInt().coerceIn(0, 255)
                val nb = (cb + (cb - blurB) * amount).toInt().coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /**
     * Bakes live exposure parameters (EV, Shadows, Highlights, Contrast, Saturation, Sharpness)
     * into final output Bitmap.
     */
    suspend fun bakeExposureEdits(source: Bitmap, edit: LiveExposureEdit): Bitmap = withContext(Dispatchers.Default) {
        if (edit.evOffset == 0f && edit.shadows == 0f && edit.highlights == 0f &&
            edit.contrast == 1f && edit.saturation == 1f && edit.sharpness == 0f
        ) {
            return@withContext source
        }

        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val evGain = 2f.pow(edit.evOffset)
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = ((c shr 16) and 0xFF) / 255f
            var g = ((c shr 8) and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            r *= evGain; g *= evGain; b *= evGain

            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1.5f)
            // Shadows lift on low luminance
            if (edit.shadows != 0f) {
                val shadowMask = (1f - lum).coerceIn(0f, 1f).pow(2f)
                val shift = edit.shadows * 0.35f * shadowMask
                r += shift; g += shift; b += shift
            }
            // Highlights recovery on high luminance
            if (edit.highlights != 0f) {
                val hiMask = lum.coerceIn(0f, 1f).pow(2f)
                val shift = edit.highlights * 0.32f * hiMask
                r += shift; g += shift; b += shift
            }

            // Contrast around 0.5 midpoint
            r = ((r - 0.5f) * edit.contrast + 0.5f)
            g = ((g - 0.5f) * edit.contrast + 0.5f)
            b = ((b - 0.5f) * edit.contrast + 0.5f)

            // Saturation
            val postLum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = postLum + (r - postLum) * edit.saturation
            g = postLum + (g - postLum) * edit.saturation
            b = postLum + (b - postLum) * edit.saturation

            val ir = (r * 255f).toInt().coerceIn(0, 255)
            val ig = (g * 255f).toInt().coerceIn(0, 255)
            val ib = (b * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
        }

        val finalPixels = if (edit.sharpness > 0.05f) {
            applyUnsharpDeconvolution(pixels, w, h, edit.sharpness * 0.8f)
        } else pixels

        Bitmap.createBitmap(finalPixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
