package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that libVLC native log messages (which are the only detail available
 * from vlcj's detail-free `error` event) are classified onto the
 * platform-agnostic [PlaybackError] so the common UI can render stable messages.
 */
class VlcErrorClassifierTest {

    @Test
    fun connectionRefusedIsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(listOf("live555 error: Connection refused"), hadVideoTrack = false),
        )
    }

    @Test
    fun http404IsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(listOf("access error: HTTP 404 Not Found"), hadVideoTrack = false),
        )
    }

    @Test
    fun dnsFailureIsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(listOf("core error: Failed to resolve host name"), hadVideoTrack = false),
        )
    }

    @Test
    fun socketTimeoutIsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(listOf("core error: connection timed out"), hadVideoTrack = false),
        )
    }

    @Test
    fun tlsHandshakeFailureIsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(listOf("tls error: TLS handshake failed"), hadVideoTrack = false),
        )
    }

    @Test
    fun noSuitableDemuxIsUnsupportedFormat() {
        assertEquals(
            PlaybackError.UnsupportedFormat,
            classifyVlcError(
                listOf("main demux error: no suitable demux module for \"data\""),
                hadVideoTrack = false,
            ),
        )
    }

    @Test
    fun cannotGuessFormatIsUnsupportedFormat() {
        assertEquals(
            PlaybackError.UnsupportedFormat,
            classifyVlcError(listOf("access error: cannot guess the format"), hadVideoTrack = false),
        )
    }

    @Test
    fun unsupportedDataIsUnsupportedFormat() {
        assertEquals(
            PlaybackError.UnsupportedFormat,
            classifyVlcError(listOf("stream error: unsupported format"), hadVideoTrack = false),
        )
    }

    @Test
    fun invalidDataIsUnsupportedFormat() {
        assertEquals(
            PlaybackError.UnsupportedFormat,
            classifyVlcError(listOf("demux error: invalid data in stream"), hadVideoTrack = false),
        )
    }

    @Test
    fun streamWithVideoTrackButDecoderFailureIsDecoder() {
        // We opened the stream and found a video track, but decoding failed —
        // that is a decoder problem, not a network/format discovery problem.
        assertEquals(
            PlaybackError.Decoder,
            classifyVlcError(listOf("avcodec error: failed to decode frame"), hadVideoTrack = true),
        )
    }

    @Test
    fun streamWithVideoTrackAndNetworkMessageIsStillDecoder() {
        // Once a video track exists, late failures (frame decode, stream cut
        // during playback) are decoder-side; keep it distinguishable from
        // "could not connect at all".
        assertEquals(
            PlaybackError.Decoder,
            classifyVlcError(listOf("connection lost mid-stream"), hadVideoTrack = true),
        )
    }

    @Test
    fun unrelatedMessagesAreUnknown() {
        assertEquals(
            PlaybackError.Unknown,
            classifyVlcError(listOf("main warning: useless message"), hadVideoTrack = false),
        )
    }

    @Test
    fun emptyMessagesAreUnknown() {
        assertEquals(
            PlaybackError.Unknown,
            classifyVlcError(listOf(), hadVideoTrack = false),
        )
    }

    @Test
    fun mixedNoiseAndNetworkIsNetwork() {
        assertEquals(
            PlaybackError.Network,
            classifyVlcError(
                listOf("core debug: using HTTP", "access error: server closed connection"),
                hadVideoTrack = false,
            ),
        )
    }
}
