package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.domain.model.CameraLensProfile
import com.example.domain.model.CameraMode
import com.example.domain.model.CaptureFormat
import com.example.domain.model.IspProcessingMode
import com.example.domain.model.ManualControlsState
import com.example.domain.model.WhiteBalancePreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Low-level Camera2 Engine giving unrestricted access to:
 * - 50 MP Maximum Resolution stream (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
 * - 16-bit Bayer RAW_SENSOR -> DngCreator
 * - Full Manual Shutter (1/16000s..hardware max + multi-frame stacking up to 60s), ISO, Kelvin+Tint WB, Manual Focus
 * - Zero-Processing ISP Bypass (EDGE_MODE_OFF, NOISE_REDUCTION_MODE_OFF)
 * - Live YUV stream for Histogram, Focus Peaking & Computational Burst (Motion Blur / Super-Res)
 * - 4K Stabilized Video Surface (OIS + EIS)
 */
class Camera2Engine(
    private val context: Context,
    private val onLiveYuvFrame: (Image) -> Unit,
    private val onLiveExposureReadout: (iso: Int, shutterNs: Long) -> Unit,
    private val onCameraError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("KadrCamera2Thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var videoRecordSurface: Surface? = null

    private var yuvReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var currentLens: CameraLensProfile? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var currentControls: ManualControlsState = ManualControlsState()

    private var pendingRawDeferred: CompletableDeferred<Pair<Image, TotalCaptureResult>?>? = null
    private var pendingJpegDeferred: CompletableDeferred<Pair<ByteArray, TotalCaptureResult>?>? = null
    private var pendingBurstCollector: MutableList<Bitmap>? = null
    private var pendingBurstTargetCount = 0
    private var pendingBurstDeferred: CompletableDeferred<List<Bitmap>>? = null
    private val isCapturingBurst = AtomicBoolean(false)

    @Volatile
    private var lastAutoIso = 100
    @Volatile
    private var lastAutoShutterNs = 10_000_000L

    val previewAspectRatio: Float
        get() = 4f / 3f

    @SuppressLint("MissingPermission")
    fun openCamera(
        lens: CameraLensProfile,
        previewSurface: Surface,
        controls: ManualControlsState,
        videoSurface: Surface? = null
    ) {
        this.previewSurface = previewSurface
        this.videoRecordSurface = videoSurface
        this.currentLens = lens
        this.currentControls = controls
        closeSessionOnly()

        if (cameraDevice?.id == lens.cameraId && cameraDevice != null) {
            currentCharacteristics = runCatching {
                cameraManager.getCameraCharacteristics(lens.cameraId)
            }.getOrNull()
            createCaptureSession(lens, controls)
            return
        }

        closeCameraDevice()

        try {
            currentCharacteristics = cameraManager.getCameraCharacteristics(lens.cameraId)
            cameraManager.openCamera(
                lens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(lens, currentControls)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                        onCameraError("Błąd matrycy Camera2 (kod $error)")
                    }
                },
                cameraHandler
            )
        } catch (e: SecurityException) {
            onCameraError("Brak uprawnień do aparatu.")
        } catch (e: CameraAccessException) {
            onCameraError("Nie można otworzyć obiektywu: ${e.localizedMessage}")
        }
    }

    fun updateControls(controls: ManualControlsState) {
        val old = this.currentControls
        this.currentControls = controls
        val needReconfigure = old.enable50MpPro != controls.enable50MpPro ||
            old.mode != controls.mode ||
            old.captureFormat != controls.captureFormat
        val lens = currentLens ?: return
        if (needReconfigure) {
            createCaptureSession(lens, controls)
        } else {
            updateRepeatingRequest()
        }
    }

    fun attachVideoSurfaceAndRestart(videoSurface: Surface?, controls: ManualControlsState) {
        this.videoRecordSurface = videoSurface
        this.currentControls = controls
        currentLens?.let { createCaptureSession(it, controls) }
    }

    private fun createCaptureSession(lens: CameraLensProfile, controls: ManualControlsState) {
        val device = cameraDevice ?: return
        val pSurface = previewSurface ?: return
        if (!pSurface.isValid) return

        closeSessionOnly()

        val useVideoMode = controls.mode == CameraMode.VIDEO_4K_RAM && videoRecordSurface != null
        val want50Mp = !useVideoMode &&
            (controls.enable50MpPro || controls.captureFormat == CaptureFormat.ULTRA_50MP_PRO) &&
            lens.max50MpSize != null
        val wantRaw = !useVideoMode && lens.supportsRaw &&
            (controls.captureFormat == CaptureFormat.DNG_RAW ||
                controls.captureFormat == CaptureFormat.DNG_AND_JPEG ||
                controls.mode == CameraMode.RAW_50MP)

        // Live YUV analyzer stream (640x480 for fast 15fps histogram/focus peaking & computational burst)
        val yuvSize = if (controls.mode == CameraMode.SUPER_RES || controls.mode == CameraMode.MOTION_BLUR) {
            Size(1920, 1440)
        } else {
            Size(640, 480)
        }
        yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 4).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (isCapturingBurst.get()) {
                        val bmp = yuvImageToBitmap(img, lens.sensorOrientation, lens.isFront)
                        if (bmp != null) {
                            synchronized(this@Camera2Engine) {
                                pendingBurstCollector?.add(bmp)
                                val cur = pendingBurstCollector?.size ?: 0
                                if (cur >= pendingBurstTargetCount) {
                                    isCapturingBurst.set(false)
                                    val result = pendingBurstCollector?.toList().orEmpty()
                                    pendingBurstDeferred?.complete(result)
                                }
                            }
                        }
                    } else {
                        onLiveYuvFrame(img)
                    }
                } finally {
                    img.close()
                }
            }, cameraHandler)
        }

        val outputs = mutableListOf<OutputConfiguration>()
        val pConfig = OutputConfiguration(pSurface)
        if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pConfig.setPhysicalCameraId(lens.physicalId)
        }
        outputs.add(pConfig)

        val yConfig = OutputConfiguration(yuvReader!!.surface)
        if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            yConfig.setPhysicalCameraId(lens.physicalId)
        }
        outputs.add(yConfig)

        if (useVideoMode && videoRecordSurface != null) {
            val vConfig = OutputConfiguration(videoRecordSurface!!)
            if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                vConfig.setPhysicalCameraId(lens.physicalId)
            }
            outputs.add(vConfig)
        } else {
            val targetJpegSize = if (want50Mp && lens.max50MpSize != null) lens.max50MpSize else lens.maxOutputSize
            jpegReader = ImageReader.newInstance(targetJpegSize.width, targetJpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        pendingJpegBytes = bytes
                        tryCompleteJpegCapture()
                    } finally {
                        img.close()
                    }
                }, cameraHandler)
            }
            val jConfig = OutputConfiguration(jpegReader!!.surface)
            if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                jConfig.setPhysicalCameraId(lens.physicalId)
            }
            outputs.add(jConfig)

            if (wantRaw && lens.maxRawSize != null) {
                rawReader = ImageReader.newInstance(lens.maxRawSize.width, lens.maxRawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val img = reader.acquireLatestImage()
                        if (img != null) {
                            pendingRawImage?.close()
                            pendingRawImage = img
                            tryCompleteRawCapture()
                        }
                    }, cameraHandler)
                }
                val rConfig = OutputConfiguration(rawReader!!.surface)
                if (lens.physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rConfig.setPhysicalCameraId(lens.physicalId)
                }
                outputs.add(rConfig)
            }
        }

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                updateRepeatingRequest()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                // Retry gracefully without RAW or 50MP if OEM HAL rejected combo
                if (wantRaw || want50Mp) {
                    Log.w(TAG, "Session combo rejected; falling back to standard JPEG+YUV")
                    currentControls = controls.copy(enable50MpPro = false, captureFormat = CaptureFormat.JPEG)
                    createCaptureSession(lens, currentControls)
                } else {
                    onCameraError("Konfiguracja sesji kamery nie powiodła się.")
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    sessionExecutor,
                    stateCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(outputs.mapNotNull { it.surface }, stateCallback, cameraHandler)
            }
        } catch (e: Exception) {
            onCameraError("Błąd tworzenia sesji: ${e.localizedMessage}")
        }
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val pSurface = previewSurface ?: return
        if (!pSurface.isValid) return

        val isVideo = currentControls.mode == CameraMode.VIDEO_4K_RAM && videoRecordSurface != null
        val template = if (isVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

        try {
            val builder = device.createCaptureRequest(template).apply {
                addTarget(pSurface)
                yuvReader?.surface?.let { addTarget(it) }
                if (isVideo) {
                    videoRecordSurface?.let { addTarget(it) }
                }
                applyControlsToRequest(this, isStillCapture = false, isVideo = isVideo)
            }

            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: lastAutoIso
                        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: lastAutoShutterNs
                        lastAutoIso = iso
                        lastAutoShutterNs = exp
                        onLiveExposureReadout(iso, exp)
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed repeating request", e)
        }
    }

    private fun applyControlsToRequest(
        builder: CaptureRequest.Builder,
        isStillCapture: Boolean,
        isVideo: Boolean
    ) {
        val lens = currentLens ?: return
        val chars = currentCharacteristics
        val controls = currentControls

        // 1. Exposure: Unrestricted Manual Shutter & ISO
        if (!controls.isShutterAuto || !controls.isIsoAuto) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val targetNs = if (controls.isShutterAuto) {
                lastAutoShutterNs
            } else {
                controls.shutterNs.coerceIn(lens.exposureRangeNs.first, lens.exposureRangeNs.last)
            }
            val targetIso = if (controls.isIsoAuto) {
                lastAutoIso
            } else {
                controls.isoValue.coerceIn(lens.isoRange.first, lens.isoRange.last)
            }
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val evRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (evRange != null) {
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f
                val aeComp = (controls.exposureEdit.evOffset / step).roundToInt()
                    .coerceIn(evRange.lower, evRange.upper)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeComp)
            }
        }

        // 2. White Balance: Full Kelvin + Tint Manual Control
        if (controls.wbPreset == WhiteBalancePreset.AUTO) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                ColorScience.kelvinToRggbGains(controls.wbKelvin, controls.wbTint)
            )
        }

        // 3. Manual Focus
        if (controls.manualFocusEnabled && lens.minFocusDistance > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            val dist = controls.focusDistanceNormalized.coerceIn(0f, 1f) * lens.minFocusDistance
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, dist)
        } else {
            val afMode = if (isVideo) {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }

        // 4. Zoom Ratio (API 30+ CONTROL_ZOOM_RATIO or fallback SCALER_CROP_REGION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val minZ = zoomRange?.lower ?: 1.0f
            val maxZ = zoomRange?.upper ?: 10.0f
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controls.zoomRatio.coerceIn(minZ, maxZ))
        } else if (chars != null) {
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null && controls.zoomRatio > 1.01f) {
                val z = controls.zoomRatio.coerceIn(1f, 8f)
                val cropW = (activeArray.width() / z).toInt()
                val cropH = (activeArray.height() / z).toInt()
                val dx = (activeArray.width() - cropW) / 2
                val dy = (activeArray.height() - cropH) / 2
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    Rect(dx, dy, dx + cropW, dy + cropH)
                )
            }
        }

        // 5. ISP Processing Bypass ("Całkowite wyłączenie poprawy zdjęć")
        when (controls.ispMode) {
            IspProcessingMode.PURE_SENSOR_BYPASS -> {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
            }
            IspProcessingMode.KADR_COMPUTATIONAL -> {
                // Disable aggressive OEM HAL oversharpening; let our own AI denoiser work cleanly
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
            }
            IspProcessingMode.STANDARD_ISP -> {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
        }

        // 6. 50MP Quad-Bayer Maximum Resolution Mode (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isStillCapture &&
            (controls.enable50MpPro || controls.captureFormat == CaptureFormat.ULTRA_50MP_PRO) &&
            lens.supportsMaxRes50Mp
        ) {
            builder.set(
                CaptureRequest.SENSOR_PIXEL_MODE,
                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
            )
        }

        // 7. Video Stabilization & FPS for 4K RAM mode
        if (isVideo) {
            if (controls.videoStabilizationEnabled) {
                if (lens.supportsOis) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
                if (lens.supportsEis) {
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
            } else {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            val fpsRange = Range(controls.videoFps, controls.videoFps)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
    }

    @Volatile
    private var pendingJpegBytes: ByteArray? = null
    @Volatile
    private var pendingJpegResult: TotalCaptureResult? = null
    @Volatile
    private var pendingRawImage: Image? = null
    @Volatile
    private var pendingRawResult: TotalCaptureResult? = null

    private fun tryCompleteJpegCapture() {
        val bytes = pendingJpegBytes
        val res = pendingJpegResult
        if (bytes != null && res != null) {
            pendingJpegBytes = null
            pendingJpegResult = null
            pendingJpegDeferred?.complete(bytes to res)
        }
    }

    private fun tryCompleteRawCapture() {
        val img = pendingRawImage
        val res = pendingRawResult
        if (img != null && res != null) {
            pendingRawImage = null
            pendingRawResult = null
            pendingRawDeferred?.complete(img to res)
        }
    }

    data class StillCaptureOutput(
        val jpegBytes: ByteArray?,
        val dngBytes: ByteArray?,
        val iso: Int,
        val exposureNs: Long,
        val width: Int,
        val height: Int
    )

    suspend fun captureSingleStill(): StillCaptureOutput? = withContext(Dispatchers.IO) {
        val session = captureSession ?: return@withContext null
        val device = cameraDevice ?: return@withContext null
        val jReader = jpegReader ?: return@withContext null
        val chars = currentCharacteristics ?: return@withContext null

        pendingJpegBytes = null
        pendingJpegResult = null
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawResult = null

        val jpegDef = CompletableDeferred<Pair<ByteArray, TotalCaptureResult>?>()
        pendingJpegDeferred = jpegDef

        val wantRaw = rawReader != null &&
            (currentControls.captureFormat == CaptureFormat.DNG_RAW ||
                currentControls.captureFormat == CaptureFormat.DNG_AND_JPEG ||
                currentControls.mode == CameraMode.RAW_50MP)

        val rawDef = if (wantRaw) CompletableDeferred<Pair<Image, TotalCaptureResult>?>() else null
        pendingRawDeferred = rawDef

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jReader.surface)
            if (wantRaw) rawReader?.surface?.let { addTarget(it) }
            applyControlsToRequest(this, isStillCapture = true, isVideo = false)
            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            val orientation = currentLens?.sensorOrientation ?: 90
            set(CaptureRequest.JPEG_ORIENTATION, orientation)
        }

        session.capture(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    pendingJpegResult = result
                    tryCompleteJpegCapture()
                    if (wantRaw) {
                        pendingRawResult = result
                        tryCompleteRawCapture()
                    }
                }
            },
            cameraHandler
        )

        val jpegPair = jpegDef.await()
        val rawPair = rawDef?.await()

        val dngBytes = if (rawPair != null) {
            val (rawImg, rawRes) = rawPair
            try {
                ByteArrayOutputStream().use { out ->
                    DngCreator(chars, rawRes).use { dngCreator ->
                        dngCreator.writeImage(out, rawImg)
                    }
                    out.toByteArray()
                }
            } catch (e: Exception) {
                Log.e(TAG, "DngCreator write failed", e)
                null
            } finally {
                rawImg.close()
            }
        } else null

        val res = jpegPair?.second ?: rawPair?.second
        val iso = res?.get(CaptureResult.SENSOR_SENSITIVITY) ?: lastAutoIso
        val exp = res?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: lastAutoShutterNs

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        jpegPair?.first?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }

        StillCaptureOutput(
            jpegBytes = jpegPair?.first,
            dngBytes = dngBytes,
            iso = iso,
            exposureNs = exp,
            width = opts.outWidth.coerceAtLeast(jReader.width),
            height = opts.outHeight.coerceAtLeast(jReader.height)
        )
    }

    /**
     * Captures a rapid burst of YUV-converted Bitmaps for Motion Blur (ND Simulator),
     * Handheld Super-Resolution, Extended Computational Long Exposure (> hardware max),
     * or Multi-Frame AI Denoising.
     */
    suspend fun captureBurstFrames(
        frameCount: Int,
        subFrameExposureNs: Long? = null
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val session = captureSession ?: return@withContext emptyList()
        val device = cameraDevice ?: return@withContext emptyList()
        val ySurface = yuvReader?.surface ?: return@withContext emptyList()

        val deferred = CompletableDeferred<List<Bitmap>>()
        synchronized(this@Camera2Engine) {
            pendingBurstCollector = mutableListOf()
            pendingBurstTargetCount = frameCount.coerceIn(2, 64)
            pendingBurstDeferred = deferred
            isCapturingBurst.set(true)
        }

        try {
            val requests = (0 until frameCount.coerceIn(2, 64)).map {
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(ySurface)
                    previewSurface?.takeIf { it.isValid }?.let { addTarget(it) }
                    applyControlsToRequest(this, isStillCapture = false, isVideo = false)
                    if (subFrameExposureNs != null) {
                        val range = currentLens?.exposureRangeNs ?: 100_000L..1_000_000_000L
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, subFrameExposureNs.coerceIn(range.first, range.last))
                        set(CaptureRequest.SENSOR_SENSITIVITY, currentControls.isoValue)
                    }
                }.build()
            }
            session.captureBurst(requests, null, cameraHandler)
            val result = deferred.await()
            updateRepeatingRequest()
            result
        } catch (e: Exception) {
            isCapturingBurst.set(false)
            Log.e(TAG, "Burst capture error", e)
            emptyList()
        }
    }

    private fun yuvImageToBitmap(image: Image, sensorOrientation: Int, isFront: Boolean): Bitmap? {
        return try {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer.duplicate().apply { rewind() }
            val uBuf = uPlane.buffer.duplicate().apply { rewind() }
            val vBuf = vPlane.buffer.duplicate().apply { rewind() }

            val nv21 = ByteArray(w * h * 3 / 2)
            val yRowStride = yPlane.rowStride
            if (yRowStride == w) {
                yBuf.get(nv21, 0, w * h)
            } else {
                var dstIdx = 0
                for (row in 0 until h) {
                    yBuf.position(row * yRowStride)
                    yBuf.get(nv21, dstIdx, w)
                    dstIdx += w
                }
            }

            val uvRowStride = vPlane.rowStride
            val uvPixelStride = vPlane.pixelStride
            var uvDst = w * h
            val halfH = h / 2
            val halfW = w / 2
            for (row in 0 until halfH) {
                val rowBase = row * uvRowStride
                for (col in 0 until halfW) {
                    val idx = rowBase + col * uvPixelStride
                    nv21[uvDst++] = vBuf.get(idx)
                    nv21[uvDst++] = uBuf.get(idx)
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 94, out)
            val bytes = out.toByteArray()
            val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val matrix = Matrix().apply {
                postRotate(sensorOrientation.toFloat())
                if (isFront) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
            if (rotated != rawBmp) rawBmp.recycle()
            rotated
        } catch (e: Exception) {
            null
        }
    }

    private fun closeSessionOnly() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        yuvReader?.close(); yuvReader = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
    }

    private fun closeCameraDevice() {
        runCatching { cameraDevice?.close() }
        cameraDevice = null
    }

    fun release() {
        closeSessionOnly()
        closeCameraDevice()
        cameraThread.quitSafely()
        sessionExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Camera2Engine"
    }
}
