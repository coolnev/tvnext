package com.tvnext.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ActiveRecording(val id: String, val profileId: String, val scheduled: Boolean)

/** Service-owned process state; persisted library entries are not evidence of a running capture. */
internal object RecordingRuntime {
    private val mutable = MutableStateFlow<ActiveRecording?>(null)
    val active = mutable.asStateFlow()
    fun started(recording: ActiveRecording) { mutable.value = recording }
    fun finished(id: String) { mutable.compareAndSet(mutable.value?.takeIf { it.id == id }, null) }
}
