package com.tvnext.app.core.common.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** UI commands exposed by the Android player; no second settings implementation. */
object PlaybackUiRequests {
    private val aspectClicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val recordClicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val previousClicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val aspectRequests = aspectClicks.asSharedFlow()
    val recordRequests = recordClicks.asSharedFlow()
    val previousRequests = previousClicks.asSharedFlow()
    val aspectLabel = MutableStateFlow("Автоматически")
    fun chooseAspect() { aspectClicks.tryEmit(Unit) }
    fun toggleRecording() { recordClicks.tryEmit(Unit) }
    fun previousChannel() { previousClicks.tryEmit(Unit) }
}

/** One step back; another click returns to the channel we just left. */
object PreviousChannelHistory {
    private var currentId: String? = null
    private var previousId: String? = null
    fun entered(id: String) {
        if (id.isBlank() || id == currentId) return
        previousId = currentId
        currentId = id
    }
    fun target(): String? = previousId?.takeIf { it != currentId }
    fun clear() { currentId = null; previousId = null }
}
