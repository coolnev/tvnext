package com.tvnext.app.core.player

/** Real Android MediaCodec video paths, NOT FFmpeg. */
internal enum class AndroidVideoDecoderPath { HARDWARE, SYSTEM_SOFTWARE }

/** AUTO is bounded: one hardware attempt then one system software attempt. */
internal fun androidVideoDecoderPlan(mode: VideoDecodeMode): List<AndroidVideoDecoderPath> = when (mode) {
    VideoDecodeMode.AUTO -> listOf(AndroidVideoDecoderPath.HARDWARE, AndroidVideoDecoderPath.SYSTEM_SOFTWARE)
    VideoDecodeMode.HARDWARE -> listOf(AndroidVideoDecoderPath.HARDWARE)
    VideoDecodeMode.SOFTWARE -> listOf(AndroidVideoDecoderPath.SYSTEM_SOFTWARE)
}

/** Only a VIDEO decoder/format failure may change the video backend. */
internal fun nextAndroidVideoDecoderPath(
    mode: VideoDecodeMode,
    current: AndroidVideoDecoderPath,
    error: PlaybackError,
): AndroidVideoDecoderPath? {
    if (error != PlaybackError.Decoder && error != PlaybackError.UnsupportedFormat) return null
    val plan = androidVideoDecoderPlan(mode)
    val index = plan.indexOf(current)
    return if (index >= 0) plan.getOrNull(index + 1) else null
}

/** A per-channel override does not change VOD settings or other channels. */
internal fun resolveAndroidVideoDecodeMode(global: VideoDecodeMode, override: String?): VideoDecodeMode =
    when (override?.uppercase()) {
        "AUTO" -> VideoDecodeMode.AUTO
        "HARDWARE" -> VideoDecodeMode.HARDWARE
        "SOFTWARE" -> VideoDecodeMode.SOFTWARE
        else -> global
    }
