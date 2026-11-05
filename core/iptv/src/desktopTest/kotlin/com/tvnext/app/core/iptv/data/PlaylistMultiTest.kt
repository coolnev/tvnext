package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.m3u.M3uParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-playlist behaviour: adding one source never touches the others,
 * refresh keeps stable ids, equal channel names in different playlists are
 * separate entities, removal cleans up only the removed playlist.
 */
class PlaylistMultiTest {

    private class FakeM3uSource(
        var contents: MutableMap<String, String>,
    ) : M3uSource {
        override suspend fun fetch(url: String): AppResult<String> {
            val content = contents[url] ?: return AppResult.Failure(
                com.tvnext.app.core.common.result.AppError.Network(),
            )
            return AppResult.Success(content)
        }
    }

    private fun m3u(channels: List<Pair<String, String>>): String = buildString {
        appendLine("#EXTM3U")
        channels.forEach { (name, tvgId) ->
            appendLine("#EXTINF:-1 tvg-id=\"$tvgId\" group-title=\"Новости\",$name")
            // URL depends on the channel identity, NOT on the list position, so
            // reordering the M3U cannot change a channel's stable id.
            appendLine("http://host/$tvgId.m3u8")
        }
    }

    private fun m3uNamed(channels: List<Pair<String, Pair<String, String>>>): String = buildString {
        appendLine("#EXTM3U")
        channels.forEachIndexed { index, (name, attrs) ->
            val (tvgId, url) = attrs
            appendLine("#EXTINF:-1 tvg-id=\"$tvgId\" group-title=\"Тест\",$name")
            appendLine(url)
        }
    }

    private fun createRepo(
        source: M3uSource,
    ): Pair<IptvPlaylistRepository, ChannelStore> {
        val database = createTestDatabase()
        val store = SqlDelightChannelStore(database)
        val repo = IptvPlaylistRepository(
            source = source,
            parser = M3uParser(),
            store = store,
            mediaStore = SqlDelightMediaItemStore(database),
            epgStore = SqlDelightEpgStore(database),
            archiveCapabilityStore = com.tvnext.app.core.iptv.archive.SqlDelightArchiveCapabilityStore(database),
            scheduledRepository = SqlDelightScheduledRepository(database),
            metaRepository = com.tvnext.app.core.database.SqlDelightAppMetaRepository(database),
            idMigration = ChannelIdMigration(database),
        )
        return repo to store
    }

    private val PLAYLIST_A = "http://a.example/pl.m3u"
    private val PLAYLIST_B = "http://b.example/pl.m3u"

    @Test
    fun addingTwoPlaylistsKeepsBoth() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3u(listOf("Новости 1" to "news1")),
                PLAYLIST_B to m3u(listOf("Спорт 1" to "sport1")),
            ),
        )
        val (repo, store) = createRepo(source)

        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist(PLAYLIST_B, PlaylistType.LIVE)

        assertEquals(2, repo.getPlaylists().size)
        val channels = store.getChannels()
        assertEquals(2, channels.size)
        // Channels belong to different playlists.
        assertTrue(channels.any { it.name == "Новости 1" })
        assertTrue(channels.any { it.name == "Спорт 1" })
        assertTrue(channels.map { it.playlistId }.distinct().size == 2)
    }

    @Test
    fun refreshDoesNotCreateNewChannels() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(PLAYLIST_A to m3u(listOf("Новости 1" to "news1", "Фильм 1" to "film1"))),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        val before = store.getChannels().associateBy { it.id }
        val filmIdBefore = store.getChannels().first { it.name == "Фильм 1" }.id

        // Channel order swapped + one removed.
        source.contents[PLAYLIST_A] = m3u(listOf("Фильм 1" to "film1"))
        val result = repo.refreshPlaylist(IdGenerator.playlistId(PLAYLIST_A))
        assertTrue(result is AppResult.Success)

        val after = store.getChannels().associateBy { it.id }
        // Surviving channel keeps its stable id (position change is irrelevant).
        assertEquals(filmIdBefore, after.getValue(filmIdBefore).id)
        assertEquals(1, after.size)
    }

    @Test
    fun sameChannelNameInDifferentPlaylistsIsSeparate() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3uNamed(listOf("Новости" to ("news-a" to "http://a/1.m3u8"))),
                PLAYLIST_B to m3uNamed(listOf("Новости" to ("news-b" to "http://b/1.m3u8"))),
            ),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist(PLAYLIST_B, PlaylistType.LIVE)

        val news = store.getChannels().filter { it.name == "Новости" }
        assertEquals(2, news.size)
        assertEquals(2, news.map { it.id }.distinct().size)
    }

    @Test
    fun favoritesSurviveRefreshOfTheirPlaylistOnly() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3u(listOf("Новости 1" to "news1")),
                PLAYLIST_B to m3u(listOf("Спорт 1" to "sport1")),
            ),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist(PLAYLIST_B, PlaylistType.LIVE)

        val news = store.getChannels().first { it.name == "Новости 1" }
        val sport = store.getChannels().first { it.name == "Спорт 1" }
        store.setFavorite(news.id, true)
        store.setFavorite(sport.id, true)

        // Refresh only playlist A.
        repo.refreshPlaylist(IdGenerator.playlistId(PLAYLIST_A))

        val after = store.getChannels().associateBy { it.id }
        assertEquals(true, after.getValue(news.id).isFavorite)
        assertEquals(true, after.getValue(sport.id).isFavorite)
    }

    @Test
    fun removePlaylistCleansOnlyItsChannels() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3u(listOf("Новости 1" to "news1")),
                PLAYLIST_B to m3u(listOf("Спорт 1" to "sport1")),
            ),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist(PLAYLIST_B, PlaylistType.LIVE)

        repo.removePlaylist(IdGenerator.playlistId(PLAYLIST_A))

        assertEquals(1, repo.getPlaylists().size)
        val remaining = store.getChannels()
        assertEquals(listOf("Спорт 1"), remaining.map { it.name })
    }

    @Test
    fun stableIdIndependentOfPosition() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(PLAYLIST_A to m3uNamed(listOf("Канал 1" to ("c1" to "http://host/one.m3u8")))),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        val idBefore = store.getChannels().single().id

        // Same channel, different position and different number of channels.
        source.contents[PLAYLIST_A] = m3uNamed(
            listOf(
                "Другой" to ("x" to "http://host/x.m3u8"),
                "Канал 1" to ("c1" to "http://host/one.m3u8"),
            ),
        )
        repo.refreshPlaylist(IdGenerator.playlistId(PLAYLIST_A))

        val channelAfter = store.getChannels().first { it.name == "Канал 1" }
        assertEquals(idBefore, channelAfter.id)
    }

    @Test
    fun defaultPlaylistCanBeSetAndCleared() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3u(listOf("Новости 1" to "news1")),
                PLAYLIST_B to m3u(listOf("Спорт 1" to "sport1")),
            ),
        )
        val (repo, _) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist(PLAYLIST_B, PlaylistType.LIVE)
        assertNull(repo.getDefaultPlaylist())

        val idA = IdGenerator.playlistId(PLAYLIST_A)
        repo.setDefaultPlaylist(idA)
        assertEquals(idA, repo.getDefaultPlaylist()?.id)
        assertTrue(repo.getDefaultPlaylist()?.isDefault == true)

        repo.setDefaultPlaylist(null)
        assertNull(repo.getDefaultPlaylist())
    }

    @Test
    fun vodPlaylistIsKeptSeparateFromLive() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(
                PLAYLIST_A to m3u(listOf("Новости 1" to "news1")),
                "http://vod.example/m.m3u" to m3u(listOf("Фильм" to "film1")),
            ),
        )
        val (repo, store) = createRepo(source)
        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        repo.addPlaylist("http://vod.example/m.m3u", PlaylistType.VOD)

        val vodPlaylists = repo.getPlaylists().filter { it.type == PlaylistType.VOD }
        assertEquals(1, vodPlaylists.size)
        assertEquals(0, store.getChannels().filter { it.name == "Фильм" }.size)
        assertEquals(1, store.getChannels().filter { it.name == "Новости 1" }.size)
    }
}


