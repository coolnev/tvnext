package com.tvnext.app.core.player

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderCounters
import java.util.concurrent.ConcurrentHashMap

/** A bounded, in-process retry profile; ordinary startup always uses Media3 defaults. */
internal class AndroidCodecCompatibility(private val sdk: Int = Build.VERSION.SDK_INT) {
    private data class Profile(val sourceId: String, val decoder: String)
    private val synchronousHevc = ConcurrentHashMap.newKeySet<Profile>()
    @Volatile private var sourceId: String? = null

    fun selectSource(id: String) { sourceId = id }

    private fun eligible(name: String?, format: Format?): Boolean = sdk >= 31 &&
        name?.startsWith("OMX.realtek.", true) == true &&
        format?.sampleMimeType == "video/hevc" && (format.width > 1920 || format.height > 1080)

    fun recordNoOutputStall(name: String?, format: Format?, counters: DecoderCounters?): Boolean {
        val id = sourceId ?: return false
        if (!eligible(name, format) || counters == null) return false
        counters.ensureUpdated()
        if (counters.queuedInputBufferCount < 32 || counters.renderedOutputBufferCount != 0 ||
            counters.skippedOutputBufferCount != 0 || counters.droppedBufferCount != 0) return false
        return synchronousHevc.add(Profile(id, name!!.lowercase()))
    }

    fun useSynchronous(name: String, format: Format): Boolean =
        eligible(name, format) && sourceId?.let { Profile(it, name.lowercase()) in synchronousHevc } == true
}
