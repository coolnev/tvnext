package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that the lightweight EPG repository serves cached programmes through
 * its public API (this is what the TV Guide grid consumes via
 * `getProgrammesInRange`).
 */
class LiteEpgRepositoryCacheTest {

    private object FakePlaylists : PlaylistRepository {
        override fun observePlaylists(): Flow<List<Playlist>> = emptyFlow()
        override suspend fun getPlaylists(): List<Playlist> = emptyList()
        override suspend fun getPlaylist(id: String): Playlist? = null
        override suspend fun getDefaultPlaylist(): Playlist? = null
        override suspend fun addPlaylist(url: String, type: PlaylistType, name: String?) =
            AppResult.Failure(AppError.InvalidData(""))
        override suspend fun removePlaylist(id: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun renamePlaylist(id: String, name: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshPlaylist(playlistId: String): AppResult<Playlist> =
            AppResult.Failure(AppError.InvalidData(""))
        override suspend fun refreshAll(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun setDefaultPlaylist(id: String?): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshIfParserUpgraded(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private object FakeChannels : ChannelsRepository {
        override suspend fun getChannels(): List<Channel> = emptyList()
        override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> = emptyList()
        override suspend fun isConfigured(): Boolean = false
        override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getFavoriteIds(): Set<String> = emptySet()
        override suspend fun setFavorite(channelId: String, favorite: Boolean) = Unit
    }

    private object FakeConfig : EpgConfigProvider {
        override suspend fun isEnabled(): Boolean = true
        override suspend fun userEpgUrl(): String? = null
    }

    private val dayStart = 1_784_995_200_000L // 2026-07-25 16:00 UTC = 2026-07-26 00:00 +0800

    @Test
    fun freshCacheIsReusedOnColdStartWithoutNetwork() {
        val now = System.currentTimeMillis()
        val dir = Files.createTempDirectory("epg-relaunch-test").toString()
        val playlist = Playlist(
            id = "p1", name = "Cached", url = "https://unused.invalid/channels.m3u",
            epgUrl = "https://unused.invalid/guide.xml",
        )
        val channel = Channel(
            id = "101", name = "Channel", number = 1, category = "",
            streamUrl = "http://unused.invalid/live", playlistId = "p1",
        )
        writeEpgCacheFile(
            "$dir/epg_programs.dat",
            "v1:$now\n" +
                "p1|101|${now - 60_000L}|${now + 600_000L}|Новости|\n",
        )
        val playlists = object : PlaylistRepository by FakePlaylists {
            override suspend fun getPlaylists() = listOf(playlist)
            override suspend fun getPlaylist(id: String): Playlist? = playlist.takeIf { it.id == id }
            override suspend fun getDefaultPlaylist(): Playlist? = playlist
        }
        val channels = object : ChannelsRepository by FakeChannels {
            override suspend fun getChannelsByPlaylist(playlistId: String) =
                if (playlistId == "p1") listOf(channel) else emptyList()
        }
        // A new repository simulates a new process. The supplied EPG URL is
        // deliberately unreachable: a cache hit must not make an HTTP request.
        val repository = LiteEpgRepository(
            client = HttpClient(), playlistRepository = playlists,
            channelsRepository = channels, configProvider = FakeConfig,
            cacheDir = dir, clock = { now },
        )
        runBlocking {
            val result = repository.refreshIfStale(force = false)
            assertTrue(result is AppResult.Success)
            assertEquals("Новости", repository.getNowPlaying(listOf(channel), now)["101"]?.title)
        }
    }

    @Test
    fun interruptedCacheReadIsNotReportedAsComplete() {
        val dir = Files.createTempDirectory("epg-early-stop").toString()
        val path = "$dir/epg_programs.dat"
        writeEpgCacheFile(path, "v1:123\nfirst\nsecond\n")
        var lines = 0
        val complete = readEpgCacheLines(path) { lines++; false }
        assertEquals(1, lines)
        assertEquals(false, complete)
    }

    @Test
    fun getProgrammesInRangeReturnsCachedData() {
        val dir = Files.createTempDirectory("epg-cache-test").toString()
        writeEpgCacheFile(
            "$dir/epg_programs.dat",
            buildString {
                append("v1:").append(dayStart).append('\n')
                append("p1|101|").append(dayStart).append('|').append(dayStart + 3_600_000)
                    .append("|Новости|Важное описание\n")
                append("p1|202|").append(dayStart + 1_800_000).append('|').append(dayStart + 7_200_000)
                    .append("|Фильм|\n")
            },
        )

        val repo = LiteEpgRepository(
            client = HttpClient(),
            playlistRepository = FakePlaylists,
            channelsRepository = FakeChannels,
            configProvider = FakeConfig,
            cacheDir = dir,
        )

        val channel1 = Channel(id = "101", name = "Первый", number = 1, category = "", streamUrl = "http://x", playlistId = "p1")
        val channel2 = Channel(id = "202", name = "Россия 1", number = 2, category = "", streamUrl = "http://y", playlistId = "p1")

        runBlocking {
            val result = repo.getProgrammesInRange(
                listOf(channel1, channel2),
                dayStart,
                dayStart + 24L * 60 * 60 * 1000,
            )

            val p1 = result["101"]
            assertNotNull(p1, "channel 101 must have programmes in range")
            assertEquals(1, p1.size)
            assertEquals("Новости", p1[0].title)
            assertEquals("Важное описание", p1[0].description)

            val p2 = result["202"]
            assertNotNull(p2, "channel 202 must have programmes in range")
            assertEquals(1, p2.size)
            assertEquals("Фильм", p2[0].title)
        }
    }

    @Test
    fun getNowNextUsesCachedData() {
        val dir = Files.createTempDirectory("epg-cache-test-2").toString()
        writeEpgCacheFile(
            "$dir/epg_programs.dat",
            buildString {
                append("v1:").append(dayStart).append('\n')
                append("p1|101|").append(dayStart).append('|').append(dayStart + 3_600_000)
                    .append("|Новости|Важное описание\n")
                append("p1|101|").append(dayStart + 3_600_000).append('|').append(dayStart + 7_200_000)
                    .append("|Фильм|\n")
            },
        )

        val repo = LiteEpgRepository(
            client = HttpClient(),
            playlistRepository = FakePlaylists,
            channelsRepository = FakeChannels,
            configProvider = FakeConfig,
            cacheDir = dir,
        )

        val channel = Channel(id = "101", name = "Первый", number = 1, category = "", streamUrl = "http://x", playlistId = "p1")
        runBlocking {
            val nowNext = repo.getNowNext(channel, dayStart + 500_000)
            assertEquals("Новости", nowNext.current?.title)
            assertEquals("Фильм", nowNext.next?.title)
        }
    }
}
