package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.camera.ColorScience
import com.example.domain.model.CameraLensProfile
import com.example.ui.theme.AmberPro
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.EmeraldStable
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceObsidian
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiagnosticsSheet(
    deviceModel: String,
    isEdge50Pro: Boolean,
    lenses: List<CameraLensProfile>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceObsidian
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Diagnostyka Matryc Camera2",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = if (isEdge50Pro) {
                    "Wykryto profil: $deviceModel (Aktywna optymalizacja Edge 50 Pro: 50 MP Quad-Bayer + 4K RAM Buffer)"
                } else {
                    "Urządzenie: $deviceModel (Kompatybilny profil uniwersalny Camera2)"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isEdge50Pro) EmeraldStable else CyanOptic
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lenses) { lens ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceElevated)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${lens.label} · ${lens.fullNamePl}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AmberPro
                            )
                            Text(
                                text = "ID ${lens.cameraId}${lens.physicalId?.let { "/$it" } ?: ""} (${lens.hardwareLevelLabel})",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = String.format(
                                "Ogniskowa: %.1f mm · f/%.1f · Max: %dx%d (%.1f MP)",
                                lens.focalLengthMm,
                                lens.aperture,
                                lens.maxOutputSize.width,
                                lens.maxOutputSize.height,
                                lens.megapixels
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "RAW DNG: ${if (lens.supportsRaw) "TAK (${lens.maxRawSize?.width}x${lens.maxRawSize?.height})" else "NIE"} · " +
                                "50MP Max-Res: ${if (lens.supportsMaxRes50Mp) "TAK" else "NIE"} · " +
                                "OIS: ${if (lens.supportsOis) "TAK" else "NIE"} · EIS: ${if (lens.supportsEis) "TAK" else "NIE"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanOptic
                        )
                        Text(
                            text = "Sprzętowy czas: ${ColorScience.formatShutterNs(lens.exposureRangeNs.first)} .. ${ColorScience.formatShutterNs(lens.exposureRangeNs.last)} " +
                                "(AI Stack do 60s) · ISO: ${lens.isoRange.first}..${lens.isoRange.last}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
