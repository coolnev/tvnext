package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
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

    @Test fun animationMovesInLayerWithoutPerFrameSurfaceRemeasure() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player();var destination by mutableStateOf(0)
        var compositions=0;var measures=0
        val sizes=mutableListOf<IntSize>()
        setContent { CompositionLocalProvider(LocalVideoScene provides scene) { Box(Modifier.fillMaxSize()) {
            SharedVideoStage(scene,player,true,transitionKey=destination,surface={m,_ ->
                SideEffect{compositions++}
                Box(m.layout{measurable,constraints ->
                    measures++
                    val placeable=measurable.measure(constraints)
                    layout(placeable.width,placeable.height){placeable.place(0,0)}
                }.onSizeChanged{sizes+=it}.background(Color.Cyan).testTag("native-video"))
            })
            if(destination==0)PlayerSurface(player,Modifier.fillMaxSize())
            else PlayerSurface(player,Modifier.offset(700.dp,16.dp).size(224.dp,126.dp))
        } } }
        waitForIdle();mainClock.autoAdvance=false
        runOnIdle{destination=1};mainClock.advanceTimeBy(64);waitForIdle()
        val initialCompositions=compositions;val initialMeasures=measures;val initialSizes=sizes.size
        val widths=mutableListOf<Float>()
        repeat(8){
            mainClock.advanceTimeByFrame();waitForIdle()
            widths+=onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width
        }
        val extraCompositions=compositions-initialCompositions
        val extraMeasures=measures-initialMeasures
        val extraSizes=sizes.size-initialSizes
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"video-animation-work.json");file.parentFile.mkdirs()
            file.writeText("""{"sampledFrames":8,"nativeCompositions":$extraCompositions,"nativeMeasures":$extraMeasures,"nativeSizeChanges":$extraSizes}""")
        }
        assertTrue(extraCompositions<=1 && extraMeasures<=1 && extraSizes==0,
            "Eight animation frames must not rebuild/resize the surface: compositions=$extraCompositions measures=$extraMeasures sizes=$extraSizes")
        assertTrue(widths.zipWithNext().all{(a,b)->b<a},"Video must keep moving smoothly while its native layout stays fixed: $widths")
        mainClock.advanceTimeBy(320);waitForIdle()
        assertEquals(224f,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width,1f)
        assertEquals(IntSize(224,126),sizes.last())
        // A second Back may arrive before expansion has finished. Reverse the
        // active flight without snapping/rebuilding its native surface.
        runOnIdle{destination=0};mainClock.advanceTimeBy(96);waitForIdle()
        assertTrue(onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width>224f)
        assertEquals(IntSize(224,126),sizes.last())
        runOnIdle{destination=1};mainClock.advanceTimeBy(520);waitForIdle()
        assertEquals(224f,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width,1f)
        assertEquals(IntSize(224,126),sizes.last())
    }

    @Test fun navigationRaisesVideoBeforeTheSamePlaceholderReceivesItsNewBounds() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player();val token=Any()
        var destination by mutableStateOf(0)
        val videoColor=Color(0xFF178A87)
        scene.update(VideoViewport(token,Rect(0f,0f,960f,540f),Rect(0f,0f,960f,540f),player))
        setContent { Box(Modifier.fillMaxSize()) {
            SharedVideoStage(scene,player,true,transitionKey=destination,surface={m,_ -> Box(m.background(videoColor))})
            if(destination!=0) Box(Modifier.width(400.dp).fillMaxHeight().background(Color.Magenta))
        } }
        waitForIdle();mainClock.autoAdvance=false
        // A navigation composition precedes the destination's layout callback.
        // Keep the old viewport registered while the new chrome has appeared.
        runOnIdle{destination=1}
        mainClock.advanceTimeByFrame();waitForIdle()
        assertEquals(videoColor,onRoot().captureToImage().toPixelMap()[100,100])
        runOnIdle{scene.update(VideoViewport(token,Rect(400f,32f,720f,212f),Rect(400f,32f,720f,212f),player))}
        mainClock.advanceTimeBy(650);waitForIdle()
        assertEquals(Color.Magenta,onRoot().captureToImage().toPixelMap()[100,100])
        assertEquals(320f,onNodeWithTag("shared-video-stage").fetchSemanticsNode().boundsInRoot.width,1f)
    }

    @Test fun destinationChromeCannotFlashBeforeVideoShrinksIntoWindowOrHome() = runDesktopComposeUiTest(width=960,height=540) {
        val scene=VideoScene();val player=Player()
        var destination by mutableStateOf(0)
        var attached by mutableStateOf(true)
        var mounts=0
        val videoColor=Color(0xFF178A87)
        setContent { CompositionLocalProvider(LocalVideoScene provides scene) { Box(Modifier.fillMaxSize()) {
            SharedVideoStage(scene,player,true,transitionKey=destination,surface={m,_ ->
                DisposableEffect(Unit){mounts++;onDispose{}}
                Box(m.background(videoColor).testTag("native-video"))
            })
            if(destination!=0) Box(Modifier.width((if(destination==1)400 else 650).dp).fillMaxHeight()
                .background(Color.Magenta).testTag("destination-chrome"))
            if(attached) when(destination) {
                0 -> PlayerSurface(player,Modifier.fillMaxSize())
                1 -> PlayerSurface(player,Modifier.offset(400.dp,32.dp).size(320.dp,180.dp))
                else -> PlayerSurface(player,Modifier.offset(650.dp,80.dp).size(240.dp,135.dp))
            }
        } } }
        waitForIdle()
        mainClock.autoAdvance=false
        for((next,width,point) in listOf(Triple(1,320f,100 to 100),Triple(2,240f,450 to 60))) {
            runOnIdle{destination=next;attached=false}
            mainClock.advanceTimeBy(160);waitForIdle()
            val frame=onRoot().captureToImage()
            assertEquals(videoColor,frame.toPixelMap()[point.first,point.second],
                "Destination $next must stay behind the old video before its preview is measured")
            System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
                val file=java.io.File(root,"video-handoff-before-shrink-$next.png");file.parentFile.mkdirs()
                org.jetbrains.skia.Image.makeFromBitmap(frame.asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
            }
            runOnIdle{attached=true}
            mainClock.advanceTimeBy(650);waitForIdle()
            assertEquals(width,onNodeWithTag("native-video").fetchSemanticsNode().boundsInRoot.width,1f)
            assertEquals(Color.Magenta,onRoot().captureToImage().toPixelMap()[point.first,point.second])
        }
        assertEquals(1,mounts)
        assertEquals("live",player.state.value.item?.id)
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
