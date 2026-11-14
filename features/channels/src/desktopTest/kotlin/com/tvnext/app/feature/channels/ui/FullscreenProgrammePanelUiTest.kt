package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.tvnext.app.core.ui.component.VideoPlaybackStatus
import com.tvnext.app.core.ui.component.PlaybackOverlayVisibility
import com.tvnext.app.core.ui.component.LocalPlaybackOverlayVisibility
import com.tvnext.app.core.ui.component.EpgLoadingIndicator
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FullscreenProgrammePanelUiTest {
    private lateinit var ui: ComposeUiTest
    // Use a real 1080p test viewport, rather than a 1920dp Box clamped to 1024x768.
    private fun at1080p(test: () -> Unit) = runDesktopComposeUiTest(width=1920,height=1080) {
        ui=this
        test()
    }
    private val now = System.currentTimeMillis()
    private val channel = Channel("channel", "КИНО ТВ", 50, "Кино", "https://unused.invalid/live", catchup = "default")
    private val programme = EpgProgramme(channel.id, now-3_600_000, now+3_600_000, "Холоп 2", "Сюжет, который не должен появляться в обычной панели.")
    private val next = EpgProgramme(channel.id, programme.stop, programme.stop+5_400_000, "СуперБобровы", "Описание следующего фильма.")
    private val epg = ChannelEpg(current = programme, next = next, channelId = channel.id)
    private val buttonTags = listOf("aspect","previous","rewind","pause","forward","live","record","close")
    @Before fun start() { startKoin {} }
    @After fun stop() { stopKoin() }
    private class Player : PlaybackController {
        override val state = MutableStateFlow(PlayerState())
        val seeks = mutableListOf<Long>()
        override fun play(item: PlaybackItem) { state.value = state.value.copy(item=item) }
        override fun pause() { state.value=state.value.copy(state=PlaybackState.PAUSED) }
        override fun resume() { state.value=state.value.copy(state=PlaybackState.PLAYING) }
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) { seeks += deltaMs }
        override fun setVolume(volume: Float) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun release() = Unit
    }
    private fun player(archive: Boolean) = Player().also { p ->
        p.state.value = PlayerState(state = PlaybackState.PLAYING, item = PlaybackItem(channel.id,channel.name,channel.streamUrl,
            kind=if(archive)PlaybackContentKind.ARCHIVE else PlaybackContentKind.LIVE, archiveEpgAnchorMs=if(archive)programme.start else null),positionMs=35*60_000)
    }
    private fun focus(tag: String) { ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };ui.waitForIdle() }
    private fun key(tag: String, key: Key) { ui.onNodeWithTag(tag).performKeyInput { pressKey(key) };ui.waitForIdle() }
    private fun screenshot(name: String) {
        val root=System.getenv("TVNEXT_VALIDATION_DIR") ?: return
        val out=java.io.File(root).apply { mkdirs() }
        ui.onRoot().captureToImage().asSkiaBitmap().let { bmp -> org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData()?.use { data -> java.io.File(out,name).writeBytes(data.bytes) } }
    }
    @Test fun archiveUsesTwoEqualCardsAndOneTimelineWithoutSynopsisOrDescriptionButton() = at1080p {
        val controller = player(true)
        val panelHeight = mutableIntStateOf(0)
        val panelOpen = mutableStateOf(true)
        val overlays = PlaybackOverlayVisibility()
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF3DDC97))) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPlaybackOverlayVisibility provides overlays) {
            Box(Modifier.size(1920.dp,1080.dp).testTag("test-video-layout").background(Color(0xFF173036)),contentAlignment=Alignment.BottomCenter) {
                VideoPlaybackStatus(PlaybackState.PLAYING, "КИНО ТВ • Архив • 14.11 20:00",
                    bottomInset = if(panelOpen.value) with(density) { panelHeight.intValue.toDp() } else 0.dp)
                if(panelOpen.value) AndroidProgrammeOverlay(channel,epg,controller,true,{}, {panelOpen.value=false},modifier=Modifier.fillMaxWidth()
                    .onSizeChanged { panelHeight.intValue = it.height })
                EpgLoadingIndicator("Обработка EPG: 1800 каналов",Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
            }
        } }
        ui.waitForIdle()
        val current=ui.onNodeWithTag("overlay-current-card").fetchSemanticsNode().boundsInRoot
        val following=ui.onNodeWithTag("overlay-next-card").fetchSemanticsNode().boundsInRoot
        assertEquals(current.top,following.top,.1f)
        assertEquals(current.width,following.width,1.1f)
        assertEquals(current.height,following.height,.1f)
        ui.onAllNodesWithTag("overlay-programme-progress").assertCountEquals(1)
        ui.onNodeWithText(programme.title).assertIsDisplayed();ui.onNodeWithText(next.title).assertIsDisplayed()
        ui.onNodeWithText(programme.description!!).assertDoesNotExist();ui.onNodeWithText("Описание").assertDoesNotExist()
        ui.onNodeWithText("Архив").assertIsDisplayed()
        ui.onNodeWithTag("fullscreen-pause").assertIsFocused()
        val buttons=buttonTags.flatMap { ui.onAllNodesWithTag("fullscreen-$it").fetchSemanticsNodes() }.map { it.boundsInRoot }
        assertEquals(7,buttons.size)
        assertTrue(buttons.all { kotlin.math.abs(it.top-buttons.first().top)<1.1f && kotlin.math.abs(it.height-buttons.first().height)<1.1f })
        val panel=ui.onNodeWithTag("fullscreen-programme-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(1920f,ui.onNodeWithTag("test-video-layout").fetchSemanticsNode().boundsInRoot.width,1.1f)
        assertTrue(panel.height<360f,"Panel must retain the approved compact proportions: $panel")
        val archiveLabel=ui.onNodeWithTag("archive-video-label").fetchSemanticsNode().boundsInRoot
        assertTrue(archiveLabel.bottom < panel.top, "Persistent archive label must stay above the measured controls")
        assertTrue(archiveLabel.height < 50f, "Archive label remains compact at 1080p")
        ui.onNodeWithTag("epg-loading-status").assertDoesNotExist()
        screenshot("fullscreen-two-cards-archive-1920.png")
        ui.onNodeWithTag("fullscreen-close").performClick()
        ui.onNodeWithText("Обработка EPG: 1800 каналов").assertIsDisplayed()
        val status=ui.onNodeWithTag("epg-loading-status").fetchSemanticsNode().boundsInRoot
        assertEquals(960f,status.center.x,1f)
        assertTrue(status.bottom>1040f)
    }
    @Test fun remoteActionsFireOnceAndPausedOrLiveModeChangesKeepUsableFocus() = at1080p {
        val paused=mutableStateOf(false);val live=mutableStateOf(false)
        var pauses=0;var records=0;var previous=0;var aspects=0;var closes=0
        val seeks=mutableListOf<Long>()
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.width(960.dp)) {
            AndroidFullscreenPlaybackControls(1f,paused.value,!live.value,true,live.value,true,60_000,
                { pauses++;paused.value=!paused.value }, { seeks+=it }, if(!live.value)({live.value=true}) else null,
                {closes++},onAspect={aspects++},onPrevious={previous++},onRecord={records++})
        } } }
        ui.waitForIdle();ui.onNodeWithTag("fullscreen-pause").assertIsFocused()
        val before=ui.onNodeWithTag("fullscreen-pause").fetchSemanticsNode().boundsInRoot
        key("fullscreen-pause",Key.DirectionCenter)
        assertEquals(1,pauses);ui.onNodeWithTag("fullscreen-pause").assertIsFocused()
        assertEquals(before,ui.onNodeWithTag("fullscreen-pause").fetchSemanticsNode().boundsInRoot)
        key("fullscreen-pause",Key.DirectionLeft);ui.onNodeWithTag("fullscreen-rewind").assertIsFocused()
        key("fullscreen-rewind",Key.Enter);assertEquals(listOf(-60_000L),seeks)
        key("fullscreen-rewind",Key.DirectionRight);key("fullscreen-pause",Key.DirectionRight)
        key("fullscreen-forward",Key.Enter);assertEquals(listOf(-60_000L,60_000L),seeks)
        key("fullscreen-forward",Key.DirectionRight);key("fullscreen-live",Key.Enter)
        ui.onNodeWithTag("fullscreen-live").assertDoesNotExist();ui.onNodeWithTag("fullscreen-record").assertIsDisplayed()
        ui.onNodeWithTag("fullscreen-rewind").assertIsFocused()
        focus("fullscreen-record");key("fullscreen-record",Key.Enter);assertEquals(1,records)
        focus("fullscreen-previous");key("fullscreen-previous",Key.Enter);assertEquals(1,previous)
        focus("fullscreen-aspect");key("fullscreen-aspect",Key.Enter);assertEquals(1,aspects)
        focus("fullscreen-close");key("fullscreen-close",Key.NumPadEnter);assertEquals(1,closes)
    }
    @Test fun longTitlesAndThreeDifferentWidthsDoNotWrapTheControls() = at1080p {
        val width=mutableStateOf(720);val controller=player(false)
        val longEpg=epg.copy(current=programme.copy(title="Очень длинное название текущей передачи ".repeat(5)),next=next.copy(title="Очень длинное название следующей передачи ".repeat(5)))
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(width.value.dp,(width.value*9/16).dp).testTag("test-video-layout"),contentAlignment=Alignment.BottomCenter) {
                AndroidProgrammeOverlay(channel,longEpg,controller,true,{}, {},modifier=Modifier.fillMaxWidth())
            }
        } }
        for(w in listOf(720,1280,1920)) {
            ui.runOnIdle {width.value=w};ui.waitForIdle()
            assertEquals(w.toFloat(),ui.onNodeWithTag("test-video-layout").fetchSemanticsNode().boundsInRoot.width,1.1f)
            val bs=buttonTags.flatMap {ui.onAllNodesWithTag("fullscreen-$it").fetchSemanticsNodes()}.map {it.boundsInRoot}
            assertEquals(6,bs.size)
            assertTrue(bs.all {kotlin.math.abs(it.top-bs.first().top)<1.1f && it.left>=0 && it.right<=w+1})
            ui.onNodeWithText("Описание").assertDoesNotExist()
        }
        screenshot("fullscreen-two-cards-live-long-titles.png")
    }
    @Test fun liveWithoutEpgSkipsDisabledSeekButtonsAndOffersRecording() = at1080p {
        val controller=player(false)
        ui.setContent {MaterialTheme { Box(Modifier.width(960.dp)) {
            AndroidProgrammeOverlay(channel,ChannelEpg(channelId=channel.id),controller,true,{}, {},modifier=Modifier.fillMaxWidth())
        } } }
        ui.waitForIdle();ui.onNodeWithTag("fullscreen-aspect").assertIsFocused()
        ui.onNodeWithTag("fullscreen-rewind").assertIsNotEnabled();ui.onNodeWithTag("fullscreen-forward").assertIsNotEnabled()
        ui.onNodeWithTag("fullscreen-pause").assertDoesNotExist();ui.onNodeWithTag("fullscreen-live").assertDoesNotExist()
        ui.onNodeWithTag("fullscreen-record").assertIsDisplayed()
        key("fullscreen-aspect",Key.DirectionRight);ui.onNodeWithTag("fullscreen-previous").assertIsFocused()
        key("fullscreen-previous",Key.DirectionRight);ui.onNodeWithTag("fullscreen-record").assertIsFocused()
    }
    @Test fun doubleRightPassesThroughPassivePanelAndOpensASeparateDescriptionView() = at1080p {
        val controller=player(false);val details=mutableStateOf(false);var right=0;val video=FocusRequester()
        ui.setContent {MaterialTheme {
            Box(Modifier.size(960.dp,540.dp).focusRequester(video).onPreviewKeyEvent { e ->
                if(e.key==Key.DirectionRight){if(e.type==KeyEventType.KeyUp){right++;if(right==2)details.value=true};true}else false
            }.focusable().testTag("test-video"),contentAlignment=Alignment.BottomCenter) {
                AndroidProgrammeOverlay(channel,epg,controller,false,{}, {details.value=false},detailsVisible=details.value,modifier=Modifier.fillMaxWidth())
            }
            LaunchedEffect(Unit){video.requestFocus()}
        } }
        ui.waitForIdle();ui.onNodeWithTag("test-video").assertIsFocused()
        key("test-video",Key.DirectionRight);ui.onNodeWithTag("test-video").assertIsFocused()
        key("test-video",Key.DirectionRight)
        ui.onNodeWithTag("fullscreen-description-view").assertIsDisplayed()
        ui.onNodeWithTag("fullscreen-programme-panel").assertDoesNotExist();ui.onNodeWithTag("fullscreen-controls").assertDoesNotExist()
        ui.onNodeWithText(programme.description!!).assertIsDisplayed()
        screenshot("fullscreen-description-separate-view.png")
        key("fullscreen-description-close",Key.Back)
        ui.onNodeWithTag("fullscreen-description-view").assertDoesNotExist()
    }
}
