package com.tvnext.app.core.player

/**
 * Behavior reconstructed from Windows TVNext 0.5.8 JVM bytecode.
 * Function names and argument names were confirmed against Kotlin Metadata.
 * Reference candidate only; verify against Android TVNext 0.5.21 before integration.
 */
fun isTransientStreamFailure(
    error: PlaybackError,
    httpStatus: Int? = null,
    media3Code: Int? = null,
): Boolean = httpStatus != 401 && httpStatus != 403 &&
    media3Code != 2007 &&
    error != PlaybackError.UnsupportedFormat &&
    error != PlaybackError.Decoder

fun isNaturalStreamEnd(
    item: PlaybackItem,
    positionMs: Long,
    durationMs: Long,
    failed: Boolean,
): Boolean = !failed &&
    item.kind != PlaybackContentKind.LIVE &&
    durationMs > 0L &&
    positionMs >= durationMs - 2_000L
