package com.tvnext.app.feature.home.data

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.EpgState
import com.tvnext.app.core.iptv.data.HistoryEntry
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.data.NowNext
import com.tvnext.app.core.iptv.data.ProgrammeSearchHit
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home screen content contracts: the new «Сейчас идёт», «Ближайшие передачи»,
 * «Избранные фильмы и сериалы» and «Категории медиатеки» sections are
 * populated from REAL repositories, and stay empty (hidden) when there is no
 * matching data — never crash, never invent content.
 */
class HomeRepositoryTest {

    private class FakeChannelsRepository(
        val channels: List<Channel>,
        val favorites: Set<String> = emptySet(),
    ) : ChannelsRepository {
        override suspend fun getChannels(): List<Channel> = channels
        override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> =
            channels.filter { it.playlistId == playlistId }
        override suspend fun isConfigured(): Boolean = true
        override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getFavoriteIds(): Set<String> = favorites
        override suspend fun setFavorite(channelId: String, favorite: Boolean) = Unit
    }

    private class FakeHistoryRepository(
        var entries: List<HistoryEntry> = emptyList(),
    ) : HistoryRepository {
        override suspend fun add(itemId: String, contentType: WatchContentType) = Unit
        override suspend fun getRecent(limit: Int): List<HistoryEntry> = entries.take(limit)
        override suspend fun getRecentByType(contentType: WatchContentType, limit: Int): List<HistoryEntry> =
            entries.filter { it.contentType == contentType }.take(limit)
        override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = flowOf(entries.take(limit))
        override suspend fun clear() {
            entries = emptyList()
        }
    }

    private class FakeEpgRepository(
        val nowNext: Map<String, NowNext> = emptyMap(),
    ) : EpgRepository {
        override fun observeState() = MutableStateFlow(EpgState())
        override fun observeVersion(): Flow<Long> = flowOf(0L)
        override suspend fun refreshState() = Unit
        override suspend fun refreshIfStale(force: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshPlaylist(playlistId: String, force: Boolean): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun getUpcomingProgrammes(channel: Channel, from: Long, limit: Int): List<EpgProgramme> =
            emptyList()
        override suspend fun getNowNext(channel: Channel, at: Long): NowNext = nowNext[channel.id] ?: NowNext()
        override suspend fun getNowNextForChannels(channels: List<Channel>, at: Long): Map<String, NowNext> =
            channels.mapNotNull { channel -> nowNext[channel.id]?.let { channel.id to it } }.toMap()
        override suspend fun getNowPlaying(channels: List<Channel>, at: Long): Map<String, EpgProgramme> =
            channels.mapNotNull { channel ->
                nowNext[channel.id]?.current?.let { channel.id to it }
            }.toMap()
        override suspend fun getProgrammesInRange(
            channels: List<Channel>,
            from: Long,
            to: Long,
        ): Map<String, List<EpgProgramme>> = emptyMap()
        override suspend fun getAvailableProgrammeRange(channels: List<Channel>): LongRange? = null

    override suspend fun getMatchingDiagnostics() = null
        override suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit> = emptyList()
    }

    private class FakeMediaLibrary(
        val items: List<MediaItem>,
        val favorites: Set<String> = emptySet(),
    ) : MediaLibraryRepository {
        override suspend fun getSources(): List<Playlist> = emptyList()
        override suspend fun addSource(url: String, name: String?): AppResult<Playlist> =
            AppResult.Failure(AppError.InvalidData("unused"))
        override suspend fun refreshSource(playlistId: String): AppResult<Playlist> =
            AppResult.Failure(AppError.InvalidData("unused"))
        override suspend fun refreshAll(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun removeSource(playlistId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun renameSource(playlistId: String, name: String): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun getItems(playlistId: String?): List<MediaItem> = items
        override suspend fun getItemsByCategory(playlistId: String, category: String): List<MediaItem> =
            items.filter { it.category == category }
        override suspend fun getCategories(playlistId: String): List<String> = items.map { it.category }.distinct()
        override suspend fun getItemById(id: String): MediaItem? = items.firstOrNull { it.id == id }
        override suspend fun search(query: String): List<MediaItem> =
            items.filter { it.name.contains(query, ignoreCase = true) }
        override suspend fun getFavoriteIds(): Set<String> = favorites
        override suspend fun setFavorite(itemId: String, favorite: Boolean) = Unit
    }

    private fun channel(
        id: String,
        name: String,
        playlistId: String = "pl",
        tvgId: String? = null,
    ) = Channel(
        id = id,
        name = name,
        number = 1,
        category = "Тест",
        streamUrl = "http://example.com/$id",
        tvgId = tvgId,
        playlistId = playlistId,
    )

    private fun vod(id: String, name: String, category: String) = MediaItem(
        id = id,
        playlistId = "vod",
        name = name,
        number = 1,
        category = category,
        streamUrl = "http://example.com/$id.m3u8",
    )

    private fun programme(title: String, start: Long, stop: Long) = EpgProgramme(
        channelId = "epg1",
        start = start,
        stop = stop,
        title = title,
    )

    @Test
    fun newUserWithoutDataHasNoSections() = runBlocking {
        val repo = DefaultHomeRepository(
            channelsRepository = FakeChannelsRepository(emptyList()),
            historyRepository = FakeHistoryRepository(),
            epgRepository = FakeEpgRepository(),
            mediaLibrary = FakeMediaLibrary(emptyList()),
        )
        val content = repo.getContent()

        assertTrue(content.continueWatching == null)
        assertTrue(content.recent.isEmpty())
        assertTrue(content.favorites.isEmpty())
        assertTrue(content.nowPlaying.isEmpty(), "no EPG → «Сейчас идёт» is empty")
        assertTrue(content.upcoming.isEmpty(), "no EPG → «Ближайшие передачи» is empty")
        assertTrue(content.continueWatchingVod == null)
        assertTrue(content.recentVod.isEmpty())
        assertTrue(content.favoriteVod.isEmpty(), "no favorites → «Избранные фильмы» is empty")
    }

    @Test
    fun favoriteVodComesFromRealData() = runBlocking {
        val items = listOf(
            vod("f1", "Интерстеллар", "Фильмы"),
            vod("f2", "Дюна", "Фильмы"),
            vod("s1", "Чернобыль S01E01", "Сериалы"),
        )
        val repo = DefaultHomeRepository(
            channelsRepository = FakeChannelsRepository(emptyList()),
            historyRepository = FakeHistoryRepository(),
            epgRepository = FakeEpgRepository(),
            mediaLibrary = FakeMediaLibrary(items, favorites = setOf("f1")),
        )
        val content = repo.getContent()

        assertEquals(listOf("f1"), content.favoriteVod.map { it.id }, "favorite VOD section")
    }

    @Test
    fun nowPlayingAndUpcomingComeFromEpg() = runBlocking {
        val ch = channel("ch1", "Канал 1", tvgId = "epg1")
        val now = 1_000_000L
        val nowNext = mapOf(
            "ch1" to NowNext(
                current = programme("Новости", start = now - 600_000, stop = now + 600_000),
                next = programme("Время", start = now + 600_000, stop = now + 1_800_000),
            ),
        )
        val repo = DefaultHomeRepository(
            channelsRepository = FakeChannelsRepository(listOf(ch)),
            historyRepository = FakeHistoryRepository(),
            epgRepository = FakeEpgRepository(nowNext = nowNext),
            mediaLibrary = FakeMediaLibrary(emptyList()),
        )
        val content = repo.getContent()

        assertEquals(listOf("ch1"), content.nowPlaying.map { it.id }, "«Сейчас идёт» uses current programme")
        assertEquals(
            "Новости",
            content.nowPlaying.single().currentProgramme?.title,
        )
        assertEquals(1, content.upcoming.size, "«Ближайшие передачи» uses next programme")
        assertEquals("Время", content.upcoming.single().programme.title)
        assertEquals("Канал 1", content.upcoming.single().channelName)
    }

    @Test
    fun historyFeedsContinueWatchingAndRecentSections() = runBlocking {
        val first = channel("ch1", "Канал 1")
        val second = channel("ch2", "Канал 2")
        val repo = DefaultHomeRepository(
            channelsRepository = FakeChannelsRepository(listOf(first, second)),
            historyRepository = FakeHistoryRepository(
                entries = listOf(
                    HistoryEntry(itemId = "ch1", contentType = WatchContentType.LIVE, watchedAt = 2_000_000_000L),
                    HistoryEntry(itemId = "ch2", contentType = WatchContentType.LIVE, watchedAt = 1_000_000_000L),
                ),
            ),
            epgRepository = FakeEpgRepository(),
            mediaLibrary = FakeMediaLibrary(emptyList()),
        )
        val content = repo.getContent()

        assertEquals("ch1", content.continueWatching?.id, "«Продолжить просмотр» = most recent LIVE history")
        assertEquals(listOf("ch2"), content.recent.map { it.id }, "recent excludes the continue-watching row")
    }
}
