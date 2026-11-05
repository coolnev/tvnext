package com.tvnext.app.core.player

import kotlinx.coroutines.flow.StateFlow

/** Optional Android capability. Positions are broadcast timestamps, not programme offsets. */
interface TimeShiftController {
    val timeShiftState: StateFlow<TimeShiftState>
    fun seekToTime(epochMs: Long)
    fun returnToLive()
}

data class TimeShiftState(
    val oldestMs: Long? = null,
    val liveMs: Long = 0,
    val positionMs: Long = 0,
    val seeking: Boolean = false,
    val message: String? = null,
)

internal fun boundedArchiveTarget(target: Long, now: Long, depth: Long?): Long =
    target.coerceIn(depth?.let { (now - it.coerceAtLeast(0)).coerceAtLeast(0) } ?: 0, now)
