package com.tvnext.app.core.ui.image

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an encoded image (PNG/JPEG/WebP) into a Compose [ImageBitmap], or null. */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
