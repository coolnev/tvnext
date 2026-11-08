package com.tvnext.app.core.common.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Future EPG recording request; ephemeral event, no persistent URL or credentials. */
data class RecordingScheduleRequest(
    val channelName: String,
    val programmeTitle: String,
    val streamUrl: String,
    val startAt: Long,
    val stopAt: Long,
    val channelId: String = "",
)

object RecordingScheduleRequests {
    private val events = MutableSharedFlow<RecordingScheduleRequest>(extraBufferCapacity = 4)
    val requests = events.asSharedFlow()
    fun request(value: RecordingScheduleRequest): Boolean = events.tryEmit(value)
}
