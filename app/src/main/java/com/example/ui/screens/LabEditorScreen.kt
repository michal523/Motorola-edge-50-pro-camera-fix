package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.ColorScience
import com.example.domain.model.DenoiseLevel
import com.example.ui.CameraViewModel
import com.example.ui.theme.AmberPro
import com.example.ui.theme.AmberProContainer
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.CyanOpticContainer
import com.example.ui.theme.EmeraldStable
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceObsidian
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidBlack
import kotlin.math.roundToInt

@Composable
fun LabEditorScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit
) {
    val lab by viewModel.labState.collectAsStateWithLifecycle()
    val capturedItems by viewModel.capturedItems.collectAsStateWithLifecycle()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importLabPhoto(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("lab_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Powrót do aparatu",
                        tint = TextPrimary
                    )
                }
                Column {
                    Text("Laboratorium AI", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(lab.sourceTitle, style = MaterialTheme.typography.labelSmall, color = CyanOptic)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                    modifier = Modifier.testTag("lab_import_photo")
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wybierz", style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = viewModel::exportLabResult,
                    enabled = lab.sourceBitmap != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberPro, contentColor = VoidBlack),
                    modifier = Modifier.testTag("lab_export_button")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zapisz", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Split Before / After Preview Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceObsidian)
                .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            val src = lab.sourceBitmap
            if (src == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = AmberPro, modifier = Modifier.size(36.dp))
                    Text(
                        text = "Zrób zdjęcie aparatem lub wybierz plik z galerii",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                val colorFilter = remember(lab.exposureEdit) {
                    ColorFilter.colorMatrix(ColorScience.buildPreviewColorMatrix(lab.exposureEdit))
                }
                // Original Left
                Image(
                    bitmap = src.asImageBitmap(),
                    contentDescription = "Oryginał przed odszumianiem AI",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // Processed Right clipped at splitPosition with interactive drag
                val processed = lab.denoisedBitmap ?: src
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0.05f, 0.95f)
                                viewModel.updateLabState { it.copy(splitPosition = frac) }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0.05f, 0.95f)
                                viewModel.updateLabState { it.copy(splitPosition = frac) }
                            }
                        }
                ) {
                    Image(
                        bitmap = processed.asImageBitmap(),
                        contentDescription = "Obraz po odszumianiu AI i edycji ekspozycji",
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipRect(left = size.width * lab.splitPosition) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val splitX = size.width * lab.splitPosition
                        drawLine(
                            color = AmberPro,
                            start = Offset(splitX, 0f),
                            end = Offset(splitX, size.height),
                            strokeWidth = 3f
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ORYGINAŁ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = if (lab.denoisedBitmap != null) "PO AI DENOISE" else "PODGLĄD EDYCJI",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanOptic,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (lab.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = CyanOptic)
                        Text(lab.statusText, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                    }
                }
            }
        }

        // Scrollable Controls
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (capturedItems.isNotEmpty()) {
                Text("Ostatnie ujęcia w tej sesji", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    capturedItems.take(8).forEach { item ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceElevated)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                                .clickable { viewModel.selectCapturedForLab(item) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${item.modeLabel} · ${item.shutterDisplay}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.isDng) AmberPro else TextPrimary
                            )
                        }
                    }
                }
            }

            Text(lab.statusText, style = MaterialTheme.typography.bodyMedium, color = EmeraldStable)

            Text("MODEL ODSZUMIANIA AI (YCbCr Joint-Bilateral + Multi-Scale)", style = MaterialTheme.typography.labelMedium, color = CyanOptic)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DenoiseLevel.entries.forEach { lvl ->
                    FilterChip(
                        selected = lab.denoiseLevel == lvl,
                        onClick = { viewModel.updateLabState { it.copy(denoiseLevel = lvl) } },
                        label = { Text(lvl.labelPl, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanOpticContainer,
                            selectedLabelColor = CyanOptic
                        )
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kalibracja szumu ISO: ${lab.simulatedIso}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Slider(
                    value = lab.simulatedIso.toFloat(),
                    onValueChange = { v -> viewModel.updateLabState { it.copy(simulatedIso = v.roundToInt()) } },
                    valueRange = 100f..12800f,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = viewModel::runLabAiDenoise,
                enabled = lab.sourceBitmap != null && !lab.isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = CyanOptic, contentColor = VoidBlack),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_lab_ai_denoise")
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Uruchom Model Odszumiania AI", style = MaterialTheme.typography.titleMedium)
            }

            // Exposure Edits in Lab
            Text("KOREKCJA EKSPOZYCJI I TONACJI", style = MaterialTheme.typography.labelMedium, color = AmberPro)
            LabSlider("EV", lab.exposureEdit.evOffset, -3f..3f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(evOffset = v)) }
            }
            LabSlider("Cienie", lab.exposureEdit.shadows, -1f..1f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(shadows = v)) }
            }
            LabSlider("Światła", lab.exposureEdit.highlights, -1f..1f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(highlights = v)) }
            }
            LabSlider("Kontrast", lab.exposureEdit.contrast, 0.5f..1.8f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(contrast = v)) }
            }
            LabSlider("Nasycenie", lab.exposureEdit.saturation, 0f..1.8f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(saturation = v)) }
            }
            LabSlider("Ostrość", lab.exposureEdit.sharpness, 0f..1f) { v ->
                viewModel.updateLabState { it.copy(exposureEdit = it.exposureEdit.copy(sharpness = v)) }
            }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(64.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = AmberPro, activeTrackColor = AmberPro),
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
        )
        Text(String.format("%.2f", value), style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.width(42.dp))
    }
}
