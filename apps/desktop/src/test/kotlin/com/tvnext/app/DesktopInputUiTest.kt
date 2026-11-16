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
import com.tvnext.app.core.iptv.archive.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.feature.channels.ui.ChannelEpg
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
    private class Player:PlaybackController,TimeShiftController {
        override val timeShiftState=MutableStateFlow(TimeShiftState(oldestMs=System.currentTimeMillis()-86_400_000))
        val timestamps=mutableListOf<Long>();var lives=0
        override fun seekToTime(epochMs:Long){timestamps+=epochMs}
        override fun returnToLive(){lives++;state.value=state.value.copy(item=state.value.item?.copy(kind=PlaybackContentKind.LIVE,archiveEpgAnchorMs=null))}
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
        val haveEpg=MutableStateFlow(false)
        val fixtureNow=System.currentTimeMillis()
        fun programmes(id:String)=(-24..24).map {n->EpgProgramme(id,fixtureNow-60_000+n*1_800_000L,fixtureNow-60_000+(n+1)*1_800_000L,"Передача $id / $n",description="Описание передачи $n")}
        val originalEpg=koin.get<EpgRepository>()
        koin.declare<EpgRepository>(object:EpgRepository by originalEpg {
            override fun observeVersion():Flow<Long> = haveEpg.map {if(it)1L else 0L}
            override suspend fun getNowNext(channel:Channel,at:Long):NowNext {
                val list=if(haveEpg.value)programmes(channel.id) else emptyList()
                return NowNext(list.find {at in it.start until it.stop},list.firstOrNull {it.start>at})
            }
            override suspend fun getUpcomingProgrammes(channel:Channel,from:Long,limit:Int)=if(haveEpg.value)programmes(channel.id).filter {it.stop>from}.take(limit) else emptyList()
            override suspend fun getNowPlaying(channels:List<Channel>,at:Long)=channels.mapNotNull {c->getNowNext(c,at).current?.let {c.id to it}}.toMap()
            override suspend fun getProgrammesInRange(channels:List<Channel>,from:Long,to:Long)=channels.associate {it.id to if(haveEpg.value)programmes(it.id).filter {p->p.stop>from && p.start<to} else emptyList()}
            override suspend fun getAvailableProgrammeRange(channels:List<Channel>):LongRange? = if(haveEpg.value)(fixtureNow-24*1_800_000L)..(fixtureNow+24*1_800_000L) else null
        },allowOverride=true)
        val originalArchive=koin.get<ArchiveRepository>()
        koin.declare<ArchiveRepository>(object:ArchiveRepository by originalArchive {
            override suspend fun getCapability(channel:Channel)=ArchiveCapability(channel.id,ArchiveStatus.AVAILABLE,declaredDays=7)
            override suspend fun effectiveArchiveDepthMs(channel:Channel)=7*86_400_000L
            override fun buildArchiveUrl(channel:Channel,startMs:Long,endMs:Long)="https://fixture.invalid/archive/${channel.id}/$startMs"
            override suspend fun isArchiveUrlPlayable(channel:Channel,startMs:Long,endMs:Long)=true
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
            var immersive by mutableStateOf(false);var fullscreen by mutableStateOf(false);var windowRevision by mutableStateOf(0);var shellWidth by mutableStateOf(1440.dp)
            setContent {androidx.compose.foundation.layout.Box(Modifier.width(shellWidth)) {key(windowRevision) {DesktopShell(root,runtime!!,fullscreen,{fullscreen=!fullscreen;windowRevision++},immersive,{immersive=it},false,EpgState(enabled=false),null,false,
                settingsContent={c,back->DesktopSettings(c,runtime!!,back)},overlays={})}}}
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
            onNodeWithText("Архив").performClick()
            onNodeWithText("Максимальная глубина архива").assertIsDisplayed()
            onNodeWithText("Плейлисты").performClick()
            onNodeWithText("▸").assertDoesNotExist()
            onNodeWithText("Телепрограмма").performClick()
            onNodeWithText("Телепрограмма (EPG)").assertIsDisplayed()
            onNodeWithText("Данные").performClick()
            onNodeWithText("▸").assertDoesNotExist()
            onNodeWithTag("desktop-nav-more").assertDoesNotExist()
            onNodeWithTag("desktop-nav-RECORDINGS").assertIsDisplayed()
            onNodeWithTag("desktop-nav-EDITOR").assertIsDisplayed()
            capture("desktop-settings-flat.png")
            runOnIdle {shellWidth=720.dp};waitForIdle()
            onNodeWithTag("desktop-nav-more").assertIsDisplayed().performClick()
            onNode(hasText("Профили") and hasClickAction()).assertIsDisplayed().performClick()
            runOnIdle {shellWidth=1440.dp};waitForIdle()
            onNodeWithTag("desktop-nav-more").assertDoesNotExist()
            onNodeWithTag("desktop-nav-CHANNELS").performClick();waitForIdle()
            onNodeWithTag("desktop-back").performClick();assertEquals(AppTab.HOME,root.childStack.value.active.configuration)
            onNodeWithTag("desktop-nav-CHANNELS").performClick()
            runOnIdle {haveEpg.value=true}
            val current=fixtureNow-60_000
            waitUntil(timeoutMillis=10_000){onAllNodesWithTag("desktop-programmes-$current").fetchSemanticsNodes().isNotEmpty()}
            val channelBounds=onNodeWithTag("desktop-channels-qa-1").fetchSemanticsNode().boundsInRoot
            val programmeBounds=onNodeWithTag("desktop-programmes-$current").fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(channelBounds.center.y-programmeBounds.center.y)<4f,"Current programme must align with channel: $channelBounds / $programmeBounds")
            capture("desktop-channel-epg-alignment.png")
            // Browsing an old programme must not become the playback cursor when reopening the list.
            val browsed=current-1_800_000
            onNodeWithTag("desktop-programmes").performScrollToNode(hasTestTag("desktop-programmes-$browsed"))
            onNodeWithTag("desktop-programmes-$browsed").performClick()
            runOnIdle {immersive=true};waitForIdle();runOnIdle {immersive=false};waitForIdle()
            onNodeWithText("Смотреть архив").assertDoesNotExist()
            onNodeWithText("Смотреть").assertIsDisplayed()
            // Only an explicit archive action starts archive. Changing channels must tune LIVE.
            val old=current-1_800_000
            onNodeWithTag("desktop-programmes").performScrollToNode(hasTestTag("desktop-programmes-$old"))
            onNodeWithTag("desktop-programmes-$old").performClick()
            onNodeWithText("Смотреть архив").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.kind==PlaybackContentKind.ARCHIVE}
            assertEquals("qa-1",player.state.value.item?.id)
            // Tune another channel directly from archive, with no intermediate return-to-live action.
            onNodeWithTag("desktop-channels-qa-2").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.id=="qa-2" && player.state.value.item?.kind==PlaybackContentKind.LIVE}
            assertNull(player.state.value.item?.archiveEpgAnchorMs)
            waitUntil(timeoutMillis=10_000){onAllNodesWithTag("desktop-programmes-$current").fetchSemanticsNodes().isNotEmpty()}
            val nextChannel=onNodeWithTag("desktop-channels-qa-2").fetchSemanticsNode().boundsInRoot
            val nextCurrent=onNodeWithTag("desktop-programmes-$current").fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(nextChannel.center.y-nextCurrent.center.y)<4f,"Switching away from an archive cursor must realign live")
            onNodeWithTag("desktop-programmes").performScrollToNode(hasTestTag("desktop-programmes-$old"))
            onNodeWithTag("desktop-programmes-$old").performClick()
            onNodeWithText("Смотреть архив").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.kind==PlaybackContentKind.ARCHIVE && player.state.value.item?.id=="qa-2"}
            onNodeWithTag("desktop-return-live").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.kind==PlaybackContentKind.LIVE}
            onNodeWithTag("desktop-channels-qa-2").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.id=="qa-2"}
            assertEquals(PlaybackContentKind.LIVE,player.state.value.item?.kind)
            assertNull(player.state.value.item?.archiveEpgAnchorMs)
            // Simulate full video and use the left mouse edge, without opening a menu.
            runOnIdle {immersive=true}
            onNodeWithTag("desktop-shell").performMouseInput {moveTo(androidx.compose.ui.geometry.Offset(2f,height/2f))}
            waitForIdle();onNodeWithTag("desktop-channel-overlay").assertIsDisplayed()
            onNodeWithTag("desktop-hover-channels-qa-3").performClick()
            waitUntil(timeoutMillis=10_000){player.state.value.item?.id=="qa-3"}
            assertEquals(PlaybackContentKind.LIVE,player.state.value.item?.kind)
            onNodeWithTag("desktop-shell").performMouseInput {moveTo(androidx.compose.ui.geometry.Offset(width-50f,height/2f))}
            onNodeWithTag("desktop-channel-overlay").assertDoesNotExist()
            runOnIdle {immersive=false}
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
    @Test fun archiveSeekUsesBroadcastTimeAndLiveVideoClicksDoNotPause()=runDesktopComposeUiTest(width=920,height=640) {
        val player=Player();val now=System.currentTimeMillis();val start=now-1_200_000
        player.state.value=PlayerState(PlaybackState.PLAYING,PlaybackItem("qa-1","Архив","archive",PlaybackContentKind.ARCHIVE,start),60_000,1_200_000)
        val epg=ChannelEpg(current=EpgProgramme("qa-1",start,now+1_200_000,"Текущая передача"),channelId="qa-1")
        setContent {MaterialTheme {DesktopPlayerView(player,false,true,player::pause,{},{},{},{},{},{},epg,Modifier.fillMaxSize())}}
        onNodeWithTag("desktop-live-marker").assertIsDisplayed()
        onNodeWithTag("desktop-seek").performTouchInput {click(androidx.compose.ui.geometry.Offset(width*.75f,centerY))}
        assertTrue(player.timestamps.last() in now-2_000..System.currentTimeMillis(),"Future part must clamp at live, using epoch time")
        onNodeWithTag("desktop-video").performMouseInput {click()};mainClock.advanceTimeBy(500);waitForIdle();assertEquals(1,player.pauses)
        capture("desktop-archive-transport.png")
        onNodeWithTag("desktop-return-live").performClick();assertEquals(1,player.lives)
        onNodeWithTag("desktop-return-live").assertDoesNotExist()
        onNodeWithTag("desktop-video").performMouseInput {click()};mainClock.advanceTimeBy(500);waitForIdle();assertEquals(1,player.pauses,"Live click reveals controls; it must not pause")
        onNodeWithTag("desktop-pause").performClick();assertEquals(2,player.pauses,"Explicit pause button must still work on live")
    }
    @Test fun artworkHasBoundedSizeAndDescriptionStaysToItsRight()=runDesktopComposeUiTest(width=600,height=420) {
        var frame by mutableStateOf(false)
        setContent {MaterialTheme {DesktopDescription("Фильм","16.11.2026","https://fixture.invalid/poster",frame,"Подробное описание. ".repeat(100),Modifier.fillMaxSize())}}
        fun check() {
            val art=onNodeWithTag("desktop-description-art").fetchSemanticsNode().boundsInRoot
            val text=onNodeWithTag("desktop-description-text").fetchSemanticsNode().boundsInRoot
            assertTrue(art.width<=201f);assertTrue(art.height<=211f);assertTrue(text.left>art.right);assertTrue(text.right<=600)
        }
        check();runOnIdle {frame=true};check()
    }
    @Test fun differentlySizedRowsAlignAtSameViewportPosition()=runDesktopComposeUiTest(width=600,height=400) {
        setContent {MaterialTheme {Row(Modifier.fillMaxSize()) {
            DesktopCursorList((0..60).toList(),Int::toString,"25","align-channels",onActivate={},anchor=DesktopListAnchor("25",180f),modifier=Modifier.weight(1f)) {i,_->Text("Канал $i",Modifier.height(56.dp))}
            DesktopCursorList((0..120).toList(),Int::toString,"75","align-programmes",onActivate={},anchor=DesktopListAnchor("75",180f),modifier=Modifier.weight(1f)) {i,_->Text("Передача $i",Modifier.height(28.dp))}
        }}}
        val left=onNodeWithTag("align-channels-25").fetchSemanticsNode().boundsInRoot
        val right=onNodeWithTag("align-programmes-75").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(left.center.y-right.center.y)<2f,"Different row heights must not shift programme alignment")
    }
    private fun ComposeUiTest.capture(name:String) {
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root->val file=java.io.File(root,name);file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap((if(onAllNodesWithTag("desktop-shell").fetchSemanticsNodes().isNotEmpty())onNodeWithTag("desktop-shell") else onNodeWithTag("desktop-transport")).captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}}
    }
}
