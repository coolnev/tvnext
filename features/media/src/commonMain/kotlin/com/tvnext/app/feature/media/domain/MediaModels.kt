package com.tvnext.app.feature.media.domain

import com.tvnext.app.core.iptv.domain.MediaItem

/**
 * A VOD item as shown in the Media Library UI: the core [MediaItem] plus the
 * saved resume position (last play position, persisted in app_meta).
 */
data class FeaturedMediaItem(
    val item: MediaItem,
    val resumePositionMs: Long = 0L,
)

/** Media Library screen content, resolved once per load from the repositories. */
data class MediaContent(
    val sources: List<com.tvnext.app.core.iptv.domain.Playlist> = emptyList(),
    /** The most recently watched VOD item (continue-watching card). */
    val continueWatching: FeaturedMediaItem? = null,
    /** Recently watched VOD items (excluding the continue-watching one). */
    val recent: List<FeaturedMediaItem> = emptyList(),
    val favorites: List<FeaturedMediaItem> = emptyList(),
    val allItems: List<MediaItem> = emptyList(),
)

val FeaturedMediaItem.name: String get() = item.name
val FeaturedMediaItem.category: String get() = item.category
val FeaturedMediaItem.logoUrl: String? get() = item.logoUrl
val FeaturedMediaItem.durationSeconds: Long? get() = item.durationSeconds
val FeaturedMediaItem.isFavorite: Boolean get() = item.isFavorite
