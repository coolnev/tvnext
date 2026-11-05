package com.tvnext.app.core.iptv.domain

/** Type of an IPTV source: a live-TV playlist or a VOD / media-library source. */
enum class PlaylistType {
    LIVE,
    VOD,

    ;

    companion object {
        fun fromDb(raw: String?): PlaylistType =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(LIVE)

        fun toDb(type: PlaylistType): String = type.name
    }
}

data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long? = null,
    val enabled: Boolean = true,
    val type: PlaylistType = PlaylistType.LIVE,
    val isDefault: Boolean = false,
    val channelCount: Int = 0,
    /** EPG (XMLTV) URL detected in the M3U header (`url-tvg`). */
    val epgUrl: String? = null,
)
