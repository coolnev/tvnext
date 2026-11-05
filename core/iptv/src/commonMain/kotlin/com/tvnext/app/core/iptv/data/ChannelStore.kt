package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.tvnext.app.core.database.Channel as DbChannel
import com.tvnext.app.core.database.Playlist as DbPlaylist
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface ChannelStore {
    suspend fun savePlaylist(playlist: Playlist)
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getEnabledPlaylists(): List<Playlist>
    suspend fun getEnabledByType(type: PlaylistType): List<Playlist>
    suspend fun getPlaylistById(id: String): Playlist?
    suspend fun getDefaultPlaylist(): Playlist?
    suspend fun deletePlaylist(id: String)
    suspend fun deleteAllPlaylists()
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun setDefaultPlaylist(id: String?)
    suspend fun updatePlaylistType(id: String, type: PlaylistType)
    suspend fun updatePlaylistEpgUrl(id: String, urlTvg: String?)

    /**
     * Replaces the channels of [playlistId]. Favorites are preserved by their
     * stable channel id: either read from the database, or supplied via
     * [preservedFavorites] — which is required when the rows were already wiped
     * (e.g. `deleteAllPlaylists` before a playlist (re)load).
     */
    suspend fun replacePlaylistChannels(
        playlistId: String,
        channels: List<Channel>,
        preservedFavorites: Set<String> = emptySet(),
    )
    suspend fun getChannels(): List<Channel>
    suspend fun getChannelsByPlaylist(playlistId: String): List<Channel>
    suspend fun setFavorite(channelId: String, favorite: Boolean)
    suspend fun getFavoriteIds(): Set<String>
    fun observeEnabledPlaylists(): Flow<List<Playlist>>
    fun observePlaylists(): Flow<List<Playlist>>
}

class SqlDelightChannelStore(
    private val database: TvNextDatabase,
) : ChannelStore {

    private val playlists = database.playlistQueries
    private val channelQueries = database.channelQueries

    override suspend fun savePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        playlists.insertPlaylist(
            id = playlist.id,
            name = playlist.name,
            url = playlist.url,
            last_updated = playlist.lastUpdated,
            enabled = playlist.enabled.toDb(),
            url_tvg = playlist.epgUrl,
            type = PlaylistType.toDb(playlist.type),
            is_default = playlist.isDefault.toDb(),
        )
        Unit
    }

    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        playlists.selectAllPlaylists().executeAsList().map(::toDomainPlaylist)
    }

    override suspend fun getEnabledPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        playlists.selectEnabledPlaylists().executeAsList().map(::toDomainPlaylist)
    }

    override suspend fun getEnabledByType(type: PlaylistType): List<Playlist> = withContext(Dispatchers.IO) {
        playlists.selectEnabledByType(PlaylistType.toDb(type)).executeAsList().map(::toDomainPlaylist)
    }

    override suspend fun getPlaylistById(id: String): Playlist? = withContext(Dispatchers.IO) {
        playlists.selectPlaylistById(id).executeAsOneOrNull()?.let(::toDomainPlaylist)
    }

    override suspend fun getDefaultPlaylist(): Playlist? = withContext(Dispatchers.IO) {
        playlists.selectDefaultPlaylist().executeAsOneOrNull()?.let(::toDomainPlaylist)
    }

    override suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        database.transaction {
            channelQueries.deleteChannelsByPlaylist(id)
            playlists.deletePlaylist(id)
        }
    }

    override suspend fun deleteAllPlaylists() = withContext(Dispatchers.IO) {
        database.transaction {
            playlists.deleteAllPlaylists()
            channelQueries.deleteAllChannels()
        }
    }

    override suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        playlists.renamePlaylist(name, id)
        Unit
    }

    override suspend fun setDefaultPlaylist(id: String?) = withContext(Dispatchers.IO) {
        if (id == null) {
            playlists.clearDefaultPlaylist()
        } else {
            playlists.setDefaultPlaylist(id)
        }
        Unit
    }

    override suspend fun updatePlaylistType(id: String, type: PlaylistType) = withContext(Dispatchers.IO) {
        playlists.updatePlaylistType(PlaylistType.toDb(type), id)
        Unit
    }

    override suspend fun updatePlaylistEpgUrl(id: String, urlTvg: String?) = withContext(Dispatchers.IO) {
        playlists.updateEpgUrl(urlTvg, id)
        Unit
    }

    override suspend fun replacePlaylistChannels(
        playlistId: String,
        channels: List<Channel>,
        preservedFavorites: Set<String>,
    ) = withContext(Dispatchers.IO) {
        database.transaction {
            val previousFavorites = preservedFavorites +
                channelQueries.selectChannelsByPlaylist(playlistId).executeAsList()
                    .filter { it.is_favorite == TRUE }
                    .map { it.id }
            channelQueries.deleteChannelsByPlaylist(playlistId)
            channels.forEach { channel ->
                channelQueries.insertChannel(
                    id = channel.id,
                    playlist_id = playlistId,
                    number = channel.number.toLong(),
                    name = channel.name,
                    stream_url = channel.streamUrl,
                    logo_url = channel.logoUrl,
                    category = channel.category,
                    tvg_id = channel.tvgId,
                    tvg_name = channel.tvgName,
                    is_favorite = if (previousFavorites.contains(channel.id)) TRUE else FALSE,
                    catchup = channel.catchup,
                    catchup_days = channel.catchupDays,
                    catchup_source = channel.catchupSource,
                    catchup_type = channel.catchupType,
                    timeshift = channel.timeshift,
                )
            }
        }
    }

    override suspend fun getChannels(): List<Channel> = withContext(Dispatchers.IO) {
        channelQueries.selectAllChannels().executeAsList().map(::toDomainChannel)
    }

    override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> =
        withContext(Dispatchers.IO) {
            channelQueries.selectChannelsByPlaylist(playlistId).executeAsList().map(::toDomainChannel)
        }

    override suspend fun setFavorite(channelId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        channelQueries.setFavorite(is_favorite = favorite.toDb(), id = channelId)
        Unit
    }

    override suspend fun getFavoriteIds(): Set<String> = withContext(Dispatchers.IO) {
        channelQueries.selectFavoriteIds().executeAsList().toSet()
    }

    override fun observeEnabledPlaylists(): Flow<List<Playlist>> =
        playlists.selectEnabledPlaylists().asFlow()
            .mapToList(EmptyCoroutineContext)
            .map { rows -> rows.map(::toDomainPlaylist) }
            .flowOn(Dispatchers.IO)

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlists.selectAllPlaylists().asFlow()
            .mapToList(EmptyCoroutineContext)
            .map { rows -> rows.map(::toDomainPlaylist) }
            .flowOn(Dispatchers.IO)

    private fun toDomainPlaylist(row: DbPlaylist): Playlist = Playlist(
        id = row.id,
        name = row.name,
        url = row.url,
        lastUpdated = row.last_updated,
        enabled = row.enabled == TRUE,
        type = PlaylistType.fromDb(row.type),
        isDefault = row.is_default == TRUE,
        channelCount = channelQueries.countChannelsByPlaylist(row.id).executeAsOne().toInt(),
        epgUrl = row.url_tvg?.takeIf { it.isNotBlank() },
    )

    private fun toDomainChannel(row: DbChannel): Channel = Channel(
        id = row.id,
        name = row.name,
        number = row.number.toInt(),
        category = row.category,
        streamUrl = row.stream_url,
        logoUrl = row.logo_url,
        tvgId = row.tvg_id,
        tvgName = row.tvg_name,
        isFavorite = row.is_favorite == TRUE,
        playlistId = row.playlist_id,
        catchup = row.catchup,
        catchupDays = row.catchup_days,
        catchupSource = row.catchup_source,
        catchupType = row.catchup_type,
        timeshift = row.timeshift,
    )

    private companion object {
        const val TRUE = 1L
        const val FALSE = 0L

        fun Boolean.toDb(): Long = if (this) TRUE else FALSE
    }
}
