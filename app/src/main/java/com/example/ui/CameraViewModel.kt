package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.Camera2Engine
import com.example.camera.CameraHardwareDiscovery
import com.example.camera.ColorScience
import com.example.camera.RamBufferedVideoRecorder
import com.example.data.MediaStorageRepository
import com.example.domain.model.CameraLensProfile
import com.example.domain.model.CameraMode
import com.example.domain.model.CapturedMediaItem
import com.example.domain.model.CaptureFormat
import com.example.domain.model.DenoiseLevel
import com.example.domain.model.FocusPeakingColor
import com.example.domain.model.IspProcessingMode
import com.example.domain.model.LiveExposureEdit
import com.example.domain.model.ManualControlsState
import com.example.domain.model.MotionBlurStyle
import com.example.domain.model.TelemetryState
import com.example.domain.model.WhiteBalancePreset
import com.example.processing.AiDenoiseEngine
import com.example.processing.ComputationalModesEngine
import com.example.processing.LiveFrameAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ProControlTab(val labelPl: String) {
    EXPOSURE("Ekspozycja"),
    WB_FOCUS("WB i Ostrość"),
    LIVE_EDIT("Edycja Live"),
    AI_ISP("AI i ISP"),
    EXPERIMENTAL("Eksperymentalne")
}

data class LabState(
    val sourceBitmap: Bitmap? = null,
    val denoisedBitmap: Bitmap? = null,
    val isProcessing: Boolean = false,
    val statusText: String = "Wybierz zdjęcie z aparatu lub galerii, aby przetestować model odszumiania AI i edycję ekspozycji.",
    val denoiseLevel: DenoiseLevel = DenoiseLevel.BALANCED,
    val simulatedIso: Int = 1600,
    val exposureEdit: LiveExposureEdit = LiveExposureEdit(),
    val splitPosition: Float = 0.5f,
    val sourceTitle: String = "Brak pliku"
)

class CameraViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val storageRepo = MediaStorageRepository(application)
    private val liveAnalyzer = LiveFrameAnalyzer()
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _lenses = MutableStateFlow<List<CameraLensProfile>>(emptyList())
    val lenses: StateFlow<List<CameraLensProfile>> = _lenses.asStateFlow()

    private val _selectedLens = MutableStateFlow<CameraLensProfile?>(null)
    val selectedLens: StateFlow<CameraLensProfile?> = _selectedLens.asStateFlow()

    private val _deviceModel = MutableStateFlow("")
    val deviceModel: StateFlow<String> = _deviceModel.asStateFlow()

    private val _isEdge50Pro = MutableStateFlow(false)
    val isEdge50Pro: StateFlow<Boolean> = _isEdge50Pro.asStateFlow()

    private val _controls = MutableStateFlow(ManualControlsState())
    val controls: StateFlow<ManualControlsState> = _controls.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private val _peakingBitmap = MutableStateFlow<Bitmap?>(null)
    val peakingBitmap: StateFlow<Bitmap?> = _peakingBitmap.asStateFlow()

    private val _capturedItems = MutableStateFlow<List<CapturedMediaItem>>(emptyList())
    val capturedItems: StateFlow<List<CapturedMediaItem>> = _capturedItems.asStateFlow()

    private val _activeProTab = MutableStateFlow(ProControlTab.EXPOSURE)
    val activeProTab: StateFlow<ProControlTab> = _activeProTab.asStateFlow()

    private val _isProDrawerOpen = MutableStateFlow(true)
    val isProDrawerOpen: StateFlow<Boolean> = _isProDrawerOpen.asStateFlow()

    private val _labState = MutableStateFlow(LabState())
    val labState: StateFlow<LabState> = _labState.asStateFlow()

    private var previewSurface: Surface? = null
    private var fpsCounter = 0
    private var fpsWindowStart = System.currentTimeMillis()

    private val cameraEngine = Camera2Engine(
        context = application,
        onLiveYuvFrame = { img ->
            fpsCounter++
            val now = System.currentTimeMillis()
            val curFps = if (now - fpsWindowStart >= 1000L) {
                val measured = fpsCounter
                fpsCounter = 0
                fpsWindowStart = now
                measured
            } else {
                _telemetry.value.liveFps
            }

            val ctrl = _controls.value
            val lens = _selectedLens.value
            val analysis = liveAnalyzer.analyzeIfReady(
                image = img,
                sensorOrientation = lens?.sensorOrientation ?: 90,
                isFront = lens?.isFront ?: false,
                computeRgb = ctrl.histogramRgbMode,
                computePeaking = ctrl.focusPeakingEnabled,
                peakingColorArgb = ctrl.focusPeakingColor.argb,
                peakingSensitivity = ctrl.focusPeakingSensitivity,
                zebraEnabled = ctrl.exposureEdit.zebraEnabled
            )
            if (analysis != null) {
                _peakingBitmap.value = analysis.peakingOverlay
                _telemetry.update {
                    it.copy(
                        liveFps = curFps,
                        lumaHistogram = analysis.lumaBins,
                        redHistogram = analysis.redBins,
                        greenHistogram = analysis.greenBins,
                        blueHistogram = analysis.blueBins,
                        highlightClipPct = analysis.highlightClipPct,
                        shadowClipPct = analysis.shadowClipPct
                    )
                }
            } else if (curFps != _telemetry.value.liveFps) {
                _telemetry.update { it.copy(liveFps = curFps) }
            }
        },
        onLiveExposureReadout = { iso, shutterNs ->
            _telemetry.update {
                it.copy(activeIsoReadout = iso, activeShutterNsReadout = shutterNs)
            }
        },
        onCameraError = { msg ->
            showBanner(msg)
        }
    )

    private val videoRecorder = RamBufferedVideoRecorder(
        context = application,
        scope = viewModelScope,
        onTelemetry = { usedMb, maxMb, drops, elapsed ->
            _telemetry.update {
                it.copy(
                    ramBufferUsedMb = usedMb,
                    ramBufferMaxMb = maxMb,
                    droppedFrames = drops,
                    recordingElapsedSec = elapsed
                )
            }
        },
        onFinished = { uri, fileName, width, drops ->
            viewModelScope.launch {
                val lens = _selectedLens.value
                _telemetry.update {
                    it.copy(
                        isRecording = false,
                        ramBufferUsedMb = 0f,
                        recordingElapsedSec = 0
                    )
                }
                cameraEngine.attachVideoSurfaceAndRestart(null, _controls.value)
                if (uri != null) {
                    val item = CapturedMediaItem(
                        id = System.currentTimeMillis().toString(),
                        uriString = uri.toString(),
                        fileName = fileName,
                        isDng = false,
                        isVideo = true,
                        width = width,
                        height = 2160,
                        megapixels = 8.3f,
                        iso = _telemetry.value.activeIsoReadout,
                        shutterDisplay = "${_controls.value.videoFps}fps",
                        lensLabel = lens?.label ?: "1x",
                        modeLabel = "4K RAM-Buffer",
                        denoiseLabel = "OIS+EIS ($drops drop)",
                        timestampMs = System.currentTimeMillis()
                    )
                    _capturedItems.update { listOf(item) + it }
                    showBanner("Zapisano 4K MP4 (utracono klatek: $drops)")
                } else {
                    showBanner("Zakończono nagrywanie 4K (brak zapisanego strumienia na emulatorze).")
                }
            }
        }
    )

    init {
        viewModelScope.launch {
            val discovery = withContext(Dispatchers.Default) {
                CameraHardwareDiscovery.discoverLenses(application)
            }
            _lenses.value = discovery.lenses
            _deviceModel.value = discovery.deviceModel
            _isEdge50Pro.value = discovery.isEdge50ProDetected

            // Default to main 1x lens
            val defaultLens = discovery.lenses.firstOrNull { it.label == "1x" }
                ?: discovery.lenses.firstOrNull()
            _selectedLens.value = defaultLens

            val savedIsp = storageRepo.savedIspMode.first()
            val savedRam = storageRepo.savedRamBufferMb.first()
            _controls.update { it.copy(ispMode = savedIsp, ramBufferMb = savedRam) }
        }

        // Register Accelerometer & Gyroscope for spirit level and hand-tremor Super-Res stability
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun onPreviewSurfaceReady(surface: Surface) {
        previewSurface = surface
        val lens = _selectedLens.value ?: return
        cameraEngine.openCamera(lens, surface, _controls.value)
    }

    fun onPreviewSurfaceDestroyed() {
        previewSurface = null
    }

    fun selectLens(lens: CameraLensProfile) {
        if (_telemetry.value.isRecording) return
        _selectedLens.value = lens
        _controls.update { it.copy(zoomRatio = 1.0f) }
        val surface = previewSurface ?: return
        cameraEngine.openCamera(lens, surface, _controls.value)
    }

    fun selectMode(mode: CameraMode) {
        if (_telemetry.value.isRecording) return
        _controls.update { cur ->
            val auto50Mp = mode == CameraMode.RAW_50MP
            val autoFormat = if (mode == CameraMode.RAW_50MP) CaptureFormat.DNG_AND_JPEG else cur.captureFormat
            cur.copy(
                mode = mode,
                enable50MpPro = if (auto50Mp) true else cur.enable50MpPro,
                captureFormat = autoFormat
            )
        }
        cameraEngine.updateControls(_controls.value)
    }

    fun setProTab(tab: ProControlTab) {
        _activeProTab.value = tab
    }

    fun toggleProDrawer() {
        _isProDrawerOpen.update { !it }
    }

    fun updateControls(transform: (ManualControlsState) -> ManualControlsState) {
        _controls.update(transform)
        val next = _controls.value
        if (!next.focusPeakingEnabled && !next.exposureEdit.zebraEnabled) {
            _peakingBitmap.value = null
        }
        cameraEngine.updateControls(next)
    }

    fun setIspMode(mode: IspProcessingMode) {
        updateControls { it.copy(ispMode = mode) }
        viewModelScope.launch { storageRepo.saveIspMode(mode) }
    }

    fun setRamBufferMb(mb: Int) {
        updateControls { it.copy(ramBufferMb = mb) }
        viewModelScope.launch { storageRepo.saveRamBufferMb(mb) }
    }

    fun setWhiteBalancePreset(preset: WhiteBalancePreset) {
        updateControls {
            if (preset == WhiteBalancePreset.AUTO) {
                it.copy(wbPreset = preset)
            } else {
                it.copy(wbPreset = preset, wbKelvin = preset.kelvin, wbTint = preset.tint)
            }
        }
    }

    fun toggleHistogramMode() {
        _controls.update { it.copy(histogramRgbMode = !it.histogramRgbMode) }
    }

    fun resetLiveExposure() {
        updateControls { it.copy(exposureEdit = LiveExposureEdit()) }
    }

    fun onShutterPressed(hasAudioPermission: Boolean) {
        val ctrl = _controls.value
        val lens = _selectedLens.value
        if (lens == null) {
            showBanner("Nie wykryto aktywnego obiektywu.")
            return
        }
        if (_telemetry.value.isProcessing) return

        if (ctrl.mode == CameraMode.VIDEO_4K_RAM) {
            toggle4kRamRecording(lens, ctrl, hasAudioPermission)
            return
        }

        viewModelScope.launch {
            _telemetry.update {
                it.copy(isProcessing = true, processingProgress = 0.05f, processingStatusPl = "Wyzwolenie migawki...")
            }
            try {
                when (ctrl.mode) {
                    CameraMode.MOTION_BLUR -> executeMotionBlurCapture(lens, ctrl)
                    CameraMode.SUPER_RES -> executeSuperResCapture(lens, ctrl)
                    else -> {
                        // Check if user requested ultra-long shutter beyond hardware sensor cap (e.g., 4s..60s)
                        val hwMaxNs = lens.exposureRangeNs.last
                        if (!ctrl.isShutterAuto && ctrl.shutterNs > hwMaxNs) {
                            executeExtendedLongExposure(lens, ctrl, hwMaxNs)
                        } else {
                            executeStandardOr50MpRawCapture(lens, ctrl)
                        }
                    }
                }
            } catch (e: Exception) {
                showBanner("Błąd zapisu: ${e.localizedMessage ?: "nieznany"}")
            } finally {
                _telemetry.update {
                    it.copy(isProcessing = false, processingProgress = 0f, processingStatusPl = "")
                }
            }
        }
    }

    private suspend fun executeStandardOr50MpRawCapture(
        lens: CameraLensProfile,
        ctrl: ManualControlsState
    ) {
        _telemetry.update {
            it.copy(
                processingProgress = 0.25f,
                processingStatusPl = if (ctrl.enable50MpPro) "Przechwytywanie 50 MP PRO..." else "Przechwytywanie klatki..."
            )
        }

        // If ULTRA_AI_MULTIFRAME is selected (and ISP isn't Bypass), grab burst frames for multi-frame AI denoise
        val burstForAi = if (ctrl.ispMode != IspProcessingMode.PURE_SENSOR_BYPASS &&
            ctrl.denoiseLevel == DenoiseLevel.ULTRA_AI_MULTIFRAME
        ) {
            _telemetry.update { it.copy(processingStatusPl = "AI Multi-Frame: seria 5 klatek...") }
            cameraEngine.captureBurstFrames(5)
        } else emptyList()

        val result = cameraEngine.captureSingleStill()
        if (result == null) {
            showBanner("Nie udało się przechwycić obrazu z matrycy.")
            return
        }

        val shutterLabel = ColorScience.formatShutterNs(result.exposureNs)

        // 1. Save 16-bit DNG RAW if captured
        if (result.dngBytes != null) {
            _telemetry.update { it.copy(processingProgress = 0.55f, processingStatusPl = "Zapisywanie 16-bit DNG...") }
            val dngItem = storageRepo.saveDngBytes(
                dngBytes = result.dngBytes,
                width = result.width,
                height = result.height,
                iso = result.iso,
                shutterDisplay = shutterLabel,
                lensLabel = lens.label
            )
            if (dngItem != null) {
                _capturedItems.update { listOf(dngItem) + it }
            }
        }

        // 2. Process & Save JPEG (if requested or fallback)
        if (result.jpegBytes != null && ctrl.captureFormat != CaptureFormat.DNG_RAW) {
            val needSoftwarePipeline = ctrl.ispMode != IspProcessingMode.PURE_SENSOR_BYPASS &&
                (ctrl.denoiseLevel != DenoiseLevel.OFF ||
                    ctrl.exposureEdit != LiveExposureEdit())

            val savedItem = if (needSoftwarePipeline) {
                var bmp = withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply {
                        // Keep full res unless huge memory pressure; downsample only > 55MP if needed
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(result.jpegBytes, 0, result.jpegBytes.size, opts)
                }
                if (bmp != null) {
                    if (ctrl.denoiseLevel != DenoiseLevel.OFF) {
                        bmp = AiDenoiseEngine.denoise(
                            source = bmp,
                            level = ctrl.denoiseLevel,
                            iso = result.iso,
                            burstFrames = burstForAi
                        ) { p, label ->
                            _telemetry.update { it.copy(processingProgress = 0.55f + p * 0.35f, processingStatusPl = label) }
                        }
                    }
                    if (ctrl.exposureEdit != LiveExposureEdit()) {
                        _telemetry.update { it.copy(processingProgress = 0.92f, processingStatusPl = "Wypalanie krzywej ekspozycji...") }
                        bmp = ComputationalModesEngine.bakeExposureEdits(bmp, ctrl.exposureEdit)
                    }
                    storageRepo.saveJpegBitmap(
                        bitmap = bmp,
                        fileNamePrefix = if (ctrl.enable50MpPro) "KADR_50MP" else "KADR_PRO",
                        iso = result.iso,
                        shutterDisplay = shutterLabel,
                        lensLabel = lens.label,
                        modeLabel = if (ctrl.enable50MpPro) "50MP PRO" else ctrl.ispMode.shortPl,
                        denoiseLabel = ctrl.denoiseLevel.shortPl
                    )
                } else null
            } else {
                storageRepo.saveJpegBytes(
                    bytes = result.jpegBytes,
                    width = result.width,
                    height = result.height,
                    fileNamePrefix = if (ctrl.ispMode == IspProcessingMode.PURE_SENSOR_BYPASS) "KADR_RAW_SENSOR" else "KADR_PRO",
                    iso = result.iso,
                    shutterDisplay = shutterLabel,
                    lensLabel = lens.label,
                    modeLabel = ctrl.ispMode.shortPl,
                    denoiseLabel = "OFF"
                )
            }

            if (savedItem != null) {
                _capturedItems.update { listOf(savedItem) + it }
                loadBitmapIntoLab(Uri.parse(savedItem.uriString), savedItem.fileName, result.iso)
            }
        }

        val mpStr = String.format("%.1f MP", (result.width * result.height) / 1_000_000f)
        val rawNote = if (result.dngBytes != null) " + DNG" else ""
        showBanner("Zapisano $mpStr$rawNote · $shutterLabel · ISO ${result.iso}")
    }

    private suspend fun executeExtendedLongExposure(
        lens: CameraLensProfile,
        ctrl: ManualControlsState,
        hwMaxNs: Long
    ) {
        val totalRequestedNs = ctrl.shutterNs
        val subExpNs = hwMaxNs.coerceAtMost(500_000_000L) // 0.5s subframes keep responsiveness smooth
        val frameCount = (totalRequestedNs / subExpNs).toInt().coerceIn(4, 48)
        _telemetry.update {
            it.copy(
                processingProgress = 0.15f,
                processingStatusPl = "Wydłużona migawka ${ColorScience.formatShutterNs(totalRequestedNs)}: zbieranie $frameCount klatek..."
            )
        }
        val frames = cameraEngine.captureBurstFrames(frameCount, subFrameExposureNs = subExpNs)
        if (frames.isEmpty()) {
            showBanner("Nie udało się zebrać klatek dla długiego czasu.")
            return
        }
        var blended = ComputationalModesEngine.processMotionBlur(
            frames = frames,
            style = MotionBlurStyle.SILKY_WATER
        ) { p, msg ->
            _telemetry.update { it.copy(processingProgress = 0.2f + p * 0.7f, processingStatusPl = msg) }
        }
        if (ctrl.denoiseLevel != DenoiseLevel.OFF) {
            blended = AiDenoiseEngine.denoise(blended, ctrl.denoiseLevel, ctrl.isoValue)
        }
        blended = ComputationalModesEngine.bakeExposureEdits(blended, ctrl.exposureEdit)
        val shutterLabel = ColorScience.formatShutterNs(totalRequestedNs)
        val item = storageRepo.saveJpegBitmap(
            bitmap = blended,
            fileNamePrefix = "KADR_BULB_${shutterLabel}",
            iso = ctrl.isoValue,
            shutterDisplay = shutterLabel,
            lensLabel = lens.label,
            modeLabel = "Długi czas AI",
            denoiseLabel = ctrl.denoiseLevel.shortPl
        )
        if (item != null) {
            _capturedItems.update { listOf(item) + it }
            loadBitmapIntoLab(Uri.parse(item.uriString), item.fileName, ctrl.isoValue)
        }
        showBanner("Zapisano długi czas $shutterLabel ($frameCount klatek)")
    }

    private suspend fun executeMotionBlurCapture(lens: CameraLensProfile, ctrl: ManualControlsState) {
        _telemetry.update {
            it.copy(
                processingProgress = 0.15f,
                processingStatusPl = "Symulator ND: przechwytywanie ${ctrl.motionFrameCount} klatek w dzień..."
            )
        }
        val frames = cameraEngine.captureBurstFrames(ctrl.motionFrameCount)
        if (frames.isEmpty()) {
            showBanner("Brak klatek z matrycy.")
            return
        }
        var result = ComputationalModesEngine.processMotionBlur(frames, ctrl.motionBlurStyle) { p, msg ->
            _telemetry.update { it.copy(processingProgress = 0.2f + p * 0.65f, processingStatusPl = msg) }
        }
        if (ctrl.denoiseLevel != DenoiseLevel.OFF && ctrl.ispMode != IspProcessingMode.PURE_SENSOR_BYPASS) {
            result = AiDenoiseEngine.denoise(result, ctrl.denoiseLevel, _telemetry.value.activeIsoReadout)
        }
        result = ComputationalModesEngine.bakeExposureEdits(result, ctrl.exposureEdit)
        val item = storageRepo.saveJpegBitmap(
            bitmap = result,
            fileNamePrefix = "KADR_ND_MOTION",
            iso = _telemetry.value.activeIsoReadout,
            shutterDisplay = "${ctrl.motionFrameCount}×ND",
            lensLabel = lens.label,
            modeLabel = ctrl.motionBlurStyle.labelPl,
            denoiseLabel = ctrl.denoiseLevel.shortPl
        )
        if (item != null) {
            _capturedItems.update { listOf(item) + it }
            loadBitmapIntoLab(Uri.parse(item.uriString), item.fileName, _telemetry.value.activeIsoReadout)
        }
        showBanner("Zapisano Rozmycie Ruchu (${ctrl.motionFrameCount} klatek)")
    }

    private suspend fun executeSuperResCapture(lens: CameraLensProfile, ctrl: ManualControlsState) {
        _telemetry.update {
            it.copy(
                processingProgress = 0.12f,
                processingStatusPl = "Super-Res: przechwytywanie ${ctrl.superResFrameCount} klatek (drgania ręki)..."
            )
        }
        val frames = cameraEngine.captureBurstFrames(ctrl.superResFrameCount)
        if (frames.isEmpty()) {
            showBanner("Brak klatek do Super-Rozdzielczości.")
            return
        }
        var srBmp = ComputationalModesEngine.processSuperResolution(frames, ctrl.zoomRatio) { p, msg ->
            _telemetry.update { it.copy(processingProgress = 0.2f + p * 0.65f, processingStatusPl = msg) }
        }
        if (ctrl.denoiseLevel != DenoiseLevel.OFF) {
            srBmp = AiDenoiseEngine.denoise(srBmp, DenoiseLevel.FAST, _telemetry.value.activeIsoReadout)
        }
        srBmp = ComputationalModesEngine.bakeExposureEdits(srBmp, ctrl.exposureEdit)
        val item = storageRepo.saveJpegBitmap(
            bitmap = srBmp,
            fileNamePrefix = "KADR_SUPERRES_2X",
            iso = _telemetry.value.activeIsoReadout,
            shutterDisplay = "${ctrl.superResFrameCount}×SR",
            lensLabel = "${lens.label} (${String.format("%.1fx", ctrl.zoomRatio)})",
            modeLabel = "Super-Res 2x",
            denoiseLabel = ctrl.denoiseLevel.shortPl
        )
        if (item != null) {
            _capturedItems.update { listOf(item) + it }
            loadBitmapIntoLab(Uri.parse(item.uriString), item.fileName, _telemetry.value.activeIsoReadout)
        }
        showBanner("Super-Res gotowe: ${srBmp.width}×${srBmp.height} px")
    }

    private fun toggle4kRamRecording(
        lens: CameraLensProfile,
        ctrl: ManualControlsState,
        hasAudioPermission: Boolean
    ) {
        if (_telemetry.value.isRecording) {
            videoRecorder.stopRecording()
            return
        }
        val surface = videoRecorder.prepare(
            width = 3840,
            height = 2160,
            fps = ctrl.videoFps,
            bitrateMbps = ctrl.videoBitrateMbps,
            useHevc = ctrl.videoHevcEnabled,
            ramBufferMb = ctrl.ramBufferMb,
            orientationHint = lens.sensorOrientation,
            recordAudio = hasAudioPermission
        )
        if (surface == null) {
            showBanner("Urządzenie nie obsługuje enkodera 4K Surface.")
            return
        }
        cameraEngine.attachVideoSurfaceAndRestart(surface, ctrl)
        _telemetry.update {
            it.copy(
                isRecording = true,
                ramBufferMaxMb = ctrl.ramBufferMb,
                ramBufferUsedMb = 0f,
                droppedFrames = 0,
                recordingElapsedSec = 0
            )
        }
        videoRecorder.startRecording()
    }

    // --- Lab & AI Denoise Inspector ---

    fun importLabPhoto(uri: Uri) {
        viewModelScope.launch {
            loadBitmapIntoLab(uri, "Import_Galerii.jpg", _telemetry.value.activeIsoReadout)
        }
    }

    fun selectCapturedForLab(item: CapturedMediaItem) {
        if (item.isVideo || item.isDng) {
            showBanner(if (item.isDng) "Plik DNG jest w DCIM/KadrRAW (gotowy do Lightroom/Darktable)." else "Wideo zapisano w Movies/KadrRAW.")
            return
        }
        viewModelScope.launch {
            loadBitmapIntoLab(Uri.parse(item.uriString), item.fileName, item.iso)
        }
    }

    private suspend fun loadBitmapIntoLab(uri: Uri, title: String, iso: Int) {
        val bmp = storageRepo.loadBitmapFromUri(uri, maxDim = 1400) ?: return
        _labState.update {
            it.copy(
                sourceBitmap = bmp,
                denoisedBitmap = null,
                simulatedIso = iso.coerceIn(100, 12800),
                sourceTitle = title,
                statusText = "Wczytano ${bmp.width}×${bmp.height} px · Gotowe do odszumiania AI."
            )
        }
    }

    fun updateLabState(transform: (LabState) -> LabState) {
        _labState.update(transform)
    }

    fun runLabAiDenoise() {
        val src = _labState.value.sourceBitmap ?: return
        if (_labState.value.isProcessing) return
        viewModelScope.launch {
            _labState.update { it.copy(isProcessing = true, statusText = "Uruchamianie modelu AI Denoise...") }
            val result = AiDenoiseEngine.denoise(
                source = src,
                level = _labState.value.denoiseLevel,
                iso = _labState.value.simulatedIso
            ) { _, msg ->
                _labState.update { it.copy(statusText = msg) }
            }
            val baked = ComputationalModesEngine.bakeExposureEdits(result, _labState.value.exposureEdit)
            _labState.update {
                it.copy(
                    denoisedBitmap = baked,
                    isProcessing = false,
                    statusText = "Odszumianie AI zakończone (${it.denoiseLevel.labelPl}, ISO ${it.simulatedIso}). Przesuń suwak porównania."
                )
            }
        }
    }

    fun exportLabResult() {
        val target = _labState.value.denoisedBitmap ?: _labState.value.sourceBitmap ?: return
        viewModelScope.launch {
            val baked = ComputationalModesEngine.bakeExposureEdits(target, _labState.value.exposureEdit)
            val saved = storageRepo.saveJpegBitmap(
                bitmap = baked,
                fileNamePrefix = "KADR_AI_LAB",
                iso = _labState.value.simulatedIso,
                shutterDisplay = "LAB",
                lensLabel = "AI",
                modeLabel = "AI Lab",
                denoiseLabel = _labState.value.denoiseLevel.shortPl
            )
            if (saved != null) {
                _capturedItems.update { listOf(saved) + it }
                showBanner("Wyeksportowano zdjęcie z Laboratorium AI!")
            }
        }
    }

    private fun showBanner(message: String) {
        viewModelScope.launch {
            _telemetry.update { it.copy(statusBannerPl = message) }
            delay(3600L)
            _telemetry.update { if (it.statusBannerPl == message) it.copy(statusBannerPl = null) else it }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val angle = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                _telemetry.update { it.copy(tiltDegrees = angle) }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val mag = sqrt(wx * wx + wy * wy + wz * wz)
                // Micro hand tremor around 0.02..0.15 rad/s is actually ideal for Drizzle Super-Res
                val score = (1f - (mag / 1.5f)).coerceIn(0f, 1f)
                _telemetry.update { it.copy(handStabilityScore = score) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        super.onCleared()
        sensorManager.unregisterListener(this)
        videoRecorder.cleanup()
        cameraEngine.release()
    }
}
