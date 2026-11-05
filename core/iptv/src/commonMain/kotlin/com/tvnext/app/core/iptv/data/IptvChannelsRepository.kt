package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Channel

class IptvChannelsRepository(
    private val store: ChannelStore,
    private val playlistRepository: PlaylistRepository,
) : ChannelsRepository {

    override suspend fun getChannels(): List<Channel> = store.getChannels()

    override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> =
        store.getChannelsByPlaylist(playlistId)

    override suspend fun isConfigured(): Boolean = store.getEnabledPlaylists().isNotEmpty()

    override suspend fun refresh(): AppResult<Unit> = playlistRepository.refreshAll()

    override suspend fun getFavoriteIds(): Set<String> = store.getFavoriteIds()

    override suspend fun setFavorite(channelId: String, favorite: Boolean) {
        store.setFavorite(channelId, favorite)
    }
}
