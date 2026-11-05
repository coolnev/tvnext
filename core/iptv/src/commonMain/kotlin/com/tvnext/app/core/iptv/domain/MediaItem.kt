package com.tvnext.app.core.iptv.domain

/**
 * A single entry of a VOD / media-library M3U source (film, series episode,
 * sport event, documentary, ...). Belongs to exactly one [Playlist] of type
 * [PlaylistType.VOD].
 *
 * No universal series system is invented here: the source decides. When the
 * M3U provides season/episode attributes (or encodes them in tvg-id/name),
 * they are preserved as-is and the UI may group by them.
 */
data class MediaItem(
    val id: String,
    val playlistId: String,
    val name: String,
    val number: Int,
    val category: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val durationSeconds: Long? = null,
    val season: String? = null,
    val episode: String? = null,
    val isFavorite: Boolean = false,
)
