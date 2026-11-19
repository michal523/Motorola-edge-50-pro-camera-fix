package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.ColorScience
import com.example.domain.model.CameraLensProfile
import com.example.domain.model.CaptureFormat
import com.example.domain.model.DenoiseLevel
import com.example.domain.model.FocusPeakingColor
import com.example.domain.model.IspProcessingMode
import com.example.domain.model.ManualControlsState
import com.example.domain.model.MotionBlurStyle
import com.example.domain.model.WhiteBalancePreset
import com.example.ui.ProControlTab
import com.example.ui.theme.AmberPro
import com.example.ui.theme.AmberProContainer
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.CyanOpticContainer
import com.example.ui.theme.EmeraldStable
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceGlass
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun ProControlDeck(
    controls: ManualControlsState,
    lens: CameraLensProfile?,
    activeTab: ProControlTab,
    isExpanded: Boolean,
    handStabilityScore: Float,
    onSelectTab: (ProControlTab) -> Unit,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit,
    onSetIspMode: (IspProcessingMode) -> Unit,
    onSetWbPreset: (WhiteBalancePreset) -> Unit,
    onSetRamBufferMb: (Int) -> Unit,
    onResetExposure: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProControlTab.entries.forEach { tab ->
                val selected = tab == activeTab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) AmberProContainer else Color.Transparent)
                        .border(
                            1.dp,
                            if (selected) AmberPro else BorderSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("pro_tab_${tab.name.lowercase()}")
                ) {
                    Text(
                        text = tab.labelPl,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) AmberPro else TextSecondary
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (activeTab) {
                    ProControlTab.EXPOSURE -> ExposureTab(controls, lens, onUpdateControls)
                    ProControlTab.WB_FOCUS -> WbAndFocusTab(controls, onUpdateControls, onSetWbPreset)
                    ProControlTab.LIVE_EDIT -> LiveEditTab(controls, onUpdateControls, onResetExposure)
                    ProControlTab.AI_ISP -> AiAndIspTab(controls, onUpdateControls, onSetIspMode)
                    ProControlTab.EXPERIMENTAL -> ExperimentalTab(
                        controls = controls,
                        handStabilityScore = handStabilityScore,
                        onUpdateControls = onUpdateControls,
                        onSetRamBufferMb = onSetRamBufferMb
                    )
                }
            }
        }
    }
}

@Composable
private fun ExposureTab(
    controls: ManualControlsState,
    lens: CameraLensProfile?,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit
) {
    val hwMaxNs = lens?.exposureRangeNs?.last ?: 1_000_000_000L
    val isExtendedShutter = !controls.isShutterAuto && controls.shutterNs > hwMaxNs

    // Shutter Speed (1/16000s .. 60s)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "MIGAWKA: ${if (controls.isShutterAuto) "AUTO" else ColorScience.formatShutterNs(controls.shutterNs)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (isExtendedShutter) CyanOptic else AmberPro
            )
            if (isExtendedShutter) {
                Text(
                    text = "Tryb AI Stack (> limit sprzętu ${ColorScience.formatShutterNs(hwMaxNs)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanOptic
                )
            }
        }
        AutoToggleChip(
            isAuto = controls.isShutterAuto,
            tag = "shutter_auto_toggle",
            onClick = { onUpdateControls { it.copy(isShutterAuto = !it.isShutterAuto) } }
        )
    }

    Slider(
        value = ColorScience.shutterNsToSlider(controls.shutterNs),
        onValueChange = { frac ->
            val ns = ColorScience.sliderToShutterNs(frac)
            onUpdateControls { it.copy(isShutterAuto = false, shutterNs = ns) }
        },
        colors = SliderDefaults.colors(thumbColor = AmberPro, activeTrackColor = AmberPro),
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .testTag("shutter_slider")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            62_500L to "1/16000",
            125_000L to "1/8000",
            1_000_000L to "1/1000",
            8_000_000L to "1/125",
            1_000_000_000L to "1s",
            8_000_000_000L to "8s",
            30_000_000_000L to "30s",
            60_000_000_000L to "60s"
        ).forEach { (ns, label) ->
            SmallValuePill(
                label = label,
                selected = !controls.isShutterAuto && kotlin.math.abs(controls.shutterNs - ns) < ns * 0.2f,
                onClick = { onUpdateControls { it.copy(isShutterAuto = false, shutterNs = ns) } }
            )
        }
    }

    // ISO Sensitivity
    val minIso = lens?.isoRange?.first ?: 50
    val maxIso = (lens?.isoRange?.last ?: 12800).coerceAtLeast(3200)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CZUŁOŚĆ ISO: ${if (controls.isIsoAuto) "AUTO" else controls.isoValue}",
            style = MaterialTheme.typography.labelMedium,
            color = AmberPro
        )
        AutoToggleChip(
            isAuto = controls.isIsoAuto,
            tag = "iso_auto_toggle",
            onClick = { onUpdateControls { it.copy(isIsoAuto = !it.isIsoAuto) } }
        )
    }

    Slider(
        value = ((controls.isoValue - minIso).toFloat() / (maxIso - minIso).coerceAtLeast(1)).coerceIn(0f, 1f),
        onValueChange = { frac ->
            val iso = (minIso + frac * (maxIso - minIso)).roundToInt()
            onUpdateControls { it.copy(isIsoAuto = false, isoValue = iso) }
        },
        colors = SliderDefaults.colors(thumbColor = CyanOptic, activeTrackColor = CyanOptic),
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .testTag("iso_slider")
    )

    // Capture Format & 50 MP PRO toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val supports50Mp = lens?.supportsMaxRes50Mp ?: true
        FilterChip(
            selected = controls.enable50MpPro,
            onClick = {
                onUpdateControls {
                    val next50 = !it.enable50MpPro
                    it.copy(
                        enable50MpPro = next50,
                        captureFormat = if (next50 && it.captureFormat == CaptureFormat.JPEG) {
                            CaptureFormat.ULTRA_50MP_PRO
                        } else it.captureFormat
                    )
                }
            },
            label = {
                Text(
                    text = if (supports50Mp) "50 MP PRO" else "MAX RES PRO",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AmberProContainer,
                selectedLabelColor = AmberPro
            ),
            modifier = Modifier.testTag("toggle_50mp_pro")
        )

        CaptureFormat.entries.forEach { fmt ->
            FilterChip(
                selected = controls.captureFormat == fmt,
                onClick = { onUpdateControls { it.copy(captureFormat = fmt) } },
                label = { Text(fmt.labelPl, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanOpticContainer,
                    selectedLabelColor = CyanOptic
                ),
                modifier = Modifier.testTag("format_${fmt.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun WbAndFocusTab(
    controls: ManualControlsState,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit,
    onSetWbPreset: (WhiteBalancePreset) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BALANS BIELI: ${if (controls.wbPreset == WhiteBalancePreset.AUTO) "AUTO" else "${controls.wbKelvin}K · Odcień ${controls.wbTint}"}",
            style = MaterialTheme.typography.labelMedium,
            color = AmberPro
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        WhiteBalancePreset.entries.forEach { preset ->
            SmallValuePill(
                label = preset.labelPl,
                selected = controls.wbPreset == preset,
                onClick = { onSetWbPreset(preset) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Temp", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(36.dp))
        Slider(
            value = ((controls.wbKelvin - 2000f) / 8000f).coerceIn(0f, 1f),
            onValueChange = { f ->
                val k = (2000 + (f * 8000f)).roundToInt()
                onUpdateControls { it.copy(wbPreset = WhiteBalancePreset.MANUAL, wbKelvin = k) }
            },
            colors = SliderDefaults.colors(thumbColor = AmberPro, activeTrackColor = AmberPro),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .testTag("wb_kelvin_slider")
        )
        Text("${controls.wbKelvin}K", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Odcień", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(36.dp))
        Slider(
            value = ((controls.wbTint + 50f) / 100f).coerceIn(0f, 1f),
            onValueChange = { f ->
                val t = (-50 + (f * 100f)).roundToInt()
                onUpdateControls { it.copy(wbPreset = WhiteBalancePreset.MANUAL, wbTint = t) }
            },
            colors = SliderDefaults.colors(thumbColor = Color(0xFFE879F9), activeTrackColor = Color(0xFF34D399)),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .testTag("wb_tint_slider")
        )
        Text("${if (controls.wbTint > 0) "+" else ""}${controls.wbTint}", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }

    // Manual Focus + Focus Peaking
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "OSTROŚĆ: ${if (controls.manualFocusEnabled) "MF ${(controls.focusDistanceNormalized * 100).toInt()}%" else "AF Ciągły"}",
            style = MaterialTheme.typography.labelMedium,
            color = CyanOptic
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallValuePill(
                label = if (controls.manualFocusEnabled) "MF WŁ." else "AF",
                selected = controls.manualFocusEnabled,
                onClick = { onUpdateControls { it.copy(manualFocusEnabled = !it.manualFocusEnabled) } }
            )
            SmallValuePill(
                label = "PEAKING",
                selected = controls.focusPeakingEnabled,
                onClick = { onUpdateControls { it.copy(focusPeakingEnabled = !it.focusPeakingEnabled) } }
            )
        }
    }

    Slider(
        value = controls.focusDistanceNormalized,
        onValueChange = { f ->
            onUpdateControls {
                it.copy(
                    manualFocusEnabled = true,
                    focusPeakingEnabled = true,
                    focusDistanceNormalized = f
                )
            }
        },
        colors = SliderDefaults.colors(thumbColor = CyanOptic, activeTrackColor = CyanOptic),
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .testTag("manual_focus_slider")
    )

    if (controls.focusPeakingEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Kolor krawędzi:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            FocusPeakingColor.entries.forEach { pc ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(pc.argb))
                        .border(
                            width = if (controls.focusPeakingColor == pc) 2.5.dp else 1.dp,
                            color = if (controls.focusPeakingColor == pc) Color.White else BorderSubtle,
                            shape = CircleShape
                        )
                        .clickable { onUpdateControls { it.copy(focusPeakingColor = pc) } }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Czułość", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Slider(
                value = controls.focusPeakingSensitivity,
                onValueChange = { s -> onUpdateControls { it.copy(focusPeakingSensitivity = s) } },
                modifier = Modifier
                    .width(90.dp)
                    .height(24.dp)
            )
        }
    }
}

@Composable
private fun LiveEditTab(
    controls: ManualControlsState,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit,
    onResetExposure: () -> Unit
) {
    val edit = controls.exposureEdit
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("PARAMETRY EKSPOZYCJI NA ŻYWO", style = MaterialTheme.typography.labelMedium, color = AmberPro)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallValuePill(
                label = "ZEBRA",
                selected = edit.zebraEnabled,
                onClick = { onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(zebraEnabled = !it.exposureEdit.zebraEnabled)) } }
            )
            TextButton(onClick = onResetExposure, modifier = Modifier.height(30.dp)) {
                Text("Reset", style = MaterialTheme.typography.labelSmall, color = CyanOptic)
            }
        }
    }

    CompactEditSlider("EV", edit.evOffset, -3f..3f, String.format("%+.1f EV", edit.evOffset)) { v ->
        onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(evOffset = v)) }
    }
    CompactEditSlider("Cienie", edit.shadows, -1f..1f, String.format("%+.0f%%", edit.shadows * 100)) { v ->
        onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(shadows = v)) }
    }
    CompactEditSlider("Światła", edit.highlights, -1f..1f, String.format("%+.0f%%", edit.highlights * 100)) { v ->
        onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(highlights = v)) }
    }
    CompactEditSlider("Kontrast", edit.contrast, 0.5f..1.8f, String.format("%.2f×", edit.contrast)) { v ->
        onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(contrast = v)) }
    }
    CompactEditSlider("Nasycenie", edit.saturation, 0f..1.8f, String.format("%.2f×", edit.saturation)) { v ->
        onUpdateControls { it.copy(exposureEdit = it.exposureEdit.copy(saturation = v)) }
    }
}

@Composable
private fun AiAndIspTab(
    controls: ManualControlsState,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit,
    onSetIspMode: (IspProcessingMode) -> Unit
) {
    Text("SYSTEMOWA POPRAWA ZDJĘĆ (ISP HAL)", style = MaterialTheme.typography.labelMedium, color = AmberPro)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IspProcessingMode.entries.forEach { mode ->
            FilterChip(
                selected = controls.ispMode == mode,
                onClick = { onSetIspMode(mode) },
                label = { Text(mode.labelPl, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (mode == IspProcessingMode.PURE_SENSOR_BYPASS) CyanOpticContainer else AmberProContainer,
                    selectedLabelColor = if (mode == IspProcessingMode.PURE_SENSOR_BYPASS) CyanOptic else AmberPro
                ),
                modifier = Modifier.testTag("isp_mode_${mode.name.lowercase()}")
            )
        }
    }
    Text(
        text = controls.ispMode.detailPl,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary
    )

    Spacer(modifier = Modifier.height(4.dp))
    Text("MODEL ODSZUMIANIA AI (NA URZĄDZENIU)", style = MaterialTheme.typography.labelMedium, color = CyanOptic)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DenoiseLevel.entries.forEach { lvl ->
            FilterChip(
                selected = controls.denoiseLevel == lvl,
                onClick = { onUpdateControls { it.copy(denoiseLevel = lvl) } },
                label = { Text(lvl.labelPl, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanOpticContainer,
                    selectedLabelColor = CyanOptic
                ),
                modifier = Modifier.testTag("denoise_${lvl.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun ExperimentalTab(
    controls: ManualControlsState,
    handStabilityScore: Float,
    onUpdateControls: ((ManualControlsState) -> ManualControlsState) -> Unit,
    onSetRamBufferMb: (Int) -> Unit
) {
    Text("ROZMYCIE RUCHU W DZIEŃ (SYMULATOR FILTRA ND)", style = MaterialTheme.typography.labelMedium, color = AmberPro)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MotionBlurStyle.entries.forEach { style ->
            SmallValuePill(
                label = style.labelPl,
                selected = controls.motionBlurStyle == style,
                onClick = { onUpdateControls { it.copy(motionBlurStyle = style) } }
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Klatki ND: ${controls.motionFrameCount}", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        Slider(
            value = ((controls.motionFrameCount - 8f) / 56f).coerceIn(0f, 1f),
            onValueChange = { f ->
                onUpdateControls { it.copy(motionFrameCount = (8 + (f * 56)).roundToInt()) }
            },
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
    }

    Text(
        text = "SUPER-ROZDZIELCZOŚĆ Z DRGAŃ RĘKI (${controls.superResFrameCount} klatek · Stabilność ${(handStabilityScore * 100).toInt()}%)",
        style = MaterialTheme.typography.labelMedium,
        color = CyanOptic
    )
    Slider(
        value = ((controls.superResFrameCount - 4f) / 8f).coerceIn(0f, 1f),
        onValueChange = { f ->
            onUpdateControls { it.copy(superResFrameCount = (4 + (f * 8)).roundToInt()) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
    )

    Text("WIDEO 4K + OIS/EIS BUFOROWANE W PAMIĘCI RAM (EDGE 50 PRO FIX)", style = MaterialTheme.typography.labelMedium, color = EmeraldStable)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(128, 256, 512).forEach { mb ->
            SmallValuePill(
                label = "RAM $mb MB",
                selected = controls.ramBufferMb == mb,
                onClick = { onSetRamBufferMb(mb) }
            )
        }
        SmallValuePill(
            label = "${controls.videoFps} FPS",
            selected = controls.videoFps == 60,
            onClick = { onUpdateControls { it.copy(videoFps = if (it.videoFps == 30) 60 else 30) } }
        )
        SmallValuePill(
            label = if (controls.videoHevcEnabled) "H.265 HEVC" else "H.264",
            selected = controls.videoHevcEnabled,
            onClick = { onUpdateControls { it.copy(videoHevcEnabled = !it.videoHevcEnabled) } }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OIS+EIS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = controls.videoStabilizationEnabled,
                onCheckedChange = { on -> onUpdateControls { it.copy(videoStabilizationEnabled = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = EmeraldStable)
            )
        }
    }
}

@Composable
private fun CompactEditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(62.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = AmberPro, activeTrackColor = AmberPro),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Text(readout, style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun AutoToggleChip(isAuto: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isAuto) EmeraldStable.copy(alpha = 0.2f) else AmberProContainer)
            .border(1.dp, if (isAuto) EmeraldStable else AmberPro, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(tag)
    ) {
        Text(
            text = if (isAuto) "AUTO" else "MANUAL",
            style = MaterialTheme.typography.labelSmall,
            color = if (isAuto) EmeraldStable else AmberPro
        )
    }
}

@Composable
private fun SmallValuePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AmberProContainer else SurfaceElevated)
            .border(1.dp, if (selected) AmberPro else BorderSubtle, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AmberPro else TextSecondary
        )
    }
}
