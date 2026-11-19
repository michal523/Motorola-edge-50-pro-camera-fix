package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberPro
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CrimsonRecord
import com.example.ui.theme.CyanOptic
import com.example.ui.theme.SurfaceGlass
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun HistogramOverlay(
    lumaBins: FloatArray,
    redBins: FloatArray,
    greenBins: FloatArray,
    blueBins: FloatArray,
    isRgbMode: Boolean,
    highlightClipPct: Float,
    shadowClipPct: Float,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleMode)
            .padding(8.dp)
            .testTag("histogram_widget"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isRgbMode) "HIST RGB" else "HIST LUMA",
                style = MaterialTheme.typography.labelSmall,
                color = if (isRgbMode) CyanOptic else AmberPro
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shadowClipPct > 2f) {
                    Text(
                        text = "C:${shadowClipPct.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanOptic
                    )
                }
                if (highlightClipPct > 1.5f) {
                    Text(
                        text = "Ś:${highlightClipPct.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = CrimsonRecord
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Quarter grid lines
                for (i in 1..3) {
                    val x = w * (i / 4f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.08f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f
                    )
                }

                if (isRgbMode) {
                    drawHistogramCurve(redBins, Color(0xFFFF4D6D).copy(alpha = 0.65f), fillAlpha = 0.16f)
                    drawHistogramCurve(greenBins, Color(0xFF10B981).copy(alpha = 0.65f), fillAlpha = 0.16f)
                    drawHistogramCurve(blueBins, Color(0xFF38BDF8).copy(alpha = 0.70f), fillAlpha = 0.18f)
                } else {
                    drawHistogramCurve(lumaBins, TextPrimary.copy(alpha = 0.9f), fillAlpha = 0.30f)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramCurve(
    bins: FloatArray,
    strokeColor: Color,
    fillAlpha: Float
) {
    if (bins.isEmpty()) return
    val w = size.width
    val h = size.height
    val stepX = w / (bins.size - 1).coerceAtLeast(1)

    val fillPath = Path().apply {
        moveTo(0f, h)
        for (i in bins.indices) {
            val x = i * stepX
            val y = h - (bins[i].coerceIn(0f, 1f) * (h - 2f))
            lineTo(x, y)
        }
        lineTo(w, h)
        close()
    }
    drawPath(fillPath, color = strokeColor.copy(alpha = fillAlpha))

    val strokePath = Path().apply {
        for (i in bins.indices) {
            val x = i * stepX
            val y = h - (bins[i].coerceIn(0f, 1f) * (h - 2f))
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(strokePath, color = strokeColor, style = Stroke(width = 2f))
}
