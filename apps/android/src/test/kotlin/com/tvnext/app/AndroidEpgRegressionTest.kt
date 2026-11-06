package com.tvnext.app

import android.app.Application
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import com.tvnext.app.core.common.platform.AppDataReset
import com.tvnext.app.core.common.platform.NoOpAppAutostart
import com.tvnext.app.core.tmdb.TmdbRepository
import com.tvnext.app.core.tmdb.TmdbSettingsStore
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.domain.AppInfo
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.ui.DefaultSettingsComponent
import com.tvnext.app.feature.settings.ui.SettingsIntent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import com.tvnext.app.core.common.result.*
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Android FrameworkSQLite + native sqlite, not the desktop JDBC driver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidEpgRegressionTest {
    private val item = Channel("channel", "Тестовый канал", 1, "Тест", "https://unused.invalid/live",
        tvgId = "news", playlistId = "playlist")
    private val playlist = Playlist("playlist", "Проверка", "https://unused.invalid/list",
        epgUrl = "https://unused.invalid/epg.xml")
    private val playlists = object : PlaylistRepository {
        override fun observePlaylists(): Flow<List<Playlist>> = emptyFlow()
        override suspend fun getPlaylists() = listOf(playlist)
        override suspend fun getPlaylist(id: String) = playlist.takeIf { it.id == id }
        override suspend fun getDefaultPlaylist() = playlist
        override suspend fun addPlaylist(url: String, type: PlaylistType, name: String?): AppResult<Playlist> = error("Unexpected network")
        override suspend fun removePlaylist(id: String): AppResult<Unit> = error("unused")
        override suspend fun renamePlaylist(id: String, name: String): AppResult<Unit> = error("unused")
        override suspend fun refreshPlaylist(playlistId: String): AppResult<Playlist> = error("Unexpected M3U refresh")
        override suspend fun refreshAll(): AppResult<Unit> = error("Unexpected M3U refresh")
        override suspend fun setDefaultPlaylist(id: String?): AppResult<Unit> = error("unused")
        override suspend fun refreshIfParserUpgraded(): AppResult<Unit> = AppResult.Success(Unit)
    }
    private val channels = object : ChannelsRepository {
        override suspend fun getChannels() = listOf(item)
        override suspend fun getChannelsByPlaylist(playlistId: String) = listOf(item)
        override suspend fun isConfigured() = true
        override suspend fun refresh(): AppResult<Unit> = error("Unexpected M3U refresh")
        override suspend fun getFavoriteIds(): Set<String> = emptySet()
        override suspend fun setFavorite(channelId: String, favorite: Boolean) = Unit
    }
    private val config = object : EpgConfigProvider {
        override suspend fun isEnabled() = true
        override suspend fun userEpgUrl(): String? = null
    }

    @Test fun openingEpgStateForSettingsUsesTheRealAndroidDatabaseDriver() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        AndroidFallbackEpgSettings(context).setEnabled(false)
        val client = HttpClient(MockEngine { error("Reading cache must not use network") }) { install(HttpTimeout) }
        try {
            val repository = AndroidEpgRepository(client, playlists, channels, config, context)
            repository.refreshState()
            assertEquals("playlist", repository.observeState().value.playlistId)
            assertEquals(0, repository.observeState().value.programmeCount)
        } finally { client.close() }
    }

    @Test fun androidImportThenFreshRepositoryImmediatelyReadsCurrentAndArchivedProgrammes() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        AndroidFallbackEpgSettings(context).setEnabled(false)
        AndroidEpgPlaybackGate.setAppForeground(true)
        val now = System.currentTimeMillis()
        val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        fun time(at: Long) = format.format(Instant.ofEpochMilli(at))
        val xml = """<tv><channel id="news"><display-name>Тестовый канал</display-name></channel>
            <programme channel="news" start="${time(now - 86_400_000)}" stop="${time(now - 82_800_000)}"><title>Архив</title></programme>
            <programme channel="news" start="${time(now - 60_000)}" stop="${time(now + 3_600_000)}"><title>Новости</title></programme></tv>"""
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond(xml, headers = headersOf("Content-Type", "application/xml")) }) { install(HttpTimeout) }
        try {
            val first = AndroidEpgRepository(client, playlists, channels, config, context)
            val imported = first.refreshIfStale(true)
            assertTrue("$imported", imported is AppResult.Success)
            assertEquals("Новости", first.getNowPlaying(listOf(item), now)[item.id]?.title)
            val reopened = AndroidEpgRepository(client, playlists, channels, config, context)
            assertEquals("Новости", reopened.getNowPlaying(listOf(item), now)[item.id]?.title)
            val archive = reopened.getProgrammesInRange(listOf(item), now - 2 * 86_400_000, now).getValue(item.id)
            assertTrue(archive.any { it.title == "Архив" })
            assertTrue(reopened.refreshIfStale(false) is AppResult.Success)
            assertEquals(1, requests)
        } finally { client.close() }
    }
    @Test fun largeGuideFinishesWhileVideoIsActiveAndActivityLeavesForeground() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        AndroidFallbackEpgSettings(context).setEnabled(false)
        AndroidEpgPlaybackGate.setAppForeground(true)
        AndroidEpgPlaybackGate.setVideoActive(true)
        val now = System.currentTimeMillis()
        val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val xml = buildString {
            append("<tv>")
            repeat(20_000) { i ->
                val start = now + (i - 10_000) * 60_000L
                append("<programme channel='news' start='${format.format(Instant.ofEpochMilli(start))}' stop='${format.format(Instant.ofEpochMilli(start + 60_000))}'><title>Передача $i</title><desc>${"Описание передачи. ".repeat(16)}</desc></programme>")
            }
            append("</tv>")
        }
        val client = HttpClient(MockEngine {
            AndroidEpgPlaybackGate.setAppForeground(false)
            respond(xml)
        }) { install(HttpTimeout) }
        val started = System.nanoTime()
        try {
            val repository = AndroidEpgRepository(client, playlists, channels, config, context)
            val result = repository.refreshIfStale(true)
            assertTrue("$result", result is AppResult.Success)
            assertEquals(20_000, repository.observeState().value.programmeCount)
            assertNotNull(repository.getNowPlaying(listOf(item), now)[item.id])
            assertNotNull(repository.getAvailableProgrammeRange(listOf(item)))
            println("ANDROID_INDEX_BENCHMARK programmes=20000 elapsedMs=${(System.nanoTime() - started)/1_000_000}")
        } finally {
            AndroidEpgPlaybackGate.setAppForeground(true)
            AndroidEpgPlaybackGate.setVideoActive(false)
            client.close()
        }
    }

    @Test fun guideFor1800ChannelsCommitsAndPreservesArchiveOnNativeAndroidSqlite() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        AndroidFallbackEpgSettings(context).setEnabled(false)
        AndroidEpgPlaybackGate.setAppForeground(true)
        val roster = (0 until 1800).map { item.copy(id = "c$it", tvgId = "news$it", name = "Канал $it") }
        val manyChannels = object : ChannelsRepository by channels {
            override suspend fun getChannels() = roster
            override suspend fun getChannelsByPlaylist(playlistId: String) = roster
        }
        val now = System.currentTimeMillis() / 1000 * 1000
        val hour = 3_600_000L
        val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        fun time(at: Long) = format.format(Instant.ofEpochMilli(at))
        var rolling = false
        val client = HttpClient(MockEngine {
            val body = buildString {
                append("<tv>")
                repeat(1800) { channel ->
                    val slots = if (rolling) 0..0 else -24..23
                    for (slot in slots) {
                        val start = now + slot * hour
                        append("<programme channel='news$channel' start='${time(start)}' stop='${time(start+hour)}'><title>${if (rolling) "Новая" else "Передача"} $channel</title><desc>Сохранённое описание.</desc></programme>")
                    }
                }
                append("</tv>")
            }
            respond(body)
        }) { install(HttpTimeout) }
        val start = System.nanoTime()
        try {
            val repository = AndroidEpgRepository(client, playlists, manyChannels, config, context)
            assertTrue(withTimeout(120_000) { repository.refreshIfStale(true) } is AppResult.Success)
            assertEquals(1800, repository.observeState().value.channelCount)
            assertEquals(86_400, repository.observeState().value.programmeCount)
            assertFalse(repository.observeState().value.isLoading)
            rolling = true
            assertTrue(withTimeout(120_000) { repository.refreshIfStale(true) } is AppResult.Success)
            assertEquals(1800, repository.observeState().value.channelCount)
            assertEquals(45_000, repository.observeState().value.programmeCount)
            assertEquals(1800, repository.getNowPlaying(roster, now).size)
            assertEquals("Новая 1799", repository.getNowPlaying(listOf(roster.last()), now)["c1799"]?.title)
            assertEquals(24, repository.getProgrammesInRange(listOf(roster.last()), now-25*hour, now).getValue("c1799").size)
            assertFalse(repository.observeState().value.isLoading)
            println("ANDROID_INDEX_1800 channels=1800 imported=86400 retained=45000 elapsedMs=${(System.nanoTime()-start)/1_000_000}")
        } finally { client.close() }
    }

    @Test fun settingsRemainUsableWhenReadingEpgCacheFails() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val client = HttpClient(MockEngine { error("Unexpected network") })
        val epg = object : EpgRepository by AndroidEpgRepository(client, playlists, channels, config, context) {
            override suspend fun refreshState(): Unit = error("Storage unavailable")
        }
        val settings = object : SettingsRepository {
            override suspend fun load() = AppSettings()
            override suspend fun save(settings: AppSettings) = Unit
            override fun observe() = flowOf(AppSettings())
            override suspend fun getAppInfo() = AppInfo()
            override suspend fun getLastChannelId(): String? = null
            override suspend fun setLastChannelId(channelId: String?) = Unit
            override suspend fun getLastCategoryId(): String? = null
            override suspend fun setLastCategoryId(categoryId: String?) = Unit
        }
        val tmdbSettings = object : TmdbSettingsStore {
            override suspend fun isEnabled() = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun getApiKey() = ""
            override suspend fun setApiKey(apiKey: String) = Unit
        }
        val tmdb = Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,
            arrayOf(TmdbRepository::class.java)) { _, _, _ -> error("Unexpected metadata call") } as TmdbRepository
        val reset = object : AppDataReset {
            override fun handlePendingReset() = Unit
            override fun requestResetAndRestart() = error("Unexpected reset")
        }
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val lifecycle = LifecycleRegistry().apply { create(); resume() }
        try {
            val component = DefaultSettingsComponent(DefaultComponentContext(lifecycle), settings,
                playlists, epg, tmdbSettings, tmdb, reset, NoOpAppAutostart(),
                Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> uncaught += error })
            component.dispatch(SettingsIntent.Load)
            withTimeout(10_000) { component.state.first { !it.isLoading && it.epgStateError != null } }
            component.dispatch(SettingsIntent.ShowChannelNumbersChanged(false))
            assertFalse(component.state.value.settings.showChannelNumbers)
            assertTrue("EPG cache failure must not escape the settings coroutine", uncaught.isEmpty())
        } finally { lifecycle.destroy(); client.close() }
    }

}
