package com.tvnext.app.core.player

/** No Android runtime required: smoke-check the real transition policy. */
fun main() {
    check(androidVideoDecoderPlan(VideoDecodeMode.AUTO) == listOf(
        AndroidVideoDecoderPath.HARDWARE, AndroidVideoDecoderPath.SYSTEM_SOFTWARE,
    ))
    check(androidVideoDecoderPlan(VideoDecodeMode.HARDWARE) == listOf(AndroidVideoDecoderPath.HARDWARE))
    check(androidVideoDecoderPlan(VideoDecodeMode.SOFTWARE) == listOf(AndroidVideoDecoderPath.SYSTEM_SOFTWARE))
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.AUTO, AndroidVideoDecoderPath.HARDWARE,
        PlaybackError.Decoder) == AndroidVideoDecoderPath.SYSTEM_SOFTWARE)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.AUTO, AndroidVideoDecoderPath.HARDWARE,
        PlaybackError.UnsupportedFormat) == AndroidVideoDecoderPath.SYSTEM_SOFTWARE)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.AUTO, AndroidVideoDecoderPath.HARDWARE,
        PlaybackError.Network) == null)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.AUTO, AndroidVideoDecoderPath.HARDWARE,
        PlaybackError.Timeout) == null)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.AUTO, AndroidVideoDecoderPath.SYSTEM_SOFTWARE,
        PlaybackError.Decoder) == null)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.HARDWARE, AndroidVideoDecoderPath.HARDWARE,
        PlaybackError.Decoder) == null)
    check(nextAndroidVideoDecoderPath(VideoDecodeMode.SOFTWARE, AndroidVideoDecoderPath.SYSTEM_SOFTWARE,
        PlaybackError.Decoder) == null)
    check(resolveAndroidVideoDecodeMode(VideoDecodeMode.HARDWARE, "inherit") == VideoDecodeMode.HARDWARE)
    check(resolveAndroidVideoDecodeMode(VideoDecodeMode.AUTO, "SOFTWARE") == VideoDecodeMode.SOFTWARE)
    check(resolveAndroidVideoDecodeMode(VideoDecodeMode.SOFTWARE, "HARDWARE") == VideoDecodeMode.HARDWARE)
    check(resolveAndroidVideoDecodeMode(VideoDecodeMode.SOFTWARE, "bogus") == VideoDecodeMode.SOFTWARE)
    println("Android decoder policy: 14 assertions passed")
}
