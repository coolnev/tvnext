package com.tvnext.app.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay

/**
 * Text that slowly scrolls VERTICALLY when its content does not fit the given
 * [maxLines] window: after a 3-second pause the text starts moving up until the
 * LAST line is visible, pauses 3 seconds, then returns to the beginning.
 * The cycle repeats while the description is visible. Useful for EPG/TMDB descriptions on
 * TV screens where "…" truncation loses information.
 *
 * - Auto-scrolls only when the content overflows the window.
 * - Slow, linear scrolling in both directions with a 3-second pause at each end.
 * - Restarts from the top whenever [text] changes.
 */
@Composable
fun AutoScrollText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    if (com.tvnext.app.core.common.platform.isAndroidPlatform()) {
        AndroidDescription(text, style, color, maxLines, modifier)
        return
    }
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    var fullHeight by remember { mutableStateOf(0) }
    val lineHeightPx = with(density) {
        when {
            style.lineHeight.isSpecified -> style.lineHeight.toPx()
            style.fontSize.isSpecified -> style.fontSize.toPx() * 1.2f
            else -> 16.dp.toPx()
        }
    }
    val visibleHeightPx = (maxLines * lineHeightPx).toInt()

    Box(
        modifier = modifier
            .height(with(density) { visibleHeightPx.toDp() })
            .clipToBounds(),
    ) {
        // Invisible measuring pass: captures the full (unclipped) text height.
        Text(
            text = text,
            style = style,
            color = Color.Transparent,
            onTextLayout = { layout -> fullHeight = layout.size.height },
        )
        // The scrolled window MUST be constrained to the box height, otherwise
        // the column grows to the content height and the scroll has no room to
        // move (the text would just overflow the box).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(scrollState),
        ) {
            Text(text = text, style = style, color = color)
        }
    }

    LaunchedEffect(text, fullHeight) {
        scrollState.scrollTo(0)
        val maxScroll = (fullHeight - visibleHeightPx).coerceAtLeast(0)
        if (maxScroll > 0) {
            // Wait 3 s so the user reads the first lines, then scroll up slowly
            // until the last line is visible, then stop.
            delay(3000)
            val durationMs = (maxScroll / SCROLL_SPEED_PX_PER_SEC * 1000)
                .toInt()
                .coerceIn(1500, 30_000)
            while (true) {
                scrollState.animateScrollTo(maxScroll, tween(durationMillis = durationMs, easing = LinearEasing))
                delay(3000)
                scrollState.animateScrollTo(0, tween(durationMillis = durationMs, easing = LinearEasing))
                delay(3000)
            }
        }
    }
}

private const val SCROLL_SPEED_PX_PER_SEC = 30f

@Composable
private fun AndroidDescription(text: String, style: TextStyle, color: Color, maxLines: Int, modifier: Modifier) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val lineHeight = with(density) {
        when {
            style.lineHeight.isSpecified -> style.lineHeight.toDp()
            style.fontSize.isSpecified -> style.fontSize.toDp() * 1.3f
            else -> 20.dp
        }
    }
    Box(modifier.height(lineHeight * maxLines.coerceAtLeast(1)).clipToBounds()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(scroll)) {
            Text(text, style = style, color = color, modifier = Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(0)
        if (scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            delay(3000)
            val pixelsPerSecond = with(density) { 18.dp.toPx() }
            val durationMs = (scroll.maxValue / pixelsPerSecond * 1000).toInt().coerceIn(1500, 90_000)
            while (true) {
                scroll.animateScrollTo(scroll.maxValue, tween(durationMs, easing = LinearEasing))
                delay(3000)
                scroll.animateScrollTo(0, tween(durationMs, easing = LinearEasing))
                delay(3000)
            }
        }
    }
}
