package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylist(id: String): Playlist?

    /** The user's default playlist (used for Home/continue-watching), if any. */
    suspend fun getDefaultPlaylist(): Playlist?

    /**
     * Adds a new M3U/M3U8 source ([url]) of the given [type]. Re-adding an
     * existing source refreshes it instead of creating a duplicate. Other
     * playlists are never touched.
     */
    suspend fun addPlaylist(
        url: String,
        type: PlaylistType = PlaylistType.LIVE,
        name: String? = null,
    ): AppResult<Playlist>

    suspend fun removePlaylist(id: String): AppResult<Unit>
    suspend fun renamePlaylist(id: String, name: String): AppResult<Unit>
    suspend fun refreshPlaylist(playlistId: String): AppResult<Playlist>
    suspend fun refreshAll(): AppResult<Unit>
    suspend fun setDefaultPlaylist(id: String?): AppResult<Unit>

    /**
     * Re-imports every playlist ONCE when the M3U parser learned new channel
     * attributes since the last import (e.g. catch-up / archive fields).
     * Existing databases imported by older app versions must be re-read so the
     * new attributes land in the channel table. Cheap no-op afterwards.
     */
    suspend fun refreshIfParserUpgraded(): AppResult<Unit>
}
