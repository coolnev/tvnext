package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.iptv.m3u.M3uParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VOD / media-library source: M3U parsing into media items, real categories
 * (group-title), stable identity across refresh, search and VOD watch history.
 */
class VodMediaLibraryTest {

    private class FakeM3uSource(
        var contents: MutableMap<String, String>,
    ) : M3uSource {
        override suspend fun fetch(url: String): AppResult<String> =
            AppResult.Success(contents.getValue(url))
    }

    private val VOD_M3U = """
        #EXTM3U
        #EXTINF:-1 tvg-id="film-1" group-title="Фильмы" tvg-logo="http://host/f1.jpg" duration="7200",Фильм 1
        http://host/film1.m3u8
        #EXTINF:-1 tvg-id="s1" group-title="Сериалы" season="1" episode="2",Сериал 1 S01E02
        http://host/s1e2.m3u8
        #EXTINF:-1 tvg-id="doc-1" group-title="Документальные",Документальный фильм
        http://host/doc1.m3u8
    """.trimIndent()

    private fun createRepo(
        source: M3uSource,
    ): Pair<MediaLibraryRepository, IptvPlaylistRepository> {
        val database = createTestDatabase()
        val mediaStore = SqlDelightMediaItemStore(database)
        val playlistRepo = IptvPlaylistRepository(
            source = source,
            parser = M3uParser(),
            store = SqlDelightChannelStore(database),
            mediaStore = mediaStore,
            epgStore = SqlDelightEpgStore(database),
            archiveCapabilityStore = com.tvnext.app.core.iptv.archive.SqlDelightArchiveCapabilityStore(database),
            scheduledRepository = SqlDelightScheduledRepository(database),
            metaRepository = com.tvnext.app.core.database.SqlDelightAppMetaRepository(database),
            idMigration = ChannelIdMigration(database),
        )
        return IptvMediaLibraryRepository(mediaStore, playlistRepo) to playlistRepo
    }

    private val VOD_URL = "http://vod.example/library.m3u"

    @Test
    fun vodM3uParsesIntoItemsWithCategories() = runBlocking {
        val source = FakeM3uSource(mutableMapOf(VOD_URL to VOD_M3U))
        val (library, _) = createRepo(source)

        library.addSource(VOD_URL)
        val items = library.getItems(null)

        assertEquals(3, items.size)
        val categories = items.map { it.category }.sorted()
        assertEquals(listOf("Документальные", "Сериалы", "Фильмы"), categories)

        val series = items.first { it.name == "Сериал 1 S01E02" }
        assertEquals("1", series.season)
        assertEquals("2", series.episode)

        val film = items.first { it.name == "Фильм 1" }
        assertNotNull(film.logoUrl)

        assertEquals(listOf("Документальные", "Сериалы", "Фильмы"), library.getCategories(IdGenerator.playlistId(VOD_URL)))
    }

    @Test
    fun refreshKeepsStableIdentityNoDuplicates() = runBlocking {
        val source = FakeM3uSource(mutableMapOf(VOD_URL to VOD_M3U))
        val (library, _) = createRepo(source)

        library.addSource(VOD_URL)
        val before = library.getItems(null).associateBy { it.id }

        // Same source, order changed + one new item.
        val shuffled = """
            #EXTM3U
            #EXTINF:-1 tvg-id="doc-1" group-title="Документальные",Документальный фильм
            http://host/doc1.m3u8
            #EXTINF:-1 tvg-id="film-1" group-title="Фильмы",Фильм 1
            http://host/film1.m3u8
            #EXTINF:-1 tvg-id="s1" group-title="Сериалы" season="1" episode="2",Сериал 1 S01E02
            http://host/s1e2.m3u8
        """.trimIndent()
        source.contents[VOD_URL] = shuffled
        library.refreshSource(IdGenerator.playlistId(VOD_URL))

        val after = library.getItems(null)
        // No duplicates (same 3 items).
        assertEquals(3, after.size)
        // Identical ids for the surviving items.
        after.forEach { item ->
            assertEquals(before[item.id]?.name, item.name)
        }
    }

    @Test
    fun searchFindsByNameAndCategory() = runBlocking {
        val source = FakeM3uSource(mutableMapOf(VOD_URL to VOD_M3U))
        val (library, _) = createRepo(source)
        library.addSource(VOD_URL)

        val byName = library.search("Фильм")
        assertEquals(2, byName.size)

        val byCategory = library.search("сериалы")
        assertTrue(byCategory.isNotEmpty())
        assertTrue(byCategory.all { it.category == "Сериалы" })
    }

    @Test
    fun vodWatchHistoryIsTyped() = runBlocking {
        val source = FakeM3uSource(mutableMapOf(VOD_URL to VOD_M3U))
        val (library, playlistRepo) = createRepo(source)
        library.addSource(VOD_URL)

        val item = library.getItems(null).first()
        val database = createTestDatabase()
        val history = DefaultHistoryRepository(SqlDelightHistoryStore(database))
        history.add(item.id, WatchContentType.VOD)

        val recentVod = history.getRecentByType(WatchContentType.VOD, 10)
        assertEquals(listOf(item.id), recentVod.map { it.itemId })
        assertTrue(history.getRecentByType(WatchContentType.LIVE, 10).isEmpty())
    }

    @Test
    fun removeSourceDeletesItsItems() = runBlocking {
        val source = FakeM3uSource(mutableMapOf(VOD_URL to VOD_M3U))
        val (library, _) = createRepo(source)
        library.addSource(VOD_URL)
        assertEquals(3, library.getItems(null).size)

        library.removeSource(IdGenerator.playlistId(VOD_URL))
        assertTrue(library.getItems(null).isEmpty())
        assertTrue(library.getSources().isEmpty())
    }
}


