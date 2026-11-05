package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.tvnext.app.core.common.log.Logger
import com.tvnext.app.core.common.mvi.MviComponent
import com.tvnext.app.core.common.mvi.MviEffect
import com.tvnext.app.core.common.mvi.MviIntent
import com.tvnext.app.core.common.mvi.MviState
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.fold
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.feature.settings.data.SettingsRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Special "category" shown on top of the channel list: the user's favorites. */
internal const val FAVORITES_CATEGORY = "__favorites"

/** EPG info shown for the selected (watching) channel. */
data class ChannelEpg(
    val current: EpgProgramme? = null,
    val next: EpgProgramme? = null,
    val upcoming: List<EpgProgramme> = emptyList(),
    val channelId: String? = null,
)

private data class ChannelProjections(
    val categories: List<String>,
    val categoryCounts: Map<String, Int>,
    val favoritesCount: Int,
    val filteredChannels: List<Channel>,
)

private fun buildChannelProjections(
    channels: List<Channel>,
    query: String,
    selectedCategory: String?,
): ChannelProjections {
    // One linear pass instead of distinct()+sorted()+groupingBy()+count()+filter().
    // This matters on low-end boxes because this function runs whenever the
    // playlist/filter snapshot changes. Sorting only the small category set is
    // much cheaper than repeatedly walking the full channel list.
    val categoryCounts = LinkedHashMap<String, Int>()
    val categorySet = LinkedHashSet<String>()
    val categoryMinNumber = mutableMapOf<String, Int>()  // Track min channel number per category
    var favoritesCount = 0
    val normalizedQuery = query.trim()
    val hasQuery = normalizedQuery.isNotEmpty()
    val filtered = ArrayList<Channel>(channels.size)

    for (channel in channels) {
        categorySet += channel.category
        categoryCounts[channel.category] = (categoryCounts[channel.category] ?: 0) + 1
        // Track minimum channel number for this category
        categoryMinNumber[channel.category] = 
            minOf(categoryMinNumber[channel.category] ?: Int.MAX_VALUE, channel.number)
        if (channel.isFavorite) favoritesCount++

        val matchesCategory = when (selectedCategory) {
            null -> true
            FAVORITES_CATEGORY -> channel.isFavorite
            else -> channel.category == selectedCategory
        }
        if (!matchesCategory) continue

        val matchesQuery = !hasQuery ||
            channel.name.contains(normalizedQuery, ignoreCase = true) ||
            channel.category.contains(normalizedQuery, ignoreCase = true)
        if (matchesQuery) filtered += channel
    }

    // Sort categories by minimum channel number in each category (ascending order)
    val categories = categorySet.toMutableList().sortedBy { categoryMinNumber[it] ?: Int.MAX_VALUE }
    return ChannelProjections(categories, categoryCounts, favoritesCount, filtered)
}

private fun buildFilteredChannels(
    channels: List<Channel>,
    query: String,
    selectedCategory: String?,
): List<Channel> {
    val normalizedQuery = query.trim()
    return channels.filter { channel ->
        val matchesCategory = when (selectedCategory) {
            null -> true
            FAVORITES_CATEGORY -> channel.isFavorite
            else -> channel.category == selectedCategory
        }
        val matchesQuery = normalizedQuery.isEmpty() ||
            channel.name.contains(normalizedQuery, ignoreCase = true) ||
            channel.category.contains(normalizedQuery, ignoreCase = true)
        matchesCategory && matchesQuery
    }
}

data class ChannelTimelineState(
    val channelId: String? = null,
    val loading: Boolean = false,
    val programmes: List<EpgProgramme> = emptyList(),
)

data class ChannelsState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasPlaylist: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val query: String = "",
    val selectedCategory: String? = null,
    val selectedChannelId: String? = null,
    /** Android TV cursor position. Does not start playback. */
    val focusedChannelId: String? = null,
    val focusedChannelIds: Map<String?, String> = emptyMap(),
    val error: AppError? = null,
    /** Precomputed channel-list projections. Rebuilt only when channels/filter inputs change. */
    val categories: List<String> = emptyList(),
    val filteredChannels: List<Channel> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val favoritesCount: Int = 0,
) : MviState {
}

sealed interface ChannelsIntent : MviIntent {
    data object Load : ChannelsIntent
    data object Refresh : ChannelsIntent
    data class SearchChanged(val query: String) : ChannelsIntent
    data class CategorySelected(val category: String?) : ChannelsIntent
    data class SelectChannel(val channelId: String) : ChannelsIntent
    /** Android TV: moves focus only, never changes playback. */
    data class FocusChannel(val channelId: String) : ChannelsIntent
    /** Android TV: IDs of channels currently rendered by the LazyColumn viewport. */
    data class VisibleChannelsChanged(val channelIds: Set<String>) : ChannelsIntent
    data class FavoriteToggled(val channelId: String) : ChannelsIntent
    data object ClosePlayer : ChannelsIntent
    data object TogglePlayback : ChannelsIntent

    /** Plays an archive (past) programme of the selected channel. */
    data class ArchiveProgrammeClicked(
        val programme: EpgProgramme,
        /** Android TV timeline: channel currently represented by the focused row. */
        val channelId: String? = null,
    ) : ChannelsIntent

    /** Android TV: opens the full-day EPG timeline panel for a channel (the row's clock icon). */
    data class ShowChannelTimeline(val channelId: String) : ChannelsIntent
    data object HideChannelTimeline : ChannelsIntent

    /** Internal: re-query EPG for the selected channel (e.g. after a refresh). */
    data object LoadEpg : ChannelsIntent
}

sealed interface ChannelsEffect : MviEffect {
    data class ShowError(val error: AppError) : ChannelsEffect
}

interface ChannelsComponent {
    fun setScreenVisible(visible: Boolean) {}
    val state: StateFlow<ChannelsState>
    /** High-frequency EPG hints are isolated from the main screen state. */
    val nowPlaying: StateFlow<Map<String, EpgProgramme>>
    /** Selected-channel EPG is isolated so guide updates never invalidate the channel list. */
    val playingEpg: StateFlow<ChannelEpg> get() = channelEpg
    val channelEpg: StateFlow<ChannelEpg>
    /** Transient channel-name overlay is isolated from the list state. */
    val channelNameVisible: StateFlow<Boolean>
    /** Timeline is isolated from the main channel-list state. */
    val timeline: StateFlow<ChannelTimelineState>
    val playbackController: PlaybackController
    /** Configured remote seek step for archived playback. */
    val archiveSeekStepMs: StateFlow<Long>
    val effects: kotlinx.coroutines.flow.SharedFlow<ChannelsEffect>
    fun dispatch(intent: ChannelsIntent)
    /** UI-level Back: closes drawer/timeline/categories/browse layers before app navigation. */
    fun setBackHandler(handler: () -> Boolean)
    fun consumeBack(): Boolean
}

class DefaultChannelsComponent(
    componentContext: ComponentContext,
    private val repository: ChannelsRepository,
    override val playbackController: PlaybackController,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val epgRepository: EpgRepository,
    private val archiveRepository: com.tvnext.app.core.iptv.archive.ArchiveRepository,
    mainContext: CoroutineContext,
    private val channelNameTimeoutMs: Long = CHANNEL_NAME_TIMEOUT_MS,
    /** Requests full-screen video after an archive stream has started. */
    private val onForceImmersive: () -> Unit = {},
) : ChannelsComponent,
    ComponentContext by componentContext,
    MviComponent<ChannelsState, ChannelsIntent, ChannelsEffect>(
        initialState = ChannelsState(),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    private var uiBackHandler: () -> Boolean = { false }

    override fun setBackHandler(handler: () -> Boolean) {
        uiBackHandler = handler
    }

    override fun consumeBack(): Boolean = uiBackHandler()

    private var screenVisible = false
    private var resumeChannelJob: Job? = null
    override fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        resumeChannelJob?.cancel()
        if (!visible) return
        resumeChannelJob = launch {
            val channels = state.map { it.channels }.first { it.isNotEmpty() }
            val current = playbackController.state.value.item
            val lastId = com.tvnext.app.core.player.AndroidPlaybackSession.ids.value.channelId
                ?: settingsRepository.getLastChannelId()
            val target = channels.firstOrNull { it.id == current?.id && current?.kind != PlaybackContentKind.VOD }
                ?: channels.firstOrNull { it.id == lastId } ?: channels.first()
            if (!screenVisible) return@launch
            val category = state.value.selectedCategory
            val keepCategory = category == null || category == target.category ||
                (category == FAVORITES_CATEGORY && target.isFavorite)
            if (!keepCategory) dispatch(ChannelsIntent.CategorySelected(target.category))
            dispatch(ChannelsIntent.FocusChannel(target.id))
            if (current?.id != target.id || current?.kind == PlaybackContentKind.VOD) {
                dispatch(ChannelsIntent.SelectChannel(target.id))
            } else setState { it.copy(selectedChannelId = target.id) }
            loadNowPlaying()
            loadEpgForSelected()
        }
    }

    /** Channel-name overlay timer; only one exists at a time (cancelled first). */
    private var channelNameTimer: Job? = null

    /**
     * In-flight EPG refresh jobs. Each of [loadNowPlaying]/[loadEpgForSelected]
     * runs database/EPG work off the UI thread; rapid channel switching (or the
     * observeVersion stream) must not stack up parallel copies of them. If a
     * previous scan is still running, it is cancelled and restarted from the
     * latest state instead.
     */
    private var nowPlayingJob: Job? = null
    private var warmNowPlayingJob: Job? = null
    private var epgForSelectedJob: Job? = null
    private var selectionSaveJob: Job? = null
    private var archiveRequestJob: Job? = null
    private var categoryEpgJob: Job? = null
    private var epgGeneration = 0L
    private data class EpgCacheKey(val channelId: String, val archiveAnchor: Long?)
    private data class CachedEpg(val at: Long, val value: ChannelEpg)
    private val selectedEpgCache = linkedMapOf<EpgCacheKey, CachedEpg>()
    private val timelineCachedAt = mutableMapOf<String, Long>()
    /** Kept outside StateFlow: scrolling must NOT invalidate the whole screen. */
    private var visibleChannelIds: Set<String> = emptySet()
    /** Cancels stale filtering work so rapid search/category changes never stack CPU jobs. */
    private var projectionJob: Job? = null
    private var projectionGeneration: Long = 0L
    private val _nowPlaying = kotlinx.coroutines.flow.MutableStateFlow<Map<String, EpgProgramme>>(emptyMap())
    override val nowPlaying: StateFlow<Map<String, EpgProgramme>> = _nowPlaying.asStateFlow()

    private val _playingEpg = MutableStateFlow(ChannelEpg())
    override val playingEpg: StateFlow<ChannelEpg> = _playingEpg.asStateFlow()
    private val _channelEpg = MutableStateFlow(ChannelEpg())
    override val channelEpg: StateFlow<ChannelEpg> = _channelEpg.asStateFlow()

    private val _channelNameVisible = MutableStateFlow(false)
    override val channelNameVisible: StateFlow<Boolean> = _channelNameVisible.asStateFlow()

    private val _timeline = MutableStateFlow(ChannelTimelineState())
    override val timeline: StateFlow<ChannelTimelineState> = _timeline.asStateFlow()
    private var timelineJob: Job? = null
    // Android TV timeline cache: opening/closing the drawer must not re-run the
    // expensive SQLite EPG range query for channels already visited.
    private val timelineCache = mutableMapOf<String, List<EpgProgramme>>()
    private val _archiveSeekStepMs = MutableStateFlow(60_000L)
    override val archiveSeekStepMs: StateFlow<Long> = _archiveSeekStepMs.asStateFlow()
    private var archiveTransitionJob: Job? = null
    private var handledArchiveEndAnchorMs: Long? = null

    init {
        if (isAndroidPlatform()) launch {
            var previous: Pair<PlaybackItem?, Long>? = null
            while (true) {
                val player = playbackController.state.value
                val item = player.item
                val at = if (item?.kind == PlaybackContentKind.ARCHIVE)
                    (item.archiveEpgAnchorMs ?: currentTimeMillis()) + player.positionMs else currentTimeMillis()
                val key = item to (at / 5_000)
                if (screenVisible && key != previous) {
                    previous = key
                    val channel = state.value.channels.firstOrNull { it.id == item?.id }
                    if (channel != null) runCatching {
                        val info = epgRepository.getNowNext(channel, at)
                        if (playbackController.state.value.item == item) {
                            _playingEpg.value = ChannelEpg(info.current, info.next, emptyList(), channel.id)
                        }
                    } else _playingEpg.value = ChannelEpg()
                }
                delay(1_000)
            }
        }
        // Re-query EPG whenever the cache is refreshed in the background.
        launch {
            epgRepository.observeVersion().collectLatest {
                // XMLTV imports can emit several cache/version notifications
                // close together. Coalesce them so the channel list does not
                // start the same SQLite/UI work repeatedly.
                delay(150)
                dispatch(ChannelsIntent.LoadEpg)
            }
        }
        // Keep the remote seek step live when the user changes it in Settings.
        launch {
            var previousDepth: Int? = null
            settingsRepository.observe().collect { settings ->
                _archiveSeekStepMs.value = settings.archiveSeekStepMs
                if (previousDepth != null && previousDepth != settings.archiveLimitDays) {
                    timelineCache.clear()
                    timelineCachedAt.clear()
                    _timeline.value.channelId?.let { loadChannelTimeline(it) }
                }
                previousDepth = settings.archiveLimitDays
            }
        }
        // Archive playback is a sequence, not a one-off VOD item. When one
        // archived programme ends, continue with the next archived programme
        // if it is still in the past; once wall-clock time has caught up with
        // the live schedule, switch the same channel back to LIVE.
        launch {
            playbackController.state.collect { playbackState ->
                val item = playbackState.item ?: return@collect
                if (item.kind != PlaybackContentKind.ARCHIVE ||
                    playbackState.state != PlaybackState.ENDED
                ) return@collect
                if (isAndroidPlatform()) return@collect // Android player continues by broadcast timestamp.
                val anchor = item.archiveEpgAnchorMs ?: return@collect
                if (handledArchiveEndAnchorMs == anchor) return@collect
                handledArchiveEndAnchorMs = anchor
                archiveTransitionJob?.cancel()
                archiveTransitionJob = launch {
                    continueArchiveOrLive(item.id, anchor)
                }
            }
        }
        // The component loads itself ONCE at creation. The screen must NOT
        // re-dispatch Load on every mount: a fullscreen F11 window switch
        // recreates the screen composition, and re-running the heavy EPG
        // scans there made the switch stall for seconds.
        if (isAndroidPlatform()) {
            launch {
                playbackController.state.map { it.item }.distinctUntilChanged().collect { item ->
                    if (item != null && item.kind != PlaybackContentKind.VOD && state.value.channels.any { it.id == item.id }) {
                        setState { it.copy(selectedChannelId = item.id) }
                        loadEpgForSelected()
                    } else {
                        epgForSelectedJob?.cancel()
                        _channelEpg.value = ChannelEpg()
                    }
                }
            }
            launch {
                while (true) {
                    delay(30_000)
                    if (screenVisible) {
                        loadNowPlaying()
                        loadEpgForSelected()
                    }
                }
            }
        }
        load()
    }

    override fun reduce(state: ChannelsState, intent: ChannelsIntent): ChannelsState = when (intent) {
        ChannelsIntent.Load -> state.copy(isLoading = true, error = null)
        ChannelsIntent.Refresh -> state.copy(isRefreshing = true, error = null)
        is ChannelsIntent.SearchChanged -> state.copy(query = intent.query)
        is ChannelsIntent.CategorySelected -> state.copy(
            // Cyclic toggle: clicking the already-selected category deselects it
            // (back to «Все»), matching the collapsible category list behaviour.
            selectedCategory = if (isAndroidPlatform()) intent.category
                else if (intent.category == state.selectedCategory) null else intent.category,
        )
        is ChannelsIntent.SelectChannel -> if (isAndroidPlatform() && state.channels.none { it.id == intent.channelId }) state
            else state.copy(selectedChannelId = intent.channelId, focusedChannelId = intent.channelId)
        is ChannelsIntent.FocusChannel -> state.copy(
            focusedChannelId = intent.channelId,
            focusedChannelIds = if (isAndroidPlatform()) state.focusedChannelIds + (state.selectedCategory to intent.channelId)
                else state.focusedChannelIds,
        )
        is ChannelsIntent.VisibleChannelsChanged -> state
        is ChannelsIntent.FavoriteToggled -> state
        ChannelsIntent.ClosePlayer -> state.copy(selectedChannelId = null)
        ChannelsIntent.TogglePlayback -> state
        is ChannelsIntent.ArchiveProgrammeClicked -> state
        is ChannelsIntent.ShowChannelTimeline -> state
        ChannelsIntent.HideChannelTimeline -> state
        ChannelsIntent.LoadEpg -> state
    }

    override fun handle(intent: ChannelsIntent) {
        when (intent) {
            ChannelsIntent.Load -> load()
            ChannelsIntent.Refresh -> refresh()
            is ChannelsIntent.SelectChannel -> selectChannel(intent.channelId)
            is ChannelsIntent.VisibleChannelsChanged -> {
                if (visibleChannelIds != intent.channelIds) {
                    visibleChannelIds = intent.channelIds
                    loadNowPlaying()
                }
            }
            is ChannelsIntent.FavoriteToggled -> persistFavorite(intent.channelId)
            ChannelsIntent.ClosePlayer -> {
                if (isAndroidPlatform()) {
                    archiveRequestJob?.cancel()
                    epgForSelectedJob?.cancel()
                    timelineJob?.cancel()
                    _timeline.value = ChannelTimelineState()
                }
                channelNameTimer?.cancel()
                archiveTransitionJob?.cancel()
                handledArchiveEndAnchorMs = null
                _nowPlaying.value = emptyMap()
                _channelEpg.value = ChannelEpg()
                _channelNameVisible.value = false
                playbackController.stop()
            }
            ChannelsIntent.TogglePlayback -> togglePlayback()
            is ChannelsIntent.FocusChannel -> {
                if (isAndroidPlatform()) loadEpgForSelected()
            }
            is ChannelsIntent.ArchiveProgrammeClicked -> playArchive(intent.programme, intent.channelId)
            is ChannelsIntent.ShowChannelTimeline -> loadChannelTimeline(intent.channelId)
            ChannelsIntent.HideChannelTimeline -> {
                timelineJob?.cancel()
                timelineJob = null
                _timeline.value = ChannelTimelineState()
            }
            ChannelsIntent.LoadEpg -> {
                timelineCache.clear()
                if (isAndroidPlatform()) {
                    epgGeneration++
                    timelineCachedAt.clear()
                    selectedEpgCache.clear()
                    val openId = _timeline.value.channelId
                    if (openId != null) loadChannelTimeline(openId)
                }
                // After a full EPG refresh do not leave the list with only the
                // handful of channels that happened to be visible. The native/
                // database layer resolves current programmes for the whole
                // playlist in small IN-clauses, so the channel list becomes
                // immediately consistent without a giant SQL variable list.
                loadNowPlaying(allChannels = true)
                loadEpgForSelected()
            }
            is ChannelsIntent.SearchChanged -> {
                rebuildFilteredAsync()
                // Поиск меняет отображаемый набор каналов — сбрасываем
                // stale visibleChannelIds и перезапрашиваем nowPlaying,
                // иначе EPG для найденных каналов останется пустым.
                if (isAndroidPlatform()) visibleChannelIds = emptySet()
                loadNowPlaying()
            }
            is ChannelsIntent.CategorySelected -> {
                rebuildFilteredAsync()
                // Смена категории: visibleChannelIds всё ещё хранит ID каналов
                // старой категории (LazyColumn ещё не перекомпонован). Без сброса
                // loadNowPlaying() отфильтрует по старым visible ID, и EPG для
                // новой категории не покажется.
                if (isAndroidPlatform()) visibleChannelIds = emptySet()
                // Даём LazyColumn время на перестройку перед загрузкой EPG
                if (isAndroidPlatform()) categoryEpgJob?.cancel()
                val job = launch {
                    delay(50)
                    loadNowPlaying()
                }
                if (isAndroidPlatform()) categoryEpgJob = job
                loadEpgForSelected()
                // Desktop: remember the last selected category so re-opening
                // the Channels tab restores the list to the same place
                // (Android TV behaviour is left untouched).
                if (!isAndroidPlatform()) {
                    launch {
                        runCatching { settingsRepository.setLastCategoryId(intent.category) }
                            .onFailure { Logger.e(TAG, "Failed to save last category", it) }
                    }
                }
            }
        }
    }

    private fun rebuildFilteredAsync() {
        projectionJob?.cancel()
        val generation = ++projectionGeneration
        val snapshot = state.value
        projectionJob = launch {
            val filtered = withContext(Dispatchers.Default) {
                buildFilteredChannels(snapshot.channels, snapshot.query, snapshot.selectedCategory)
            }
            if (generation == projectionGeneration) {
                setState { current ->
                    if (current.query == snapshot.query && current.selectedCategory == snapshot.selectedCategory) {
                        current.copy(filteredChannels = filtered)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private suspend fun projectionsFor(
        channels: List<Channel>,
        query: String,
        selectedCategory: String?,
    ): ChannelProjections = withContext(Dispatchers.Default) {
        buildChannelProjections(channels, query, selectedCategory)
    }

    /**
     * Desktop: восстанавливает фокус на канале. Приоритет:
     * 1) играющий канал (всегда виден пользователю — он сейчас смотрит его);
     * 2) последний канал/категория (из настроек).
     * Категория согласована с выбранным каналом: если играет/выбран канал из
     * категории «Балеты», список открывается в «Балетах» — иначе пользователь
     * листал другую категорию и при возврате канал оказывается скрыт («фокус на
     * канале не возвращается»). Если канал не выбран, вспоминается последняя
     * категория. Android TV не использует — возвращает nulls.
     */
    private suspend fun restoreLastView(
        cached: List<Channel>,
        playingItemId: String? = null,
    ): Pair<String?, String?> {
        val playingInCache = playingItemId?.takeIf { id -> cached.any { ch -> ch.id == id } }
        val targetChannelId = playingInCache
            ?: runCatching { settingsRepository.getLastChannelId() }.getOrNull()
                ?.takeIf { id -> cached.any { ch -> ch.id == id } }
        if (targetChannelId != null) {
            // Open the category of the playing/last channel so it stays visible
            // in the list ("focus returns to the channel"). Fallback: null = "All".
            val channelCategory = cached.firstOrNull { it.id == targetChannelId }?.category
            return channelCategory to targetChannelId
        }
        // Nothing playing / no last channel: restore the last-opened category.
        val savedCategory = runCatching { settingsRepository.getLastCategoryId() }.getOrNull()
            ?.takeIf { category ->
                category == FAVORITES_CATEGORY || cached.any { it.category == category }
            }
        return savedCategory to null
    }

    private suspend fun applyChannelsSnapshot(
        channels: List<Channel>,
        transform: (ChannelsState) -> ChannelsState = { it },
    ) {
        // Apply selection changes before building projections: otherwise a restored
        // category would be applied to the old category and the list would briefly
        // contain a projection for the wrong filter.
        val base = transform(state.value).copy(channels = channels)
        val projections = projectionsFor(channels, base.query, base.selectedCategory)
        setState {
            base.copy(
                categories = projections.categories,
                categoryCounts = projections.categoryCounts,
                favoritesCount = projections.favoritesCount,
                filteredChannels = projections.filteredChannels,
            )
        }
    }

    private fun load() {
        // load() is invoked directly from init (NOT via dispatch(Load)), so the
        // reduce() branch that sets isLoading=true never runs here. Set it
        // explicitly: otherwise the very first startup would show the
        // "channels not loaded" empty state during the whole first refresh
        // instead of a loading spinner.
        setState { it.copy(isLoading = true, error = null) }
        launch {
            runCatching {
                val configured = repository.isConfigured()
                setState { it.copy(hasPlaylist = configured) }
                if (!configured) {
                    applyChannelsSnapshot(emptyList()) { it.copy(isLoading = false) }
                } else {
                    val cached = repository.getChannels()
                    // Desktop: restore focus on the playing/last-selected channel
                    // (its category is opened too so it stays visible).
                    val (savedCategory, savedChannel) = restoreLastView(
                        cached,
                        playingItemId = playbackController.state.value.item?.id,
                    )
                    val playingItem = playbackController.state.value.item
                    when {
                        playingItem != null && cached.any { it.id == playingItem.id } -> {
                            // The component was recreated (e.g. after switching tabs) while a
                            // channel is still playing — restore the watching state.
                            applyChannelsSnapshot(cached) {
                                it.copy(
                                    isLoading = false,
                                    selectedChannelId = playingItem.id,
                                    selectedCategory = savedCategory,
                                    error = null,
                                )
                            }
                            showChannelName()
                            loadNowPlaying()
                            loadEpgForSelected()
                        }

                        playingItem != null -> {
                            // Something else is playing (a VOD film, or a removed
                            // channel). Only steal the playback when the user is
                            // actually ON the Channels tab: during a window
                            // recreation (fullscreen F11 switch) the whole tab
                            // hierarchy is rebuilt and background tabs must NOT
                            // stop the active tab's film.
                            if (lifecycle.state == Lifecycle.State.RESUMED) {
                                playbackController.stop()
                                applyChannelsSnapshot(cached) {
                                    it.copy(
                                        isLoading = false,
                                        selectedCategory = savedCategory,
                                        error = null,
                                    )
                                }
                                loadNowPlaying()
                                launch { autoOpenLastChannel(cached) }
                            } else {
                                applyChannelsSnapshot(cached) {
                                    it.copy(
                                        isLoading = false,
                                        selectedCategory = savedCategory,
                                        error = null,
                                    )
                                }
                                loadNowPlaying()
                            }
                        }

                        cached.isNotEmpty() -> {
                            applyChannelsSnapshot(cached) {
                                it.copy(
                                    isLoading = false,
                                    selectedCategory = savedCategory,
                                    selectedChannelId = savedChannel,
                                    error = null,
                                )
                            }
                            loadNowPlaying()
                            launch { autoOpenLastChannel(cached) }
                        }

                        else -> {
                            refreshChannels()
                        }
                    }
                }
            }.onFailure { error ->
                Logger.e(TAG, "Failed to load channels", error)
                setState { it.copy(isLoading = false, error = AppError.Database(error)) }
            }
            // NOTE: no EPG refresh here. ChannelsComponent is recreated on every
            // tab switch, and on Android TV the cold import of a multi-hundred-
            // MB XMLTV kills a low-end box within seconds (the OS silently
            // drops the process — "the app just closes"). The single deferred
            // app-level import lives in DefaultAppComponent (process lifetime);
            // the now-playing / guide data re-query via observeVersion() when it
            // lands.
        }
    }

    private fun refresh() {
        launch {
            setState { it.copy(isRefreshing = true, error = null) }
            refreshChannels()
        }
    }

    private suspend fun refreshChannels() {
        repository.refresh().fold(
            onSuccess = {
                val channels = repository.getChannels()
                // Desktop: after a manual refresh keep the user's current
                // selection; otherwise (re)anchor on the playing/last channel.
                val (savedCategory, savedChannel) = restoreLastView(
                    channels,
                    playingItemId = playbackController.state.value.item?.id,
                )
                applyChannelsSnapshot(channels) {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        hasPlaylist = channels.isNotEmpty(),
                        selectedCategory = it.selectedCategory ?: savedCategory,
                        selectedChannelId = it.selectedChannelId ?: savedChannel,
                        error = null,
                    )
                }
                // Keep EPG in sync with the refreshed playlist (TTL-guarded).
                epgRepository.refreshIfStale(force = false)
                loadNowPlaying()
                loadEpgForSelected()
                // First-run / empty-cache case: the channels were just imported,
                // nothing is playing yet. Auto-open the last channel (or the
                // first one on Android TV) exactly like load() does for a
                // populated cache — otherwise the user is left on an empty list
                // after the very first import.
                if (playbackController.state.value.item == null) {
                    launch { autoOpenLastChannel(channels) }
                }
            },
            onFailure = { error ->
                Logger.e(TAG, "Failed to refresh channels", null)
                setState { it.copy(isLoading = false, isRefreshing = false, error = error) }
            },
        )
    }

    private fun selectChannel(channelId: String) {
        val channel = state.value.channels.firstOrNull { it.id == channelId } ?: return
        if (!com.tvnext.app.core.iptv.domain.ParentalChannelGate.request(channel) {
                dispatch(ChannelsIntent.SelectChannel(channelId))
            }) return
        if (isAndroidPlatform()) {
            archiveRequestJob?.cancel()
            archiveTransitionJob?.cancel()
            handledArchiveEndAnchorMs = null
            selectionSaveJob?.cancel()
        }
        val saveJob = launch {
            runCatching { settingsRepository.setLastChannelId(channelId) }
                .onFailure { error ->
                    Logger.e(TAG, "Failed to save last channel id", error)
                }
            runCatching { historyRepository.add(channelId, WatchContentType.LIVE) }
                .onFailure { error ->
                    Logger.e(TAG, "Failed to record history", error)
                }
        }
        if (isAndroidPlatform()) selectionSaveJob = saveJob
        playbackController.play(
            PlaybackItem(id = channel.id, title = channel.name, url = channel.streamUrl),
        )
        showChannelName()
        loadEpgForSelected()
    }

    /**
     * Shows the channel-name overlay and (re)starts its 3-second hide timer.
     * The previous timer is always cancelled first, so a fast A → B → C switch
     * sequence never races: only the latest channel name is shown and it hides
     * exactly 3 s after the last switch.
     */
    private fun showChannelName() {
        channelNameTimer?.cancel()
        _channelNameVisible.value = true
        channelNameTimer = launch {
            delay(channelNameTimeoutMs)
            _channelNameVisible.value = false
        }
    }

    /**
     * Restores the last watched channel when `openLastChannel` is enabled and
     * nothing is playing yet (e.g. on app start / first entry to the tab).
     * On Android TV, when there is no last channel yet, the FIRST channel of the
     * list is opened instead — clicking «Каналы» from Home always starts
     * full-screen playback.
     */
    private suspend fun autoOpenLastChannel(channels: List<Channel>) {
        val settings = runCatching { settingsRepository.load() }.getOrNull() ?: return
        if (!settings.openLastChannel) return
        val lastId = runCatching { settingsRepository.getLastChannelId() }.getOrNull()
        val targetId = when {
            lastId != null && channels.any { it.id == lastId } -> lastId
            isAndroidPlatform() && channels.isNotEmpty() -> channels.first().id
            else -> null
        }
        if (targetId != null) {
            if (isAndroidPlatform() && playbackController.state.value.item != null) return
            dispatch(ChannelsIntent.SelectChannel(targetId))
        }
    }

    private fun togglePlayback() {
        when (playbackController.state.value.state) {
            PlaybackState.PLAYING,
            PlaybackState.READY,
            PlaybackState.BUFFERING,
            -> playbackController.pause()
            PlaybackState.PAUSED -> playbackController.resume()
            else -> Unit
        }
    }

    /**
     * Persists a favorite toggle. The target value is computed from the CURRENT
     * state (which `reduce` must not optimistically flip) — otherwise the value
     * written to the database would be inverted and the star would immediately
     * snap back («звезда не нажимается»).
     */
    private fun persistFavorite(channelId: String) {
        val current = state.value.channels.firstOrNull { it.id == channelId }?.isFavorite ?: return
        val target = !current
        launch {
            runCatching {
                repository.setFavorite(channelId, target)
            }.onSuccess {
                // Update the UI only after the database write succeeded, keeping
                // the state in sync with the authoritative source.
                val updatedChannels = state.value.channels.map { channel ->
                    if (channel.id == channelId) channel.copy(isFavorite = target) else channel
                }
                val snapshot = state.value
                val projections = projectionsFor(updatedChannels, snapshot.query, snapshot.selectedCategory)
                setState { current ->
                    current.copy(
                        channels = updatedChannels,
                        categories = projections.categories,
                        categoryCounts = projections.categoryCounts,
                        favoritesCount = projections.favoritesCount,
                        filteredChannels = projections.filteredChannels,
                    )
                }
            }.onFailure { error ->
                Logger.e(TAG, "Failed to save favorite", error)
                emitEffect(ChannelsEffect.ShowError(AppError.Database(error)))
            }
        }
    }

    /**
     * Android TV: full-day EPG for one channel (the clock-icon panel), spanning
     * past + future so the user can see what already aired today too, not just
     * "upcoming". Independent of [selectedChannelId] — browsing another
     * channel's schedule does not touch playback.
     */
    private fun loadChannelTimeline(channelId: String) {
        // A fast D-pad repeat can move through many channels before the previous
        // range query finishes. Never let those queries pile up.
        timelineJob?.cancel()
        timelineJob = null
        // Android TV only: a focused row can change rapidly while the user
        // scrolls. Re-use an already fetched timeline immediately instead of
        // flashing a spinner and hitting SQLite again.
        if (isAndroidPlatform()) {
            timelineCache[channelId]?.takeIf {
                currentTimeMillis() - (timelineCachedAt[channelId] ?: 0L) in 0L until 60_000L
            }?.let { cached ->
                _timeline.value = ChannelTimelineState(channelId, false, cached)
                return
            }
        }

        if (isAndroidPlatform()) {
            // Do not put the Android TV UI into a spinner state while the full
            // day range is being fetched. The channel list already has the
            // current programme in nowPlaying, so show that immediately and
            // replace it with the full timeline when the range query finishes.
            val immediate = _nowPlaying.value[channelId]
            _timeline.value = ChannelTimelineState(
                channelId = channelId,
                loading = false,
                programmes = immediate?.let(::listOf).orEmpty(),
            )
        } else {
            _timeline.value = ChannelTimelineState(channelId = channelId, loading = true)
        }
        val channel = state.value.channels.firstOrNull { it.id == channelId } ?: run {
            _timeline.value = ChannelTimelineState()
            return
        }
        val requestVersion = epgGeneration
        timelineJob = launch {
            runCatching {
                val now = currentTimeMillis()
                // This panel is a TELEPROGRAM/EPG, not an "archive window".
                // Ask the EPG repository for the real available range for this
                // channel and load that entire range, so the user can browse
                // backwards and forwards through everything the provider supplied.
                // Only if the cache has no range yet do we use the old bounded
                // fallback around the current time.
                val availableRange = runCatching {
                    epgRepository.getAvailableProgrammeRange(listOf(channel))
                }.getOrNull()
                // EPG may contain more history than the playable archive.
                // Honour the user's archive limit (0 = provider-declared depth)
                // while keeping whatever historical programmes are available.
                val userDays = if (isAndroidPlatform())
                    archiveRepository.userArchiveLimitDays().coerceAtLeast(0) else 0
                // User-selected days limit the history DISPLAY, not storage.
                // Automatic mode shows all real history available from EPG,
                // independently of the provider's playable catch-up depth.
                val cutoff = userDays.takeIf { it > 0 }?.let {
                    now - it.coerceAtMost(3650).toLong() * 24L * 60 * 60 * 1000
                }
                val historyFallback = now - 30L * 24 * 60 * 60 * 1000
                val from = cutoff?.let { maxOf(availableRange?.first ?: historyFallback, it) }
                    ?: (availableRange?.first ?: historyFallback)
                val to = availableRange?.last?.let { maxOf(it, now + TIMELINE_LOOKAHEAD_MS) }
                    ?: (now + TIMELINE_LOOKAHEAD_MS)
                val result = epgRepository.getProgrammesInRange(
                    channels = listOf(channel),
                    from = from,
                    to = to,
                )
                val programmes = (result[channel.id] ?: emptyList()).sortedBy { it.start }
                if (isAndroidPlatform()) {
                    if (requestVersion != epgGeneration) return@launch
                    if (timelineCache.size >= 12 && channelId !in timelineCache) {
                        val oldest = timelineCache.keys.first()
                        timelineCache.remove(oldest)
                        timelineCachedAt.remove(oldest)
                    }
                    timelineCache[channelId] = programmes
                    timelineCachedAt[channelId] = currentTimeMillis()
                }
                // The panel may have been closed (or re-opened for a different
                // channel) while this query was in flight — do not resurrect it
                // or clobber a newer request's result.
                if (_timeline.value.channelId != channelId) return@launch
                _timeline.value = ChannelTimelineState(channelId, false, programmes)
            }.onFailure { error ->
                Logger.e(TAG, "Failed to load channel timeline", error)
                if (_timeline.value.channelId == channelId) {
                    _timeline.value = ChannelTimelineState(channelId, false, emptyList())
                }
            }
        }
    }

    private fun loadEpgForSelected() {
        if (!isAndroidPlatform()) {
            loadDesktopEpgForSelected()
            return
        }
        val item = playbackController.state.value.item
        val targetId = state.value.focusedChannelId ?: item?.id
        val channel = state.value.channels.firstOrNull { it.id == targetId }
        if (channel == null) {
            epgForSelectedJob?.cancel()
            _channelEpg.value = ChannelEpg()
            return
        }
        epgForSelectedJob?.cancel()
        val key = EpgCacheKey(channel.id, item?.takeIf { it.id == channel.id }?.archiveEpgAnchorMs)
        val now = currentTimeMillis()
        val cached = selectedEpgCache[key]
        if (cached != null && now - cached.at in 0L until 30_000L &&
            (key.archiveAnchor != null || cached.value.current?.stop?.let { it > now } != false)) {
            _channelEpg.value = cached.value
            return
        }
        epgForSelectedJob?.cancel()
        if (_channelEpg.value.channelId != channel.id) _channelEpg.value = ChannelEpg(channelId = channel.id)
        val requestVersion = epgGeneration
        epgForSelectedJob = launch {
            runCatching {
                val anchor = key.archiveAnchor ?: now
                val nowNext = epgRepository.getNowNext(channel, anchor)
                val upcoming = epgRepository.getUpcomingProgrammes(channel, anchor, EpgRepository.UPCOMING_LIMIT)
                if ((state.value.focusedChannelId ?: playbackController.state.value.item?.id) != channel.id ||
                    requestVersion != epgGeneration) return@launch
                val result = ChannelEpg(nowNext.current, nowNext.next, upcoming, channel.id)
                if (selectedEpgCache.size >= 32) selectedEpgCache.remove(selectedEpgCache.keys.first())
                selectedEpgCache[key] = CachedEpg(now, result)
                _channelEpg.value = result
            }.onFailure { Logger.e(TAG, "Failed to load EPG", it) }
        }
    }

    private fun loadDesktopEpgForSelected() {
        val selectedId = state.value.selectedChannelId ?: return
        val channel = state.value.channels.firstOrNull { it.id == selectedId } ?: return
        // При смене категории выбранный канал может быть вне текущего фильтра
        // Не загружаем EPG для него, чтобы избежать рассогласования UI
        val filteredChannels = state.value.filteredChannels
        if (filteredChannels.none { it.id == selectedId }) {
            // Канал не в текущем фильтре (например, выбран в категории А, потом открыта категория Б)
            _channelEpg.value = ChannelEpg()
            return
        }
        // Never stack parallel scans: cancel a previous one and restart from
        // the latest selection. Rapid A→B→C channel switching otherwise queues
        // up three full EPG scans, each re-reading the whole table.
        epgForSelectedJob?.cancel()
        epgForSelectedJob = launch {
            runCatching {
                // While an ARCHIVE item is playing, the EPG panel anchors at the
                // archived programme's start: "current" = the film itself and
                // "next"/"upcoming" continue from it, instead of the live
                // schedule. For LIVE/VOD the anchor is "now".
                val playingItem = playbackController.state.value.item
                val anchor = playingItem
                    ?.takeIf { it.kind == PlaybackContentKind.ARCHIVE }
                    ?.archiveEpgAnchorMs
                    ?: currentTimeMillis()
                val nowNext = epgRepository.getNowNext(channel, anchor)
                val upcoming = epgRepository.getUpcomingProgrammes(
                    channel = channel,
                    from = anchor,
                    limit = EpgRepository.UPCOMING_LIMIT,
                )
                _channelEpg.value = ChannelEpg(
                    current = nowNext.current,
                    next = nowNext.next,
                    upcoming = upcoming,
                )
            }.onFailure { error ->
                Logger.e(TAG, "Failed to load EPG", error)
            }
        }
    }

    /**
     * Enriches the channel list with the currently playing EPG programme.
     * On Android TV the visible list is filtered to ONE category, so that
     * category is loaded FIRST (its "now playing" hints appear immediately),
     * and the rest of the playlist fills in afterwards in the background.
     * Desktop loads everything at once, as before.
     */
    private fun loadNowPlaying(allChannels: Boolean = false) {
        val channels = state.value.channels
        if (channels.isEmpty()) return
        // Cancel/restart from the latest channel/category snapshot. The EPG query
        // itself is now restricted to the requested channels, so Android TV no
        // longer performs a hidden second whole-playlist scan.
        val jobToReplace = if (allChannels) warmNowPlayingJob else nowPlayingJob
        jobToReplace?.cancel()
        val requestVersion = epgGeneration
        val job = launch {
            // A D-pad scroll can change the viewport several times in a single
            // frame. Wait briefly and use only the latest viewport snapshot.
            if (isAndroidPlatform()) delay(100)
            val snapshot = state.value
            val requested = if (!isAndroidPlatform()) {
                // Full list hydration is intentionally used only after an EPG
                // version change. The repository chunks the EPG channel ids,
                // so Android SQLite never receives >~500 bind variables.
                snapshot.channels
            } else {
                val visible = visibleChannelIds
                if (visible.isNotEmpty()) {
                    snapshot.channels.filter { it.id in visible }
                } else {
                    val category = snapshot.selectedCategory
                        ?: snapshot.channels.firstOrNull { it.id == snapshot.selectedChannelId }?.category
                    snapshot.channels.filter { channel ->
                        when (category) {
                            null -> true
                            FAVORITES_CATEGORY -> channel.isFavorite
                            else -> channel.category == category
                        }
                    }
                }
            }
            if (requested.isEmpty()) return@launch
            val queryStarted = currentTimeMillis()

            runCatching {
                epgRepository.getNowPlaying(if (isAndroidPlatform()) requested.take(128) else requested, currentTimeMillis())
            }.onSuccess { nowPlaying ->
                // One StateFlow emission per refresh. Avoid even that emission
                // when the visible EPG did not actually change: nowPlaying is
                // observed by every visible row on Android TV.
                if (isAndroidPlatform()) {
                    if (requestVersion != epgGeneration) return@onSuccess
                    val requestedIds = requested.map { it.id }.toSet()
                    val now = currentTimeMillis()
                    _nowPlaying.value = _nowPlaying.value.filter { (id, programme) ->
                        id !in requestedIds && programme.stop > now
                    }.plus(nowPlaying).entries.toList().takeLast(256).associate { it.toPair() }
                    com.tvnext.app.core.common.log.AndroidPerformanceCounters.set("epg.nowPlayingEntries", _nowPlaying.value.size.toLong())
                    com.tvnext.app.core.common.log.AndroidPerformanceCounters.set("epg.queryMs", currentTimeMillis() - queryStarted)
                    com.tvnext.app.core.common.log.AndroidPerformanceCounters.set("epg.selectedCache", selectedEpgCache.size.toLong())
                    com.tvnext.app.core.common.log.AndroidPerformanceCounters.set("epg.timelineCache", timelineCache.size.toLong())
                    com.tvnext.app.core.common.log.AndroidPerformanceCounters.increment("epg.queries")
                } else if (_nowPlaying.value != nowPlaying) {
                    _nowPlaying.value = nowPlaying
                }
            }.onFailure { error ->
                Logger.e(TAG, "Failed to load now-playing EPG", error)
            }
        }
        if (allChannels) warmNowPlayingJob = job else nowPlayingJob = job
    }

    /**
     * Plays a past programme of the selected channel through the provider's
     * catch-up mechanism (invoked from the EPG strip under the video).
     */
    /**
     * Continues an archive programme seamlessly. If the next programme has
     * already become LIVE, the catch-up sequence ends and the normal live URL
     * is opened. Otherwise the next programme is requested through catch-up.
     */
    private suspend fun continueArchiveOrLive(channelId: String, endedArchiveStartMs: Long) {
        val channel = state.value.channels.firstOrNull { it.id == channelId } ?: return
        val now = currentTimeMillis()
        val endedItem = playbackController.state.value.item

        // Do not use getNowNext(now) here: if the clock has already caught up
        // with a later programme, that would jump straight to LIVE and skip
        // archive programmes between the one that ended and the current time.
        // Start from the programme immediately after the one that just ended.
        val nextProgramme = epgRepository
            .getUpcomingProgrammes(channel, endedArchiveStartMs + 1L, 32)
            .asSequence()
            .filter { it.start >= endedArchiveStartMs }
            .firstOrNull()

        if (isAndroidPlatform() && playbackController.state.value.item != endedItem) return
        if (nextProgramme == null) {
            // No known next programme: safest behaviour is to return to LIVE.
            playbackController.play(
                PlaybackItem(
                    id = channel.id,
                    title = channel.name,
                    url = channel.streamUrl,
                    kind = PlaybackContentKind.LIVE,
                ),
            )
            showChannelName()
            loadEpgForSelected()
            return
        }

        when {
            nextProgramme.start <= now && now < nextProgramme.stop -> {
                // Wall clock has caught up: the next programme is LIVE now.
                playbackController.play(
                    PlaybackItem(
                        id = channel.id,
                        title = channel.name,
                        url = channel.streamUrl,
                        kind = PlaybackContentKind.LIVE,
                    ),
                )
                showChannelName()
                loadEpgForSelected()
            }

            nextProgramme.stop <= now -> {
                // We are still behind LIVE. Play this already-finished programme
                // from catch-up and continue the chain on its ENDED event.
                val url = archiveRepository.buildArchiveUrl(
                    channel,
                    nextProgramme.start,
                    nextProgramme.stop,
                ) ?: return
                if (!archiveRepository.isArchiveUrlPlayable(
                        channel,
                        nextProgramme.start,
                        nextProgramme.stop,
                    )
                ) return
                if (isAndroidPlatform() && playbackController.state.value.item != endedItem) return

                playbackController.play(
                    PlaybackItem(
                        id = channel.id,
                        title = "${channel.name} • Архив • ${formatDateTime(nextProgramme.start)}",
                        url = url,
                        kind = PlaybackContentKind.ARCHIVE,
                        archiveEpgAnchorMs = nextProgramme.start,
                    ),
                )
                loadEpgForSelected()
            }

            else -> {
                // There is a gap between the archived programme and the next
                // scheduled programme. Nothing valid can be played from archive
                // for that gap, so return to the live stream.
                playbackController.play(
                    PlaybackItem(
                        id = channel.id,
                        title = channel.name,
                        url = channel.streamUrl,
                        kind = PlaybackContentKind.LIVE,
                    ),
                )
                showChannelName()
                loadEpgForSelected()
            }
        }
    }

    private fun playArchive(programme: EpgProgramme, channelId: String? = null) {
        val targetChannelId = channelId ?: state.value.selectedChannelId
        val channel = state.value.channels.firstOrNull { it.id == targetChannelId }
            ?: return
        if (!com.tvnext.app.core.iptv.domain.ParentalChannelGate.request(channel) {
                dispatch(ChannelsIntent.ArchiveProgrammeClicked(programme, channelId))
            }) return
        if (isAndroidPlatform()) {
            archiveRequestJob?.cancel()
            archiveTransitionJob?.cancel()
        }
        val requestJob = launch {
            val capability = runCatching { archiveRepository.getCapability(channel) }.getOrNull()
            if (capability == null || !capability.isPlayable) {
                Logger.d(TAG, "Archive unavailable for ${channel.name} (${capability?.status})")
                return@launch
            }
            val archiveEnd = if (isAndroidPlatform()) minOf(programme.stop, currentTimeMillis()) else programme.stop
            val url = archiveRepository.buildArchiveUrl(channel, programme.start, archiveEnd)
                ?: return@launch
            // Pre-flight: some providers DECLARE catch-up but serve an empty
            // playlist — do not hand the player a dead stream.
            if (!archiveRepository.isArchiveUrlPlayable(channel, programme.start, archiveEnd)) {
                Logger.d(TAG, "Archive URL not playable for ${channel.name}")
                return@launch
            }
            val title = "${channel.name} • Архив • ${formatDateTime(programme.start)}"
            handledArchiveEndAnchorMs = null
            if (isAndroidPlatform()) setState { it.copy(selectedChannelId = channel.id) }
            playbackController.play(
                PlaybackItem(
                    id = channel.id,
                    title = title,
                    url = url,
                    kind = PlaybackContentKind.ARCHIVE,
                    archiveEpgAnchorMs = programme.start,
                ),
            )
            // An archive stream opens full-screen (immersive), never in the
            // browse/windowed layout.
            onForceImmersive()
            showChannelName()
            // Re-anchor the EPG panel to the archived programme.
            loadEpgForSelected()
        }
        if (isAndroidPlatform()) archiveRequestJob = requestJob
    }

    private companion object {
        const val TAG = "Channels"
        /** Channel-name overlay duration (Windows requirement: 3 seconds). */
        const val CHANNEL_NAME_TIMEOUT_MS = 3_000L
        /** EPG timeline panel window: how far back/forward from "now" to fetch. */
        const val TIMELINE_LOOKBACK_MS = 12L * 60 * 60 * 1000
        const val TIMELINE_LOOKAHEAD_MS = 16L * 60 * 60 * 1000
    }
}
