package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.*

/** A saved recording owns a video surface and transport, independently of the Home tab. */
@Composable
fun RecordingPlaybackScreen(controller: PlaybackController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val video = remember { FocusRequester() }
    val pause = remember { FocusRequester() }
    val rewind = remember { FocusRequester() }
    val forward = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls) {
        withFrameNanos { }
        if (controls) pause.requestFocus() else video.requestFocus()
    }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("recording-playback")
        .onPreviewKeyEvent { event ->
            when {
                event.key == Key.Back -> { if (event.type == KeyEventType.KeyUp) onBack(); true }
                event.key == Key.DirectionUp && controls -> {
                    if (event.type == KeyEventType.KeyUp) controls = false
                    true
                }
                !controls && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionDown) -> {
                    if (event.type == KeyEventType.KeyUp) controls = true
                    true
                }
                !controls && event.key in listOf(Key.DirectionLeft, Key.DirectionRight) -> {
                    if (event.type == KeyEventType.KeyDown) controller.seekBy(if (event.key == Key.DirectionLeft) -60_000 else 60_000)
                    true
                }
                else -> false
            }
        }) {
        Box(Modifier.fillMaxSize().focusRequester(video).focusable().testTag("recording-video")) {
            PlayerSurface(controller, Modifier.fillMaxSize())
        }
        if (state.state in listOf(PlaybackState.LOADING, PlaybackState.BUFFERING)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (state.error != null) Text(state.error.orEmpty(), color = Color.White,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(16.dp))
        if (controls) MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = .7f))) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xF20D1216))
                .padding(16.dp).testTag("recording-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.item?.title ?: "Запись", maxLines = 1, style = MaterialTheme.typography.titleMedium, color = Color.White)
                PlayerSeekBar(state.positionMs, state.durationMs, controller::seekTo)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvSeekButton(TvSeekDirection.BACKWARD, { controller.seekBy(-60_000) }, Modifier.testTag("recording-rewind")
                        .recordingDirections(FocusRequester.Cancel, pause), focusRequester = rewind)
                    TvCardButton(onClick = {
                        if (state.state == PlaybackState.PAUSED) controller.resume() else controller.pause()
                    }, modifier = Modifier.size(52.dp).focusRequester(pause).testTag("recording-pause")
                        .recordingDirections(rewind, forward), contentPadding = PaddingValues(0.dp),
                        contentAlignment = Alignment.Center) {
                        TvPlayPauseIcon(paused = state.state == PlaybackState.PAUSED, modifier = Modifier.size(40.dp),
                            circleSize = 40.dp, backgroundAlpha = 0f)
                    }
                    TvSeekButton(TvSeekDirection.FORWARD, { controller.seekBy(60_000) }, Modifier.testTag("recording-forward")
                        .recordingDirections(pause, if (state.state == PlaybackState.ERROR) retry else close), focusRequester = forward)
                    Spacer(Modifier.weight(1f))
                    if (state.state == PlaybackState.ERROR) TvActionButton(onClick = { state.item?.let(controller::play) },
                        modifier = Modifier.focusRequester(retry).recordingDirections(forward, close)) { Text("Повторить") }
                    TvActionButton(onClick = onBack, modifier = Modifier.testTag("recording-close").focusRequester(close)
                        .recordingDirections(if (state.state == PlaybackState.ERROR) retry else forward, FocusRequester.Cancel)) { Text("К записям") }
                }
            }
        }
    }
}

private fun Modifier.recordingDirections(left: FocusRequester, right: FocusRequester) = onPreviewKeyEvent { event ->
    if (event.key !in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionDown)) false
    else {
        val target = if (event.key == Key.DirectionLeft) left else if (event.key == Key.DirectionRight) right else FocusRequester.Cancel
        if (event.type == KeyEventType.KeyDown && target != FocusRequester.Cancel) target.requestFocus()
        true
    }
}
