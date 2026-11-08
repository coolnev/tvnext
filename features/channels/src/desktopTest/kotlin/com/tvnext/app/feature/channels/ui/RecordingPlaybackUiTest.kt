package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.RecordingIndicator
import com.tvnext.app.core.ui.component.RecordingPlaybackScreen
import com.tvnext.app.core.ui.component.TextOnlyNotice
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RecordingPlaybackUiTest {
    private class Player : PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,
            PlaybackItem("recording","Сохранённая запись","file:///recording.m3u8",PlaybackContentKind.VOD),120_000,900_000))
        val seeks=mutableListOf<Long>();var pauses=0;var resumed=0
        override fun play(item: PlaybackItem) {state.value=state.value.copy(item=item,state=PlaybackState.LOADING,error=null)}
        override fun pause() {pauses++;state.value=state.value.copy(state=PlaybackState.PAUSED)}
        override fun resume() {resumed++;state.value=state.value.copy(state=PlaybackState.PLAYING)}
        override fun seekBy(deltaMs: Long) {seeks+=deltaMs}
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun release() = Unit
    }

    @Test fun recordingOwnsVideoAndRemoteControlsWhileRecBadgeDoesNotTakeFocus() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();val recording=mutableStateOf(true);var closed=0
        val notice=mutableStateOf<String?>(null)
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.fillMaxSize()) {
                RecordingPlaybackScreen(player,{closed++})
                if(recording.value) RecordingIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp))
                notice.value?.let {TextOnlyNotice(it,Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp))}
            }
        }}
        waitForIdle();onNodeWithTag("recording-pause").assertIsFocused()
        onNodeWithText("Сохранённая запись").assertIsDisplayed();onNodeWithText("REC").assertIsDisplayed()
        runOnIdle {notice.value="Запись начата"};waitForIdle()
        onNodeWithText("Запись начата").assertIsDisplayed();onNodeWithTag("recording-pause").assertIsFocused()
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"recording-text-only-notice.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
        onNodeWithTag("recording-pause").performKeyInput {pressKey(Key.DirectionCenter)}
        waitForIdle();assertEquals(1,player.pauses);onNodeWithTag("recording-pause").assertIsFocused()
        onNodeWithTag("recording-pause").performKeyInput {pressKey(Key.DirectionLeft)}
        onNodeWithTag("recording-rewind").assertIsFocused()
        onNodeWithTag("recording-rewind").performKeyInput {pressKey(Key.Enter)}
        assertEquals(listOf(-60_000L),player.seeks)
        onNodeWithTag("recording-rewind").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("recording-pause").performKeyInput {pressKey(Key.Enter);pressKey(Key.DirectionRight)}
        assertEquals(1,player.resumed)
        onNodeWithTag("recording-forward").performKeyInput {pressKey(Key.Enter)}
        assertEquals(listOf(-60_000L,60_000L),player.seeks)
        onNodeWithTag("recording-forward").performKeyInput {pressKey(Key.DirectionUp)}
        waitForIdle();onNodeWithTag("recording-controls").assertDoesNotExist();onNodeWithTag("recording-video").assertIsFocused()
        onNodeWithTag("recording-video").performKeyInput {pressKey(Key.DirectionCenter)}
        waitForIdle();onNodeWithTag("recording-pause").assertIsFocused()
        runOnIdle {recording.value=false;notice.value="Запись остановлена"};waitForIdle()
        onNodeWithText("Запись остановлена").assertIsDisplayed()
        onNodeWithTag("recording-indicator").assertDoesNotExist();onNodeWithTag("recording-pause").assertIsFocused()
        onNodeWithTag("recording-pause").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("recording-forward").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("recording-close").performKeyInput {pressKey(Key.Enter)}
        assertEquals(1,closed)
    }

    @Test fun playbackFailureStaysInPlayerWithFileTitleAndRetryInsteadOfReturningHome() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();var closed=0
        player.state.value=player.state.value.copy(state=PlaybackState.ERROR,error="Ошибка чтения записи")
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {Box(Modifier.fillMaxSize()) {
            RecordingPlaybackScreen(player,{closed++})
            RecordingIndicator(Modifier.align(Alignment.TopEnd).padding(16.dp))
        }}}
        waitForIdle();onNodeWithText("Ошибка чтения записи").assertIsDisplayed();assertEquals(0,closed)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"recording-player-error-and-rec.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
        onNodeWithText("Повторить").performClick();waitForIdle()
        assertEquals(PlaybackState.LOADING,player.state.value.state);assertEquals(0,closed)
        onNodeWithTag("recording-playback").assertIsDisplayed()
    }
}
