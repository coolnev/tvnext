package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure classification of Media3 `PlaybackException.errorCode` onto
 * [PlaybackError]. The constants mirror androidx.media3.common.PlaybackException
 * (Media3 1.x) — keep in sync with the classifier in `PlaybackError.kt`.
 */
class PlaybackErrorClassifierTest {

    // androidx.media3.common.PlaybackException.ERROR_CODE_*
    private val TIMEOUT = 1000001
    private val IO_NETWORK_CONNECTION_FAILED = 2000001
    private val IO_NETWORK_CONNECTION_TIMEOUT = 2000002
    private val IO_INVALID_HTTP_CONTENT_TYPE = 2000003
    private val IO_BAD_HTTP_STATUS = 2000004
    private val IO_FILE_NOT_FOUND = 2000005
    private val IO_CLEARTEXT_NOT_PERMITTED = 2000007
    private val PARSING_CONTAINER_UNSUPPORTED = 3000001
    private val PARSING_MANIFEST_UNSUPPORTED = 3000002
    private val DECODER_INIT_FAILED = 5000001
    private val DECODER_QUERY_FAILED = 5000002
    private val DECODING_FAILED = 5000003
    private val DECODING_FORMAT_UNSUPPORTED = 5000004

    @Test
    fun timeoutMapsToTimeout() {
        assertEquals(PlaybackError.Timeout, classifyPlaybackErrorCode(TIMEOUT))
    }

    @Test
    fun networkErrorsMapToNetwork() {
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_INVALID_HTTP_CONTENT_TYPE))
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_BAD_HTTP_STATUS))
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_FILE_NOT_FOUND))
        assertEquals(PlaybackError.Network, classifyPlaybackErrorCode(IO_CLEARTEXT_NOT_PERMITTED))
    }

    @Test
    fun parsingErrorsMapToUnsupportedFormat() {
        assertEquals(PlaybackError.UnsupportedFormat, classifyPlaybackErrorCode(PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackError.UnsupportedFormat, classifyPlaybackErrorCode(PARSING_MANIFEST_UNSUPPORTED))
    }

    @Test
    fun decoderErrorsMapToDecoder() {
        assertEquals(PlaybackError.Decoder, classifyPlaybackErrorCode(DECODER_INIT_FAILED))
        assertEquals(PlaybackError.Decoder, classifyPlaybackErrorCode(DECODER_QUERY_FAILED))
        assertEquals(PlaybackError.Decoder, classifyPlaybackErrorCode(DECODING_FAILED))
        assertEquals(PlaybackError.Decoder, classifyPlaybackErrorCode(DECODING_FORMAT_UNSUPPORTED))
    }

    @Test
    fun unknownCodesMapToUnknown() {
        assertEquals(PlaybackError.Unknown, classifyPlaybackErrorCode(-1))
        assertEquals(PlaybackError.Unknown, classifyPlaybackErrorCode(0))
        assertEquals(PlaybackError.Unknown, classifyPlaybackErrorCode(4000001)) // unsupported DRM
        assertEquals(PlaybackError.Unknown, classifyPlaybackErrorCode(6000001)) // audio track init
    }
}
