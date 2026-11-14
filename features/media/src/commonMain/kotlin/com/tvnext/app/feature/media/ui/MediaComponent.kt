package com.tvnext.app.feature.media.ui

import com.tvnext.app.feature.settings.domain.*
import com.tvnext.app.core.tmdb.KinopoiskProgrammeLookup
import com.tvnext.app.core.tmdb.ProgrammeKind
import com.tvnext.app.core.tmdb.hasProgrammeSynopsis
import kotlinx.coroutines.coroutineScope

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MediaResumeRequest(val itemId:String,val title:String,val positionMs:Long)

data class MediaState(
    val preferences:MediaPreferences=MediaPreferences(),
    val browser:MediaBrowserPosition=MediaBrowserPosition(),
    val pendingResume:MediaResumeRequest?=null,
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
                .distinct().let { if(preferences.categorySort==MediaCategorySort.NAME) it.sortedBy(String::lowercase) else it }
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
            }.let { items -> when(preferences.filmSort) {
                MediaFilmSort.PROVIDER -> items
                MediaFilmSort.NAME_ASC -> items.sortedBy {it.name.lowercase()}
                MediaFilmSort.NAME_DESC -> items.sortedByDescending {it.name.lowercase()}
            } }
        }
}

sealed interface MediaIntent : MviIntent {
    data class BrowserMoved(val position:MediaBrowserPosition):MediaIntent
    data class ResumeChosen(val resume:Boolean):MediaIntent
    data object ResumeCancelled:MediaIntent
    data object MetadataSettingsChanged:MediaIntent
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
    private val kinopoiskLookup:KinopoiskProgrammeLookup?=null,
) : MediaComponent,
    ComponentContext by componentContext,
    MviComponent<MediaState, MediaIntent, MediaEffect>(
        initialState = MediaState(isLoading=true),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    private var uiBackHandler: () -> Boolean = { false }
    override fun setBackHandler(handler: () -> Boolean) { uiBackHandler = handler }
    override fun consumeBack(): Boolean = uiBackHandler()

    /** Item-name overlay timer; only one exists at a time (cancelled first). */
    private var channelNameTimer: Job? = null
    private var selectionJob: Job? = null
    private var enrichmentJob: Job? = null
    private var infoEnrichmentJob: Job? = null
    private var browserSaveJob: Job? = null
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
                setState {it.copy(preferences=settings.media)}
                if(!settings.media.rememberPosition) browserSaveJob?.cancel()
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
        is MediaIntent.BrowserMoved -> state.copy(browser=intent.position)
        MediaIntent.MetadataSettingsChanged, is MediaIntent.ResumeChosen -> state
        MediaIntent.ResumeCancelled -> state.copy(pendingResume=null,selectedItemId=playbackController.state.value.item?.takeIf{it.kind==PlaybackContentKind.VOD}?.id)
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
        is MediaIntent.SelectItem -> state.copy(pendingResume=null,selectedItemId = intent.itemId, tmdbMetadata = null, tmdbLoading = true, infoItemId = null, infoTmdbMetadata = null)
        is MediaIntent.FavoriteToggled -> state
        is MediaIntent.ShowInfo -> state.copy(infoItemId = intent.itemId, infoTmdbMetadata = null, infoTmdbLoading = true)
        MediaIntent.ClosePlayer -> state.copy(selectedItemId = null, channelNameVisible = false, infoItemId = null)
        MediaIntent.TogglePlayback -> state
    }

    override fun handle(intent: MediaIntent) {
        when (intent) {
            is MediaIntent.BrowserMoved -> {
                browserSaveJob?.cancel()
                if(state.value.preferences.rememberPosition) browserSaveJob=launch {
                    delay(250)
                    settingsRepository.setMediaBrowserPosition(intent.position)
                }
            }
            is MediaIntent.ResumeChosen -> state.value.pendingResume?.let {request->
                setState{it.copy(pendingResume=null)}
                startItem(request.itemId,if(intent.resume)request.positionMs else 0L)
            }
            MediaIntent.ResumeCancelled -> {selectionJob?.cancel();setState{it.copy(pendingResume=null)}}
            MediaIntent.MetadataSettingsChanged -> {
                state.value.selectedItemId?.let(::enrichTmdb)
                state.value.infoItemId?.takeIf{it!=state.value.selectedItemId}?.let(::enrichTmdbForInfo)
            }
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
                val settings=settingsRepository.load()
                val saved=if(settings.media.rememberPosition)settingsRepository.getMediaBrowserPosition() else null
                val content = resolveContent()
                val source=saved?.sourceId?.takeIf{id->content.sources.any{it.id==id}}
                val category=saved?.category?.takeIf{c->content.allItems.any{it.category==c && (source==null||it.playlistId==source)}}
                // If a VOD item is playing, keep the watching state.
                val playingId = playbackController.state.value.item?.takeIf {
                    it.id in content.allItems.map { item -> item.id }
                }?.id
                setState {
                    it.copy(
                        isLoading = false,
                        content = content,
                        preferences=settings.media,selectedSourceId=source,selectedCategory=category,
                        browser=saved?.copy(sourceId=source,category=category) ?: MediaBrowserPosition(),
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
        selectionJob?.cancel()
        selectionJob=launch {
            val resume=(if(isAndroidPlatform())com.tvnext.app.core.player.AndroidPlaybackSession.vodPosition(itemId) else null) ?: progressStore.get(itemId)
            if(state.value.selectedItemId!=itemId)return@launch
            val item=state.value.content.allItems.firstOrNull{it.id==itemId} ?: return@launch
            val valid=resume?.takeIf{it in 1L..RESUME_MAX_MS} ?: 0L
            if(resume!=null && valid==0L)progressStore.clear(itemId)
            when(state.value.preferences.resumeMode) {
                MediaResumeMode.ASK -> if(valid>0) {setState{it.copy(pendingResume=MediaResumeRequest(itemId,item.name,valid))};return@launch}
                MediaResumeMode.START_OVER -> {startItem(itemId,0L);return@launch}
                else -> Unit
            }
            startItem(itemId,valid)
        }
    }
    private fun startItem(itemId:String,position:Long) {
        val item=state.value.content.allItems.firstOrNull{it.id==itemId} ?: return
        // Do not cancel the coroutine resolving the original selection.
        launch {
            if(position==0L)progressStore.clear(itemId)
            runCatching{historyRepository.add(itemId,WatchContentType.VOD)}
            if(state.value.selectedItemId!=itemId)return@launch
            playbackController.play(PlaybackItem(item.id,item.name,item.streamUrl,PlaybackContentKind.VOD))
            if(position>0)playbackController.seekTo(position)
        }
        enrichTmdb(itemId)
        showChannelName()
    }

    private fun enrichTmdbForInfo(itemId: String) {
        if (isAndroidPlatform()) infoEnrichmentJob?.cancel()
        val job = launch {
            val item = allItemsById[itemId] ?: state.value.content.allItems.firstOrNull { it.id == itemId } ?: return@launch
            setState { it.copy(infoTmdbLoading = true, infoTmdbMetadata = null) }
            val result = libraryMetadata(item) {card->
                setState {if(it.infoItemId==itemId)it.copy(infoTmdbMetadata=card,infoTmdbLoading=card==null) else it}
            }
            setState {
                if (state.value.infoItemId == itemId) it.copy(infoTmdbLoading = false, infoTmdbMetadata = result) else it
            }
        }
        if (isAndroidPlatform()) infoEnrichmentJob = job
    }

    private fun enrichTmdb(itemId: String) {
        if (isAndroidPlatform()) enrichmentJob?.cancel()
        val job = launch {
            setState { it.copy(tmdbLoading = true, tmdbMetadata = null) }
            val result = (allItemsById[itemId] ?: state.value.content.allItems.firstOrNull{it.id==itemId})?.let {item->libraryMetadata(item) {card->
                setState {if(it.selectedItemId==itemId)it.copy(tmdbMetadata=card,tmdbLoading=card==null) else it}
            }}
            setState {
                if (state.value.selectedItemId == itemId) it.copy(tmdbLoading = false, tmdbMetadata = result) else it
            }
        }
        if (isAndroidPlatform()) enrichmentJob = job
    }

    private suspend fun libraryMetadata(item:MediaItem,publish:(com.tvnext.app.core.tmdb.TmdbMetadata?)->Unit={}):com.tvnext.app.core.tmdb.TmdbMetadata?=coroutineScope {
        var tmdb:com.tvnext.app.core.tmdb.TmdbMetadata?=null
        var kino:com.tvnext.app.core.tmdb.TmdbMetadata?=null
        fun best()=tmdb?.takeIf{hasProgrammeSynopsis(it.overview)} ?: kino ?: tmdb
        val first=launch {tmdb=runCatching{tmdbRepository.enrich(item).metadata}.getOrNull();publish(best())}
        val second=launch {
            val card=runCatching {kinopoiskLookup?.let {lookup->kotlinx.coroutines.withContext(Dispatchers.IO) {
                lookup.lookup(item.name,null,if(!item.season.isNullOrBlank()||!item.episode.isNullOrBlank()) ProgrammeKind.SERIES else ProgrammeKind.MOVIE)
            }}}.getOrNull()
            if(card!=null)kino=com.tvnext.app.core.tmdb.TmdbMetadata(0,"movie",title=item.name,overview=card.overview,
                externalPosterUrl=card.posterUrl,externalBackdropUrl=card.frameUrl)
            publish(best())
        }
        first.join();second.join()
        best()
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
