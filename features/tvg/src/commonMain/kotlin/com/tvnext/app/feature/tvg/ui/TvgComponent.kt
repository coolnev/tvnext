package com.tvnext.app.feature.tvg.ui

import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.tvnext.app.core.common.log.Logger
import com.tvnext.app.core.common.mvi.MviComponent
import com.tvnext.app.core.common.mvi.MviEffect
import com.tvnext.app.core.common.mvi.MviIntent
import com.tvnext.app.core.common.mvi.MviState
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.fold
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.common.util.startOfDay
import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.archive.ArchiveDepthChanges
import com.tvnext.app.core.iptv.archive.ArchiveStatus
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.data.ScheduledRepository
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Selected programme shown in the info panel. */
data class ProgrammeInfo(
    val channel: Channel,
    val programme: EpgProgramme,
)

/** High-frequency TV-guide focus state. Kept separate from the large grid state
 * so D-pad navigation does not emit the whole EPG/channel state on every key. */
data class TvgFocusState(
    val channelId: String? = null,
    val programmeStart: Long? = null,
    val info: ProgrammeInfo? = null,
)

data class TvgState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val channels: List<Channel> = emptyList(),
    /** Каналы, отфильтрованные по выбранной категории. */
    val filteredChannels: List<Channel> = emptyList(),
    /** Выбранная категория (null = все каналы). */
    val selectedCategory: String? = null,
    /** Playlist id → name (для подписи «Провайдер» в деталях передачи). */
    val playlistNames: Map<String, String> = emptyMap(),
    /** Programmes of the visible grid window per channel id. */
    val programmes: Map<String, List<EpgProgramme>> = emptyMap(),
    /** EPG cache version represented by [programmes]. */
    val programmesEpgVersion: Long = -1L,
    /** Current programme per channel. Small hot-path index used by D-pad focus. */
    val currentProgrammes: Map<String, EpgProgramme> = emptyMap(),
    /** Grid window start (epoch millis, aligned to the local day). */
    val from: Long = 0L,
    /** Grid window end (exclusive). */
    val to: Long = 0L,
    /** Day offset relative to today: 0 = today, -1 = yesterday, +1 = tomorrow. */
    val selectedDayOffset: Int = 0,
    /** Oldest day with actually cached EPG, capped by the user/provider depth. */
    val minArchiveDayOffset: Int = -365,
    /**
     * Cached archive availability per channel id (NONE / AVAILABLE / UNKNOWN).
     * Loaded without any network (cache only) — drives which past programmes
     * can be played as archive.
     */
    val archiveStatus: Map<String, ArchiveStatus> = emptyMap(),
    /** Keys (playlistId|channelId|start) of programmes the user scheduled. */
    val scheduledKeys: Set<String> = emptySet(),
    /** Currently focused channel row (drives the highlight + details panel). */
    val focusChannelId: String? = null,
    /** Currently focused programme start (the highlighted grid cell). */
    val focusProgrammeStart: Long? = null,
    val info: ProgrammeInfo? = null,
    val error: AppError? = null,
    /** Transient user-facing message (e.g. «Архив передачи недоступен»). */
    val message: String? = null,
) : MviState

sealed interface TvgIntent : MviIntent {
    data object Load : TvgIntent
    data object Refresh : TvgIntent
    /** Фильтр по категории каналов. */
    data class CategorySelected(val category: String?) : TvgIntent
    /**
     * Moves the grid by [delta] days RELATIVE to the CURRENT state. The UI
     * buttons send a delta (not an absolute offset) so that fast repeated
     * clicks always accumulate: `−7` twice = 14 days back, `+1` after `−7` = 6
     * days back, regardless of how stale the composed button state is.
     */
    data class DayDelta(val delta: Int) : TvgIntent
    /** Jumps to an absolute offset (the «Сегодня» button sends 0). */
    data class DaySet(val offset: Int) : TvgIntent
    data class ChannelClicked(val channelId: String) : TvgIntent
    /** Android TV viewport: load EPG only for rows the user can currently see. */
    data class VisibleChannelsChanged(val channelIds: Set<String>) : TvgIntent
    data class ProgrammeClicked(val channelId: String, val programmeStart: Long) : TvgIntent
    /** Focus moved onto a programme cell (highlights it + shows details). */
    data class ProgrammeFocused(val channelId: String, val programmeStart: Long) : TvgIntent
    /** Plays the past programme as archive (catch-up URL). */
    data class ArchiveProgrammeClicked(val channelId: String, val programmeStart: Long) : TvgIntent
    /** Schedules (or unschedules) a future programme. */
    data class ScheduleToggled(val channelId: String, val programmeStart: Long) : TvgIntent
    data object DismissInfo : TvgIntent
}

sealed interface TvgEffect : MviEffect

interface TvgComponent {
    val state: StateFlow<TvgState>
    /** High-frequency focus stream; changes here must not invalidate the EPG grid state. */
    val focusState: StateFlow<TvgFocusState>
    val playbackController: PlaybackController
    fun dispatch(intent: TvgIntent)
}

/**
 * Selected TV Guide day offset, kept for the PROCESS lifetime.
 *
 * The TV Guide component is recreated whenever its tab leaves the navigation
 * stack, and an async restore would lose the race with the next click. Storing
 * the offset here (in memory) lets every recreation resume exactly where the
 * user was — while a fresh app launch still starts at «Сегодня» (offset 0),
 * which is what users expect from the −7 / −1 / +1 / +7 buttons.
 */
internal object TvgDayOffsetHolder {
    @Volatile
    var offset: Int = 0
}

class DefaultTvgComponent(
    componentContext: ComponentContext,
    private val channelsRepository: ChannelsRepository,
    private val playlistRepository: PlaylistRepository,
    private val epgRepository: EpgRepository,
    private val archiveRepository: ArchiveRepository,
    private val scheduledRepository: ScheduledRepository,
    override val playbackController: PlaybackController,
    /** Opens a channel in the player and switches to the Channels tab. */
    private val onOpenChannel: (Channel) -> Unit,
    /** Requests full-screen video after a successful archive start. */
    private val onForceImmersive: () -> Unit = {},
    mainContext: CoroutineContext,
) : TvgComponent,
    ComponentContext by componentContext,
    MviComponent<TvgState, TvgIntent, TvgEffect>(
        initialState = TvgState(
            selectedDayOffset = TvgDayOffsetHolder.offset.coerceIn(
                if (isAndroidPlatform()) MIN_DAY_OFFSET else DESKTOP_MIN_DAY_OFFSET, MAX_DAY_OFFSET),
        ),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    init {
        // The component loads the grid ONCE at creation; the screen must NOT
        // re-dispatch Load on every mount (a fullscreen F11 window switch
        // recreates the screen and would re-run the EPG grid scans).
        loadGrid(forceRefresh = false)
        // Periodically re-read the grid window so the timeline does not go stale
        // when the current hour/day rolls over.
        launch {
            while (true) {
                delay(AUTO_RELOAD_MS)
                // Update programme positions without scheduling another XMLTV
                // download. The EPG repository independently owns its 12h TTL.
                loadGrid(forceRefresh = false, refreshEpg = false)
            }
        }
        // Changing depth in Settings must update guide dates immediately,
        // without waiting for the 10-minute clock or re-downloading XMLTV.
        launch {
            ArchiveDepthChanges.revision.drop(1).collect {
                loadGrid(forceRefresh = false, refreshEpg = false)
            }
        }
        // Keep the scheduled-markers in sync with the repository.
        launch {
            scheduledRepository.observeAll().collect { scheduled ->
                setState { it.copy(scheduledKeys = scheduled.map { entry -> entry.key }.toSet()) }
            }
        }
        // Reload the grid whenever a background EPG refresh finishes. The grid
        // is otherwise only re-read every 10 minutes, so a first-time XMLTV
        // import (or the app-level refresh) would leave the guide blank until
        // the next tab re-entry — `observeVersion` closes that gap.
        launch {
            epgRepository.observeVersion().distinctUntilChanged().collect { version: Long ->
                // StateFlow is replaying: do not drop the first value. The EPG
                // import can finish before this collector starts, and dropping
                // that replayed version used to leave the guide with the old
                // empty rows until the user left and re-entered the screen.
                if (version <= state.value.programmesEpgVersion) return@collect
                // Do NOT wipe `programmes` here: clearing the whole map turns
                // the guide blank for every row (including rows the user can
                // currently see) until the viewport reload lands — visible as
                // "shows for a second, then everything is empty". Update the
                // version and re-query exactly the current viewport instead;
                // off-screen rows refresh lazily when scrolled into view.
                setState { current ->
                    current.copy(
                        programmesEpgVersion = version,
                    )
                }
                if (isAndroidPlatform()) {
                    // A newly loaded feed may extend the oldest available day.
                    loadGrid(forceRefresh = false)
                } else {
                    loadVisibleChannels(currentVisibleOrInitialIds(), forceReload = true,
                        epgVersion = version)
                }
            }
        }
    }

    override fun reduce(state: TvgState, intent: TvgIntent): TvgState = when (intent) {
        TvgIntent.Load -> state.copy(isLoading = true, error = null)
        TvgIntent.Refresh -> state.copy(isRefreshing = true, error = null)
        is TvgIntent.CategorySelected -> state.copy(
            selectedCategory = intent.category,
            filteredChannels = if (intent.category == null) {
                state.channels
            } else {
                state.channels.filter { it.category == intent.category }
            },
        )
        is TvgIntent.DayDelta -> state.copy(
            selectedDayOffset = (state.selectedDayOffset + intent.delta).coerceIn(
                (if (isAndroidPlatform()) state.minArchiveDayOffset else DESKTOP_MIN_DAY_OFFSET).coerceIn(MIN_DAY_OFFSET, 0), MAX_DAY_OFFSET),
            message = null,
        )
        is TvgIntent.DaySet -> state.copy(
            selectedDayOffset = intent.offset.coerceIn((if (isAndroidPlatform()) state.minArchiveDayOffset else DESKTOP_MIN_DAY_OFFSET).coerceIn(MIN_DAY_OFFSET, 0), MAX_DAY_OFFSET),
            message = null,
        )
        is TvgIntent.ChannelClicked -> state
        is TvgIntent.VisibleChannelsChanged -> state
        is TvgIntent.ProgrammeClicked -> {
            val channel = channelForFocus(state, intent.channelId)
            val programme = channel?.let { c ->
                state.programmes[c.id]?.let { findProgrammeByStart(it, intent.programmeStart) }
            }
            val info = if (channel != null && programme != null) ProgrammeInfo(channel, programme) else null
            _focusState.value = TvgFocusState(
                channelId = intent.channelId,
                programmeStart = intent.programmeStart,
                info = info,
            )
            state.copy(info = info)
        }
        is TvgIntent.ProgrammeFocused -> {
            // Hot path for D-pad navigation. Do not copy TvgState.
            // When UP/DOWN moves focus to another channel, the guide must
            // immediately show that channel's CURRENT programme (today) —
            // without requiring OK on the channel. Horizontal movement inside
            // the same channel still selects the programme under the focus.
            val channel = channelForFocus(state, intent.channelId)
            val programmes = channel?.let { state.programmes[it.id].orEmpty() }.orEmpty()
            val previous = _focusState.value
            val channelChanged = previous.channelId != intent.channelId
            val targetProgramme = if (channelChanged && state.selectedDayOffset == 0) {
                // Current programme is a separate small index. Do not depend on
                // the row's seven-day projection being loaded yet. This makes
                // UP/DOWN immediately show the programme for the focused channel.
                state.currentProgrammes[intent.channelId]
                    ?: run {
                        val now = currentTimeMillis()
                        programmes.firstOrNull { it.start <= now && now < it.stop }
                            ?: programmes.firstOrNull { it.start > now }
                            ?: findProgrammeByStart(programmes, intent.programmeStart)
                    }
            } else {
                findProgrammeByStart(programmes, intent.programmeStart)
            }
            _focusState.value = TvgFocusState(
                channelId = intent.channelId,
                // Keep the PHYSICAL programme that Compose focus actually moved to.
                // On channel changes the details/current-programme presentation is
                // updated independently below. This avoids requestFocus() loops that
                // made the LazyColumn jump and then snap back during ↑/↓.
                programmeStart = intent.programmeStart,
                info = if (channel != null && targetProgramme != null) ProgrammeInfo(channel, targetProgramme) else previous.info,
            )
            state
        }
        is TvgIntent.ArchiveProgrammeClicked -> state
        is TvgIntent.ScheduleToggled -> state
        TvgIntent.DismissInfo -> {
            _focusState.value = _focusState.value.copy(info = null)
            state
        }
    }

    override fun handle(intent: TvgIntent) {
        when (intent) {
            TvgIntent.Load -> loadGrid(forceRefresh = false)
            TvgIntent.Refresh -> loadGrid(forceRefresh = true)
            is TvgIntent.CategorySelected -> {
                loadGrid(forceRefresh = false)
            }
            is TvgIntent.DayDelta,
            is TvgIntent.DaySet,
            -> {
                // `state.value` already holds the reduced offset (dispatch runs
                // reduce before handle): persist it for tab switches.
                persistDayOffset(state.value.selectedDayOffset)
                loadGrid(forceRefresh = false)
            }
            is TvgIntent.ChannelClicked -> openChannel(intent.channelId)
            is TvgIntent.VisibleChannelsChanged -> {
                lastVisibleChannelIds = intent.channelIds
                if (intent.channelIds.isNotEmpty()) {
                    loadVisibleChannels(intent.channelIds)
                }
            }
            is TvgIntent.ProgrammeClicked,
            is TvgIntent.ProgrammeFocused,
            TvgIntent.DismissInfo,
            -> Unit
            is TvgIntent.ArchiveProgrammeClicked -> playArchive(intent.channelId, intent.programmeStart)
            is TvgIntent.ScheduleToggled -> toggleSchedule(intent.channelId, intent.programmeStart)
        }
    }

    private fun channelForFocus(state: TvgState, id: String): Channel? {
        if (focusChannelCacheId == id) return focusChannelCache
        val channel = state.channels.firstOrNull { it.id == id }
        focusChannelCacheId = id
        focusChannelCache = channel
        return channel
    }

    private fun findProgrammeByStart(list: List<EpgProgramme>, start: Long): EpgProgramme? {
        var low = 0
        var high = list.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = list[mid].start
            when {
                value < start -> low = mid + 1
                value > start -> high = mid - 1
                else -> return list[mid]
            }
        }
        return null
    }

    /** Keeps the selected day across tab switches (in-memory, process-lifetime). */
    private fun persistDayOffset(offset: Int) {
        TvgDayOffsetHolder.offset = offset.coerceIn(
            if (isAndroidPlatform()) MIN_DAY_OFFSET else DESKTOP_MIN_DAY_OFFSET, MAX_DAY_OFFSET)
    }

    private val _focusState = MutableStateFlow(TvgFocusState())
    private var focusChannelCacheId: String? = null
    private var focusChannelCache: Channel? = null
    override val focusState: StateFlow<TvgFocusState> = _focusState.asStateFlow()

    companion object {
        const val TAG = "Tvg"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val AUTO_RELOAD_MS = 10L * 60_000L

        /** Guide window: from the current programme, one week forward (no archive). */
        const val GUIDE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        /** Rows warmed up immediately when the guide opens (Android TV). */
        const val FIRST_ROWS_WARMUP = 8

        /** A generous safety bound; the actual lower bound is the cached
         * EPG range, user's limit and provider-declared archive depth. */
        const val MIN_DAY_OFFSET = -365
        const val DESKTOP_MIN_DAY_OFFSET = -30
        const val MAX_DAY_OFFSET = +7

        // Bound the visible-row query so a pathological viewport/category
        // snapshot cannot turn into a huge SQL IN(...) request.
        const val MAX_VISIBLE_QUERY_CHANNELS = 16
    }

    private var gridJob: kotlinx.coroutines.Job? = null
    private var visibleLoadJob: kotlinx.coroutines.Job? = null
    private var priorityVisibleLoadJob: kotlinx.coroutines.Job? = null
    private var epgRefreshJob: kotlinx.coroutines.Job? = null
    private var lastVisibleChannelIds: Set<String> = emptySet()
    private var categoryAutoSelected = false
    private val androidRowVersions = mutableMapOf<String, Long>()

    private fun startEpgRefresh(force: Boolean) {
        // A running import is NonCancellable inside the repository. Never start
        // another one just because the guide recomposed or the viewport moved.
        if (epgRefreshJob?.isActive == true) return
        epgRefreshJob = launch {
            val result = runCatching { epgRepository.refreshIfStale(force = force) }
            result.onFailure { error -> Logger.e(TAG, "EPG background refresh failed", error) }
            // Do not rely solely on observeVersion(). A Guide can be mounted
            // after the repository emitted its in-memory version, and a viewport
            // request can have been cancelled while the import was finishing.
            // Re-read the CURRENT viewport explicitly after the refresh completes.
            if (result.isSuccess) {
                val version = readEpgVersion()
                loadVisibleChannels(
                    currentVisibleOrInitialIds(),
                    forceReload = true,
                    epgVersion = version,
                )
            }
        }
    }

    private fun loadGrid(forceRefresh: Boolean, refreshEpg: Boolean = true) {
        // Cancel the previous load: fast repeated clicks must not run parallel
        // grid queries whose late results would overwrite the day the user
        // finally landed on.
        gridJob?.cancel()
        gridJob = launch {
            // Load the grid from the local DB immediately — the EPG refresh
            // (a large XMLTV re-import) must never block the TV Guide from
            // appearing. It runs in the background; when it finishes, the
            // grid simply shows fresher data on the next reload.
            try {
                val all = channelsRepository.getChannels()
                if (all.isEmpty()) {
                    setState {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            channels = emptyList(),
                            programmes = emptyMap(),
                            archiveStatus = emptyMap(),
                        )
                    }
                    return@launch
                }
                // The TV Guide shows LIVE channels only: VOD rows have no EPG,
                // and if they happened to be first in the database the grid
                // would look completely blank on open.
                val livePlaylistIds = playlistRepository.getPlaylists()
                    .filter { it.type == PlaylistType.LIVE }
                    .mapTo(HashSet()) { it.id }
                val playlistNames = playlistRepository.getPlaylists().associate { it.id to it.name }
                val channels = if (livePlaylistIds.isNotEmpty()) {
                    all.filter { it.playlistId in livePlaylistIds }
                } else {
                    all
                }
                if (channels.isEmpty()) {
                    setState {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            channels = emptyList(),
                            programmes = emptyMap(),
                            archiveStatus = emptyMap(),
                            error = AppError.InvalidData("Нет LIVE-каналов для ТВ-гида."),
                        )
                    }
                    return@launch
                }
                val now = currentTimeMillis()
                // Use only REAL cached EPG history; the user setting narrows the
                // archive, and provider-declared catchup-days narrows it when
                // automatic depth is selected. No past programmes are invented.
                val oldestOffset = if (isAndroidPlatform()) {
                    val availableRange = epgRepository.getAvailableProgrammeRange(channels)
                    val userDays = runCatching { archiveRepository.userArchiveLimitDays() }
                        .getOrDefault(0).coerceAtLeast(0)
                    // EPG history and *playable* provider catch-up have separate
                    // boundaries. 0 = automatic (all actual cached EPG dates).
                    // An explicit depth narrows the visible history immediately.
                    val allowedStart = if (userDays > 0) {
                        maxOf(availableRange?.first ?: now,
                            now - userDays.toLong().coerceAtMost(3650L) * DAY_MS)
                    } else availableRange?.first ?: now
                    var firstDay = 0
                    while (firstDay > MIN_DAY_OFFSET &&
                        startOfLocalDayOffset(now, firstDay) > allowedStart) firstDay--
                    firstDay
                } else DESKTOP_MIN_DAY_OFFSET
                // Clamp a remembered offset if the provider shortened its feed
                // or if the user lowered the archive depth in settings.
                val offset = state.value.selectedDayOffset.coerceIn(oldestOffset, MAX_DAY_OFFSET)
                if (offset != state.value.selectedDayOffset) persistDayOffset(offset)
                val from = startOfLocalDayOffset(now, offset)
                val to = if (isAndroidPlatform()) startOfLocalDayOffset(now, offset + 1) else from + DAY_MS
                // On first open, scope the Guide to the category of the LIVE
                // channel the user is currently watching. Without this the guide
                // opens on "all channels" (2000+ rows) and the auto-scroll to the
                // watched channel crawls across everything (and fights the focus
                // logic, which pulls back to the first row).
                if (!categoryAutoSelected) {
                    categoryAutoSelected = true
                    val playingId = playbackController.state.value.item?.id
                    if (playingId != null) {
                        channels.firstOrNull { it.id == playingId }?.category
                            ?.takeIf { it.isNotBlank() }
                            ?.let { category ->
                                setState {
                                    it.copy(
                                        selectedCategory = category,
                                        filteredChannels = channels.filter { c -> c.category == category },
                                    )
                                }
                            }
                    }
                }
                // Используем отфильтрованные по категории каналы для сетки.
                val allChannels = if (isAndroidPlatform()) channels.filter { state.value.selectedCategory == null || it.category == state.value.selectedCategory }
                    else state.value.filteredChannels.ifEmpty { channels }
                // Android TV: paint the guide shell immediately and let the
                // LazyColumn viewport request only the rows actually visible.
                // The old 24-channel warm-up still queried a full day of EPG
                // before the viewport was known, which delayed the first
                // programme strip and made the guide feel like it was scrolling
                // through an empty list.
                val currentEpgVersion = readEpgVersion()
                val programmes = if (com.tvnext.app.core.common.platform.isAndroidPlatform()) {
                    // Android TV: paint the guide shell fast, but STILL warm the
                    // first rows immediately — the viewport loader alone left the
                    // guide blank on some devices (its snapshotFlow/delay never
                    // fired), so do not depend on it for the initial paint. The
                    // viewport loader keeps refilling rows as the user scrolls.
                    epgRepository.getProgrammesInRange(
                        allChannels.take(FIRST_ROWS_WARMUP),
                        from,
                        to,
                    )
                } else {
                    epgRepository.getProgrammesInRange(allChannels, from, to)
                }
                // Build a tiny current-programme index separately from the grid.
                // This is the hot path for UP/DOWN focus and must not wait for the
                // large seven-day programme projection. The repository keeps EPG
                // in memory, so this is cheap once the cache is warm.
                val currentProgrammes = if (state.value.selectedDayOffset == 0) {
                    runCatching { epgRepository.getNowPlaying(allChannels, currentTimeMillis()) }
                        .getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }

                if (isAndroidPlatform()) {
                    androidRowVersions.clear()
                    programmes.keys.forEach { androidRowVersions[it] = currentEpgVersion }
                }
                // Paint the guide immediately. Cache-only archive capability
                // lookup for hundreds/thousands of channels must never delay the
                // first frame of the Guide. It is loaded independently below.
                setState {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        channels = channels,
                        filteredChannels = allChannels,
                        playlistNames = playlistNames,
                        programmes = programmes,
                        programmesEpgVersion = currentEpgVersion,
                        currentProgrammes = currentProgrammes,
                        from = from,
                        to = to,
                        selectedDayOffset = offset,
                        minArchiveDayOffset = oldestOffset,
                        archiveStatus = it.archiveStatus,
                        error = null,
                    )
                }
                launch {
                    val archiveStatus = runCatching {
                        archiveRepository.getCapabilitiesCached(channels)
                            .mapValues { (_, capability) -> capability.status }
                    }.getOrDefault(emptyMap())
                    setState { it.copy(archiveStatus = archiveStatus) }
                }
                // Rehydrate the viewport explicitly. A database/version
                // refresh does not necessarily change LazyColumn.layoutInfo,
                // so snapshotFlow in the screen may not emit a second time.
                loadVisibleChannels(
                    currentVisibleOrInitialIds(),
                    forceReload = true,
                    epgVersion = currentEpgVersion,
                )
                // Open the guide on the currently watched channel: focus its
                // current (or next) programme so the row is highlighted and the
                // details panel is visible right away.
                autoFocusCurrentProgramme()
            } catch (error: Exception) {
                error.rethrowAndroidCancellation()
                Logger.e(TAG, "Failed to load TV guide", error)
                setState { it.copy(isLoading = false, isRefreshing = false, error = AppError.Database(error)) }
            }
            // IMPORTANT: never make the long EPG import a child of gridJob.
            // A multi-minute import used to keep this coroutine alive and made
            // grid reload/cancellation fight with the import lifecycle. The UI
            // grid and EPG refresh are independent jobs now.
            if (refreshEpg) startEpgRefresh(forceRefresh)
        }
    }

    /**
     * Loads only the rows currently visible in the Android TV viewport.
     * Already-loaded rows remain in the map. Missing rows are fetched as one
     * indexed SQL query, and a previous viewport request is cancelled when the
     * user scrolls again.
     */
    private fun loadVisibleChannels(
        channelIds: Set<String>,
        forceReload: Boolean = false,
        epgVersion: Long = state.value.programmesEpgVersion,
    ) {
        val snapshot = state.value
        if (snapshot.channels.isEmpty() || snapshot.from >= snapshot.to) return

        // A map entry containing an empty list is NOT a successful load. It can
        // be the result of the Guide opening while an EPG import was still in
        // progress. Also, rows loaded from an older EPG version are stale.
        val visible = snapshot.channels
            .filter { channel ->
                channel.id in channelIds && (
                    forceReload ||
                        (isAndroidPlatform() && androidRowVersions[channel.id] != epgVersion) ||
                        snapshot.programmesEpgVersion != epgVersion ||
                        !snapshot.programmes.containsKey(channel.id) ||
                        snapshot.programmes[channel.id].isNullOrEmpty()
                    )
            }
            .take(MAX_VISIBLE_QUERY_CHANNELS)
        if (visible.isEmpty()) return

        // A priority reload (EPG just finished importing) must not be cancelled
        // by the next D-pad viewport event. Otherwise the exact race that made
        // EPG appear only after restarting the app can happen again. Ordinary
        // viewport changes remain cancellable to keep scrolling responsive.
        if (forceReload) {
            priorityVisibleLoadJob?.cancel()
            priorityVisibleLoadJob = launch {
                runCatching {
                val loaded = epgRepository.getProgrammesInRange(
                    visible,
                    snapshot.from,
                    snapshot.to,
                )
                val current = state.value
                if (current.from != snapshot.from || current.to != snapshot.to ||
                    (isAndroidPlatform() && current.programmesEpgVersion != epgVersion)) return@runCatching
                if (isAndroidPlatform()) loaded.keys.forEach { androidRowVersions[it] = epgVersion }
                setState { latest ->
                    val merged = latest.programmes.toMutableMap()
                    val currentMerged = latest.currentProgrammes.toMutableMap()
                    loaded.forEach { (id, programmes) ->
                        // Never replace a known-good row with an empty transient
                        // result while XMLTV batches are being imported.
                        if (programmes.isNotEmpty() || merged[id].isNullOrEmpty()) {
                            merged[id] = programmes
                        }
                        if (latest.selectedDayOffset == 0 && programmes.isNotEmpty()) {
                            currentProgrammeFromSorted(programmes, currentTimeMillis())?.let { currentMerged[id] = it }
                        }
                    }
                    latest.copy(
                        programmes = merged,
                        currentProgrammes = currentMerged,
                        programmesEpgVersion = epgVersion,
                    )
                }
                autoFocusCurrentProgramme()
                }.onFailure { error ->
                    Logger.e(TAG, "Failed to load visible TV guide rows", error)
                }
            }
        } else {
            visibleLoadJob?.cancel()
            visibleLoadJob = launch {
                runCatching {
                    val loaded = epgRepository.getProgrammesInRange(
                        visible,
                        snapshot.from,
                        snapshot.to,
                    )
                    val current = state.value
                    if (current.from != snapshot.from || current.to != snapshot.to ||
                    (isAndroidPlatform() && current.programmesEpgVersion != epgVersion)) return@runCatching
                if (isAndroidPlatform()) loaded.keys.forEach { androidRowVersions[it] = epgVersion }
                    setState { latest ->
                        val merged = latest.programmes.toMutableMap()
                        val currentMerged = latest.currentProgrammes.toMutableMap()
                        val nowValue = currentTimeMillis()
                        loaded.forEach { (id, programmes) ->
                            // Never erase a good cached row with a transient empty
                            // result while an XMLTV import is still being committed.
                            if (programmes.isNotEmpty() || merged[id].isNullOrEmpty()) {
                                merged[id] = programmes
                            }
                            if (latest.selectedDayOffset == 0 && programmes.isNotEmpty()) {
                                currentProgrammeFromSorted(programmes, nowValue)?.let {
                                    currentMerged[id] = it
                                }
                            }
                        }
                        latest.copy(
                            programmes = merged,
                            currentProgrammes = currentMerged,
                            programmesEpgVersion = epgVersion,
                        )
                    }
                }.onFailure { error ->
                    Logger.e(TAG, "Failed to load visible TV guide rows", error)
                }
            }
        }
    }

    private fun currentVisibleOrInitialIds(): Set<String> {
        if (lastVisibleChannelIds.isNotEmpty()) return lastVisibleChannelIds
        return state.value.filteredChannels
            .take(MAX_VISIBLE_QUERY_CHANNELS)
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun currentProgrammeFromSorted(programmes: List<EpgProgramme>, now: Long): EpgProgramme? {
        if (programmes.isEmpty()) return null
        var lo = 0
        var hi = programmes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (programmes[mid].start <= now) lo = mid + 1 else hi = mid
        }
        val index = (lo - 1).coerceAtLeast(0)
        val candidate = programmes[index]
        return candidate.takeIf { it.start <= now && now < it.stop }
    }

    /**
     * Once EPG rows for the currently watched channel are available, focuses
     * its current (or next) programme so the guide opens on it highlighted.
     * No-op after the first focus, so repeated grid loads do not steal the
     * user's D-pad selection.
     */
    private fun autoFocusCurrentProgramme() {
        val current = state.value
        if (_focusState.value.channelId != null) return
        val playingId = playbackController.state.value.item?.id ?: return
        val channel = current.filteredChannels.firstOrNull { it.id == playingId } ?: return
        val programmes = current.programmes[channel.id].orEmpty()
        val target = current.currentProgrammes[channel.id]
            ?: run {
                if (programmes.isEmpty()) return
                val now = currentTimeMillis()
                programmes.firstOrNull { it.start <= now && now < it.stop }
                    ?: programmes.firstOrNull { it.start > now }
                    ?: return
            }
        _focusState.value = TvgFocusState(
            channelId = channel.id,
            programmeStart = target.start,
            info = ProgrammeInfo(channel, target),
        )
    }

    /** Reads the repository's current version without exposing implementation details. */
    private suspend fun readEpgVersion(): Long {
        return epgRepository.observeVersion().first()
    }

    private fun openChannel(channelId: String) {
        if (isAndroidPlatform()) playbackRequestJob?.cancel()
        val channel = state.value.channels.firstOrNull { it.id == channelId } ?: return
        if (!com.tvnext.app.core.iptv.domain.ParentalChannelGate.request(channel) {
                openChannel(channelId)
            }) return
        playbackController.play(
            PlaybackItem(id = channel.id, title = channel.name, url = channel.streamUrl),
        )
        onOpenChannel(channel)
    }

    /**
     * Plays a past programme through the provider's catch-up mechanism.
     * The capability is resolved lazily here (one probe when the cache is
     * stale); the real proof is the playback itself.
     */
    private var playbackRequestJob: kotlinx.coroutines.Job? = null

    private fun launchPlayback(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (isAndroidPlatform()) playbackRequestJob?.cancel()
        val job = launch(block)
        if (isAndroidPlatform()) playbackRequestJob = job
    }

    private fun playArchive(channelId: String, programmeStart: Long) {
        val stateSnapshot = state.value
        val channel = stateSnapshot.channels.firstOrNull { it.id == channelId } ?: return
        val programme = stateSnapshot.programmes[channel.id]
            ?.firstOrNull { it.start == programmeStart }
            ?: return
        if (!com.tvnext.app.core.iptv.domain.ParentalChannelGate.request(channel) {
                playArchive(channelId, programmeStart)
            }) return
        setState { it.copy(message = null) }
        launchPlayback {
            val capability = runCatching { archiveRepository.getCapability(channel) }.getOrNull()
            if (capability == null || !capability.isPlayable) {
                Logger.d(TAG, "Archive unavailable for ${channel.name} (${capability?.status})")
                setState { it.copy(message = "Архив передачи недоступен.") }
                return@launchPlayback
            }
            val archiveEnd = if (isAndroidPlatform()) minOf(programme.stop, currentTimeMillis()) else programme.stop
            val url = archiveRepository.buildArchiveUrl(channel, programme.start, archiveEnd)
            if (url == null) {
                Logger.d(TAG, "No archive URL for ${channel.name}")
                setState { it.copy(message = "Архив передачи недоступен.") }
                return@launchPlayback
            }
            // Pre-flight: some providers DECLARE catch-up but serve an empty
            // playlist — show an honest message instead of a broken player.
            if (!archiveRepository.isArchiveUrlPlayable(channel, programme.start, archiveEnd)) {
                Logger.d(TAG, "Archive URL not playable for ${channel.name}")
                setState { it.copy(message = "Архив передачи недоступен (провайдер не отдаёт архив).") }
                return@launchPlayback
            }
            val title = "${channel.name} • Архив • ${formatDateTime(programme.start)}"
            playbackController.play(
                PlaybackItem(
                    id = channel.id,
                    title = title,
                    url = url,
                    kind = PlaybackContentKind.ARCHIVE,
                    archiveEpgAnchorMs = programme.start,
                ),
            )
            onForceImmersive()
            onOpenChannel(channel)
        }
    }

    private fun toggleSchedule(channelId: String, programmeStart: Long) {
        val stateSnapshot = state.value
        val channel = stateSnapshot.channels.firstOrNull { it.id == channelId } ?: return
        val programme = stateSnapshot.programmes[channel.id]
            ?.firstOrNull { it.start == programmeStart }
            ?: return
        val key = "${channel.playlistId}|$channelId|$programmeStart"
        if (key in stateSnapshot.scheduledKeys) {
            launch {
                runCatching {
                    scheduledRepository.unschedule(channelId, channel.playlistId, programmeStart)
                }.onFailure { error -> Logger.e(TAG, "Failed to unschedule", error) }
            }
        } else {
            launch {
                runCatching {
                    scheduledRepository.schedule(channel, programme, currentTimeMillis())
                }.onFailure { error -> Logger.e(TAG, "Failed to schedule", error) }
            }
        }
    }
}
