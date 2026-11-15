package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.PlaybackStreamInfoHost
import com.tvnext.app.core.ui.i18n.UiLocale
import com.tvnext.app.core.common.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StreamInfoUiTest {
    private class Controller:PlaybackController {
        override val state=MutableStateFlow(PlayerState(state=PlaybackState.LOADING,
            item=PlaybackItem("box","Box Premier 4K","https://fixture.invalid/live/channel.m3u8"),
            diagnostics=PlayerDiagnostics("AUTO","HARDWARE",false,"c2.mtk.hevc.decoder","SurfaceView","hvc1.2.4.L153","3840 × 2160","23.976","40 Mbps","5.1 · AC3")))
        var snapshots=0;var playbackCalls=0
        override fun streamInfo():PlaybackStreamInfo {snapshots++;return basicPlaybackStreamInfo(state.value).copy(
            facts=basicPlaybackStreamInfo(state.value).facts.filterNot{it.label=="Видеокодек"}+listOf(
                StreamInfoFact("Видеокодек","video/hevc · hvc1.2.4.L153"),StreamInfoFact("Аудиокодек","audio/ac3"),
                StreamInfoFact("Устройство","TCL Google TV / API35"),StreamInfoFact("Аудиодекодер","ffmpegac3"),
                StreamInfoFact("Параметры звука","6 ch · 48000 Hz · AUTO"),StreamInfoFact("Буфер","0 ms"),
                StreamInfoFact("HTTP","503"),StreamInfoFact("Потери кадров","0"),StreamInfoFact("Восстановления","3")),
            lastRequestUrl="https://fixture.invalid/cdn/segment.ts",lastError="ERROR_CODE_IO_BAD_HTTP_STATUS\nInvalidResponseCodeException: HTTP 503")}
        override fun play(item:PlaybackItem){playbackCalls++}
        override fun pause(){playbackCalls++}
        override fun resume(){playbackCalls++}
        override fun stop(){playbackCalls++}
        override fun seekTo(positionMs:Long){playbackCalls++}
        override fun seekBy(deltaMs:Long){playbackCalls++}
        override fun setVolume(volume:Float){playbackCalls++}
        override fun setMuted(muted:Boolean){playbackCalls++}
        override fun release(){playbackCalls++}
    }
    @Test fun remoteOpensTheRightmostSmallInfoButtonAndKeepsTheSnapshotUntilRefresh()=runDesktopComposeUiTest(width=1280,height=720) {
        UiLocale.language=UiLanguage.RUSSIAN
        val controller=Controller()
        setContent {MaterialTheme {Box(Modifier.fillMaxSize().testTag("info-test-root")) {
            AndroidFullscreenPlaybackControls(1f,false,false,false,true,true,60_000,{}, {},null,{},Modifier.width(960.dp))
            PlaybackStreamInfoHost(controller)
        }}}
        waitForIdle()
        val close=onNodeWithTag("fullscreen-close").fetchSemanticsNode().boundsInRoot
        val info=onNodeWithTag("fullscreen-info").fetchSemanticsNode().boundsInRoot
        assertTrue(info.left>=close.right);assertTrue(info.width<close.width)
        onNodeWithTag("fullscreen-close").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        onNodeWithTag("fullscreen-close").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("fullscreen-info").assertIsFocused()
        onNodeWithTag("fullscreen-info").performKeyInput {pressKey(Key.DirectionCenter)}
        waitUntil(timeoutMillis=5000){onAllNodesWithTag("stream-info-dialog").fetchSemanticsNodes().isNotEmpty()}
        onNodeWithTag("stream-info-title").assertTextEquals("Box Premier 4K")
        onNodeWithText("video/hevc",substring=true).assertExists()
        onNodeWithTag("stream-info-source").assertTextEquals(controller.state.value.item!!.url)
        onNodeWithTag("stream-info-error").assertTextContains("HTTP 503",substring=true)
        assertEquals(1,controller.snapshots);assertEquals(0,controller.playbackCalls)
        runOnIdle {controller.state.value=controller.state.value.copy(item=controller.state.value.item!!.copy(title="Box Ultra Stars 4K"))}
        onNodeWithTag("stream-info-title").assertTextEquals("Box Premier 4K")
        onNodeWithTag("stream-info-refresh").performClick()
        onNodeWithTag("stream-info-title").assertTextEquals("Box Ultra Stars 4K")
        assertEquals(2,controller.snapshots);assertEquals(0,controller.playbackCalls)
        for((language,label) in listOf(UiLanguage.ENGLISH to "Stream information",UiLanguage.CHINESE to "流信息")) {
            runOnIdle {UiLocale.language=language}
            onNodeWithText(label).assertIsDisplayed()
        }
        runOnIdle {UiLocale.language=UiLanguage.RUSSIAN}
        val root=System.getenv("TVNEXT_VALIDATION_DIR")
        if(root!=null) {
            val folder=java.io.File(root).apply{mkdirs()}
            onNodeWithTag("stream-info-dialog").captureToImage().asSkiaBitmap().let {bitmap->
                org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData()?.use {java.io.File(folder,"stream-info-dialog.png").writeBytes(it.bytes)}
            }
        }
        repeat(30) {onNodeWithTag("stream-info-body").performKeyInput {pressKey(Key.DirectionDown)};waitForIdle()}
        waitForIdle()
        onNodeWithTag("stream-info-refresh").assertIsFocused()
        onNodeWithTag("stream-info-refresh").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("stream-info-close").assertIsFocused()
        onNodeWithTag("stream-info-close").performKeyInput {pressKey(Key.Back)}
        onNodeWithTag("stream-info-dialog").assertDoesNotExist()
        onNodeWithTag("fullscreen-info").assertIsFocused()
        onNodeWithTag("fullscreen-info").performKeyInput {pressKey(Key.Enter)}
        waitUntil(timeoutMillis=5000){onAllNodesWithTag("stream-info-dialog").fetchSemanticsNodes().isNotEmpty()}
        onNodeWithTag("stream-info-body").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("stream-info-refresh").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("stream-info-close").performKeyInput {pressKey(Key.Enter)}
        onNodeWithTag("stream-info-dialog").assertDoesNotExist()
        assertEquals(0,controller.playbackCalls)
    }
}
