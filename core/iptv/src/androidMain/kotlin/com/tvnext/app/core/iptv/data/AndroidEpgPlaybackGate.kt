package com.tvnext.app.core.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Android-only import policy. Playback NEVER blocks a foreground EPG import:
 * XMLTV uses a dedicated low-priority worker and cooperative pacing instead.
 * New background tasks wait until the app is foreground; existing EPG remains
 * available while a refresh is in progress.
 */
object AndroidEpgPlaybackGate {
    private val playing = MutableStateFlow(false)
    private val foreground = MutableStateFlow(false)

    val isVideoActive: Boolean get() = playing.value
    val canImport: Boolean get() = foreground.value

    fun setVideoActive(active: Boolean) {
        playing.value = active
    }

    fun setAppForeground(active: Boolean) {
        foreground.value = active
    }

    suspend fun awaitForeground() {
        // Do not gate imports on the player: live TV can play all day.
        foreground.first { it }
    }
}
