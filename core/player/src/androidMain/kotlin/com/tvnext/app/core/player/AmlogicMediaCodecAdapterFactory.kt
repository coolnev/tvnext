package com.tvnext.app.core.player

import android.content.Context
import android.os.Build
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.tvnext.app.core.common.log.AndroidPerformanceCounters
import java.io.IOException

/** Use callback/queueing threads for legacy Amlogic video, leaving audio and other vendors alone. */
internal class AmlogicMediaCodecAdapterFactory(
    private val sdk: Int,
    private val defaults: MediaCodecAdapter.Factory,
    private val asynchronous: MediaCodecAdapter.Factory,
    private val synchronous: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {
    constructor(context: Context) : this(
        Build.VERSION.SDK_INT,
        DefaultMediaCodecAdapterFactory(context),
        DefaultMediaCodecAdapterFactory(context).forceEnableAsynchronous(),
        DefaultMediaCodecAdapterFactory(context).forceDisableAsynchronous(),
    )

    private val synchronousCodecs = mutableSetOf<String>()
    @Volatile var videoOutputDescription: String = "Media3/ExoPlayer"
        private set

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val info = configuration.codecInfo
        val video = configuration.format.sampleMimeType?.startsWith("video/", ignoreCase = true) == true
        val name = info.name.lowercase()
        val legacyAmlogic = sdk in 23..30 && video && info.hardwareAccelerated && !info.softwareOnly &&
            (name.startsWith("omx.amlogic.") || name.startsWith("c2.amlogic."))
        if (!legacyAmlogic) {
            if (video) {
                videoOutputDescription = "Media3/ExoPlayer"
                AndroidPerformanceCounters.set("video.amlogicAsyncQueue", 0)
            }
            return defaults.createAdapter(configuration)
        }
        if (name in synchronousCodecs) return createSynchronous(configuration)
        try {
            val adapter = asynchronous.createAdapter(configuration)
            videoOutputDescription = "Media3/ExoPlayer · Amlogic async"
            AndroidPerformanceCounters.set("video.amlogicAsyncQueue", 1)
            return adapter
        } catch (failure: IOException) {
            return recoverSynchronously(configuration, name, failure)
        } catch (failure: RuntimeException) {
            return recoverSynchronously(configuration, name, failure)
        }
    }

    private fun recoverSynchronously(configuration: MediaCodecAdapter.Configuration, name: String,
        failure: Exception): MediaCodecAdapter {
        // Media3's async factory releases the failed codec/threads before throwing.
        // Retry this exact hardware decoder once, rather than falling straight to CPU video.
        synchronousCodecs += name
        AndroidPerformanceCounters.increment("video.amlogicAsyncInitFallbacks")
        androidx.media3.common.util.Log.w("TVNextCodecQueue", "Amlogic async initialization failed; retrying the same codec synchronously", failure)
        try {
            return createSynchronous(configuration)
        } catch (retryFailure: Exception) {
            if (retryFailure !== failure) retryFailure.addSuppressed(failure)
            throw retryFailure
        }
    }

    private fun createSynchronous(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = synchronous.createAdapter(configuration)
        videoOutputDescription = "Media3/ExoPlayer · Amlogic sync fallback"
        AndroidPerformanceCounters.set("video.amlogicAsyncQueue", 0)
        return adapter
    }
}
