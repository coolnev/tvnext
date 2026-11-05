package com.tvnext.app.core.iptv.domain

/**
 * Kind of content stored in the watch history. LIVE = a TV channel,
 * VOD = a media-library item.
 */
enum class WatchContentType {
    LIVE,
    VOD,

    ;

    companion object {
        fun fromDb(raw: String?): WatchContentType =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(LIVE)

        fun toDb(type: WatchContentType): String = type.name
    }
}
