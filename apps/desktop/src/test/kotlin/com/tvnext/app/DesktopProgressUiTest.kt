package com.tvnext.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import com.tvnext.app.core.player.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.feature.channels.ui.ChannelEpg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopProgressUiTest {
    @Test fun bothSliderSizesKeepThumbAndTrackOnTheSameHorizontalAxis()=runDesktopComposeUiTest(width=500,height=140) {
        var value by mutableStateOf(.5f)
        setContent {MaterialTheme {Column {
            DesktopSlider(value,{value=it},Modifier.width(380.dp).height(30.dp),tag="seek")
            DesktopSlider(value,{value=it},Modifier.width(82.dp).height(30.dp),tag="volume")
        }}}
        for(v in listOf(0f,.5f,1f)) {
            runOnIdle {value=v}
            for(tag in listOf("seek","volume")) {
                val track=onNodeWithTag("$tag-track",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                val thumb=onNodeWithTag("$tag-thumb",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                assertEquals(track.center.y,thumb.center.y,.5f,"Track/thumb at $v for $tag")
            }
        }
    }
    @Test fun liveLabelFollowsTheRedMarkerInsideTheTimeline()=runDesktopComposeUiTest(width=900,height=180) {
        val now=System.currentTimeMillis();val start=now-3_600_000
        val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("a","Канал","archive",PlaybackContentKind.ARCHIVE,start),600_000,3_600_000))
        val player=object:PlaybackController,TimeShiftController {
            override val state=state
            override val timeShiftState=MutableStateFlow(TimeShiftState(oldestMs=start-86_400_000))
            override fun play(item:PlaybackItem)=Unit
            override fun pause()=Unit
            override fun resume()=Unit
            override fun seekBy(deltaMs:Long)=Unit
            override fun seekTo(positionMs:Long)=Unit
            override fun setVolume(volume:Float)=Unit
            override fun setMuted(muted:Boolean)=Unit
            override fun stop()=Unit
            override fun release()=Unit
            override fun seekToTime(epochMs:Long)=Unit
            override fun returnToLive()=Unit
        }
        setContent {MaterialTheme {DesktopTransport(player,{},{},{},{},{},{},epg=ChannelEpg(current=EpgProgramme("a",start,now+3_600_000,"Фильм"),channelId="a"))}}
        val bar=onNodeWithTag("desktop-live-marker").fetchSemanticsNode().boundsInRoot
        val label=onNodeWithTag("desktop-live-label").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.center.x,label.center.x,2f)
        assertTrue(label.bottom<=bar.top+2f,"Label must sit directly above its marker")
        onAllNodesWithText("Эфир",substring=true).assertCountEquals(1)
    }
    @Test fun channelProgrammeHasItsOwnProgressBelowTheTitle()=runDesktopComposeUiTest(width=400,height=100) {
        setContent {MaterialTheme {Column {DesktopChannelProgramme("a",EpgProgramme("a",1_000,11_000,"Текущая передача"),6_000)}}}
        val text=onNodeWithText("Текущая передача").fetchSemanticsNode().boundsInRoot
        val bar=onNodeWithTag("desktop-channel-progress-a").fetchSemanticsNode()
        assertTrue(bar.boundsInRoot.top>=text.bottom)
        assertEquals(.5f,bar.config[SemanticsProperties.ProgressBarRangeInfo].current,.001f)
    }
}
