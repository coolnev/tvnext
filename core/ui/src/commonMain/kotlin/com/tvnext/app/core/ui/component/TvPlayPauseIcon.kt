package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.tvnext.app.core.common.platform.isAndroidPlatform
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** Large, centered play/pause control used consistently by archive and VOD UI. */
@Composable
fun TvPlayPauseIcon(
    paused: Boolean,
    modifier: Modifier = Modifier,
    circleSize: androidx.compose.ui.unit.Dp = 96.dp,
    iconColor: Color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
    backgroundAlpha: Float = 0.72f,
) {
    Box(
        modifier = modifier
            .size(circleSize)
            .background((if (isAndroidPlatform()) MaterialTheme.colorScheme.surface else Color.Black).copy(alpha = backgroundAlpha), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(circleSize * 0.48f)) {
            if (paused) {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.10f)
                    lineTo(size.width * 0.86f, size.height * 0.50f)
                    lineTo(size.width * 0.28f, size.height * 0.90f)
                    close()
                }
                drawPath(path, iconColor)
            } else {
                val barWidth = size.width * 0.22f
                val gap = size.width * 0.16f
                val top = size.height * 0.10f
                val barHeight = size.height * 0.80f
                val firstX = size.width * 0.20f
                val radius = 3.dp.toPx()
                drawRoundRect(iconColor, Offset(firstX, top), Size(barWidth, barHeight), CornerRadius(radius, radius))
                drawRoundRect(iconColor, Offset(firstX + barWidth + gap, top), Size(barWidth, barHeight), CornerRadius(radius, radius))
            }
        }
    }
}
