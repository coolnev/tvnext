package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.common.util.startOfDay
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuideProgrammeTimelineTest {
    private val hour = 3_600_000L
    private val today = startOfDay(1_799_971_200_000L)
    private val programmes = (-48..47).map { offset ->
        EpgProgramme("channel", today + offset * hour, today + (offset + 1) * hour, "Передача $offset")
    }

    @Test fun dpadCanWalkFromLiveThroughEveryAvailableDayWithoutChoosingDate() {
        val timeline = guideProgrammeTimeline(programmes.reversed() + programmes.take(3))
        assertEquals(programmes, timeline)
        var index = guideProgrammeIndex(timeline, null, today + 12 * hour)
        val liveIndex = index
        repeat(liveIndex) { index = guideAdjacentIndex(timeline.size, index, -1)!! }
        assertEquals(programmes.first(), timeline[index])
        repeat(timeline.lastIndex) { index = guideAdjacentIndex(timeline.size, index, 1)!! }
        assertEquals(programmes.last(), timeline[index])
    }

    @Test fun midnightChangesTheDateWithoutResettingProgrammeSelection() {
        val midnight = guideProgrammeIndex(programmes, null, today)
        val previous = guideAdjacentIndex(programmes.size, midnight, -1)!!
        assertEquals(today - hour, programmes[previous].start)
        assertEquals(today - 24 * hour, startOfDay(programmes[previous].start))
        assertEquals(midnight, guideAdjacentIndex(programmes.size, previous, 1))
    }

    @Test fun openingArchiveRestoresTheProgrammeAtPlaybackPosition() {
        val archiveStart = today - 30 * hour
        // The position moved beyond the initially opened archive item.
        val index = guideProgrammeIndex(programmes, null, archiveStart + 2 * hour + hour / 2)
        assertEquals(today - 28 * hour, programmes[index].start)
    }

    @Test fun liveOnlyPlaceholderDoesNotOverrideArchiveWhenHistoryArrives() {
        val archiveTime = today - 25 * hour + hour / 2
        val live = programmes.first { it.start == today + 12 * hour }
        assertEquals(0, guideProgrammeIndex(listOf(live), null, archiveTime))
        val restored = guideProgrammeIndex(programmes, null, archiveTime)
        assertEquals(today - 25 * hour, programmes[restored].start)
    }

    @Test fun explicitBrowsingSurvivesPlaybackTicksAndMetadataRefresh() {
        val selection = today - 27 * hour
        val refreshed = programmes.map { it.copy(description = "Обновлённое описание") }
        val index = guideProgrammeIndex(refreshed, selection, today + 13 * hour)
        assertEquals(selection, refreshed[index].start)
    }

    @Test fun dateShortcutSelectsAProgrammeWithoutRemovingOtherDays() {
        val yesterday = today - 24 * hour
        val chosen = guideProgrammeOnDay(programmes, yesterday, today + 12 * hour)!!
        assertEquals(yesterday, chosen.start)
        val index = guideProgrammeIndex(programmes, chosen.start, today)
        assertEquals(yesterday - hour, programmes[guideAdjacentIndex(programmes.size, index, -1)!!].start)
        assertEquals(today + 12 * hour, guideProgrammeOnDay(programmes, today, today + 12 * hour)?.start)
    }

    @Test fun emptyEpgAndArchiveGapsHaveSafeTargets() {
        assertEquals(-1, guideProgrammeIndex(emptyList(), null, today))
        assertNull(guideProgrammeOnDay(emptyList(), today, today))
        val gap = programmes.filterNot { it.start == today - 25 * hour }
        val index = guideProgrammeIndex(gap, null, today - 25 * hour + hour / 2)
        assertEquals(today - 26 * hour, gap[index].start)
    }
}
