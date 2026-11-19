package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.camera.ColorScience
import com.example.domain.model.CameraLensProfile
import com.example.domain.model.CameraMode
import com.example.domain.model.IspProcessingMode
import com.example.domain.model.ManualControlsState
import com.example.domain.model.TelemetryState
import com.example.ui.CameraViewModel
import com.example.ui.ProControlTab
import com.example.ui.components.DeviceDiagnosticsSheet
import com.example.ui.components.FocusPeakingAndGridOverlay
import com.example.ui.components.HistogramOverlay
import com.example.ui.components.ProControlDeck
import com.example.ui.theme.AmberPro
import com.example.ui.theme.AmberProContainer
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonRecord
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.CyanOpticContainer
import com.example.ui.theme.EmeraldStable
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceGlass
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidBlack

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onNavigateToLab: () -> Unit,
    onNavigateToDocs: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (!hasCameraPermission) {
        PermissionGateScreen(
            onRequest = {
                permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            },
            onOpenLab = onNavigateToLab,
            onOpenDocs = onNavigateToDocs
        )
        return
    }

    val lenses by viewModel.lenses.collectAsStateWithLifecycle()
    val selectedLens by viewModel.selectedLens.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val peakingBitmap by viewModel.peakingBitmap.collectAsStateWithLifecycle()
    val capturedItems by viewModel.capturedItems.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeProTab.collectAsStateWithLifecycle()
    val isDrawerOpen by viewModel.isProDrawerOpen.collectAsStateWithLifecycle()
    val deviceModel by viewModel.deviceModel.collectAsStateWithLifecycle()
    val isEdge50Pro by viewModel.isEdge50Pro.collectAsStateWithLifecycle()

    var showDiagnostics by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
    ) {
        val isWide = maxWidth > 700.dp

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    ViewfinderViewport(
                        controls = controls,
                        telemetry = telemetry,
                        peakingBitmap = peakingBitmap,
                        lenses = lenses,
                        selectedLens = selectedLens,
                        isEdge50Pro = isEdge50Pro,
                        onSurfaceReady = viewModel::onPreviewSurfaceReady,
                        onSurfaceDestroyed = viewModel::onPreviewSurfaceDestroyed,
                        onToggleHistogramMode = viewModel::toggleHistogramMode,
                        onSelectLens = viewModel::selectLens,
                        onZoomChange = { z -> viewModel.updateControls { it.copy(zoomRatio = z) } },
                        onOpenDiagnostics = { showDiagnostics = true },
                        onNavigateToLab = onNavigateToLab,
                        onNavigateToDocs = onNavigateToDocs,
                        onToggleGrid = { viewModel.updateControls { it.copy(gridEnabled = !it.gridEnabled) } }
                    )
                }

                Column(
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .background(VoidBlack)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    ProControlDeck(
                        controls = controls,
                        lens = selectedLens,
                        activeTab = activeTab,
                        isExpanded = true,
                        handStabilityScore = telemetry.handStabilityScore,
                        onSelectTab = viewModel::setProTab,
                        onUpdateControls = viewModel::updateControls,
                        onSetIspMode = viewModel::setIspMode,
                        onSetWbPreset = viewModel::setWhiteBalancePreset,
                        onSetRamBufferMb = viewModel::setRamBufferMb,
                        onResetExposure = viewModel::resetLiveExposure,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    ModeAndShutterBar(
                        controls = controls,
                        telemetry = telemetry,
                        latestThumbUri = capturedItems.firstOrNull { !it.isDng && !it.isVideo }?.uriString,
                        isDrawerOpen = isDrawerOpen,
                        onSelectMode = viewModel::selectMode,
                        onShutter = { viewModel.onShutterPressed(hasAudioPermission) },
                        onToggleDrawer = viewModel::toggleProDrawer,
                        onNavigateToLab = onNavigateToLab
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                ViewfinderViewport(
                    controls = controls,
                    telemetry = telemetry,
                    peakingBitmap = peakingBitmap,
                    lenses = lenses,
                    selectedLens = selectedLens,
                    isEdge50Pro = isEdge50Pro,
                    onSurfaceReady = viewModel::onPreviewSurfaceReady,
                    onSurfaceDestroyed = viewModel::onPreviewSurfaceDestroyed,
                    onToggleHistogramMode = viewModel::toggleHistogramMode,
                    onSelectLens = viewModel::selectLens,
                    onZoomChange = { z -> viewModel.updateControls { it.copy(zoomRatio = z) } },
                    onOpenDiagnostics = { showDiagnostics = true },
                    onNavigateToLab = onNavigateToLab,
                    onNavigateToDocs = onNavigateToDocs,
                    onToggleGrid = { viewModel.updateControls { it.copy(gridEnabled = !it.gridEnabled) } }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Lens & Zoom Bar
                    LensAndZoomBar(
                        lenses = lenses,
                        selectedLens = selectedLens,
                        zoomRatio = controls.zoomRatio,
                        isSuperRes = controls.mode == CameraMode.SUPER_RES,
                        onSelectLens = viewModel::selectLens,
                        onZoomChange = { z -> viewModel.updateControls { it.copy(zoomRatio = z) } }
                    )

                    ProControlDeck(
                        controls = controls,
                        lens = selectedLens,
                        activeTab = activeTab,
                        isExpanded = isDrawerOpen,
                        handStabilityScore = telemetry.handStabilityScore,
                        onSelectTab = viewModel::setProTab,
                        onUpdateControls = viewModel::updateControls,
                        onSetIspMode = viewModel::setIspMode,
                        onSetWbPreset = viewModel::setWhiteBalancePreset,
                        onSetRamBufferMb = viewModel::setRamBufferMb,
                        onResetExposure = viewModel::resetLiveExposure
                    )

                    ModeAndShutterBar(
                        controls = controls,
                        telemetry = telemetry,
                        latestThumbUri = capturedItems.firstOrNull { !it.isDng && !it.isVideo }?.uriString,
                        isDrawerOpen = isDrawerOpen,
                        onSelectMode = viewModel::selectMode,
                        onShutter = { viewModel.onShutterPressed(hasAudioPermission) },
                        onToggleDrawer = viewModel::toggleProDrawer,
                        onNavigateToLab = onNavigateToLab
                    )
                }
            }
        }
    }

    if (showDiagnostics) {
        DeviceDiagnosticsSheet(
            deviceModel = deviceModel,
            isEdge50Pro = isEdge50Pro,
            lenses = lenses,
            onDismiss = { showDiagnostics = false }
        )
    }
}

@Composable
private fun ViewfinderViewport(
    controls: ManualControlsState,
    telemetry: TelemetryState,
    peakingBitmap: android.graphics.Bitmap?,
    lenses: List<CameraLensProfile>,
    selectedLens: CameraLensProfile?,
    isEdge50Pro: Boolean,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onToggleHistogramMode: () -> Unit,
    onSelectLens: (CameraLensProfile) -> Unit,
    onZoomChange: (Float) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onNavigateToLab: () -> Unit,
    onNavigateToDocs: () -> Unit,
    onToggleGrid: () -> Unit
) {
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeSurface?.release()
            onSurfaceDestroyed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Live Hardware Camera2 Preview TextureView with Real-time GPU ColorMatrix RenderEffect
        val colorMatrixValues = remember(controls.exposureEdit) {
            ColorScience.buildPreviewColorMatrix(controls.exposureEdit).values
        }

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            st.setDefaultBufferSize(1440, 1080)
                            val s = Surface(st)
                            activeSurface = s
                            onSurfaceReady(s)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurfaceDestroyed()
                            activeSurface?.release()
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            update = { tv ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val filter = ColorMatrixColorFilter(colorMatrixValues)
                    tv.setRenderEffect(RenderEffect.createColorFilterEffect(filter))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Focus Peaking, Zebra, Rule of Thirds & Spirit Level
        FocusPeakingAndGridOverlay(
            peakingBitmap = peakingBitmap,
            gridEnabled = controls.gridEnabled,
            levelEnabled = controls.levelEnabled,
            tiltDegrees = telemetry.tiltDegrees
        )

        // Top HUD Telemetry Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HudBadge(
                        text = if (controls.enable50MpPro) "50MP" else controls.captureFormat.labelPl,
                        accent = AmberPro
                    )
                    HudBadge(
                        text = controls.ispMode.shortPl,
                        accent = if (controls.ispMode == IspProcessingMode.PURE_SENSOR_BYPASS) CyanOptic else EmeraldStable
                    )
                    if (controls.mode == CameraMode.VIDEO_4K_RAM) {
                        HudBadge(
                            text = if (isEdge50Pro) "E50P 4K RAM" else "4K RAM",
                            accent = EmeraldStable
                        )
                    } else {
                        Text(
                            text = "ISO ${if (controls.isIsoAuto) telemetry.activeIsoReadout else controls.isoValue} · " +
                                ColorScience.formatShutterNs(
                                    if (controls.isShutterAuto) telemetry.activeShutterNsReadout else controls.shutterNs
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = onToggleGrid,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("toggle_grid_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Siatka i poziomica",
                            tint = if (controls.gridEnabled) AmberPro else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Diagnostyka Edge 50 Pro",
                            tint = CyanOptic
                        )
                    }
                    IconButton(
                        onClick = onNavigateToLab,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("nav_lab_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "Laboratorium AI i Odszumianie",
                            tint = AmberPro
                        )
                    }
                    IconButton(
                        onClick = onNavigateToDocs,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("nav_docs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Dokumentacja GitHub i APK",
                            tint = TextPrimary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Left: Live 4K RAM Buffer Telemetry or Mode Badge
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (controls.mode == CameraMode.VIDEO_4K_RAM) {
                        RamBufferTelemetryCard(telemetry = telemetry)
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceGlass)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${controls.mode.titlePl} · ${telemetry.liveFps} fps · AI ${controls.denoiseLevel.shortPl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    telemetry.statusBannerPl?.let { banner ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(AmberProContainer)
                                .border(1.dp, AmberPro, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(text = banner, style = MaterialTheme.typography.labelSmall, color = AmberPro)
                        }
                    }
                }

                // Right: Live Luma/RGB Histogram
                if (controls.histogramVisible) {
                    HistogramOverlay(
                        lumaBins = telemetry.lumaHistogram,
                        redBins = telemetry.redHistogram,
                        greenBins = telemetry.greenHistogram,
                        blueBins = telemetry.blueHistogram,
                        isRgbMode = controls.histogramRgbMode,
                        highlightClipPct = telemetry.highlightClipPct,
                        shadowClipPct = telemetry.shadowClipPct,
                        onToggleMode = onToggleHistogramMode
                    )
                }
            }
        }

        // Computational Processing Progress Overlay
        AnimatedVisibility(
            visible = telemetry.isProcessing,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, CyanOptic, RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    progress = { telemetry.processingProgress.coerceIn(0.05f, 1f) },
                    color = CyanOptic,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = telemetry.processingStatusPl.ifBlank { "Przetwarzanie..." },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun RamBufferTelemetryCard(telemetry: TelemetryState) {
    val fillFrac = (telemetry.ramBufferUsedMb / telemetry.ramBufferMaxMb.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .width(195.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceGlass)
            .border(1.dp, if (telemetry.isRecording) CrimsonRecord else EmeraldStable, RoundedCornerShape(12.dp))
            .padding(8.dp)
            .testTag("ram_buffer_telemetry"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Bufor RAM 4K",
                tint = if (telemetry.isRecording) CrimsonRecord else EmeraldStable,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (telemetry.isRecording) {
                    "REC ${telemetry.recordingElapsedSec}s · RAM ${String.format("%.1f", telemetry.ramBufferUsedMb)}/${telemetry.ramBufferMaxMb} MB"
                } else {
                    "BUFOR RAM: ${telemetry.ramBufferMaxMb} MB GOTOWY"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary
            )
        }
        LinearProgressIndicator(
            progress = { fillFrac },
            color = if (fillFrac > 0.8f) CrimsonRecord else EmeraldStable,
            trackColor = SurfaceElevated,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
        Text(
            text = "OIS+EIS Aktywne · Utracone klatki: ${telemetry.droppedFrames}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun LensAndZoomBar(
    lenses: List<CameraLensProfile>,
    selectedLens: CameraLensProfile?,
    zoomRatio: Float,
    isSuperRes: Boolean,
    onSelectLens: (CameraLensProfile) -> Unit,
    onZoomChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lenses.forEach { lens ->
                val isSelected = lens.cameraId == selectedLens?.cameraId && lens.physicalId == selectedLens.physicalId
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) AmberPro else SurfaceElevated)
                        .clickable { onSelectLens(lens) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("lens_${lens.label}")
                ) {
                    Text(
                        text = lens.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) VoidBlack else TextPrimary
                    )
                }
            }
        }

        Slider(
            value = zoomRatio,
            onValueChange = onZoomChange,
            valueRange = 1.0f..10.0f,
            colors = SliderDefaults.colors(
                thumbColor = if (isSuperRes) CyanOptic else AmberPro,
                activeTrackColor = if (isSuperRes) CyanOptic else AmberPro
            ),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .testTag("zoom_slider")
        )

        Text(
            text = String.format("%.1fx", zoomRatio),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSuperRes && zoomRatio > 1.2f) CyanOptic else AmberPro
        )
    }
}

@Composable
private fun ModeAndShutterBar(
    controls: ManualControlsState,
    telemetry: TelemetryState,
    latestThumbUri: String?,
    isDrawerOpen: Boolean,
    onSelectMode: (CameraMode) -> Unit,
    onShutter: () -> Unit,
    onToggleDrawer: () -> Unit,
    onNavigateToLab: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Mode Carousel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceGlass)
                .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CameraMode.entries.forEach { mode ->
                val active = controls.mode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                active && mode == CameraMode.VIDEO_4K_RAM -> CrimsonRecord.copy(alpha = 0.25f)
                                active -> AmberProContainer
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                active && mode == CameraMode.VIDEO_4K_RAM -> CrimsonRecord
                                active -> AmberPro
                                else -> Color.Transparent
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("mode_${mode.name.lowercase()}")
                ) {
                    Text(
                        text = mode.titlePl,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            active && mode == CameraMode.VIDEO_4K_RAM -> CrimsonRecord
                            active -> AmberPro
                            else -> TextSecondary
                        }
                    )
                }
            }
        }

        // Shutter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Latest Thumbnail / Lab Shortcut
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceElevated)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .clickable(onClick = onNavigateToLab)
                    .testTag("latest_thumb_lab"),
                contentAlignment = Alignment.Center
            ) {
                if (latestThumbUri != null) {
                    AsyncImage(
                        model = latestThumbUri,
                        contentDescription = "Ostatnie zdjęcie — otwórz Laboratorium AI",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Otwórz Laboratorium AI",
                        tint = TextSecondary
                    )
                }
            }

            // Primary Shutter / 4K Record Button
            val isVideoMode = controls.mode == CameraMode.VIDEO_4K_RAM
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(
                        width = 3.dp,
                        color = when {
                            telemetry.isRecording -> CrimsonRecord
                            isVideoMode -> CrimsonRecord.copy(alpha = 0.85f)
                            controls.mode == CameraMode.SUPER_RES -> CyanOptic
                            else -> TextPrimary
                        },
                        shape = CircleShape
                    )
                    .clickable(onClick = onShutter)
                    .padding(6.dp)
                    .testTag("shutter_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (telemetry.isRecording) 26.dp else 54.dp)
                        .clip(if (telemetry.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(
                            when {
                                isVideoMode -> CrimsonRecord
                                controls.mode == CameraMode.SUPER_RES -> CyanOptic
                                controls.mode == CameraMode.MOTION_BLUR -> AmberPro
                                else -> TextPrimary
                            }
                        )
                )
            }

            // Toggle Pro Controls Drawer
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDrawerOpen) AmberProContainer else SurfaceElevated)
                    .border(1.dp, if (isDrawerOpen) AmberPro else BorderSubtle, RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleDrawer)
                    .testTag("toggle_pro_drawer"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isDrawerOpen) Icons.Default.ExpandMore else Icons.Default.Tune,
                        contentDescription = "Pokaż lub ukryj panel Pro",
                        tint = if (isDrawerOpen) AmberPro else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "PRO",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDrawerOpen) AmberPro else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HudBadge(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

@Composable
private fun PermissionGateScreen(
    onRequest: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenDocs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(AmberProContainer)
                .border(2.dp, AmberPro, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Ikona aparatu Kadr RAW",
                tint = AmberPro,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Kadr RAW · Aparat Pro",
            style = MaterialTheme.typography.displaySmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Aplikacja potrzebuje dostępu do matryc Camera2 oraz mikrofonu, aby uruchomić tryb 50 MP RAW/DNG, Focus Peaking, histogram i buforowany zapis 4K w pamięci RAM.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = AmberPro, contentColor = VoidBlack),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("grant_camera_permission")
        ) {
            Text("Uruchom aparat (Przyznaj uprawnienia)", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onOpenLab,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = CyanOptic),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Laboratorium AI", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = onOpenDocs,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Opis GitHub/APK", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
