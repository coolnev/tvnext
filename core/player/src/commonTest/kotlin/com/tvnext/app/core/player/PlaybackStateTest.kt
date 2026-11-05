package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStateTest {

    @Test
    fun idleBackendMapsToIdle() {
        assertEquals(PlaybackState.IDLE, PlayerBackendState.IDLE.toPlaybackState(isPlaying = true))
    }

    @Test
    fun bufferingBackendMapsToBuffering() {
        assertEquals(
            PlaybackState.BUFFERING,
            PlayerBackendState.BUFFERING.toPlaybackState(isPlaying = true),
        )
    }

    @Test
    fun endedBackendMapsToEnded() {
        assertEquals(PlaybackState.ENDED, PlayerBackendState.ENDED.toPlaybackState(isPlaying = false))
    }

    @Test
    fun readyBackendWhilePlayingMapsToPlaying() {
        assertEquals(PlaybackState.PLAYING, PlayerBackendState.READY.toPlaybackState(isPlaying = true))
    }

    @Test
    fun readyBackendWhilePausedMapsToPaused() {
        assertEquals(PlaybackState.PAUSED, PlayerBackendState.READY.toPlaybackState(isPlaying = false))
    }

    @Test
    fun pauseThenResumeCyclesPlayingAndPaused() {
        val ready = PlayerBackendState.READY
        assertEquals(PlaybackState.PLAYING, ready.toPlaybackState(isPlaying = true))
        assertEquals(PlaybackState.PAUSED, ready.toPlaybackState(isPlaying = false))
        assertEquals(PlaybackState.PLAYING, ready.toPlaybackState(isPlaying = true))
    }
}
