package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Direction of a transport seek button. */
enum class TvSeekDirection { BACKWARD, FORWARD }

/**
 * Icon-only seek button with TV-remote long-press repeat.
 *
 * The first step is executed immediately. Holding the physical OK/Enter key
 * starts repeating after a short delay and stops on the corresponding KeyUp.
 * Repeated Android auto-repeat KeyDown events are deliberately ignored while
 * the button is held, so one physical press cannot produce duplicate steps.
 */
@Composable
fun TvSeekButton(
    direction: TvSeekDirection,
    onSeek: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    buttonSize: Dp = 52.dp,
) {
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    val arrowTint = MaterialTheme.colorScheme.onSurface

    TvCardButton(
        onClick = onSeek,
        enabled = enabled,
        modifier = modifier
            .size(buttonSize)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onPreviewKeyEvent { event ->
                val activation = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!activation || !enabled) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!held) {
                            held = true
                            onSeek()
                            repeatJob?.cancel()
                            repeatJob = scope.launch {
                                delay(420)
                                while (held) {
                                    onSeek()
                                    delay(180)
                                }
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        held = false
                        repeatJob?.cancel()
                        repeatJob = null
                        true
                    }
                    else -> true
                }
            },
        contentPadding = PaddingValues(0.dp),
        containerAlpha = 0f,
    ) {
        Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(buttonSize * (27f / 52f))) {
                // Two simple arrowheads follow theme tint; never platform emoji-yellow.
                val sign = if (direction == TvSeekDirection.BACKWARD) 1f else -1f
                for (n in 0..1) {
                    val centreX = size.width * (if (n == 0) 0.32f else 0.66f)
                    val halfW = size.width * 0.14f
                    val halfH = size.height * 0.33f
                    val triangle = Path().apply {
                        moveTo(centreX + sign * halfW, size.height / 2f - halfH)
                        lineTo(centreX - sign * halfW, size.height / 2f)
                        lineTo(centreX + sign * halfW, size.height / 2f + halfH)
                        close()
                    }
                    drawPath(triangle, arrowTint)
                }
            }
        }
    }
}
