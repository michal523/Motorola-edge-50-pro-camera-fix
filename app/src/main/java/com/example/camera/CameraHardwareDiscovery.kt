package com.example.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import com.example.domain.model.CameraLensProfile
import kotlin.math.roundToInt

object CameraHardwareDiscovery {

    data class DiscoveryResult(
        val lenses: List<CameraLensProfile>,
        val deviceModel: String,
        val isEdge50ProDetected: Boolean
    )

    fun discoverLenses(context: Context): DiscoveryResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val modelName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val isEdge50Pro = Build.MODEL.contains("edge 50 pro", ignoreCase = true) ||
            Build.DEVICE.contains("eqs", ignoreCase = true)

        val profiles = mutableListOf<CameraLensProfile>()
        val seenFocalSignatures = mutableSetOf<String>()

        val cameraIds = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())

        // 1. Inspect logical cameras and their physical sensors
        for (camId in cameraIds) {
            val chars = runCatching { manager.getCameraCharacteristics(camId) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val isLogicalMulti = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            } else false

            if (isLogicalMulti && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isFront) {
                val physicalIds = chars.physicalCameraIds
                // Always add main logical camera (usually 1x) + any distinct physical lenses (0.5x UW, 3x Tele)
                buildProfile(camId, null, chars, isFront)?.let { mainProfile ->
                    val sig = "${mainProfile.isFront}-${mainProfile.focalLengthMm}"
                    if (seenFocalSignatures.add(sig)) {
                        profiles.add(mainProfile)
                    }
                }
                for (physId in physicalIds) {
                    val physChars = runCatching { manager.getCameraCharacteristics(physId) }.getOrNull() ?: continue
                    // Some OEMs allow opening physicalId directly OR via logical cam; prefer direct if listed in cameraIdList
                    val openableId = if (cameraIds.contains(physId)) physId else camId
                    val physRefId = if (openableId == camId) physId else null
                    buildProfile(openableId, physRefId, physChars, isFront = false)?.let { p ->
                        val sig = "false-${(p.focalLengthMm * 10).roundToInt()}"
                        if (seenFocalSignatures.add(sig)) {
                            profiles.add(p)
                        }
                    }
                }
            } else {
                buildProfile(camId, null, chars, isFront)?.let { p ->
                    val sig = "${p.isFront}-${(p.focalLengthMm * 10).roundToInt()}"
                    if (seenFocalSignatures.add(sig)) {
                        profiles.add(p)
                    }
                }
            }
        }

        // Assign friendly 0.5x / 1x / 3x labels sorted by focal length for back lenses
        val backLenses = profiles.filter { !it.isFront }.sortedBy { it.focalLengthMm }
        val mainFocal = backLenses.maxByOrNull { it.megapixels }?.focalLengthMm
            ?: backLenses.getOrNull(backLenses.size / 2)?.focalLengthMm
            ?: 5.5f

        val labeledBack = backLenses.map { lens ->
            val ratio = (lens.focalLengthMm / mainFocal.coerceAtLeast(1f))
            val friendlyLabel = when {
                ratio < 0.72f -> "0.5x"
                ratio > 1.8f -> String.format("%.0fx", ratio.coerceAtLeast(2f))
                else -> "1x"
            }
            val friendlyName = when {
                friendlyLabel == "0.5x" -> "Szerokokątny (UW)"
                friendlyLabel == "1x" -> if (lens.megapixels >= 40f) "Główny ${lens.megapixels.roundToInt()}MP" else "Główny"
                else -> "Teleobiektyw $friendlyLabel"
            }
            lens.copy(label = friendlyLabel, fullNamePl = friendlyName)
        }

        val frontLenses = profiles.filter { it.isFront }.take(1).map {
            it.copy(label = "Front", fullNamePl = "Aparat przedni")
        }

        return DiscoveryResult(
            lenses = labeledBack + frontLenses,
            deviceModel = modelName,
            isEdge50ProDetected = isEdge50Pro
        )
    }

    private fun buildProfile(
        cameraId: String,
        physicalId: String?,
        chars: CameraCharacteristics,
        isFront: Boolean
    ): CameraLensProfile? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val highResJpeg = map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val allJpeg = (jpegSizes + highResJpeg).distinctBy { "${it.width}x${it.height}" }
        val bestJpeg = allJpeg.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val supportsRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supportsManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        val rawSizes = if (supportsRaw) {
            (map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() +
                map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty())
        } else emptyList()
        var bestRaw = rawSizes.maxByOrNull { it.width * it.height }

        var max50MpSize: Size? = null
        var supportsUltraHighRes = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            supportsUltraHighRes = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
            )
            val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (maxResMap != null) {
                val maxJpeg = (maxResMap.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
                    maxResMap.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty())
                    .maxByOrNull { it.width * it.height }
                if (maxJpeg != null && maxJpeg.width * maxJpeg.height > bestJpeg.width * bestJpeg.height) {
                    max50MpSize = maxJpeg
                }
                val maxRaw = (maxResMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() +
                    maxResMap.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty())
                    .maxByOrNull { it.width * it.height }
                if (maxRaw != null && (bestRaw == null || maxRaw.width * maxRaw.height > bestRaw.width * bestRaw.height)) {
                    bestRaw = maxRaw
                }
            }
        }

        val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val effectiveSize = max50MpSize ?: bestJpeg
        val pixelMp = if (pixelArraySize != null && pixelArraySize.width * pixelArraySize.height > effectiveSize.width * effectiveSize.height) {
            (pixelArraySize.width * pixelArraySize.height) / 1_000_000f
        } else {
            (effectiveSize.width * effectiveSize.height) / 1_000_000f
        }

        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalMm = focalLengths?.firstOrNull() ?: 5.0f
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val aperture = apertures?.firstOrNull() ?: 1.8f

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val supportsEis = eisModes.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF }

        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val exposureLongRange = if (expRange != null) expRange.lower..expRange.upper else 100_000L..1_000_000_000L

        val isoRangeObj = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val isoIntRange = if (isoRangeObj != null) isoRangeObj.lower..isoRangeObj.upper else 100..3200

        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "LEGACY"
        }

        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return CameraLensProfile(
            cameraId = cameraId,
            physicalId = physicalId,
            label = if (isFront) "Front" else "1x",
            fullNamePl = if (isFront) "Aparat przedni" else "Aparat",
            isFront = isFront,
            focalLengthMm = focalMm,
            aperture = aperture,
            maxOutputSize = bestJpeg,
            maxRawSize = bestRaw,
            max50MpSize = max50MpSize ?: if (pixelMp >= 40f) bestJpeg else null,
            megapixels = pixelMp,
            supportsRaw = supportsRaw && bestRaw != null,
            supportsMaxRes50Mp = supportsUltraHighRes || max50MpSize != null || pixelMp >= 40f,
            supportsOis = supportsOis,
            supportsEis = supportsEis,
            supportsManualSensor = supportsManualSensor,
            exposureRangeNs = exposureLongRange,
            isoRange = isoIntRange,
            minFocusDistance = minFocus,
            hardwareLevelLabel = hwLevel,
            sensorOrientation = orientation
        )
    }
}
