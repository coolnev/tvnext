package com.tvnext.app.core.player

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.*
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.EOFException

/** IPTV headers may omit muxed audio. The TS PMT/PES remains authoritative. */
internal class IptvHlsExtractorFactory(
    private val delegate: HlsExtractorFactory = DefaultHlsExtractorFactory(
        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS, true),
) : HlsExtractorFactory by delegate {
    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String,List<String>>,
        sniffingExtractorInput: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        val videoOnlyHint = !format.codecs.isNullOrBlank() &&
            MimeTypes.getVideoMediaMimeType(format.codecs) != null && MimeTypes.getAudioMediaMimeType(format.codecs) == null
        val effective = if (videoOnlyHint && isTransportStream(sniffingExtractorInput))
            format.buildUpon().setCodecs(null).build() else format
        return delegate.createExtractor(uri,effective,muxedCaptionFormats,timestampAdjuster,
            responseHeaders,sniffingExtractorInput,playerId)
    }

    private fun isTransportStream(input: ExtractorInput): Boolean {
        // This is the same five-packet prefix used by Media3's TS sniff, kept
        // in its peek buffer. No segment re-download or large read-ahead.
        val prefix=ByteArray(188*5)
        try {
            input.resetPeekPosition()
            if(!input.peekFully(prefix,0,prefix.size,true))return false
            return (0 until 188).any {offset->(0 until 5).all {prefix[offset+188*it]==0x47.toByte()}}
        } catch(_:EOFException) {return false}
        finally {input.resetPeekPosition()}
    }
}
