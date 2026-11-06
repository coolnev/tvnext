package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.common.util.startOfDay
import com.tvnext.app.core.iptv.xmltv.EpgProgramme

/** Keep the whole EPG in one chronological list, including midnight boundaries. */
internal fun guideProgrammeTimeline(programmes: List<EpgProgramme>): List<EpgProgramme> =
    programmes.distinctBy { Triple(it.channelId, it.start, it.stop) }.sortedBy { it.start }

/** Explicit browsing wins; otherwise restore the programme at the playback clock. */
internal fun guideProgrammeIndex(
    programmes: List<EpgProgramme>, selectedStartMs: Long?, playbackTimeMs: Long,
): Int {
    if (programmes.isEmpty()) return -1
    selectedStartMs?.let { start ->
        programmes.indexOfFirst { it.start == start }.takeIf { it >= 0 }?.let { return it }
    }
    val anchor = selectedStartMs ?: playbackTimeMs
    programmes.indexOfFirst { it.start <= anchor && anchor < it.stop }
        .takeIf { it >= 0 }?.let { return it }
    // A gap in EPG must not send an archive viewer to today's live programme.
    return programmes.indexOfLast { it.start <= anchor }.takeIf { it >= 0 } ?: 0
}

/** Dates are shortcuts into the continuous list, never a filter on its rows. */
internal fun guideProgrammeOnDay(
    programmes: List<EpgProgramme>, dayStartMs: Long, playbackTimeMs: Long,
): EpgProgramme? {
    val onDay = programmes.filter { startOfDay(it.start) == dayStartMs }
    return onDay.firstOrNull { it.start <= playbackTimeMs && playbackTimeMs < it.stop }
        ?: onDay.firstOrNull()
}

/**
 * One D-pad step in a virtualized guide list. Returns null for empty lists,
 * rather than calling coerceIn(0, -1) or allowing focus to escape the guide.
 * An unselected list begins at its first/last element as appropriate.
 */
internal fun guideAdjacentIndex(size: Int, currentIndex: Int, delta: Int): Int? {
    if (size <= 0) return null
    val current = if (currentIndex in 0 until size) currentIndex else if (delta >= 0) -1 else size
    return (current + if (delta < 0) -1 else 1).coerceIn(0, size - 1)
}

/**
 * Symmetric viewport slots with the selected item at the exact middle slot.
 * Out-of-bounds slots are deliberately kept (rendered as empty placeholders)
 * so first/last items do not drift away from the screen's centre.
 */
internal fun guideCentredIndexWindow(size: Int, selectedIndex: Int, capacity: Int): IntRange {
    if (size <= 0) return 0..-1
    val centre = if (selectedIndex in 0 until size) selectedIndex else 0
    val count = capacity.coerceAtLeast(1)
    val half = (count - 1) / 2
    return (centre - half)..(centre + half)
}


/**
 * The channel list never puts fake empty slots before its first real item.
 * A selected channel is centred once there are enough preceding items; near
 * either edge the viewport is clamped instead of leaving a blank strip.
 */
internal fun guideClampedChannelWindow(size: Int, selectedIndex: Int, capacity: Int): IntRange {
    if (size <= 0) return 0..-1
    val slots = guideOddCapacity(capacity)
    val selected = selectedIndex.coerceIn(0, size - 1)
    val start = (selected - slots / 2).coerceIn(0, (size - slots).coerceAtLeast(0))
    return start..(start + slots - 1)
}

/** 0-based viewport slot occupied by the highlighted channel. */
internal fun guideChannelAnchorSlot(size: Int, selectedIndex: Int, capacity: Int): Int {
    if (size <= 0) return 0
    val selected = selectedIndex.coerceIn(0, size - 1)
    return selected - guideClampedChannelWindow(size, selected, capacity).first
}

/**
 * Programme/date columns mirror the channel's selected row Y coordinate.
 * They are allowed placeholder slots because their EPG list starts/ends on
 * different indices than the channel list. Only CHANNELS clamp to the top.
 */
internal fun guideAlignedWindow(size: Int, selectedIndex: Int, capacity: Int, anchorSlot: Int): IntRange {
    if (size <= 0) return 0..-1
    val slots = capacity.coerceAtLeast(1)
    val selected = selectedIndex.coerceIn(0, size - 1)
    val anchor = anchorSlot.coerceIn(0, slots - 1)
    val first = selected - anchor
    return first..(first + slots - 1)
}

internal fun guideOddCapacity(capacity: Int): Int {
    val positive = capacity.coerceAtLeast(1)
    return if (positive % 2 == 0) positive - 1 else positive
}

/** A standalone programme pane uses every real row, including both list edges. */
internal fun guideFilledWindow(size: Int, selectedIndex: Int, capacity: Int): IntRange {
    if (size <= 0) return 0..-1
    val count = capacity.coerceAtLeast(1).coerceAtMost(size)
    val selected = selectedIndex.coerceIn(0, size - 1)
    val first = (selected - (count - 1) / 2).coerceIn(0, size - count)
    return first until first + count
}
