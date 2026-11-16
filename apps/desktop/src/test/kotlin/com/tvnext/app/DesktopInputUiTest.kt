package com.tvnext.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import com.tvnext.app.core.app.*
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.player.*
import com.tvnext.app.feature.settings.data.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.koin.core.context.stopKoin
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopInputUiTest {
    private class Player:PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("qa-1","Проверочный канал","https://fixture.invalid/live"),50_000,120_000))
        var pauses=0;var resumes=0;val seeks=mutableListOf<Long>()
        override fun play(item:PlaybackItem){state.value=state.value.copy(item=item,state=PlaybackState.PLAYING)}
        override fun pause(){pauses++;state.value=state.value.copy(state=PlaybackState.PAUSED)}
        override fun resume(){resumes++;state.value=state.value.copy(state=PlaybackState.PLAYING)}
        override fun seekBy(deltaMs:Long){seeks+=deltaMs}
        override fun seekTo(positionMs:Long){seeks+=positionMs}
        override fun setVolume(volume:Float){state.value=state.value.copy(volume=volume)}
        override fun setMuted(muted:Boolean){state.value=state.value.copy(isMuted=muted)}
        override fun stop(){state.value=state.value.copy(state=PlaybackState.IDLE)}
        override fun release()=Unit
    }
    @Test fun shortcutsPreserveTypingAndSuppressRepeatedPauseAndFullscreen() {
        val input=DesktopShortcuts()
        fun press(key:Key,editing:Boolean=false,focused:Boolean=true)=input.command(key,true,true,editing,focused)
        assertEquals(DesktopCommand.PAUSE,press(Key.Spacebar));assertNull(press(Key.Spacebar))
        input.command(Key.Spacebar,false,true,false,true)
        assertEquals(DesktopCommand.PAUSE,press(Key.Spacebar));input.reset()
        assertNull(press(Key.Spacebar,editing=true));assertNull(press(Key.M,editing=true))
        assertNull(press(Key.DirectionRight,focused=false))
        assertEquals(DesktopCommand.SEEK_FORWARD,press(Key.DirectionRight))
        assertEquals(DesktopCommand.VOLUME_UP,press(Key.DirectionUp))
        assertEquals(DesktopCommand.FULLSCREEN,press(Key.F11));assertNull(press(Key.F11))
        input.reset();assertFalse(input.holds(Key.F11))
        assertEquals(DesktopCommand.HOME,input.command(Key.MoveHome,true,false,false,false,alt=true))
    }
    @Test fun mouseControlsSeekVolumeAndCenteredGlyphsAtNarrowWidth()=runDesktopComposeUiTest(width=640,height=180) {
        val player=Player();player.state.value=player.state.value.copy(item=player.state.value.item!!.copy(kind=PlaybackContentKind.VOD))
        var info=0;var previous=0;var fullscreen=0
        setContent {MaterialTheme {DesktopTransport(player,{if(player.state.value.state==PlaybackState.PAUSED)player.resume() else player.pause()},
            {fullscreen++},{previous++},{},{info++},{},Modifier.padding(10.dp))}}
        onNodeWithTag("desktop-pause").performMouseInput {click()};assertEquals(1,player.pauses)
        onNodeWithTag("desktop-pause").performMouseInput {click()};assertEquals(1,player.resumes)
        onNodeWithTag("desktop-rewind").performMouseInput {click()}
        onNodeWithTag("desktop-forward").performMouseInput {click()};assertEquals(listOf(-10_000L,10_000L),player.seeks)
        onNodeWithTag("desktop-mute").performClick();assertTrue(player.state.value.isMuted)
        onNodeWithTag("desktop-volume").performTouchInput {click(center)}
        assertFalse(player.state.value.isMuted);assertTrue(player.state.value.volume in .3f.. .7f)
        onNodeWithTag("desktop-info").performClick()
        onNodeWithTag("desktop-fullscreen").performClick();assertEquals(0,previous);assertEquals(1,info);assertEquals(1,fullscreen)
        for(tag in listOf("desktop-pause","desktop-rewind","desktop-forward","desktop-previous","desktop-info","desktop-fullscreen")) {
            val button=onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val glyph=onNodeWithTag("$tag-glyph",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals(button.center.x,glyph.center.x,.6f,tag);assertEquals(button.center.y,glyph.center.y,.6f,tag)
        }
        capture("desktop-compact-controls.png")
    }
    @Test fun channelCursorMovesBeyondVisibleRowsWithArrowKeysAndEnter()=runDesktopComposeUiTest(width=400,height=260) {
        var selected by mutableStateOf("0");var activated:String?=null
        setContent {MaterialTheme {DesktopCursorList((0..99).map(Int::toString),{it},selected,"list",
            onFocus={selected=it},onActivate={activated=it},modifier=Modifier.fillMaxSize()) {item,_->Text(item,Modifier.height(42.dp))}}}
        onNodeWithTag("list-0").performClick()
        repeat(12){i->onNodeWithTag("list-$i").performKeyInput {pressKey(Key.DirectionDown)};waitForIdle()}
        onNodeWithTag("list-12").assertIsFocused()
        onNodeWithTag("list-12").performKeyInput {pressKey(Key.Enter)};assertEquals("12",activated)
    }
    @Test fun actualDesktopShellOpensHomeByMouseAndSpaceDoesNotActivateNavigationOrEatSearchText() {
        val before=System.getProperty("tvnext.data.dir");val directory=Files.createTempDirectory("tvnext-desktop-ui").toFile()
        System.setProperty("tvnext.data.dir",directory.absolutePath)
        val koin=startDesktopDependencies();val player=Player()
        koin.declare<PlaybackController>(player,allowOverride=true)
        koin.declare<ChannelsRepository>(object:ChannelsRepository {
            val channels=(1..20).map {Channel("qa-$it","Канал $it",it,"Тест","https://fixture.invalid/$it",playlistId="qa")}
            override suspend fun getChannels()=channels
            override suspend fun getChannelsByPlaylist(playlistId:String)=channels
            override suspend fun isConfigured()=true
            override suspend fun refresh():AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun getFavoriteIds()=emptySet<String>()
            override suspend fun setFavorite(channelId:String,favorite:Boolean)=Unit
        },allowOverride=true)
        val settings=koin.get<SettingsRepository>()
        runBlocking {settings.save(settings.load().copy(epgEnabled=false,kinopoiskEnabled=false,startFullscreen=false,theme=com.tvnext.app.feature.settings.domain.ThemePreference.DARK))}
        val lifecycle=LifecycleRegistry();var runtime:DesktopServices?=null
        try {runDesktopComposeUiTest(width=1440,height=900) {
            lateinit var root:DefaultAppComponent
            runOnUiThread {
                lifecycle.create();lifecycle.resume()
                root=DefaultAppComponent(DefaultComponentContext(lifecycle=lifecycle),koin.get(),settings,koin.get(),koin.get(),koin.get(),mainContext=Dispatchers.Main)
                runtime=koin.get<DesktopServices>()
            }
            var immersive by mutableStateOf(false);var fullscreen by mutableStateOf(false);var windowRevision by mutableStateOf(0)
            setContent {key(windowRevision) {DesktopShell(root,runtime!!,fullscreen,{fullscreen=!fullscreen;windowRevision++},immersive,{immersive=it},false,EpgState(enabled=false),null,false,
                settingsContent={_,_->Text("Settings fixture")},overlays={})}}
            waitUntil(timeoutMillis=10_000){onAllNodesWithTag("desktop-channel-search").fetchSemanticsNodes().isNotEmpty()}
            // Right on a channel without EPG must move to video, never crash on an unattached programme focus target.
            onNodeWithTag("desktop-channels-qa-1").performClick()
            onNodeWithTag("desktop-channels-qa-1").performKeyInput {pressKey(Key.DirectionRight);pressKey(Key.Spacebar)}
            assertEquals(1,player.pauses)
            onNodeWithTag("desktop-shell").performKeyInput {pressKey(Key.Spacebar)}
            assertEquals(1,player.resumes)
            onNodeWithTag("desktop-channel-search").performClick();onNodeWithTag("desktop-channel-search").performTextInput("Канал")
            onNodeWithTag("desktop-channel-search").performKeyInput {pressKey(Key.Spacebar)}
            assertEquals(1,player.pauses)
            // KeyInput simulates key transitions; text insertion is a separate IME event.
            onNodeWithTag("desktop-channel-search").performTextInput(" ")
            onNodeWithTag("desktop-channel-search").assertTextContains("Канал ")
            val titleLayout=mutableListOf<TextLayoutResult>()
            onNodeWithTag("desktop-playing-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(titleLayout)}
            assertTrue(titleLayout.single().layoutInput.style.color.luminance()>.5f,"Dark theme labels must remain readable")
            capture("desktop-windowed-channels.png")
            onNodeWithTag("desktop-nav-HOME").performClick();waitForIdle()
            assertEquals(AppTab.HOME,root.childStack.value.active.configuration)
            onNodeWithTag("desktop-nav-SETTINGS").performClick();waitForIdle()
            onNodeWithText("Settings fixture").assertIsDisplayed()
            onNodeWithTag("desktop-nav-CHANNELS").performClick();waitForIdle()
            onNodeWithTag("desktop-back").performClick();assertEquals(AppTab.HOME,root.childStack.value.active.configuration)
            runOnIdle {player.state.value=player.state.value.copy(item=player.state.value.item!!.copy(kind=PlaybackContentKind.VOD),state=PlaybackState.PAUSED)
                runtime!!.moviePlayerOpen.value=true;root.selectTab(AppTab.MEDIA_LIBRARY)}
            waitForIdle();onNodeWithTag("desktop-media-search").assertDoesNotExist()
            onNodeWithTag("desktop-fullscreen").assertIsDisplayed().performClick();waitForIdle()
            assertTrue(fullscreen,"Fullscreen callback must toggle the window");assertTrue(runtime!!.moviePlayerOpen.value,"Playback page must survive window recreation")
            onNodeWithTag("desktop-media-search").assertDoesNotExist();onNodeWithTag("desktop-video").assertIsDisplayed()
            onNodeWithTag("desktop-nav-MEDIA_LIBRARY").performClick();waitForIdle()
            onNodeWithTag("desktop-media-search").assertIsDisplayed()
        }} finally {
            runtime?.close();lifecycle.destroy();stopKoin()
            if(before==null)System.clearProperty("tvnext.data.dir") else System.setProperty("tvnext.data.dir",before)
        }
    }
    private fun ComposeUiTest.capture(name:String) {
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root->val file=java.io.File(root,name);file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap((if(onAllNodesWithTag("desktop-shell").fetchSemanticsNodes().isNotEmpty())onNodeWithTag("desktop-shell") else onNodeWithTag("desktop-transport")).captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}}
    }
}
