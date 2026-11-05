package com.tvnext.app.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-lifetime resume state. Written only by the Android controller on Main. */
object AndroidPlaybackSession {
    data class Ids(val channelId: String? = null, val vodId: String? = null)
    private val _ids = MutableStateFlow(Ids())
    val ids = _ids.asStateFlow()
    private val vodPositions = linkedMapOf<String, Long>()

    fun record(state: PlayerState) {
        val item = state.item ?: return
        if (item.kind == PlaybackContentKind.VOD) {
            if (state.state != PlaybackState.LOADING && state.positionMs >= 0) {
                vodPositions.remove(item.id)
                vodPositions[item.id] = if (state.state == PlaybackState.ENDED) 0 else state.positionMs
                while (vodPositions.size > 32) vodPositions.remove(vodPositions.keys.first())
            }
            _ids.value = _ids.value.copy(vodId = item.id)
        } else _ids.value = _ids.value.copy(channelId = item.id)
    }

    /** Prevent a previous profile's recently played item/position from leaking into the next. */
    fun clearPersonalSession() {
        _ids.value = Ids()
        vodPositions.clear()
    }

    fun vodPosition(id: String): Long? = vodPositions[id]
}
