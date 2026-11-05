package com.tvnext.app.core.player

import kotlinx.coroutines.flow.StateFlow

interface PlaybackController {
    val state: StateFlow<PlayerState>

    fun play(item: PlaybackItem)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)

    /**
     * Relative seek: jumps [deltaMs] from the ACTUAL current position (read
     * from the engine, not the cached state) — so repeated presses always
     * accumulate, e.g. «5 мин» twice = +10 мин from the current moment.
     */
    fun seekBy(deltaMs: Long)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
    fun release()
}
