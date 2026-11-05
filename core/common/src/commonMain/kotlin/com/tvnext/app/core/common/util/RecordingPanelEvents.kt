package com.tvnext.app.core.common.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Shared platform-neutral UI event: only Android listens to it. */
object RecordingPanelEvents {
    private val mutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests = mutable.asSharedFlow()
    fun open() { mutable.tryEmit(Unit) }
}
