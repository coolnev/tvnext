package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Channel

interface ChannelsRepository {
    /** All channels across every enabled playlist. */
    suspend fun getChannels(): List<Channel>

    /** Channels of one playlist (scoped EPG/matching, TV Guide). */
    suspend fun getChannelsByPlaylist(playlistId: String): List<Channel>

    suspend fun isConfigured(): Boolean

    /** Refreshes every enabled playlist. */
    suspend fun refresh(): AppResult<Unit>
    suspend fun getFavoriteIds(): Set<String>
    suspend fun setFavorite(channelId: String, favorite: Boolean)
}
