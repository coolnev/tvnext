package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvnext.app.core.common.util.PlaybackUiRequests
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PanelAction { ASPECT, PREVIOUS, REWIND, PAUSE, FORWARD, LIVE, RECORD, CLOSE }

/** Three fixed groups; changing focus/paused state cannot wrap or resize a control. */
@Composable
internal fun AndroidFullscreenPlaybackControls(
    scale: Float,
    paused: Boolean,
    canPause: Boolean,
    canSeek: Boolean,
    isLiveChannel: Boolean,
    interactive: Boolean,
    seekStepMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onLive: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onAspect: () -> Unit = PlaybackUiRequests::chooseAspect,
    onPrevious: () -> Unit = PlaybackUiRequests::previousChannel,
    onRecord: () -> Unit = PlaybackUiRequests::toggleRecording,
) {
    val aspect by PlaybackUiRequests.aspectLabel.collectAsState()
    val requesters = remember { PanelAction.entries.associateWith { FocusRequester() } }
    var lastFocused by remember { mutableStateOf<PanelAction?>(null) }
    var initialFocusSet by remember { mutableStateOf(false) }
    val visible = buildList {
        add(PanelAction.ASPECT); add(PanelAction.PREVIOUS); add(PanelAction.REWIND)
        if (canPause) add(PanelAction.PAUSE)
        add(PanelAction.FORWARD)
        if (onLive != null) add(PanelAction.LIVE) else if (isLiveChannel) add(PanelAction.RECORD)
        add(PanelAction.CLOSE)
    }
    val active = visible.filter { canSeek || it !in listOf(PanelAction.REWIND, PanelAction.FORWARD) }
    LaunchedEffect(interactive, active) {
        if (!interactive) { initialFocusSet = false; return@LaunchedEffect }
        if (!initialFocusSet || lastFocused !in active) {
            withFrameNanos { }
            val target = if (canPause) PanelAction.PAUSE else if (canSeek) PanelAction.REWIND else PanelAction.ASPECT
            requesters.getValue(target).requestFocus()
            initialFocusSet = true
        }
    }
    val step = if (seekStepMs % 60_000L == 0L) "${seekStepMs / 60_000} мин" else "${seekStepMs / 1000} с"
    val button: @Composable (PanelAction) -> Unit = { action ->
        val label = when (action) {
            PanelAction.ASPECT -> "Формат: $aspect"
            PanelAction.PREVIOUS -> "Предыдущий канал"
            PanelAction.REWIND -> "Назад на $step"
            PanelAction.PAUSE -> if (paused) "Воспроизвести" else "Пауза"
            PanelAction.FORWARD -> "Вперёд на $step"
            PanelAction.LIVE -> "К эфиру"
            PanelAction.RECORD -> "Записать эфир"
            PanelAction.CLOSE -> "Закрыть"
        }
        val caption = when (action) {
            PanelAction.ASPECT -> aspect
            PanelAction.PREVIOUS -> "Пред. канал"
            PanelAction.LIVE -> "К эфиру"
            PanelAction.RECORD -> "Запись"
            PanelAction.CLOSE -> "Закрыть"
            else -> null
        }
        val index = active.indexOf(action)
        FullscreenPanelButton(action.name.lowercase(), label, caption, step,
            scale, paused, action, enabled = interactive && action in active,
            modifier = Modifier.focusRequester(requesters.getValue(action)).focusProperties {
                left = active.getOrNull(index - 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                right = active.getOrNull(index + 1)?.let(requesters::getValue) ?: FocusRequester.Cancel
                down = FocusRequester.Cancel
            },
            onFocused = { lastFocused = action },
            onLeft = { active.getOrNull(index - 1)?.let { requesters.getValue(it).requestFocus() } },
            onRight = { active.getOrNull(index + 1)?.let { requesters.getValue(it).requestFocus() } },
            onClick = {
                when (action) {
                    PanelAction.ASPECT -> onAspect()
                    PanelAction.PREVIOUS -> onPrevious()
                    PanelAction.REWIND -> onSeekBy(-seekStepMs)
                    PanelAction.PAUSE -> onTogglePlayback()
                    PanelAction.FORWARD -> onSeekBy(seekStepMs)
                    PanelAction.LIVE -> onLive?.invoke()
                    PanelAction.RECORD -> onRecord()
                    PanelAction.CLOSE -> onClose()
                }
            })
    }
    Row(modifier.fillMaxWidth().focusGroup().testTag("fullscreen-controls"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp * scale), verticalAlignment = Alignment.CenterVertically) {
            key(PanelAction.ASPECT) { button(PanelAction.ASPECT) }
            key(PanelAction.PREVIOUS) { button(PanelAction.PREVIOUS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp * scale), verticalAlignment = Alignment.CenterVertically) {
            key(PanelAction.REWIND) { button(PanelAction.REWIND) }
            if (canPause) key(PanelAction.PAUSE) { button(PanelAction.PAUSE) }
            key(PanelAction.FORWARD) { button(PanelAction.FORWARD) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp * scale, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (onLive != null) key(PanelAction.LIVE) { button(PanelAction.LIVE) }
            else if (isLiveChannel) key(PanelAction.RECORD) { button(PanelAction.RECORD) }
            key(PanelAction.CLOSE) { button(PanelAction.CLOSE) }
        }
    }
}

@Composable
private fun FullscreenPanelButton(
    id: String, label: String, caption: String?, step: String, scale: Float, paused: Boolean,
    action: PanelAction, enabled: Boolean, modifier: Modifier, onFocused: () -> Unit,
    onLeft: () -> Unit, onRight: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var repeating by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentClick by rememberUpdatedState(onClick)
    val seek = action == PanelAction.REWIND || action == PanelAction.FORWARD
    DisposableEffect(Unit) { onDispose { repeating?.cancel() } }
    val width = when (action) {
        PanelAction.ASPECT -> 68.dp
        PanelAction.PREVIOUS -> 100.dp
        PanelAction.REWIND, PanelAction.FORWARD, PanelAction.PAUSE -> 38.dp
        PanelAction.LIVE, PanelAction.RECORD -> 78.dp
        PanelAction.CLOSE -> 76.dp
    } * scale
    val shape = RoundedCornerShape(5.dp * scale)
    val primary = MaterialTheme.colorScheme.primary
    val tint = Color.White.copy(alpha = if (enabled) .90f else .40f)
    Box(modifier.width(width).height(30.dp * scale).testTag("fullscreen-$id")
        .semantics { contentDescription = label }
        .clip(shape)
        .background(if (focused) primary.copy(alpha = .18f) else Color.White.copy(alpha = .035f))
        .border(if (focused) 1.5.dp * scale else .5.dp * scale,
            if (focused) primary else Color.White.copy(alpha = .15f), shape)
        .onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) onFocused()
            else { held = false; repeating?.cancel() }
        }
        .onPreviewKeyEvent { event ->
            val activation = event.key in listOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter, Key.Spacebar)
            if (enabled && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                if (event.type == KeyEventType.KeyDown) {
                    held = false; repeating?.cancel()
                    if (event.key == Key.DirectionLeft) onLeft() else onRight()
                }
                true
            } else if (!activation || !enabled) false
            else when (event.type) {
                KeyEventType.KeyDown -> {
                    if (!held) {
                        held = true
                        if (seek) {
                            currentClick()
                            repeating = scope.launch { delay(420); while (held) { currentClick(); delay(180) } }
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val click = held && !seek
                    held = false; repeating?.cancel()
                    if (click) currentClick()
                    true
                }
                else -> true
            }
        }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) { currentClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (seek) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp * scale)) {
            PanelActionIcon(action, paused, tint, Modifier.size(13.dp * scale))
            Text(step, color = tint, fontSize = 6.5.sp * scale, lineHeight = 7.sp * scale, maxLines = 1)
        } else Row(Modifier.padding(horizontal = 5.dp * scale), horizontalArrangement = Arrangement.spacedBy(5.dp * scale), verticalAlignment = Alignment.CenterVertically) {
            PanelActionIcon(action, paused, if (action == PanelAction.RECORD) Color(0xFFEB7979) else tint,
                Modifier.size(if (action == PanelAction.PAUSE) 14.dp * scale else 12.dp * scale))
            caption?.let { Text(it, color = tint, fontSize = 10.5.sp * scale, lineHeight = 12.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun PanelActionIcon(action: PanelAction, paused: Boolean, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * .075f
        fun point(x: Float, y: Float) = Offset(size.width * x / 24, size.height * y / 24)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, point(x,y), point(xx,yy), stroke, StrokeCap.Round)
        fun triangle(a: Offset, b: Offset, c: Offset) { drawPath(Path().apply { moveTo(a.x,a.y);lineTo(b.x,b.y);lineTo(c.x,c.y);close() }, tint) }
        when (action) {
            PanelAction.ASPECT -> {
                drawRoundRect(tint, point(3f,5f), Size(size.width*.75f,size.height*14/24), CornerRadius(stroke), style = Stroke(stroke))
                line(7f,8f,10f,8f);line(7f,8f,7f,11f);line(17f,16f,14f,16f);line(17f,16f,17f,13f)
            }
            PanelAction.PREVIOUS -> { drawArc(tint, -145f, 285f, false, point(5f,5f), Size(size.width*15/24,size.height*15/24), style=Stroke(stroke));line(4f,5f,4f,12f);line(4f,12f,11f,12f) }
            PanelAction.REWIND, PanelAction.FORWARD -> for (n in 0..1) {
                val x = 3f + n*11
                if (action == PanelAction.FORWARD) triangle(point(x,6f),point(x+7,12f),point(x,18f))
                else triangle(point(x+7,6f),point(x,12f),point(x+7,18f))
            }
            PanelAction.PAUSE -> if (paused) triangle(point(7f,4f),point(19f,12f),point(7f,20f))
                else { drawRoundRect(tint,point(6f,5f),Size(size.width*4/24,size.height*14/24),CornerRadius(stroke));drawRoundRect(tint,point(14f,5f),Size(size.width*4/24,size.height*14/24),CornerRadius(stroke)) }
            PanelAction.LIVE -> { line(5f,12f,20f,12f);line(5f,12f,11f,6f);line(5f,12f,11f,18f) }
            PanelAction.RECORD -> drawCircle(tint, size.minDimension*.26f)
            PanelAction.CLOSE -> { line(6f,6f,18f,18f);line(6f,18f,18f,6f) }
        }
    }
}
