package com.tvnext.app.core.player

/**
 * Platform-agnostic playback failure category. Platform playback backends
 * (Media3/ExoPlayer on Android, libVLC on Desktop) map their concrete failures
 * onto this type so the common UI layer can render a stable error message
 * without knowing platform-specific error hierarchies.
 */
sealed interface PlaybackError {
    data object Network : PlaybackError
    data object UnsupportedFormat : PlaybackError
    data object Decoder : PlaybackError
    data object Timeout : PlaybackError
    data object Unknown : PlaybackError
}

fun PlaybackError.toUserMessage(): String = when (this) {
    PlaybackError.Network -> "Не удалось подключиться к потоку. Проверьте сеть."
    PlaybackError.UnsupportedFormat -> "Формат потока не поддерживается."
    PlaybackError.Decoder -> "Не удалось декодировать видеопоток."
    PlaybackError.Timeout -> "Таймаут загрузки потока. Попробуйте ещё раз."
    PlaybackError.Unknown -> "Не удалось воспроизвести канал."
}

/**
 * Maps a Media3 `PlaybackException.errorCode` (an Int) onto [PlaybackError].
 *
 * Kept in common as a PURE function (no Media3 types) so the classification is
 * unit-testable on the JVM. The error-code VALUES mirror
 * `androidx.media3.common.PlaybackException.ERROR_CODE_*` (Media3 1.x). The
 * Android backend ([PlaybackException.toPlaybackError]) delegates here; if a
 * future Media3 major release renumbers the codes, update this function AND the
 * test in the same change.
 */
fun classifyPlaybackErrorCode(errorCode: Int): PlaybackError = when (errorCode) {
    ERROR_CODE_TIMEOUT -> PlaybackError.Timeout
    ERROR_CODE_IO_UNSPECIFIED,
    ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    ERROR_CODE_IO_BAD_HTTP_STATUS,
    ERROR_CODE_IO_FILE_NOT_FOUND,
    ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    -> PlaybackError.Network
    ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> PlaybackError.UnsupportedFormat
    ERROR_CODE_DECODER_INIT_FAILED,
    ERROR_CODE_DECODER_QUERY_FAILED,
    ERROR_CODE_DECODING_FAILED,
    ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> PlaybackError.Decoder
    else -> PlaybackError.Unknown
}

/**
 * PlaybackException error-code constants (Media3 1.x) mirrored for the pure
 * classifier. Keep in sync with androidx.media3.common.PlaybackException.
 */
private const val ERROR_CODE_TIMEOUT = 1000001
private const val ERROR_CODE_IO_UNSPECIFIED = 2000000
private const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2000001
private const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2000002
private const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2000003
private const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2000004
private const val ERROR_CODE_IO_FILE_NOT_FOUND = 2000005
private const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2000007
private const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3000001
private const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3000002
private const val ERROR_CODE_DECODER_INIT_FAILED = 5000001
private const val ERROR_CODE_DECODER_QUERY_FAILED = 5000002
private const val ERROR_CODE_DECODING_FAILED = 5000003
private const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 5000004
