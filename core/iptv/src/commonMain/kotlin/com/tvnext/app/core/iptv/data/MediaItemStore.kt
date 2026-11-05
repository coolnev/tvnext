package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.tvnext.app.core.database.Media_item as DbMediaItem
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.MediaItem
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface MediaItemStore {
    /**
     * Replaces all items of [playlistId]. Favorites are preserved by stable
     * item id: either read from the database before the wipe, or supplied via
     * [preservedFavorites].
     */
    suspend fun replacePlaylistItems(
        playlistId: String,
        items: List<MediaItem>,
        preservedFavorites: Set<String> = emptySet(),
    )

    suspend fun getItems(playlistId: String? = null): List<MediaItem>
    suspend fun getItemsByPlaylist(playlistId: String): List<MediaItem>
    suspend fun getItemById(id: String): MediaItem?
    suspend fun deleteByPlaylist(playlistId: String)
    suspend fun deleteAll()
    suspend fun setFavorite(itemId: String, favorite: Boolean)
    suspend fun getFavoriteIds(): Set<String>
}

class SqlDelightMediaItemStore(
    private val database: TvNextDatabase,
) : MediaItemStore {

    private val queries = database.mediaItemQueries

    override suspend fun replacePlaylistItems(
        playlistId: String,
        items: List<MediaItem>,
        preservedFavorites: Set<String>,
    ) = withContext(Dispatchers.Default) {
        database.transaction {
            val previousFavorites = preservedFavorites +
                queries.selectMediaItemsByPlaylist(playlistId).executeAsList()
                    .filter { it.is_favorite == TRUE }
                    .map { it.id }
            queries.deleteMediaItemsByPlaylist(playlistId)
            items.forEach { item ->
                queries.insertMediaItem(
                    id = item.id,
                    playlist_id = playlistId,
                    number = item.number.toLong(),
                    name = item.name,
                    stream_url = item.streamUrl,
                    logo_url = item.logoUrl,
                    category = item.category,
                    tvg_id = item.tvgId,
                    tvg_name = item.tvgName,
                    duration_seconds = item.durationSeconds,
                    season = item.season,
                    episode = item.episode,
                    is_favorite = if (previousFavorites.contains(item.id)) TRUE else FALSE,
                )
            }
        }
    }

    override suspend fun getItems(playlistId: String?): List<MediaItem> = withContext(Dispatchers.Default) {
        if (playlistId == null) {
            queries.selectAllMediaItems().executeAsList().map(::toDomain)
        } else {
            queries.selectMediaItemsByPlaylist(playlistId).executeAsList().map(::toDomain)
        }
    }

    override suspend fun getItemsByPlaylist(playlistId: String): List<MediaItem> =
        withContext(Dispatchers.Default) {
            queries.selectMediaItemsByPlaylist(playlistId).executeAsList().map(::toDomain)
        }

    override suspend fun getItemById(id: String): MediaItem? = withContext(Dispatchers.Default) {
        queries.selectMediaItemById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun deleteByPlaylist(playlistId: String) = withContext(Dispatchers.Default) {
        queries.deleteMediaItemsByPlaylist(playlistId)
        Unit
    }

    override suspend fun deleteAll() = withContext(Dispatchers.Default) {
        queries.deleteAllMediaItems()
        Unit
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) = withContext(Dispatchers.Default) {
        queries.setMediaFavorite(is_favorite = favorite.toDb(), id = itemId)
        Unit
    }

    override suspend fun getFavoriteIds(): Set<String> = withContext(Dispatchers.Default) {
        queries.selectMediaFavoriteIds().executeAsList().toSet()
    }

    private fun toDomain(row: DbMediaItem): MediaItem = MediaItem(
        id = row.id,
        playlistId = row.playlist_id,
        name = row.name,
        number = row.number.toInt(),
        category = row.category,
        streamUrl = row.stream_url,
        logoUrl = row.logo_url,
        tvgId = row.tvg_id,
        tvgName = row.tvg_name,
        durationSeconds = row.duration_seconds,
        season = row.season,
        episode = row.episode,
        isFavorite = row.is_favorite == TRUE,
    )

    private companion object {
        const val TRUE = 1L
        const val FALSE = 0L

        fun Boolean.toDb(): Long = if (this) TRUE else FALSE
    }
}
