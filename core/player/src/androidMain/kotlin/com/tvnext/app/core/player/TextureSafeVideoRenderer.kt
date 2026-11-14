package com.tvnext.app.core.player

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Main-thread surface selection, read by the playback thread before codec operations. */
internal class AndroidVideoOutputPolicy(
    private val tclDevice: Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.startsWith("tcl", true) },
) {
    @Volatile var textureOutput: Boolean = false

    fun needsTextureProtection(codec: MediaCodecInfo?, texture: Boolean): Boolean {
        if (!texture || codec == null || !codec.hardwareAccelerated || codec.softwareOnly) return false
        val name = codec.name.lowercase()
        return name.startsWith("c2.mtk.") || name.startsWith("omx.mtk.") ||
            name.startsWith("omx.mediatek.") || tclDevice
    }
}

/**
 * Media3's c2.mtk flush workaround (b/362904942) only covers its VideoSink path.
 * PlayerView renders directly to SurfaceTexture. Release an affected decoder before
 * operations that would flush it, and before replacing its output surface. Neither
 * navigation nor an animation frame triggers these operations.
 */
internal class TextureSafeVideoRenderer(
    builder: Builder,
    private val outputPolicy: AndroidVideoOutputPolicy,
) : MediaCodecVideoRenderer(builder) {
    private var previousTextureOutput = false

    private fun needsProtection(): Boolean = outputPolicy.needsTextureProtection(
        codecInfo, outputPolicy.textureOutput || previousTextureOutput,
    )

    private fun releaseAffectedCodec() {
        if (needsProtection()) releaseCodec()
    }

    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId) {
        releaseAffectedCodec()
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        if (sampleStreamIsResetToKeyFrame) releaseAffectedCodec()
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun onDisabled() {
        releaseAffectedCodec()
        super.onDisabled()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) {
            releaseAffectedCodec()
            previousTextureOutput = outputPolicy.textureOutput
        }
        super.handleMessage(messageType, message)
    }

    override fun maybeDropBuffersToKeyframe(positionUs: Long, treatDroppedBuffersAsSkipped: Boolean): Boolean {
        // Keep ordinary late-frame dropping, but avoid the catch-up operation that
        // flushes and can permanently corrupt this vendor's SurfaceTexture queue.
        if (needsProtection()) return false
        return super.maybeDropBuffersToKeyframe(positionUs, treatDroppedBuffersAsSkipped)
    }
}
