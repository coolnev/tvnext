package com.tvnext.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ActiveRecording(
    val id: String, val profileId: String, val scheduled: Boolean,
    val streamUrl: String = "", val channelId: String = "",
)

internal enum class RecordButtonAction { START, STOP, BUSY, LIVE_REQUIRED }

internal fun recordButtonAction(active: ActiveRecording?, item: com.tvnext.app.core.player.PlaybackItem?): RecordButtonAction {
    val live = item?.kind == com.tvnext.app.core.player.PlaybackContentKind.LIVE && item.url.startsWith("http")
    if (active != null) {
        val sameChannel = item != null && (
            active.channelId.isNotBlank() && active.channelId == item.id ||
                active.streamUrl.isNotBlank() && active.streamUrl == item.url)
        return if (item?.kind != com.tvnext.app.core.player.PlaybackContentKind.VOD && sameChannel) RecordButtonAction.STOP else RecordButtonAction.BUSY
    }
    return if (live) RecordButtonAction.START else RecordButtonAction.LIVE_REQUIRED
}

/** Service-owned process state; persisted library entries are not evidence of a running capture. */
internal object RecordingRuntime {
    private val mutable = MutableStateFlow<ActiveRecording?>(null)
    val active = mutable.asStateFlow()
    fun started(recording: ActiveRecording) { mutable.value = recording }
    fun finished(id: String) { mutable.compareAndSet(mutable.value?.takeIf { it.id == id }, null) }
}
