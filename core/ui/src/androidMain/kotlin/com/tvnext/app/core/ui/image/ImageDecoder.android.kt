package com.tvnext.app.core.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Channel logos are never displayed larger than this on screen (they're small
 * row/card icons), but providers sometimes ship 500px+ source images. Decoding
 * at full resolution wastes CPU (more scanlines to decode) and memory (a
 * 500x500 ARGB_8888 bitmap is ~1 MB; with the loader's 256-entry in-memory
 * cache that is potentially hundreds of MB on a TV box that may only have
 * 1-2 GB of RAM total). [BitmapFactory.Options.inSampleSize] skips scanlines
 * during decode itself, so this is real savings, not a post-decode resize.
 */
private const val MAX_LOGO_DIMENSION_PX = 256

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val (width, height) = bounds.outWidth to bounds.outHeight
    if (width <= 0 || height <= 0) return@runCatching null
    val sampleSize = if (width <= 0 || height <= 0) {
        1
    } else {
        var sample = 1
        while (width / sample > MAX_LOGO_DIMENSION_PX || height / sample > MAX_LOGO_DIMENSION_PX) {
            sample *= 2
        }
        sample
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        // Logos are small on screen and often have transparency; RGB_565 would
        // halve memory further but drops the alpha channel needed for
        // transparent-background logos, so ARGB_8888 stays the safe default.
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()
