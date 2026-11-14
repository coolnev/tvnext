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
import com.tvnext.app.core.player.PlaybackState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            _state.value = PlayerState(item = item, state = PlaybackState.PLAYING)
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

    private class FakeChannelsRepository(private val extra: List<Channel> = emptyList()) : ChannelsRepository {
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

        override suspend fun getChannels(): List<Channel> = (channels + extra).map {
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
        val settingsFlow = MutableStateFlow(AppSettings(openLastChannel = false))
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
        private var lastCategoryId: String? = null
        override suspend fun getLastCategoryId(): String? = lastCategoryId
        override suspend fun setLastCategoryId(categoryId: String?) { lastCategoryId = categoryId }
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

    private open class FakeEpgRepository : EpgRepository {
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
        repository: ChannelsRepository = FakeChannelsRepository(),
        epg: EpgRepository = FakeEpgRepository(),
    ): ChannelsComponent {
        val lifecycle = LifecycleRegistry().apply {
            create()
            resume()
        }
        val context = DefaultComponentContext(lifecycle = lifecycle)
        val component = DefaultChannelsComponent(
            componentContext = context,
            repository = repository,
            playbackController = controller,
            settingsRepository = settings,
            historyRepository = history,
            epgRepository = epg,
            archiveRepository = FakeArchiveRepository(),
            mainContext = Dispatchers.Unconfined,
            channelNameTimeoutMs = channelNameTimeoutMs,
        )
        // Construction starts the load. Wait for real async projections;
        // dispatching a second Load races selection and duplicates history.
        runBlocking {
            withTimeout(5_000) {
                component.state.first { !it.isLoading && it.channels.isNotEmpty() }
                val lastId = settings.getLastChannelId()
                if (settings.load().openLastChannel && lastId != null) {
                    controller.state.first { it.item?.id == lastId }
                }
            }
        }
        return component
    }

    @Test fun changingCategoryClosesTheOldProgrammePaneAndLateEpgCannotRestoreIt()=runBlocking<Unit> {
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished=kotlinx.coroutines.CompletableDeferred<Unit>()
        val epg=object:FakeEpgRepository() {
            override suspend fun getProgrammesInRange(channels:List<Channel>,from:Long,to:Long):Map<String,List<EpgProgramme>> {
                entered.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {release.await();finished.complete(Unit)}
                return emptyMap()
            }
        }
        val component=createComponent(FakePlaybackController(),epg=epg)
        component.dispatch(ChannelsIntent.ShowChannelTimeline("7"))
        withTimeout(5000){entered.await()}
        component.dispatch(ChannelsIntent.CategorySelected("Другая категория"))
        assertNull(component.timeline.value.channelId)
        release.complete(Unit)
        withTimeout(5000){finished.await()}
        delay(50)
        assertNull(component.timeline.value.channelId)
    }

    @Test fun refreshingAnOpenTimelineNeverPublishesAnEmptyOrSingleRowPlaceholder() = runBlocking<Unit> {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val now = System.currentTimeMillis()
        val original = (-4..4).map { n -> EpgProgramme("7", now + n * 3_600_000L,
            now + (n + 1) * 3_600_000L, "Передача $n") }
        var queries = 0
        val epg = object : FakeEpgRepository() {
            override suspend fun getProgrammesInRange(channels: List<Channel>, from: Long, to: Long): Map<String, List<EpgProgramme>> {
                if (++queries > 1) { entered.complete(Unit); release.await() }
                return mapOf("7" to original)
            }
        }
        val component = createComponent(FakePlaybackController(), epg = epg)
        component.dispatch(ChannelsIntent.ShowChannelTimeline("7"))
        withTimeout(5_000) { component.timeline.first { it.programmes.size == original.size } }
        component.dispatch(ChannelsIntent.ShowChannelTimeline("7"))
        withTimeout(5_000) { entered.await() }
        assertEquals(original, component.timeline.value.programmes)
        assertTrue(!component.timeline.value.loading)
        release.complete(Unit)
    }

    @Test fun reopeningChannelsDropsUnplayedTimelineAndReturnsFocusToPlayback() = runBlocking<Unit> {
        val controller = FakePlaybackController()
        val other = Channel("8", "Другой канал", 2, "Другая категория", "https://example.com/8")
        val component = createComponent(controller, repository = FakeChannelsRepository(listOf(other)))
        component.dispatch(ChannelsIntent.SelectChannel("7"))
        component.setScreenVisible(true)
        component.dispatch(ChannelsIntent.FocusChannel("8"))
        component.dispatch(ChannelsIntent.ShowChannelTimeline("8"))
        withTimeout(5_000) { component.timeline.first { it.channelId == "8" } }
        component.setScreenVisible(false)
        assertNull(component.timeline.value.channelId)
        component.setScreenVisible(true)
        withTimeout(5_000) { component.state.first { it.focusedChannelId == "7" } }
        assertEquals("7", controller.state.value.item?.id)
        assertEquals("7", component.state.value.selectedChannelId)
        assertNull(component.timeline.value.channelId)
    }

    @Test fun reopeningChannelsKeepsTheArchiveThatIsActuallyPlaying() = runBlocking<Unit> {
        val controller = FakePlaybackController()
        val component = createComponent(controller)
        val archive = PlaybackItem("7", "Архив", "https://example.com/archive",
            com.tvnext.app.core.player.PlaybackContentKind.ARCHIVE, 1_800_000_000_000L)
        controller.play(archive)
        component.setScreenVisible(true)
        component.dispatch(ChannelsIntent.ShowChannelTimeline("7"))
        component.setScreenVisible(false)
        component.setScreenVisible(true)
        withTimeout(5_000) { component.state.first { it.focusedChannelId == "7" } }
        assertEquals(archive, controller.state.value.item)
        assertNull(component.timeline.value.channelId)
    }

    @Test
    fun selectingChannelSetsSelectedChannelIdAndStartsPlayback() {
        val controller = FakePlaybackController()
        val component = createComponent(controller)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertEquals("7", component.state.value.selectedChannelId)
        assertEquals("7", controller.playedItem?.id)
    }

    @Test
    fun closingPlayerClearsSelectedChannelAndStopsPlayback() {
        val controller = FakePlaybackController()
        val component = createComponent(controller)
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


        assertEquals("7", component.state.value.selectedChannelId)
        assertEquals("7", controller.playedItem?.id)
    }

    @Test
    fun lastChannelIsSelectedWithoutStartingPlaybackWhenAutoOpenIsDisabled() {
        val controller = FakePlaybackController()
        val settings = FakeSettingsRepository().apply {
            settingsFlow.value = AppSettings(openLastChannel = false)
            lastChannelId = "7"
        }
        val component = createComponent(controller, settings)


        assertEquals("7", component.state.value.selectedChannelId)
        assertNull(controller.playedItem)
    }

    @Test
    fun selectingChannelRecordsHistory() {
        val controller = FakePlaybackController()
        val history = FakeHistoryRepository()
        val component = createComponent(controller, history = history)

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertEquals(listOf("7"), history.recorded)
    }

    @Test
    fun favoriteTogglePersistsToRepository() = runBlocking {
        val controller = FakePlaybackController()
        val component = createComponent(controller)

        component.dispatch(ChannelsIntent.FavoriteToggled("7"))

        withTimeout(5_000) { component.state.first { state -> state.channels.first { it.id == "7" }.isFavorite } }

        // Toggling off removes it again.
        component.dispatch(ChannelsIntent.FavoriteToggled("7"))
        withTimeout(5_000) { component.state.first { state -> !state.channels.first { it.id == "7" }.isFavorite } }
        Unit
    }

    @Test
    fun selectingChannelShowsChannelNameOverlay() {
        val component = createComponent(FakePlaybackController())

        component.dispatch(ChannelsIntent.SelectChannel("7"))

        assertTrue(component.channelNameVisible.value)
    }

    @Test
    fun channelNameHidesAfterTimeout() = runBlocking {
        val component = createComponent(FakePlaybackController(), channelNameTimeoutMs = 60)
        component.dispatch(ChannelsIntent.SelectChannel("7"))
        assertTrue(component.channelNameVisible.value)

        delay(200)

        assertTrue(!component.channelNameVisible.value, "name must hide after the timeout")
    }

    @Test
    fun repeatedSwitchRestartsTheTimer() = runBlocking {
        // A → B → C analogue: a new switch restarts the timer, so the name stays
        // visible for the full timeout measured from the LAST switch.
        val component = createComponent(FakePlaybackController(), channelNameTimeoutMs = 200)
        component.dispatch(ChannelsIntent.SelectChannel("7"))

        delay(120)
        component.dispatch(ChannelsIntent.SelectChannel("7")) // restart
        assertTrue(component.channelNameVisible.value)

        delay(120) // 240 ms after the first switch, 120 ms after the restart
        assertTrue(component.channelNameVisible.value, "name must survive the restarted timer")

        delay(150) // 270 ms after the restart — past the 200 ms timeout
        assertTrue(!component.channelNameVisible.value)
    }

    @Test
    fun closingPlayerHidesChannelName() {
        val component = createComponent(FakePlaybackController())
        component.dispatch(ChannelsIntent.SelectChannel("7"))

        component.dispatch(ChannelsIntent.ClosePlayer)

        assertTrue(!component.channelNameVisible.value)
    }
}
