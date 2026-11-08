package com.tvnext.app.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.SharedVideoStage
import com.tvnext.app.feature.home.domain.FeaturedChannel
import com.tvnext.app.feature.home.domain.HomeContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HomeVideoPreviewUiTest {
    private class Player : PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("channel","КИНО ТВ","unused")))
        override fun play(item: PlaybackItem)=error("Home must keep the existing stream")
        override fun pause()=Unit
        override fun resume()=Unit
        override fun stop()=Unit
        override fun seekTo(positionMs: Long)=Unit
        override fun seekBy(deltaMs: Long)=Unit
        override fun setVolume(volume: Float)=Unit
        override fun setMuted(muted: Boolean)=Unit
        override fun release()=Unit
    }
    private class Home : HomeComponent {
        override val state=MutableStateFlow(HomeState())
        override fun dispatch(intent: HomeIntent)=Unit
    }

    @Test fun currentChannelAndMovieHaveALivePreviewNextToTheCardWithoutRestartingPlayback() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();val home=Home();val scene=VideoScene();var opened=0;var mounts=0
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {CompositionLocalProvider(LocalVideoScene provides scene){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SharedVideoStage(scene,player,true,surface={m,_ ->
                DisposableEffect(Unit){mounts++;onDispose {}}
                Box(m.background(Color(0xFF178A87)))
            })
            HomeScreen(home,playbackController=player,onOpenPlayingVideo={opened++})
        }}}}
        waitForIdle();onNodeWithTag("home-current-video").assertIsDisplayed()
        val before=scene.viewport!!.bounds
        runOnIdle {home.state.value=HomeState(content=HomeContent(continueWatching=FeaturedChannel("channel","КИНО ТВ",1,"Кино")))}
        waitForIdle();assertEquals(before,scene.viewport!!.bounds)
        onNodeWithTag("home-current-video").performClick();assertEquals(1,opened)
        runOnIdle {player.state.value=player.state.value.copy(item=PlaybackItem("movie","Текущий фильм","unused",PlaybackContentKind.VOD))}
        waitForIdle();onNodeWithTag("home-current-video").assertIsDisplayed()
        assertEquals(2,onAllNodesWithText("Текущий фильм").fetchSemanticsNodes().size)
        assertEquals(1,mounts)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"home-current-movie-preview.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
    }

    @Test fun historyCardDoesNotPretendThatVideoIsPlayingAfterStop() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();player.state.value=PlayerState();val home=Home()
        home.state.value=HomeState(content=HomeContent(continueWatching=FeaturedChannel("channel","КИНО ТВ",1,"Кино")))
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {HomeScreen(home,playbackController=player)}}
        waitForIdle();onNodeWithTag("home-current-video").assertDoesNotExist()
        onNodeWithText("КИНО ТВ").assertIsDisplayed()
    }
}
