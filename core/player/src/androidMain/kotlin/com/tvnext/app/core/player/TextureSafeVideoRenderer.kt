package com.tvnext.app.core.player

import android.os.Build
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.exoplayer.DecoderReuseEvaluation
import android.media.MediaCodec
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Main-thread surface selection, read by the playback thread before codec operations. */
internal class AndroidVideoOutputPolicy @JvmOverloads constructor(
    private val tclDevice: Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.startsWith("tcl", true) },
    private val animationEnabled: () -> Boolean = { false },
) {
    @Volatile var textureOutput: Boolean = false
    @Volatile var coordinateOutput: Boolean = false
    @Volatile var onCadenceChanged: ((Format, Double) -> Unit)? = null

    fun expectsTexture(format: Format): Boolean = useTextureForVideoTransition(animationEnabled(), true,
        format.width, format.height, requiresNativeVideoOutput(format))

    fun needsTextureProtection(codec: MediaCodecInfo?, texture: Boolean): Boolean {
        if (!texture || codec == null || !codec.hardwareAccelerated || codec.softwareOnly) return false
        val name = codec.name.lowercase()
        return name.startsWith("c2.mtk.") || name.startsWith("omx.mtk.") ||
            name.startsWith("omx.mediatek.") || name.startsWith("omx.realtek.") || tclDevice
    }
}

internal fun requiresNativeVideoOutput(format: Format): Boolean = format.drmInitData != null ||
    format.cryptoType != C.CRYPTO_TYPE_NONE || format.sampleMimeType == "video/dolby-vision" ||
    format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 || format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG

/** Metadata-only HLS id/bitrate changes do not change the encoded timeline. */
internal fun sameVideoTiming(a: Format?, b: Format): Boolean = a != null &&
    a.sampleMimeType == b.sampleMimeType && a.width == b.width && a.height == b.height &&
    a.frameRate == b.frameRate && a.initializationData.size == b.initializationData.size &&
    a.initializationData.indices.all { a.initializationData[it].contentEquals(b.initializationData[it]) }

/** Runs solely on the playback thread, independently of decode speed and the output Surface. */
internal class VideoSourceCadence {
    private val estimator=VideoCadenceEstimator()
    private var format: Format?=null
    private var listener: ((Format, Double) -> Unit)?=null

    fun start(listener: ((Format, Double) -> Unit)?) {
        this.listener=listener;format=null;estimator.reset()
    }
    fun reset() { estimator.reset() }
    fun formatChanged(next: Format) {
        if(!sameVideoTiming(format,next)) estimator.reset()
        format=next
    }
    fun frame(ptsUs: Long) {
        val current=format ?: return
        if(listener==null) return
        estimator.frame(ptsUs)?.let { listener?.invoke(current,it) }
    }
}

/**
 * Protect direct SurfaceTexture output from vendor flush/setOutputSurface bugs.
 * Seeks, disable and keyframe catch-up release instead of flushing. Format/DRM
 * changes drain and replace the codec through Media3's normal reuse decision.
 */
internal class TextureSafeVideoRenderer(
    builder: Builder,
    private val outputPolicy: AndroidVideoOutputPolicy,
) : MediaCodecVideoRenderer(builder) {
    private var previousTextureOutput = false
    private var attachedSurface: Surface? = null
    private var pendingCodecBuffers = 0
    private var expectedTexture: Boolean? = null
    private val sourceCadence = VideoSourceCadence()

    override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId) {
        super.onStreamChanged(formats,startPositionUs,offsetUs,mediaPeriodId)
        sourceCadence.start(outputPolicy.onCadenceChanged)
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val evaluation=super.onInputFormatChanged(formatHolder)
        formatHolder.format?.let(sourceCadence::formatChanged)
        return evaluation
    }

    private fun waitingForOutput(): Boolean = expectedTexture?.let {
        attachedSurface == null || previousTextureOutput != it
    } == true

    override fun maybeInitializeProcessingPipeline(format: Format): Boolean {
        val initialized = super.maybeInitializeProcessingPipeline(format)
        expectedTexture = if (outputPolicy.coordinateOutput && format.width > 0 && format.height > 0)
            outputPolicy.expectsTexture(format) else null
        // The format event can reach Compose while codec creation waits for its
        // actual output. No keyframe has entered a decoder on the old surface.
        return initialized && !waitingForOutput()
    }

    override fun isReady(): Boolean = !waitingForOutput() && super.isReady()

    override fun skipSource(positionUs: Long): Int = if (waitingForOutput()) 0 else super.skipSource(positionUs)

    private fun needsProtection(): Boolean = outputPolicy.needsTextureProtection(
        codecInfo, outputPolicy.textureOutput || previousTextureOutput,
    )

    private fun releaseAffectedCodec() {
        if (needsProtection()) releaseCodec()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        sourceCadence.reset()
        if (sampleStreamIsResetToKeyFrame) releaseAffectedCodec()
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun onDisabled() {
        releaseAffectedCodec()
        super.onDisabled()
        expectedTexture = null
        sourceCadence.start(null)
    }

    override fun canReuseCodec(info: MediaCodecInfo, oldFormat: Format, newFormat: Format,
        isAdaptiveFormatChange: Boolean): DecoderReuseEvaluation {
        val evaluation = super.canReuseCodec(info, oldFormat, newFormat, isAdaptiveFormatChange)
        val unchangedCodec = evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION
        val protectedContent = oldFormat.drmInitData != null || newFormat.drmInitData != null ||
            oldFormat.cryptoType != C.CRYPTO_TYPE_NONE || newFormat.cryptoType != C.CRYPTO_TYPE_NONE
        if (evaluation.result != DecoderReuseEvaluation.REUSE_RESULT_NO && (!unchangedCodec || protectedContent) &&
            outputPolicy.needsTextureProtection(info, outputPolicy.textureOutput || previousTextureOutput)) {
            // Real codec/DRM changes still avoid the vendor flush. A track id or
            // bitrate header update must not drain and restart an unchanged codec.
            return DecoderReuseEvaluation(info.name, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND)
        }
        return evaluation
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) {
            super.handleMessage(messageType, message)
            return
        }
        val nextSurface = message as? Surface
        val changed = attachedSurface !== nextSurface
        // Keep duplicate notifications cheap; protect only an actual handoff.
        if (changed) releaseAffectedCodec()
        super.handleMessage(messageType, message)
        if (changed) {
            attachedSurface = nextSurface
            previousTextureOutput = outputPolicy.textureOutput
        }
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        if(!buffer.isEndOfStream && buffer.timeUs!=C.TIME_UNSET) sourceCadence.frame(buffer.timeUs)
        if (!configuration.tunneling &&
            (Build.VERSION.SDK_INT < 34 || getCodecBufferFlags(buffer) and MediaCodec.BUFFER_FLAG_DECODE_ONLY == 0)) {
            pendingCodecBuffers++
        }
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        if (!configuration.tunneling) pendingCodecBuffers = (pendingCodecBuffers - 1).coerceAtLeast(0)
    }

    override fun resetCodecStateForFlush() {
        super.resetCodecStateForFlush()
        pendingCodecBuffers = 0
    }

    override fun maybeDropBuffersToKeyframe(positionUs: Long, treatDroppedBuffersAsSkipped: Boolean): Boolean {
        if (!needsProtection()) return super.maybeDropBuffersToKeyframe(positionUs, treatDroppedBuffersAsSkipped)
        val skipped = skipSource(positionUs)
        if (skipped == 0) return false
        // Catch up when a keyframe is available, replacing the unsafe vendor flush
        // with a codec release. Disabling catch-up leaves video behind the audio.
        if (treatDroppedBuffersAsSkipped) {
            decoderCounters.skippedInputBufferCount += skipped
            decoderCounters.skippedOutputBufferCount += pendingCodecBuffers
        } else {
            decoderCounters.droppedToKeyframeCount++
            updateDroppedBufferCounters(skipped, pendingCodecBuffers)
        }
        releaseCodec()
        maybeInitCodecOrBypass()
        return true
    }
}
