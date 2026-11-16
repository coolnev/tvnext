package com.tvnext.app

import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import com.tvnext.app.feature.channels.ui.ChannelEpg
import kotlin.test.*

class DesktopTimelineTest {
    private val now=1_800_000_000_000L
    private val start=now-1_800_000
    @Test fun archiveOfOngoingProgrammeKeepsFutureVisibleButCannotSeekPastLive() {
        val p=EpgProgramme("a",start,now+1_800_000,"Фильм")
        val player=PlayerState(item=PlaybackItem("a","Архив","archive",PlaybackContentKind.ARCHIVE,start),positionMs=600_000,durationMs=1_800_000)
        val timeline=assertNotNull(desktopTimeline(player,TimeShiftState(oldestMs=now-86_400_000),ChannelEpg(current=p,channelId="a"),now))
        assertEquals(p.start,timeline.start);assertEquals(p.stop,timeline.end)
        assertEquals(.5f,timeline.fraction(assertNotNull(timeline.live)))
        assertEquals(now,timeline.target(1f));assertEquals(start+600_000,timeline.position)
        assertEquals(start+900_000,timeline.target(.25f))
    }
    @Test fun staleProgrammeOfOtherChannelCannotChangeArchiveTimeline() {
        val player=PlayerState(item=PlaybackItem("b","B","archive",PlaybackContentKind.ARCHIVE,start),positionMs=60_000,durationMs=600_000)
        val wrong=ChannelEpg(current=EpgProgramme("a",start-3_600_000,now+3_600_000,"Другой фильм"),channelId="a")
        val timeline=assertNotNull(desktopTimeline(player,null,wrong,now))
        assertEquals(start,timeline.start);assertEquals(start+600_000,timeline.end);assertNull(timeline.live)
    }
    @Test fun channelLiveAndFileOffsetsStayIndependentAndProviderDepthIsRespected() {
        val live=PlayerState(item=PlaybackItem("a","A","live"),positionMs=123,durationMs=999)
        val epg=ChannelEpg(current=EpgProgramme("a",start,now+1_800_000,"Эфир"),channelId="a")
        val timeline=assertNotNull(desktopTimeline(live,TimeShiftState(oldestMs=now-600_000),epg,now))
        assertEquals(now,timeline.position);assertEquals(now-600_000,timeline.target(0f))
        val vod=live.copy(item=live.item!!.copy(kind=PlaybackContentKind.VOD),positionMs=50_000,durationMs=120_000)
        val file=assertNotNull(desktopTimeline(vod,null,epg,now));assertFalse(file.broadcast)
        assertEquals(60_000,file.target(.5f));assertNull(file.live)
    }
}
