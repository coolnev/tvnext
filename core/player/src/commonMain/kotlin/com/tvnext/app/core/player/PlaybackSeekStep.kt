package com.tvnext.app.core.player

/**
 * Reconstructed from the compiled TVNext Windows 0.5.8 player-desktop.jar.
 * JVM bytecode: PlaybackSeekStepKt.normalizePlaybackSeekStep(long).
 * Reference candidate only; Android 0.5.21 is the functional source of truth.
 */
fun normalizePlaybackSeekStep(value: Long): Long = when (value) {
    60_000L, 300_000L, 600_000L, 1_800_000L -> value
    else -> 60_000L
}
