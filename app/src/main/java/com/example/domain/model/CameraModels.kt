package com.example.domain.model

import android.net.Uri
import android.util.Size

enum class CameraMode(
    val titlePl: String,
    val badge: String,
    val descriptionPl: String
) {
    PRO(
        titlePl = "PRO",
        badge = "PRO",
        descriptionPl = "Pełna kontrola manualna ekspozycji, WB, ostrości i podglądu na żywo."
    ),
    RAW_50MP(
        titlePl = "RAW / 50MP",
        badge = "DNG·50",
        descriptionPl = "Zapis 16-bit DNG oraz odblokowany tryb 50 MP dla matryc Quad-Bayer."
    ),
    MOTION_BLUR(
        titlePl = "Rozmycie",
        badge = "ND-AI",
        descriptionPl = "Symulator filtra ND i rozmycie ruchu w pełnym słońcu poprzez składanie klatek."
    ),
    SUPER_RES(
        titlePl = "Super-Res",
        badge = "2X-SR",
        descriptionPl = "Składanie serii zdjęć z wykorzystaniem mikrodrgań ręki na zoomie i teleobiektywie."
    ),
    VIDEO_4K_RAM(
        titlePl = "4K RAM",
        badge = "4K·OIS",
        descriptionPl = "Nagrywanie 4K ze stabilizacją OIS/EIS z buforowaniem pakietów bezpośrednio w RAM."
    )
}

enum class CaptureFormat(val labelPl: String) {
    JPEG("JPEG"),
    DNG_RAW("RAW (DNG)"),
    DNG_AND_JPEG("DNG + JPEG"),
    ULTRA_50MP_PRO("50 MP PRO")
}

enum class IspProcessingMode(val labelPl: String, val shortPl: String, val detailPl: String) {
    PURE_SENSOR_BYPASS(
        labelPl = "Czysta matryca (0% poprawy)",
        shortPl = "BEZ ISP",
        detailPl = "Wyłącza EDGE_MODE, NOISE_REDUCTION i krzywe tonów HAL. Zero sztucznego wyostrzania."
    ),
    STANDARD_ISP(
        labelPl = "Standardowe ISP",
        shortPl = "ISP",
        detailPl = "Domyślna obróbka sprzętowa producenta (odszumianie + wyostrzanie)."
    ),
    KADR_COMPUTATIONAL(
        labelPl = "Silnik Kadr RAW",
        shortPl = "KADR AI",
        detailPl = "Wyłącza agresywny HAL ISP i stosuje nasz własny model odszumiania oraz tonacji."
    )
}

enum class DenoiseLevel(val labelPl: String, val shortPl: String) {
    OFF("Wyłączone", "OFF"),
    FAST("Lekkie AI", "AI-1"),
    BALANCED("Zrównoważone AI", "AI-2"),
    ULTRA_AI_MULTIFRAME("Ultra Noc / Multi-Frame AI", "AI-MAX")
}

enum class MotionBlurStyle(val labelPl: String, val detailPl: String) {
    SILKY_WATER("Jedwabna woda / chmury", "Średnia czasowa — płynny ruch w dzień bez prześwietleń."),
    LIGHT_TRAILS("Smugi światła", "Akumulacja maksimów luminancji — idealne na reflektory i noc."),
    DYNAMIC_PANNING("Ślad ruchu (Panning)", "Ważone rozmycie kierunkowe z zachowaniem ostrej kotwicy.")
}

enum class FocusPeakingColor(val labelPl: String, val argb: Int) {
    RED("Czerwony", 0xFFFF2D55.toInt()),
    CYAN("Cyjan", 0xFF38BDF8.toInt()),
    GREEN("Zielony", 0xFF10B981.toInt()),
    YELLOW("Żółty", 0xFFFACC15.toInt())
}

enum class WhiteBalancePreset(val labelPl: String, val kelvin: Int, val tint: Int) {
    AUTO("Auto", 5500, 0),
    DAYLIGHT("Słońce", 5500, 0),
    CLOUDY("Chmury", 6500, 4),
    SHADE("Cień", 7500, 6),
    TUNGSTEN("Żarówka", 3200, -4),
    FLUORESCENT("Świetlówka", 4200, 12),
    MANUAL("Ręczny", 5600, 0)
}

data class CameraLensProfile(
    val cameraId: String,
    val physicalId: String? = null,
    val label: String,
    val fullNamePl: String,
    val isFront: Boolean,
    val focalLengthMm: Float,
    val aperture: Float,
    val maxOutputSize: Size,
    val maxRawSize: Size?,
    val max50MpSize: Size?,
    val megapixels: Float,
    val supportsRaw: Boolean,
    val supportsMaxRes50Mp: Boolean,
    val supportsOis: Boolean,
    val supportsEis: Boolean,
    val supportsManualSensor: Boolean,
    val exposureRangeNs: LongRange,
    val isoRange: IntRange,
    val minFocusDistance: Float,
    val hardwareLevelLabel: String,
    val sensorOrientation: Int
)

data class LiveExposureEdit(
    val evOffset: Float = 0f,         // -3.0 .. +3.0 EV
    val shadows: Float = 0f,          // -1.0 .. +1.0
    val highlights: Float = 0f,       // -1.0 .. +1.0
    val contrast: Float = 1.0f,       // 0.5 .. 1.8
    val saturation: Float = 1.0f,     // 0.0 .. 1.8
    val sharpness: Float = 0.0f,      // 0.0 .. 1.0
    val zebraEnabled: Boolean = false
)

data class ManualControlsState(
    val mode: CameraMode = CameraMode.PRO,
    val isShutterAuto: Boolean = true,
    val shutterNs: Long = 10_000_000L, // 1/100s default, supports 62_500ns (1/16000s) .. 60_000_000_000ns (60s)
    val isIsoAuto: Boolean = true,
    val isoValue: Int = 200,
    val wbPreset: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val wbKelvin: Int = 5500,
    val wbTint: Int = 0,
    val manualFocusEnabled: Boolean = false,
    val focusDistanceNormalized: Float = 0.5f, // 0 = infinity, 1 = macro
    val focusPeakingEnabled: Boolean = false,
    val focusPeakingColor: FocusPeakingColor = FocusPeakingColor.CYAN,
    val focusPeakingSensitivity: Float = 0.55f,
    val zoomRatio: Float = 1.0f,
    val enable50MpPro: Boolean = false,
    val captureFormat: CaptureFormat = CaptureFormat.DNG_AND_JPEG,
    val ispMode: IspProcessingMode = IspProcessingMode.KADR_COMPUTATIONAL,
    val denoiseLevel: DenoiseLevel = DenoiseLevel.BALANCED,
    val motionBlurStyle: MotionBlurStyle = MotionBlurStyle.SILKY_WATER,
    val motionFrameCount: Int = 16,
    val superResFrameCount: Int = 8,
    val gridEnabled: Boolean = true,
    val levelEnabled: Boolean = true,
    val histogramVisible: Boolean = true,
    val histogramRgbMode: Boolean = false,
    val exposureEdit: LiveExposureEdit = LiveExposureEdit(),
    val ramBufferMb: Int = 256,
    val videoFps: Int = 30,
    val videoBitrateMbps: Int = 65,
    val videoStabilizationEnabled: Boolean = true,
    val videoHevcEnabled: Boolean = true
)

data class TelemetryState(
    val liveFps: Int = 0,
    val lumaHistogram: FloatArray = FloatArray(64),
    val redHistogram: FloatArray = FloatArray(64),
    val greenHistogram: FloatArray = FloatArray(64),
    val blueHistogram: FloatArray = FloatArray(64),
    val highlightClipPct: Float = 0f,
    val shadowClipPct: Float = 0f,
    val tiltDegrees: Float = 0f,
    val handStabilityScore: Float = 1f,
    val isRecording: Boolean = false,
    val recordingElapsedSec: Int = 0,
    val ramBufferUsedMb: Float = 0f,
    val ramBufferMaxMb: Int = 256,
    val droppedFrames: Int = 0,
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,
    val processingStatusPl: String = "",
    val activeIsoReadout: Int = 100,
    val activeShutterNsReadout: Long = 10_000_000L,
    val statusBannerPl: String? = null
)

data class CapturedMediaItem(
    val id: String,
    val uriString: String,
    val fileName: String,
    val isDng: Boolean,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val megapixels: Float,
    val iso: Int,
    val shutterDisplay: String,
    val lensLabel: String,
    val modeLabel: String,
    val denoiseLabel: String,
    val timestampMs: Long
)
