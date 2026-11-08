package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.SharedVideoStage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SharedVideoStageUiTest {
    private class Player : PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("live","Текущий канал","unused")))
        override fun play(item: PlaybackItem) = error("A viewport transition must not restart playback")
        override fun pause()=Unit
        override fun resume()=Unit
        override fun stop()=Unit
        override fun seekTo(positionMs: Long)=Unit
        override fun seekBy(deltaMs: Long)=Unit
        override fun setVolume(volume: Float)=Unit
        override fun setMuted(muted: Boolean)=Unit
        override fun release()=Unit
    }

    @Test fun oneSurfaceAnimatesAcrossFullscreenGuideWindowAndHomeWithoutRemounting() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player();val mode=mutableStateOf(0);var mounts=0;var disposals=0
        setContent {CompositionLocalProvider(LocalVideoScene provides scene) {Box(Modifier.fillMaxSize()) {
            SharedVideoStage(scene,player,true,surface={m,_ ->
                DisposableEffect(Unit){mounts++;onDispose {disposals++}}
                Box(m.background(Color(0xFF178A87)).testTag("native-video"))
            })
            when(mode.value) {
                0 -> PlayerSurface(player,Modifier.fillMaxSize())
                1 -> PlayerSurface(player,Modifier.align(Alignment.TopEnd).offset((-16).dp,16.dp).size(224.dp,126.dp))
                2 -> PlayerSurface(player,Modifier.align(Alignment.TopEnd).offset((-24).dp,36.dp).size(320.dp,180.dp))
                3 -> PlayerSurface(player,Modifier.offset(400.dp,80.dp).size(240.dp,135.dp))
            }
        }}}
        waitForIdle();assertEquals(960f,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width)
        mainClock.autoAdvance=false
        runOnIdle {mode.value=1};mainClock.advanceTimeBy(150);waitForIdle()
        val middle=onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.width>225 && middle.width<950,"Must interpolate, not jump: $middle")
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"video-flight-midpoint.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
        mainClock.advanceTimeBy(600);waitForIdle()
        assertEquals(224f,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width,1f)
        for((destination,width) in listOf(2 to 320f,3 to 240f,0 to 960f)) {
            runOnIdle {mode.value=destination};mainClock.advanceTimeBy(650);waitForIdle()
            assertEquals(width,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width,1f)
        }
        assertEquals(1,mounts);assertEquals(0,disposals)
        assertEquals("live",player.state.value.item?.id)
    }

    @Test fun offSwitchSnapsAndClippedPreviewKeepsFullVideoDimensions() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player();val token=Any();var actualSize=IntSize.Zero
        scene.update(VideoViewport(token,Rect(0f,0f,960f,540f),Rect(0f,0f,960f,540f),player))
        setContent {Box(Modifier.fillMaxSize()) {SharedVideoStage(scene,player,false,surface={m,_ ->
            Box(m.onGloballyPositioned {actualSize=it.size}.background(Color.Cyan))
        })}}
        waitForIdle()
        runOnIdle {scene.update(VideoViewport(token,Rect(100f,-60f,340f,75f),Rect(100f,0f,340f,75f),player))}
        waitForIdle()
        assertEquals(IntSize(240,135),actualSize)
        assertEquals(75f,onNodeWithTag("shared-video-stage").fetchSemanticsNode().boundsInRoot.height,1f)
    }

    @Test fun gapsBetweenDestinationsDoNotDisposeTheNativeVideoSurface() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player();val token=Any();var mounts=0;var disposals=0
        scene.update(VideoViewport(token,Rect(0f,0f,960f,540f),Rect(0f,0f,960f,540f),player))
        setContent {Box(Modifier.fillMaxSize()){SharedVideoStage(scene,player,true,surface={m,_ ->
            DisposableEffect(Unit){mounts++;onDispose {disposals++}}
            Box(m)
        })}}
        waitForIdle();runOnIdle {scene.remove(token)};mainClock.advanceTimeBy(200);waitForIdle()
        runOnIdle {scene.update(VideoViewport(Any(),Rect(700f,16f,924f,142f),Rect(700f,16f,924f,142f),player))}
        waitForIdle();assertEquals(1,mounts);assertEquals(0,disposals)
    }
}
