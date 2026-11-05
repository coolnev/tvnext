package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidPlaybackSessionTest {
    private fun movie(id: String, position: Long, state: PlaybackState = PlaybackState.PLAYING) =
        PlayerState(state = state, item = PlaybackItem(id, id, "test://movie", PlaybackContentKind.VOD), positionMs = position)

    @Test fun latestChannelAndMovieRemainIndependent() {
        AndroidPlaybackSession.record(PlayerState(item = PlaybackItem("A", "A", "test://a")))
        AndroidPlaybackSession.record(PlayerState(item = PlaybackItem("B", "B", "test://b")))
        AndroidPlaybackSession.record(movie("new-movie", 123_000))
        AndroidPlaybackSession.record(movie("new-movie", 123_000, PlaybackState.PAUSED))
        assertEquals("B", AndroidPlaybackSession.ids.value.channelId)
        assertEquals("new-movie", AndroidPlaybackSession.ids.value.vodId)
        assertEquals(123_000L, AndroidPlaybackSession.vodPosition("new-movie"))
        AndroidPlaybackSession.record(PlayerState())
        assertEquals(123_000L, AndroidPlaybackSession.vodPosition("new-movie"))
    }

    @Test fun loadingDoesNotOverwriteResumePosition() {
        AndroidPlaybackSession.record(movie("resume-film", 75_000))
        AndroidPlaybackSession.record(movie("resume-film", 0, PlaybackState.LOADING))
        assertEquals(75_000L, AndroidPlaybackSession.vodPosition("resume-film"))
    }

    @Test fun resumeCacheIsBounded() {
        repeat(40) { AndroidPlaybackSession.record(movie("bounded-$it", 1000)) }
        assertNull(AndroidPlaybackSession.vodPosition("bounded-0"))
        assertEquals(1000L, AndroidPlaybackSession.vodPosition("bounded-39"))
    }
}
