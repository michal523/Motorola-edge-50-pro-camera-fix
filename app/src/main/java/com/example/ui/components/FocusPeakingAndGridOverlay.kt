package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.ui.theme.AmberPro
import com.example.ui.theme.EmeraldStable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FocusPeakingAndGridOverlay(
    peakingBitmap: Bitmap?,
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    tiltDegrees: Float,
    modifier: Modifier = Modifier
) {
    if (peakingBitmap != null) {
        Image(
            bitmap = peakingBitmap.asImageBitmap(),
            contentDescription = "Podświetlanie krawędzi ostrości (Focus Peaking)",
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        if (gridEnabled) {
            val lineColor = Color.White.copy(alpha = 0.22f)
            // Rule of thirds
            drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.2f)
            drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 1.2f)
            drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.2f)
            drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 1.2f)
        }

        if (levelEnabled) {
            val cx = w / 2f
            val cy = h / 2f
            val isLevel = abs(tiltDegrees) < 1.2f
            val levelColor = if (isLevel) EmeraldStable else AmberPro.copy(alpha = 0.85f)

            // Center reference ticks
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(cx - 56f, cy),
                end = Offset(cx - 28f, cy),
                strokeWidth = 2f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(cx + 28f, cy),
                end = Offset(cx + 56f, cy),
                strokeWidth = 2f
            )

            // Dynamic horizon tilt bar
            val rad = Math.toRadians(tiltDegrees.toDouble())
            val barHalf = 42f
            val dx = (barHalf * cos(rad)).toFloat()
            val dy = (barHalf * sin(rad)).toFloat()
            drawLine(
                color = levelColor,
                start = Offset(cx - dx, cy - dy),
                end = Offset(cx + dx, cy + dy),
                strokeWidth = if (isLevel) 4f else 2.5f
            )
        }
    }
}
