package com.tvnext.app.feature.media.ui

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.arkivanov.decompose.ComponentContext
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
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackItem
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.tmdb.TmdbMetadata
import com.tvnext.app.core.tmdb.TmdbRepository
import com.tvnext.app.feature.media.data.VodProgressStore
import com.tvnext.app.feature.media.domain.FeaturedMediaItem
import com.tvnext.app.feature.media.domain.MediaContent
import com.tvnext.app.feature.settings.data.SettingsRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MediaState(
    val openPlayer: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val content: MediaContent = MediaContent(),
    val query: String = "",
    val selectedSourceId: String? = null,
    val selectedCategory: String? = null,
    val selectedItemId: String? = null,
    val error: AppError? = null,
    /**
     * Whether the item-name overlay is visible on the video. Set on every
     * selection, hidden automatically after [CHANNEL_NAME_TIMEOUT_MS] — the
     * same behaviour as the channel name on the Channels tab.
     */
    val channelNameVisible: Boolean = false,
    val tmdbMetadata: TmdbMetadata? = null,
    val tmdbLoading: Boolean = false,
    /** Film currently selected for an Info request without starting playback. */
    val infoItemId: String? = null,
    val infoTmdbMetadata: TmdbMetadata? = null,
    val infoTmdbLoading: Boolean = false,
) : MviState {
    val visibleSources: List<Playlist>
        get() = content.sources.filter {
            selectedSourceId == null || it.id == selectedSourceId
        }

    val categories: List<String>
        get() {
            val ids = visibleSources.map { it.id }.toSet()
            return content.allItems
                .filter { it.playlistId in ids }
                .map { it.category }
                .distinct()
                .sorted()
        }

    val filteredItems: List<MediaItem>
        get() {
            val sourceIds = visibleSources.map { it.id }.toSet()
            val normalizedQuery = query.trim()
            return content.allItems.filter { item ->
                item.playlistId in sourceIds &&
                    when (selectedCategory) {
                        null -> true
                        else -> item.category == selectedCategory
                    } &&
                    (normalizedQuery.isEmpty() ||
                        item.name.contains(normalizedQuery, ignoreCase = true) ||
                        item.category.contains(normalizedQuery, ignoreCase = true))
            }
        }
}

sealed interface MediaIntent : MviIntent {
    data object ShowPlayer : MediaIntent
    data object PlayerShown : MediaIntent
    data object Load : MediaIntent
    data object Refresh : MediaIntent
    data class SourceSelected(val sourceId: String?) : MediaIntent
    data class CategorySelected(val category: String?) : MediaIntent
    data class SearchChanged(val query: String) : MediaIntent
    data class SelectItem(val itemId: String) : MediaIntent
    data class FavoriteToggled(val itemId: String) : MediaIntent
    data class ShowInfo(val itemId: String) : MediaIntent
    data object ClosePlayer : MediaIntent
    data object TogglePlayback : MediaIntent
}

sealed interface MediaEffect : MviEffect {
    data class ShowError(val error: AppError) : MediaEffect
}

interface MediaComponent {
    /** Global seek step shared by VOD and archive transport controls. */
    val seekStepMs: StateFlow<Long>
    fun setBackHandler(handler: () -> Boolean) {}
    fun consumeBack(): Boolean = false
    val state: StateFlow<MediaState>
    val playbackController: PlaybackController
    fun dispatch(intent: MediaIntent)
}

class DefaultMediaComponent(
    componentContext: ComponentContext,
    private val library: MediaLibraryRepository,
    private val historyRepository: HistoryRepository,
    private val progressStore: VodProgressStore,
    private val tmdbRepository: TmdbRepository,
    private val settingsRepository: SettingsRepository,
    override val playbackController: PlaybackController,
    mainContext: CoroutineContext,
) : MediaComponent,
    ComponentContext by componentContext,
    MviComponent<MediaState, MediaIntent, MediaEffect>(
        initialState = MediaState(),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    private var uiBackHandler: () -> Boolean = { false }
    override fun setBackHandler(handler: () -> Boolean) { uiBackHandler = handler }
    override fun consumeBack(): Boolean = uiBackHandler()

    /** Item-name overlay timer; only one exists at a time (cancelled first). */
    private var channelNameTimer: Job? = null
    private var selectionJob: Job? = null
    private var enrichmentJob: Job? = null
    private val _seekStepMs = kotlinx.coroutines.flow.MutableStateFlow(60_000L)
    override val seekStepMs: StateFlow<Long> = _seekStepMs.asStateFlow()

    /** O(1) lookup over the current VOD source (rebuilt on every load). */
    private var allItemsById: Map<String, MediaItem> = emptyMap()

    init {
        // The component loads itself ONCE at creation; the screen must NOT
        // re-dispatch Load on every mount (a fullscreen F11 window switch
        // recreates the screen and would re-run the VOD scans).
        launch {
            settingsRepository.observe().collect { settings ->
                _seekStepMs.value = settings.archiveSeekStepMs
            }
        }
        if (isAndroidPlatform()) {
            launch {
                var previous: com.tvnext.app.core.player.PlayerState? = null
                playbackController.state.collect { player ->
                    val old = previous
                    previous = player
                    if (old?.item?.kind == PlaybackContentKind.VOD && old.item?.id != player.item?.id && old.positionMs > 0) {
                        runCatching { progressStore.set(old.item!!.id, old.positionMs) }
                    }
                    if (player.item?.kind == PlaybackContentKind.VOD && player.positionMs > 0 &&
                        (old?.positionMs?.div(5000) != player.positionMs / 5000 || old?.state != player.state)) {
                        runCatching { progressStore.set(player.item!!.id, player.positionMs) }
                    }
                }
            }
            launch {
                playbackController.state.map { it.item }.distinctUntilChanged().collect { item ->
                    val id = item?.takeIf { it.kind == PlaybackContentKind.VOD }?.id
                    setState { current ->
                        current.copy(selectedItemId = id?.takeIf { target ->
                            current.content.allItems.any { it.id == target }
                        })
                    }
                    if (id != null && id in allItemsById) enrichTmdb(id)
                }
            }
        }
        load()
    }

    override fun reduce(state: MediaState, intent: MediaIntent): MediaState = when (intent) {
        MediaIntent.ShowPlayer -> state.copy(openPlayer = true)
        MediaIntent.PlayerShown -> state.copy(openPlayer = false)
        MediaIntent.Load -> state.copy(isLoading = true, error = null)
        MediaIntent.Refresh -> state.copy(isRefreshing = true, error = null)
        is MediaIntent.SourceSelected -> state.copy(selectedSourceId = intent.sourceId, selectedCategory = null)
        is MediaIntent.CategorySelected -> state.copy(
            // Cyclic toggle: clicking the already-selected category deselects it
            // (back to «Все»), matching the Channels category chips.
            selectedCategory = if (intent.category == state.selectedCategory) null else intent.category,
        )
        is MediaIntent.SearchChanged -> state.copy(query = intent.query)
        is MediaIntent.SelectItem -> state.copy(selectedItemId = intent.itemId, tmdbMetadata = null, tmdbLoading = true, infoItemId = null, infoTmdbMetadata = null)
        is MediaIntent.FavoriteToggled -> state
        is MediaIntent.ShowInfo -> state.copy(infoItemId = intent.itemId, infoTmdbMetadata = null, infoTmdbLoading = true)
        MediaIntent.ClosePlayer -> state.copy(selectedItemId = null, channelNameVisible = false, infoItemId = null)
        MediaIntent.TogglePlayback -> state
    }

    override fun handle(intent: MediaIntent) {
        when (intent) {
            MediaIntent.ShowPlayer, MediaIntent.PlayerShown -> Unit
            MediaIntent.Load -> load()
            MediaIntent.Refresh -> refresh()
            is MediaIntent.SourceSelected,
            is MediaIntent.CategorySelected,
            is MediaIntent.SearchChanged,
            -> Unit
            is MediaIntent.SelectItem -> selectItem(intent.itemId)
            is MediaIntent.FavoriteToggled -> persistFavorite(intent.itemId)
            is MediaIntent.ShowInfo -> enrichTmdbForInfo(intent.itemId)
            MediaIntent.ClosePlayer -> {
                channelNameTimer?.cancel()
                closePlayer()
            }
            MediaIntent.TogglePlayback -> togglePlayback()
        }
    }

    private fun load() {
        launch {
            try {
                val content = resolveContent()
                // If a VOD item is playing, keep the watching state.
                val playingId = playbackController.state.value.item?.takeIf {
                    it.id in content.allItems.map { item -> item.id }
                }?.id
                setState {
                    it.copy(
                        isLoading = false,
                        content = content,
                        selectedItemId = playingId,
                        error = null,
                    )
                }
                allItemsById = content.allItems.associateBy { it.id }
                if (playingId != null) {
                    enrichTmdb(playingId)
                }
            } catch (error: Exception) {
                error.rethrowAndroidCancellation()
                Logger.e(TAG, "Failed to load media library", error)
                setState { it.copy(isLoading = false, error = AppError.Database(error)) }
            }
        }
    }

    private fun refresh() {
        launch {
            setState { it.copy(isRefreshing = true, error = null) }
            library.refreshAll().fold(
                onSuccess = {
                    try {
                        val content = resolveContent()
                        setState {
                            it.copy(isRefreshing = false, isLoading = false, content = content, error = null)
                        }
                    } catch (error: Exception) {
                error.rethrowAndroidCancellation()
                        Logger.e(TAG, "Failed to load media library", error)
                        setState { it.copy(isRefreshing = false, error = AppError.Database(error)) }
                    }
                },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to refresh media library", null)
                    setState { it.copy(isRefreshing = false, error = error) }
                },
            )
        }
    }

    private suspend fun resolveContent(): MediaContent {
        val sources = library.getSources()
        val allItems = library.getItems(null)
        val byId = allItems.associateBy { it.id }
        val vodHistory = historyRepository.getRecentByType(WatchContentType.VOD, HistoryRepository.MAX_HISTORY)

        suspend fun toFeatured(itemId: String): FeaturedMediaItem? {
            val item = byId[itemId] ?: return null
            return FeaturedMediaItem(
                item = item,
                resumePositionMs = progressStore.get(item.id) ?: 0L,
            )
        }

        val continueWatching = vodHistory.firstOrNull()?.itemId?.let { toFeatured(it) }
        val recent = vodHistory.drop(1).mapNotNull { toFeatured(it.itemId) }
        val favoriteIds = library.getFavoriteIds()
        val favorites = favoriteIds.mapNotNull { byId[it] }.map { FeaturedMediaItem(item = it) }

        return MediaContent(
            sources = sources,
            continueWatching = continueWatching,
            recent = recent,
            favorites = favorites,
            allItems = allItems,
        )
    }

    private fun selectItem(itemId: String) {
        val item = allItemsById[itemId] ?: run {
            allItemsById = state.value.content.allItems.associateBy { it.id }
            allItemsById[itemId] ?: return
        }
        if (isAndroidPlatform()) selectionJob?.cancel()
        val job = launch {
            runCatching { historyRepository.add(itemId, WatchContentType.VOD) }
                .onFailure { error -> Logger.e(TAG, "Failed to record VOD history", error) }
            val resume = (if (isAndroidPlatform()) com.tvnext.app.core.player.AndroidPlaybackSession.vodPosition(itemId) else null) ?: progressStore.get(itemId)
            if (isAndroidPlatform() && state.value.selectedItemId != itemId) return@launch
            playbackController.play(
                PlaybackItem(
                    id = item.id,
                    title = item.name,
                    url = item.streamUrl,
                    kind = PlaybackContentKind.VOD,
                ),
            )
            // Only restore a sane resume position. Older app versions could
            // store bogus values (position pinned at the live edge of an
            // HLS stream); seeking to them makes the film "already over".
            if (resume != null && resume in 1L..RESUME_MAX_MS) {
                playbackController.seekTo(resume)
            } else if (resume != null) {
                runCatching { progressStore.clear(itemId) }
                    .onFailure { error -> Logger.e(TAG, "Failed to clear VOD position", error) }
            }
        }
        if (isAndroidPlatform()) selectionJob = job
        enrichTmdb(itemId)
        showChannelName()
    }

    private fun enrichTmdbForInfo(itemId: String) {
        if (isAndroidPlatform()) enrichmentJob?.cancel()
        val job = launch {
            val item = allItemsById[itemId] ?: state.value.content.allItems.firstOrNull { it.id == itemId } ?: return@launch
            setState { it.copy(infoTmdbLoading = true, infoTmdbMetadata = null) }
            val result = runCatching { tmdbRepository.enrich(item) }.getOrNull()
            setState {
                if (state.value.infoItemId == itemId) it.copy(infoTmdbLoading = false, infoTmdbMetadata = result?.metadata) else it
            }
        }
        if (isAndroidPlatform()) enrichmentJob = job
    }

    private fun enrichTmdb(itemId: String) {
        if (isAndroidPlatform()) enrichmentJob?.cancel()
        val job = launch {
            setState { it.copy(tmdbLoading = true, tmdbMetadata = null) }
            val result = runCatching { tmdbRepository.enrich(allItemsById[itemId] ?: return@runCatching null) }.getOrNull()
            setState {
                if (state.value.selectedItemId == itemId) it.copy(tmdbLoading = false, tmdbMetadata = result?.metadata) else it
            }
        }
        if (isAndroidPlatform()) enrichmentJob = job
    }

    /**
     * Shows the item-name overlay and (re)starts its 3-second hide timer.
     * The previous timer is always cancelled first, so a fast A → B → C
     * selection sequence never races.
     */
    private fun showChannelName() {
        channelNameTimer?.cancel()
        setState { it.copy(channelNameVisible = true) }
        channelNameTimer = launch {
            delay(CHANNEL_NAME_TIMEOUT_MS)
            setState { it.copy(channelNameVisible = false) }
        }
    }

    private fun closePlayer() {
        if (isAndroidPlatform()) {
            selectionJob?.cancel()
            enrichmentJob?.cancel()
        }
        val playingId = if (isAndroidPlatform()) playbackController.state.value.item
            ?.takeIf { it.kind == PlaybackContentKind.VOD }?.id else state.value.selectedItemId
        if (playingId != null) {
            val player = playbackController.state.value
            val positionMs = player.positionMs
            val durationMs = player.durationMs
            // Save the resume position only when it is meaningful: a known
            // duration and not the very end. For live-like streams (duration
            // unknown or position pinned at the end) saving it would make the
            // next open start at the end — "the film is already over".
            val shouldSave = durationMs > 0L &&
                positionMs > 0L &&
                positionMs < durationMs - END_SAVE_MARGIN_MS
            launch {
                if (shouldSave) {
                    runCatching { progressStore.set(playingId, positionMs) }
                        .onFailure { error -> Logger.e(TAG, "Failed to save VOD position", error) }
                } else {
                    runCatching { progressStore.clear(playingId) }
                        .onFailure { error -> Logger.e(TAG, "Failed to clear VOD position", error) }
                }
            }
        }
        playbackController.stop()
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

    private fun persistFavorite(itemId: String) {
        val current = state.value.content.allItems.firstOrNull { it.id == itemId }?.isFavorite ?: return
        val target = !current
        launch {
            runCatching { library.setFavorite(itemId, target) }.onSuccess {
                setState { st ->
                    st.copy(
                        content = st.content.copy(
                            allItems = st.content.allItems.map { item ->
                                if (item.id == itemId) item.copy(isFavorite = target) else item
                            },
                        ),
                    )
                }
            }.onFailure { error ->
                Logger.e(TAG, "Failed to save media favorite", error)
                emitEffect(MediaEffect.ShowError(AppError.Database(error)))
            }
        }
    }

    private companion object {
        const val TAG = "Media"
        /** Item-name overlay duration (same behaviour as the Channels tab). */
        const val CHANNEL_NAME_TIMEOUT_MS = 3_000L
        /** Do not resume from the last 5 seconds of a stream. */
        const val END_SAVE_MARGIN_MS = 5_000L
        /** Any resume position above this is considered bogus (live-edge pinning). */
        const val RESUME_MAX_MS = 10L * 60 * 60 * 1000
    }
}
