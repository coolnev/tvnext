package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType

class IptvMediaLibraryRepository(
    private val store: MediaItemStore,
    private val playlistRepository: PlaylistRepository,
) : MediaLibraryRepository {

    override suspend fun getSources(): List<Playlist> =
        playlistRepository.getPlaylists().filter { it.enabled && it.type == PlaylistType.VOD }

    override suspend fun addSource(url: String, name: String?): AppResult<Playlist> =
        playlistRepository.addPlaylist(url = url, type = PlaylistType.VOD, name = name)

    override suspend fun refreshSource(playlistId: String): AppResult<Playlist> =
        playlistRepository.refreshPlaylist(playlistId)

    override suspend fun refreshAll(): AppResult<Unit> {
        val sources = getSources()
        if (sources.isEmpty()) return AppResult.Success(Unit)
        var firstError: AppError? = null
        for (source in sources) {
            when (val result = refreshSource(source.id)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> if (firstError == null) firstError = result.error
            }
        }
        return if (firstError == null) AppResult.Success(Unit) else AppResult.Failure(firstError)
    }

    override suspend fun removeSource(playlistId: String): AppResult<Unit> =
        playlistRepository.removePlaylist(playlistId)

    override suspend fun renameSource(playlistId: String, name: String): AppResult<Unit> =
        playlistRepository.renamePlaylist(playlistId, name)

    override suspend fun getItems(playlistId: String?): List<MediaItem> = store.getItems(playlistId)

    override suspend fun getItemsByCategory(playlistId: String, category: String): List<MediaItem> =
        store.getItemsByPlaylist(playlistId).filter { it.category == category }

    override suspend fun getCategories(playlistId: String): List<String> =
        store.getItemsByPlaylist(playlistId).map { it.category }.distinct().sorted()

    override suspend fun getItemById(id: String): MediaItem? = store.getItemById(id)

    override suspend fun search(query: String): List<MediaItem> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return store.getItems(null).filter { item ->
            item.name.contains(normalized, ignoreCase = true) ||
                item.category.contains(normalized, ignoreCase = true)
        }
    }

    override suspend fun getFavoriteIds(): Set<String> = store.getFavoriteIds()

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        store.setFavorite(itemId, favorite)
    }
}
