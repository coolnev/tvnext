package com.tvnext.app.feature.tvg.ui

import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlayerState

internal fun guidePlaybackTime(player: PlayerState, now: Long): Long =
    if (player.item?.kind == PlaybackContentKind.ARCHIVE)
        (player.item?.archiveEpgAnchorMs ?: now) + player.positionMs.coerceAtLeast(0)
    else now

/** Calendar boundaries, including 23/25-hour DST days; never infer days from stale grid rows. */
internal fun guideDayOffsetAt(now: Long, time: Long, minimum: Int = -365, maximum: Int = 7): Int {
    var day = 0.coerceIn(minimum, maximum)
    while (day > minimum && time < startOfLocalDayOffset(now, day)) day--
    while (day < maximum && time >= startOfLocalDayOffset(now, day + 1)) day++
    return day
}

/** Read the cursor at each key event; the last composed selectedProgramme can already be stale. */
internal fun guideProgrammeTarget(programmes: List<EpgProgramme>, cursor: Long, forward: Boolean): Long {
    val next = if (forward) programmes.firstOrNull { it.start > cursor }
    else {
        val selected = programmes.firstOrNull { it.start <= cursor && cursor < it.stop }
        programmes.lastOrNull { it.start < (selected?.start ?: cursor) }
    }
    return next?.start ?: (cursor + if (forward) 1_800_000L else -1_800_000L)
}
