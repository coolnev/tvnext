package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Flat progress bar with square ends — unlike Material3's
 * `LinearProgressIndicator` it has no rounded "cap" that looks like a dot at
 * the end of the filled part. Used for the EPG programme progress.
 */
@Composable
fun TvProgressBar(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    val fraction = progress().coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(1.dp))
            .background(trackColor),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(progressColor),
            )
        }
    }
}
