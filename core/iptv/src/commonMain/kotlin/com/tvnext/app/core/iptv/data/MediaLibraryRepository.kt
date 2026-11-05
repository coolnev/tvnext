package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.Playlist

/**
 * VOD / media-library: a provider-side M3U source (a separate playlist of type
 * VOD) with films, series episodes and other on-demand content.
 */
interface MediaLibraryRepository {

    /** All VOD sources (playlists of type VOD). */
    suspend fun getSources(): List<Playlist>

    suspend fun addSource(url: String, name: String? = null): AppResult<Playlist>
    suspend fun refreshSource(playlistId: String): AppResult<Playlist>
    suspend fun refreshAll(): AppResult<Unit>
    suspend fun removeSource(playlistId: String): AppResult<Unit>
    suspend fun renameSource(playlistId: String, name: String): AppResult<Unit>

    /** Items of one source (or every source when [playlistId] is null). */
    suspend fun getItems(playlistId: String? = null): List<MediaItem>
    suspend fun getItemsByCategory(playlistId: String, category: String): List<MediaItem>
    suspend fun getCategories(playlistId: String): List<String>
    suspend fun getItemById(id: String): MediaItem?

    /** Case-insensitive search over name/category of every source. */
    suspend fun search(query: String): List<MediaItem>

    suspend fun getFavoriteIds(): Set<String>
    suspend fun setFavorite(itemId: String, favorite: Boolean)
}
