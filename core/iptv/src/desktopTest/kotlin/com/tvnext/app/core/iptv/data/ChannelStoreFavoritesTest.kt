package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelStoreFavoritesTest {

    private fun playlist(id: String = "pl") = Playlist(
        id = id,
        name = "Тест",
        url = "http://example.com/pl.m3u",
        enabled = true,
    )

    private fun channel(id: String, number: Int) = Channel(
        id = id,
        name = "Канал $number",
        number = number,
        category = "Тест",
        streamUrl = "http://example.com/$id",
    )

    @Test
    fun favoritesSurvivePlaylistRefresh() = runBlocking {
        val store = SqlDelightChannelStore(createTestDatabase())
        val channels = listOf(channel("ch-1", 1), channel("ch-2", 2))

        store.savePlaylist(playlist())
        store.replacePlaylistChannels("pl", channels)
        store.setFavorite("ch-1", true)

        // Simulate a playlist refresh: same channel ids re-inserted.
        store.replacePlaylistChannels("pl", channels)

        val after = store.getChannels().associateBy { it.id }
        assertEquals(true, after.getValue("ch-1").isFavorite)
        assertEquals(false, after.getValue("ch-2").isFavorite)
    }

    @Test
    fun favoriteOfRemovedChannelIsDroppedOnRefresh() = runBlocking {
        val store = SqlDelightChannelStore(createTestDatabase())
        store.savePlaylist(playlist())
        store.replacePlaylistChannels("pl", listOf(channel("ch-1", 1), channel("ch-2", 2)))
        store.setFavorite("ch-1", true)

        // ch-1 disappeared from the playlist.
        store.replacePlaylistChannels("pl", listOf(channel("ch-2", 2)))

        val ids = store.getChannels().map { it.id }
        assertEquals(listOf("ch-2"), ids)
        assertEquals(emptySet<String>(), store.getFavoriteIds())
    }

    @Test
    fun favoritesSurvivePlaylistReloadWithDeleteAll() = runBlocking {
        // Mirrors IptvPlaylistRepository.saveParsed: deleteAllPlaylists() wipes
        // every channel row, so favorites must be captured before the wipe.
        val store = SqlDelightChannelStore(createTestDatabase())
        store.savePlaylist(playlist())
        store.replacePlaylistChannels("pl", listOf(channel("ch-1", 1), channel("ch-2", 2)))
        store.setFavorite("ch-1", true)

        val preserved = store.getFavoriteIds()
        store.deleteAllPlaylists()
        store.savePlaylist(playlist())
        store.replacePlaylistChannels("pl", listOf(channel("ch-1", 1), channel("ch-2", 2)), preserved)

        val after = store.getChannels().associateBy { it.id }
        assertEquals(true, after.getValue("ch-1").isFavorite)
        assertEquals(false, after.getValue("ch-2").isFavorite)
    }

    @Test
    fun favoritesPersistAcrossStores() = runBlocking {
        val database = createTestDatabase()
        val first = SqlDelightChannelStore(database)
        first.savePlaylist(playlist())
        first.replacePlaylistChannels("pl", listOf(channel("ch-1", 1)))
        first.setFavorite("ch-1", true)

        // A new store instance over the same database (simulates app restart).
        val second = SqlDelightChannelStore(database)
        assertEquals(setOf("ch-1"), second.getFavoriteIds())
    }
}
