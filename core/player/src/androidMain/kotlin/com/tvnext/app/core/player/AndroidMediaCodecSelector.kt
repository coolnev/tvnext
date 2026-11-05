package com.tvnext.app.core.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Force only VIDEO decoder selection. Audio is always delegated to Media3's
 * normal selector, so an audio error cannot silently change the video backend.
 * On older Android versions the hardware/software flags can be approximate.
 */
internal fun androidVideoCodecSelector(path: AndroidVideoDecoderPath): MediaCodecSelector =
    MediaCodecSelector { mimeType, secure, tunneling ->
        val candidates: List<MediaCodecInfo> =
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (!mimeType.startsWith("video/", ignoreCase = true)) candidates
        else when (path) {
            AndroidVideoDecoderPath.HARDWARE -> candidates.filter { it.hardwareAccelerated }
            AndroidVideoDecoderPath.SYSTEM_SOFTWARE -> candidates.filter { it.softwareOnly }
        }
    }
