package com.tvnext.app.feature.channels.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.resume
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.archive.ArchiveCapability
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.archive.ArchiveStatus
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.EpgState
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.HistoryEntry
import com.tvnext.app.core.iptv.data.NowNext
import com.tvnext.app.core.iptv.data.ProgrammeSearchHit
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import com.tvnext.app.core.player.PlayerState
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.domain.AppInfo
import com.tvnext.app.feature.settings.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelsComponentTest {

    private class FakePlaybackController : PlaybackController {
        private val _state = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = _state.asStateFlow()
        var playedItem: PlaybackItem? = null
        var stopped = false

        override fun play(item: PlaybackItem) {
            playedItem = item
        }

        override fun pause() = Unit
        override fun resume() = Unit

        override fun stop() {
            stopped = true
            _state.value = PlayerState()
        }

        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun release() = Unit
    }

    private class FakeChannelsRepository : ChannelsRepository {
        private val favorites = mutableSetOf<String>()
        private val channels = listOf(
            Channel(
                id = "7",
                name = "Тестовый канал",
                number = 1,
                category = "Тест",
                streamUrl = "https://example.com/7",
            ),
        )

        override suspend fun getChannels(): List<Channel> = channels.map {
            if (favorites.contains(it.id)) it.copy(isFavorite = true) else it
        }

        override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> = getChannels()

        override suspend fun isConfigured(): Boolean = true
        override suspend fun refresh(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getFavoriteIds(): Set<String> = favorites.toSet()
        override suspend fun setFavorite(channelId: String, favorite: Boolean) {
            if (favorite) favorites.add(channelId) else favorites.remove(channelId)
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        val settingsFlow = MutableStateFlow(AppSettings())
        var lastChannelId: String? = null

        override suspend fun load(): AppSettings = settingsFlow.value
        override suspend fun save(settings: AppSettings) {
            settingsFlow.value = settings
        }

        override fun observe(): Flow<AppSettings> = settingsFlow
        override suspend fun getAppInfo(): AppInfo = AppInfo()
        override suspend fun getLastChannelId(): String? = lastChannelId
        override suspend fun setLastChannelId(channelId: String?) {
            lastChannelId = channelId
        }
    }

    private class FakeHistoryRepository : HistoryRepository {
        val recorded = mutableListOf<String>()

        override suspend fun add(itemId: String, contentType: WatchContentType) {
            recorded.add(itemId)
        }

        override suspend fun getRecent(limit: Int): List<HistoryEntry> = emptyList()
        override suspend fun getRecentByType(
            contentType: WatchContentType,
            limit: Int,
        ): List<HistoryEntry> = emptyList()
        override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = flowOf(emptyList())
        override suspend fun clear() = Unit
    }

    private class FakeEpgRepository : EpgRepository {
        override fun observeState(): StateFlow<EpgState> = MutableStateFlow(EpgState())
        override fun observeVersion(): Flow<Long> = flowOf(0L)
        override suspend fun refreshState() = Unit
        override suspend fun refreshIfStale(force: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshPlaylist(playlistId: String, force: Boolean): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun getUpcomingProgrammes(
            channel: Channel,
            from: Long,
            limit: Int,
        ): List<EpgProgramme> = emptyList()

        override suspend fun getNowNext(channel: Channel, at: Long): NowNext = NowNext()
        override suspend fun getNowNextForChannels(
            channels: List<Channel>,
            at: Long,
        ): Map<String, NowNext> = emptyMap()
        override suspend fun getNowPlaying(
            channels: List<Channel>,
            at: Long,
        ): Map<String, EpgProgramme> = emptyMap()

        override suspend fun getProgrammesInRange(
            channels: List<Channel>,
            from: Long,
            to: Long,
        ): Map<String, List<EpgProgramme>> = emptyMap()

        override suspend fun getAvailableProgrammeRange(channels: List<Channel>): LongRange? = null

    override suspend fun getMatchingDiagnostics() = null
        override suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit> = emptyList()
    }

    private class FakeArchiveRepository : ArchiveRepository {
        override suspend fun getCapability(channel: Channel): ArchiveCapability =
            ArchiveCapability(channelId = channel.id, status = ArchiveStatus.NONE)

        override suspend fun getCapabilitiesCached(
            channels: List<Channel>,
        ): Map<String, ArchiveCapability> = emptyMap()

        override suspend fun isArchiveAvailable(channel: Channel): Boolean = false

        override fun buildArchiveUrl(channel: Channel, startMs: Long, endMs: Long): String? = null

        override suspend fun userArchiveLimitDays(): Int = 0

        override suspend fun isArchiveUrlPlayable(
            channel: Channel,
            startMs: Long,
            endMs: Long,
        ): Boolean = false

        override suspend fun effectiveArchiveDepthMs(channel: Channel): Long? = null
    }

    private fun createComponent(
        controller: PlaybackController,
        settings: SettingsRepository = FakeSettingsRepository(),
        history: HistoryRepository = FakeHistoryRepository(),
        channelNameTimeoutMs: Long = 3_000L,
    ): ChannelsComponent {
        val lifecycle = LifecycleRegistry().apply {
            create()
            resume()
        }
        val context = DefaultComponentContext(lifecycle = lifecycle)
        return DefaultChannelsComponent(
            componentContext = context,
            repository = FakeChannelsRepository(),
            playbackController = controller,
            settingsRepository = settings,
            historyRepository = history,
            epgRepository = FakeEpgRepository(),
            archiveRepository = FakeArchiveRepository(),
            mainContext = Dispatchers.Unconfined,
            channelNameTimeoutMs = channelNameTimeoutMs,
        )
    }

    @Test
    fun selectingChannelSetsSelectedChannelIdAndStartsPlayback() {
        val controller = FakePlaybackController()
        val component = createComponent(controller)
        component.dispatch(ChannelsIntent.Load)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertEquals("7", component.state.value.selectedChannelId)
        assertEquals("7", controller.playedItem?.id)
    }

    @Test
    fun closingPlayerClearsSelectedChannelAndStopsPlayback() {
        val controller = FakePlaybackController()
        val component = createComponent(controller)
        component.dispatch(ChannelsIntent.Load)
        component.dispatch(ChannelsIntent.SelectChannel("7"))

        component.dispatch(ChannelsIntent.ClosePlayer)

        assertNull(component.state.value.selectedChannelId)
        assertTrue(controller.stopped)
    }

    @Test
    fun searchIntentUpdatesQuery() {
        val component = createComponent(FakePlaybackController())

        component.dispatch(ChannelsIntent.SearchChanged("спорт"))

        assertEquals("спорт", component.state.value.query)
    }

    @Test
    fun selectingChannelPersistsLastChannelId() {
        val controller = FakePlaybackController()
        val settings = FakeSettingsRepository()
        val component = createComponent(controller, settings)
        component.dispatch(ChannelsIntent.Load)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertEquals("7", settings.lastChannelId)
    }

    @Test
    fun openLastChannelStartsPlaybackOfLastChannel() {
        val controller = FakePlaybackController()
        val settings = FakeSettingsRepository().apply {
            settingsFlow.value = AppSettings(openLastChannel = true)
            lastChannelId = "7"
        }
        val component = createComponent(controller, settings)

        component.dispatch(ChannelsIntent.Load)

        assertEquals("7", component.state.value.selectedChannelId)
        assertEquals("7", controller.playedItem?.id)
    }

    @Test
    fun openLastChannelIgnoredWhenDisabled() {
        val controller = FakePlaybackController()
        val settings = FakeSettingsRepository().apply {
            settingsFlow.value = AppSettings(openLastChannel = false)
            lastChannelId = "7"
        }
        val component = createComponent(controller, settings)

        component.dispatch(ChannelsIntent.Load)

        assertNull(component.state.value.selectedChannelId)
        assertNull(controller.playedItem)
    }

    @Test
    fun selectingChannelRecordsHistory() {
        val controller = FakePlaybackController()
        val history = FakeHistoryRepository()
        val component = createComponent(controller, history = history)
        component.dispatch(ChannelsIntent.Load)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertEquals(listOf("7"), history.recorded)
    }

    @Test
    fun favoriteTogglePersistsToRepository() {
        val controller = FakePlaybackController()
        val component = createComponent(controller)
        component.dispatch(ChannelsIntent.Load)

        component.dispatch(ChannelsIntent.FavoriteToggled("7"))

        assertTrue(component.state.value.channels.first { it.id == "7" }.isFavorite)

        // Toggling off removes it again.
        component.dispatch(ChannelsIntent.FavoriteToggled("7"))
        assertTrue(!component.state.value.channels.first { it.id == "7" }.isFavorite)
    }

    @Test
    fun selectingChannelShowsChannelNameOverlay() {
        val component = createComponent(FakePlaybackController())
        component.dispatch(ChannelsIntent.Load)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertTrue(component.state.value.channelNameVisible)
    }

    @Test
    fun channelNameHidesAfterTimeout() = runBlocking {
        val component = createComponent(FakePlaybackController(), channelNameTimeoutMs = 60)
        component.dispatch(ChannelsIntent.Load)
        component.dispatch(ChannelsIntent.SelectChannel("7"))
        assertTrue(component.state.value.channelNameVisible)

        delay(200)

        assertTrue(!component.state.value.channelNameVisible, "name must hide after the timeout")
    }

    @Test
    fun repeatedSwitchRestartsTheTimer() = runBlocking {
        // A → B → C analogue: a new switch restarts the timer, so the name stays
        // visible for the full timeout measured from the LAST switch.
        val component = createComponent(FakePlaybackController(), channelNameTimeoutMs = 200)
        component.dispatch(ChannelsIntent.Load)
        component.dispatch(ChannelsIntent.SelectChannel("7"))

        delay(120)
        component.dispatch(ChannelsIntent.SelectChannel("7")) // restart
        assertTrue(component.state.value.channelNameVisible)

        delay(120) // 240 ms after the first switch, 120 ms after the restart
        assertTrue(component.state.value.channelNameVisible, "name must survive the restarted timer")

        delay(150) // 270 ms after the restart — past the 200 ms timeout
        assertTrue(!component.state.value.channelNameVisible)
    }

    @Test
    fun closingPlayerHidesChannelName() {
        val component = createComponent(FakePlaybackController())
        component.dispatch(ChannelsIntent.Load)
        component.dispatch(ChannelsIntent.SelectChannel("7"))

        component.dispatch(ChannelsIntent.ClosePlayer)

        assertTrue(!component.state.value.channelNameVisible)
    }
}
