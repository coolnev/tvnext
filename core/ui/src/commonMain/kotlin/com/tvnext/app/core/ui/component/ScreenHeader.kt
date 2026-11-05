package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.ui.theme.TVNextDimens

/**
 * Shared screen header: a compact back button (chevron arrow, drawn on a canvas
 * like the settings gear) on the LEFT — white in dark themes / black in light
 * ones — and the window title on the RIGHT. The back button lets the user leave
 * any sub-screen for the Home tab, including fullscreen/immersive mode where
 * the sidebar is hidden.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = TVNextDimens.SectionSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
    ) {
        if (onBack != null) {
            TvCardButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Graphic chevron arrow: near-white on dark themes,
                    // near-black on light ones (same rule as the gear button).
                    BackArrowIcon(
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Thick chevron arrow pointing left, drawn on a canvas (no icon library). The
 * colour is passed by the caller — onSurface is near-white in dark themes and
 * near-black in light themes, matching the settings gear.
 */
@Composable
fun BackArrowIcon(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.16f
        val x0 = size.width * 0.78f
        val x1 = size.width * 0.26f
        val midY = size.height * 0.5f
        val tipY = size.height * 0.2f
        val path = Path().apply {
            moveTo(x0, tipY)
            lineTo(x1, midY)
            lineTo(x0, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
