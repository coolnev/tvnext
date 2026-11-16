package com.tvnext.app

import com.tvnext.app.feature.channels.ui.ChannelEpg
import com.tvnext.app.core.player.*

/** Broadcast time is kept separate from file offsets, including a currently airing programme. */
internal data class DesktopTimeline(val start:Long,val end:Long,val position:Long,val seekEnd:Long,val live:Long?,val broadcast:Boolean) {
    val span get()=(end-start).coerceAtLeast(1)
    fun fraction(at:Long)=((at-start).toDouble()/span).toFloat().coerceIn(0f,1f)
    fun target(fraction:Float)=(start+(span*fraction.coerceIn(0f,1f)).toLong()).coerceIn(start,seekEnd)
}
internal fun desktopTimeline(player:PlayerState,shift:TimeShiftState?,epg:ChannelEpg,now:Long):DesktopTimeline? {
    val item=player.item ?: return null
    if(item.kind==PlaybackContentKind.VOD)return if(player.durationMs>0)
        DesktopTimeline(0,player.durationMs,player.positionMs.coerceIn(0,player.durationMs),player.durationMs,null,false) else null
    val at=if(item.kind==PlaybackContentKind.ARCHIVE)(item.archiveEpgAnchorMs ?: return null)+player.positionMs else now
    val programme=epg.current?.takeIf {epg.channelId==item.id && at>=it.start && at<it.stop}
    val start=programme?.start ?: item.archiveEpgAnchorMs ?: shift?.oldestMs?.coerceAtLeast(now-7_200_000) ?: return null
    val end=programme?.stop ?: if(item.kind==PlaybackContentKind.ARCHIVE && player.durationMs>0)start+player.durationMs else now
    if(end<=start)return null
    val minimum=maxOf(start,shift?.oldestMs ?: start)
    val maximum=minOf(end,now)
    if(maximum<minimum)return null
    return DesktopTimeline(minimum,end,at.coerceIn(minimum,maximum),maximum,now.takeIf {it in minimum..end},true)
}
