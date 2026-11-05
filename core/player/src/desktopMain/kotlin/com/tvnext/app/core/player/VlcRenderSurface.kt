package com.tvnext.app.core.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.nio.ByteBuffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback

/**
 * Receives decoded video frames from libVLC through a callback video surface
 * and publishes them as Compose [ImageBitmap]s.
 *
 * The pipeline replaces the old JavaFX `MediaView.snapshot()` loop:
 *
 * ```
 * libVLC decoder ──> callback video surface (RV32/[B,G,R,X]) ──> BGRA_8888 ImageBitmap ──> Compose
 * ```
 *
 * Frames arrive synchronously with the decoder (25/30/60 fps), there is no
 * fixed-rate 30 FPS snapshot timer, and the native video output stays inside
 * the existing Compose window (overlays, fullscreen and immersive UI keep
 * working unchanged).
 *
 * The pixel data is copied from the native buffer into a scratch array and
 * [Bitmap.installPixels] hands that array to the Skia bitmap **by reference**
 * (SkBitmap::installPixels does not copy). A single reused scratch array would
 * therefore mutate the previously published [ImageBitmap] while Compose may
 * still be drawing it (tearing / corrupted last frame). Three rotating buffers
 * guarantee that a published frame is only overwritten after three further
 * frames arrived (>= ~120 ms at 25 fps, >= ~50 ms at 60 fps) — a safety
 * window, not a hard guarantee: an extreme UI stall beyond it can still race
 * the decoder thread with the Compose draw. That residual race is transient
 * (the next frame restores the picture) and bounded by the 3-frame window.
 *
 * The alpha fixup (RV32 X byte → 0xFF) is fused into the row copy, so only one
 * full-frame pass is needed. Frame dimensions are bounded and the buffer size
 * is computed in `Long` to avoid integer overflow from malformed stream headers.
 */
internal class VlcRenderSurface(
    private val frameSink: (ImageBitmap) -> Unit,
) : RenderCallback {

    private val scratch = Array(BUFFER_COUNT) { ByteArray(0) }
    private var bufferIndex = 0

    override fun lock(mediaPlayer: MediaPlayer) = Unit

    override fun unlock(mediaPlayer: MediaPlayer) = Unit

    override fun display(
        mediaPlayer: MediaPlayer,
        nativeBuffers: Array<ByteBuffer>,
        bufferFormat: BufferFormat,
        width: Int,
        height: Int,
    ) {
        if (nativeBuffers.isEmpty()) return
        val buffer = nativeBuffers[0]
        val frameWidth = bufferFormat.width
        val frameHeight = bufferFormat.height
        if (frameWidth <= 0 || frameHeight <= 0) return
        if (frameWidth > MAX_FRAME_DIMENSION || frameHeight > MAX_FRAME_DIMENSION) return

        // Long arithmetic: a malformed stream header must not overflow the Int
        // buffer size and hand an undersized array to native Skia.
        val rowBytes = frameWidth.toLong() * BYTES_PER_PIXEL
        val required = rowBytes * frameHeight
        if (required <= 0L || required > Int.MAX_VALUE) return
        val rowBytesInt = rowBytes.toInt()
        val requiredInt = required.toInt()
        val stride = bufferFormat.pitches.firstOrNull()?.takeIf { it > 0 } ?: rowBytesInt

        for (i in 0 until BUFFER_COUNT) {
            if (scratch[i].size < requiredInt) {
                scratch[i] = ByteArray(requiredInt)
            }
        }
        val pixels = scratch[bufferIndex]
        bufferIndex = (bufferIndex + 1) % BUFFER_COUNT

        val savedPosition = buffer.position()
        try {
            // Copy row by row and fuse the RV32 [B,G,R,X] → BGRA_8888 [B,G,R,A]
            // fixup (force the alpha byte to 0xFF) into the same single pass.
            buffer.position(0)
            for (row in 0 until frameHeight) {
                val dst = row * rowBytesInt
                buffer.position(row * stride)
                buffer.get(pixels, dst, rowBytesInt)
                var alphaIndex = dst + 3
                val rowEnd = dst + rowBytesInt
                while (alphaIndex < rowEnd) {
                    pixels[alphaIndex] = 0xFF.toByte()
                    alphaIndex += 4
                }
            }
        } catch (e: Exception) {
            // The native buffer can be released concurrently during a stop() —
            // a lost frame is fine, we simply skip it.
            return
        } finally {
            buffer.position(savedPosition)
        }

        val bitmap = Bitmap()
        val installed = bitmap.installPixels(
            ImageInfo(frameWidth, frameHeight, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL),
            pixels,
            rowBytesInt,
        )
        if (!installed) return

        frameSink(bitmap.asComposeImageBitmap())
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4

        /** Number of rotating scratch buffers (see the class doc for the trade-off). */
        const val BUFFER_COUNT = 3

        /** Upper bound for a single frame dimension (8K was the widest target). */
        const val MAX_FRAME_DIMENSION = 8192
    }
}
