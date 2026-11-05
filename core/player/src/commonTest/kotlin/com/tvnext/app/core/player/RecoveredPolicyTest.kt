package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reference tests; run on an adapted project, not validated in this container. */
class RecoveredPolicyTest {
    @Test fun seekStep() {
        assertEquals(60_000L, normalizePlaybackSeekStep(0L))
        assertEquals(300_000L, normalizePlaybackSeekStep(300_000L))
        assertEquals(600_000L, normalizePlaybackSeekStep(600_000L))
        assertEquals(1_800_000L, normalizePlaybackSeekStep(1_800_000L))
        assertEquals(60_000L, normalizePlaybackSeekStep(120_000L))
    }

    @Test fun transientFailure() {
        assertFalse(isTransientStreamFailure(PlaybackError.Network, 401))
        assertFalse(isTransientStreamFailure(PlaybackError.Network, 403))
        assertFalse(isTransientStreamFailure(PlaybackError.Network, media3Code = 2007))
        assertFalse(isTransientStreamFailure(PlaybackError.UnsupportedFormat))
        assertFalse(isTransientStreamFailure(PlaybackError.Decoder))
        assertTrue(isTransientStreamFailure(PlaybackError.Network, 503))
    }

    @Test fun naturalEnd() {
        val archive = PlaybackItem("id", "title", "https://example.invalid/asset", PlaybackContentKind.ARCHIVE)
        val live = archive.copy(kind = PlaybackContentKind.LIVE)
        assertTrue(isNaturalStreamEnd(archive, 8_001L, 10_000L, false))
        assertFalse(isNaturalStreamEnd(archive, 7_999L, 10_000L, false))
        assertFalse(isNaturalStreamEnd(archive, 10_000L, 10_000L, true))
        assertFalse(isNaturalStreamEnd(live, 10_000L, 10_000L, false))
    }
}
