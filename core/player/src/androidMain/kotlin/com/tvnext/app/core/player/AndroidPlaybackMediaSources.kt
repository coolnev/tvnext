package com.tvnext.app.core.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

internal object AndroidPlaybackMediaSources {
    fun create(data:DataSource.Factory,item:MediaItem):MediaSource {
        val config=checkNotNull(item.localConfiguration)
        if(Util.inferContentTypeForUriAndMimeType(config.uri,config.mimeType)==C.CONTENT_TYPE_HLS)
            return HlsMediaSource.Factory(data).setExtractorFactory(IptvHlsExtractorFactory())
                .setAllowChunklessPreparation(false).createMediaSource(item)
        return DefaultMediaSourceFactory(data,DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS))
            .createMediaSource(item)
    }
}
