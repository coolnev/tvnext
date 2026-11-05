package com.tvnext.app.feature.search.ui

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
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.archive.isArchiveCandidate
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.data.ProgrammeSearchHit
import com.tvnext.app.core.iptv.data.ScheduledRepository
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Unified TVNext search: channels + «Сейчас» + «Архив» + «Медиатека» +
 * «Будущее» — one query, five sections, one shared player.
 */
data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    /** «Каналы»: live channels (name / tvg-name / tvg-id / playlist). */
    val channels: List<Channel> = emptyList(),
    /** «Сейчас»: programmes on air right now. */
    val currentProgrammes: List<ProgrammeSearchHit> = emptyList(),
    /** «Архив»: finished programmes within the archive depth. */
    val archiveProgrammes: List<ProgrammeSearchHit> = emptyList(),
    /** «Будущее»: upcoming programmes (scheduleable). */
    val futureProgrammes: List<ProgrammeSearchHit> = emptyList(),
    /** «Медиатека»: VOD items. */
    val mediaItems: List<MediaItem> = emptyList(),
    /** Keys (playlistId|channelId|start) of programmes the user scheduled. */
    val scheduledKeys: Set<String> = emptySet(),
    val error: AppError? = null,
) : MviState

sealed interface SearchIntent : MviIntent {
    data class SearchChanged(val query: String) : SearchIntent

    /** Carries the result object itself — the result list may already have
     * been cleared/rebuilt by the debounce by the time the click is handled. */
    data class ChannelClicked(val channel: Channel) : SearchIntent
    data class MediaItemClicked(val item: MediaItem) : SearchIntent
    /** «Сейчас» hit → switch to the live channel. */
    data class ProgrammeClicked(val hit: ProgrammeSearchHit) : SearchIntent
    /** «Архив» hit → play the catch-up URL. */
    data class ArchiveClicked(val hit: ProgrammeSearchHit) : SearchIntent
    /** «Будущее» hit → schedule / unschedule the programme. */
    data class FutureClicked(val hit: ProgrammeSearchHit) : SearchIntent
}

sealed interface SearchEffect : MviEffect

interface SearchComponent {
    val state: StateFlow<SearchState>
    fun dispatch(intent: SearchIntent)
}

class DefaultSearchComponent(
    componentContext: ComponentContext,
    private val channelsRepository: ChannelsRepository,
    private val mediaLibrary: MediaLibraryRepository,
    private val epgRepository: EpgRepository,
    private val playlistRepository: PlaylistRepository,
    private val archiveRepository: ArchiveRepository,
    private val scheduledRepository: ScheduledRepository,
    private val historyRepository: HistoryRepository,
    private val playbackController: PlaybackController,
    /** Opens a channel in the player and switches to the Channels tab. */
    private val onOpenChannel: (Channel) -> Unit,
    /** Opens a VOD item in the player and switches to the Media Library tab. */
    private val onOpenMediaItem: (MediaItem) -> Unit,
    /** Requests full-screen video after an archive stream has started. */
    private val onForceImmersive: () -> Unit = {},
    mainContext: CoroutineContext,
) : SearchComponent,
    ComponentContext by componentContext,
    MviComponent<SearchState, SearchIntent, SearchEffect>(
        initialState = SearchState(),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    private var searchJob: Job? = null

    init {
        launch {
            scheduledRepository.observeAll().collect { scheduled ->
                setState { it.copy(scheduledKeys = scheduled.map { entry -> entry.key }.toSet()) }
            }
        }
    }

    override fun reduce(state: SearchState, intent: SearchIntent): SearchState = when (intent) {
        is SearchIntent.SearchChanged -> state.copy(query = intent.query, error = null)
        is SearchIntent.ChannelClicked,
        is SearchIntent.MediaItemClicked,
        is SearchIntent.ProgrammeClicked,
        is SearchIntent.ArchiveClicked,
        is SearchIntent.FutureClicked,
        -> state
    }

    override fun handle(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.SearchChanged -> scheduleSearch(intent.query)
            is SearchIntent.ChannelClicked -> openChannel(intent.channel)
            is SearchIntent.MediaItemClicked -> openMediaItem(intent.item)
            is SearchIntent.ProgrammeClicked -> openChannelById(intent.hit.channelId)
            is SearchIntent.ArchiveClicked -> openArchive(intent.hit)
            is SearchIntent.FutureClicked -> toggleSchedule(intent.hit)
        }
    }

    /**
     * Debounced search: the previous job is always cancelled first, so a fast
     * A → B → C typing sequence runs exactly one search (for C) — no races.
     */
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            setState {
                it.copy(
                    isSearching = false,
                    channels = emptyList(),
                    currentProgrammes = emptyList(),
                    archiveProgrammes = emptyList(),
                    futureProgrammes = emptyList(),
                    mediaItems = emptyList(),
                    error = null,
                )
            }
            return
        }
        searchJob = launch {
            delay(DEBOUNCE_MS)
            setState { it.copy(isSearching = true, error = null) }
            try {
                val playlists = playlistRepository.getPlaylists()
                val playlistNameById = playlists.associate { it.id to it.name }
                val allChannels = channelsRepository.getChannels()
                val channelById = allChannels.associateBy { it.id }
                // «Каналы» section: only channels that actually match the query.
                val channels = allChannels.filter { it.matches(trimmed, playlistNameById[it.playlistId]) }
                val mediaItems = mediaLibrary.search(trimmed)
                val programmes = epgRepository.searchProgrammes(trimmed, EPG_SEARCH_LIMIT)

                val now = currentTimeMillis()
                // Cache-only statuses over the FULL channel set so that hits on
                // channels whose name never matched the query still resolve.
                val archiveStatus = archiveRepository.getCapabilitiesCached(allChannels)
                val userLimitDays = runCatching { archiveRepository.userArchiveLimitDays() }.getOrDefault(0)

                val current = mutableListOf<ProgrammeSearchHit>()
                val archive = mutableListOf<ProgrammeSearchHit>()
                val future = mutableListOf<ProgrammeSearchHit>()
                programmes.forEach { hit ->
                    when {
                        hit.programme.start <= now && now < hit.programme.stop -> current += hit
                        hit.programme.start > now -> future += hit
                        else -> {
                            // Finished programme — an archive candidate when the
                            // channel exposes a catch-up mechanism and the cached
                            // status is not definitively NONE.
                            val channel = hit.channelId?.let { channelById[it] }
                            if (channel != null && isArchiveCandidate(
                                    channel = channel,
                                    programmeStart = hit.programme.start,
                                    programmeStop = hit.programme.stop,
                                    now = now,
                                    capability = archiveStatus[channel.id],
                                    userLimitDays = userLimitDays,
                                )
                            ) {
                                archive += hit
                            }
                        }
                    }
                }

                setState {
                    it.copy(
                        isSearching = false,
                        channels = channels,
                        currentProgrammes = current.take(SECTION_LIMIT),
                        archiveProgrammes = archive.take(SECTION_LIMIT),
                        futureProgrammes = future.take(SECTION_LIMIT),
                        mediaItems = mediaItems.take(SECTION_LIMIT),
                        error = null,
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Debounce cancelled this search in favour of a newer one — not an error.
            } catch (error: Exception) {
                error.rethrowAndroidCancellation()
                Logger.e(TAG, "Failed to search", error)
                setState { it.copy(isSearching = false, error = AppError.Database(error)) }
            }
        }
    }

    private var playbackRequestJob: kotlinx.coroutines.Job? = null

    private fun launchPlayback(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (isAndroidPlatform()) playbackRequestJob?.cancel()
        val job = launch(block)
        if (isAndroidPlatform()) playbackRequestJob = job
    }

    private fun openChannel(channel: Channel) {
        Logger.d(TAG, "openChannel(${channel.id}): ${channel.name}")
        launchPlayback {
            runCatching { historyRepository.add(channel.id, WatchContentType.LIVE) }
                .onFailure { error -> Logger.e(TAG, "Failed to record history", error) }
            runCatching {
                playbackController.play(
                    PlaybackItem(id = channel.id, title = channel.name, url = channel.streamUrl),
                )
            }.onFailure { error ->
                Logger.e(TAG, "Playback failed from Search", error)
            }
            onOpenChannel(channel)
        }
    }

    /**
     * Resolves a channel by id from the repository. The current search's
     * channel list only contains channels MATCHING the query — a "Сейчас" or
     * "Будущее" hit can belong to a channel whose name/tvg never matched, so
     * the lookup must not rely on `state.channels`.
     */
    private fun openChannelById(channelId: String?) {
        launchPlayback {
            val channel = channelId?.let { id ->
                runCatching { channelsRepository.getChannels() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == id }
            } ?: return@launchPlayback
            openChannel(channel)
        }
    }

    private fun openMediaItem(item: MediaItem) {
        Logger.d(TAG, "openMediaItem(${item.id}): ${item.name}")
        launchPlayback {
            runCatching { historyRepository.add(item.id, WatchContentType.VOD) }
                .onFailure { error -> Logger.e(TAG, "Failed to record VOD history", error) }
            runCatching {
                playbackController.play(
                    PlaybackItem(
                        id = item.id,
                        title = item.name,
                        url = item.streamUrl,
                        kind = PlaybackContentKind.VOD,
                    ),
                )
            }.onFailure { error ->
                Logger.e(TAG, "Playback failed from Search (VOD)", error)
            }
            onOpenMediaItem(item)
        }
    }

    /**
     * Plays a finished programme through the provider's catch-up mechanism.
     * The capability is resolved lazily (one probe when the cache is stale);
     * if the archive turns out to be unavailable, the user gets a clear message
     * instead of a silently broken stream.
     */
    private fun openArchive(hit: ProgrammeSearchHit) {
        launchPlayback {
            val channel = hit.channelId?.let { channelId ->
                runCatching { channelsRepository.getChannels() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == channelId }
            } ?: return@launchPlayback
            val capability = runCatching { archiveRepository.getCapability(channel) }.getOrNull()
            if (capability == null || !capability.isPlayable) {
                Logger.d(TAG, "Archive unavailable for ${channel.name} (${capability?.status})")
                setState {
                    it.copy(error = AppError.InvalidData("Архив передачи недоступен."))
                }
                return@launchPlayback
            }
            val url = archiveRepository.buildArchiveUrl(channel, hit.programme.start, hit.programme.stop)
            if (url == null) {
                setState { it.copy(error = AppError.InvalidData("Архив передачи недоступен.")) }
                return@launchPlayback
            }
            // Pre-flight: some providers DECLARE catch-up but serve an empty
            // playlist — show an honest message instead of a broken player.
            if (!archiveRepository.isArchiveUrlPlayable(channel, hit.programme.start, hit.programme.stop)) {
                setState { it.copy(error = AppError.InvalidData("Архив передачи недоступен.")) }
                return@launchPlayback
            }
            runCatching { historyRepository.add(channel.id, WatchContentType.LIVE) }
            val title = "${channel.name} • Архив • ${formatDateTime(hit.programme.start)}"
            runCatching {
                playbackController.play(
                    PlaybackItem(
                        id = channel.id,
                        title = title,
                        url = url,
                        kind = PlaybackContentKind.ARCHIVE,
                        archiveEpgAnchorMs = hit.programme.start,
                    ),
                )
            }.onFailure { error ->
                Logger.e(TAG, "Playback failed from Search (archive)", error)
            }
            // An archive stream always opens full-screen, exactly like the TV
            // Guide: never leave the user in the browse/windowed layout.
            onForceImmersive()
            onOpenChannel(channel)
        }
    }

    private fun toggleSchedule(hit: ProgrammeSearchHit) {
        val channelId = hit.channelId ?: return
        launch {
            val channel = runCatching { channelsRepository.getChannels() }.getOrDefault(emptyList())
                .firstOrNull { it.id == channelId } ?: return@launch
            val key = "${channel.playlistId}|$channelId|${hit.programme.start}"
            if (key in state.value.scheduledKeys) {
                runCatching {
                    scheduledRepository.unschedule(channelId, channel.playlistId, hit.programme.start)
                }.onFailure { error -> Logger.e(TAG, "Failed to unschedule", error) }
            } else {
                runCatching {
                    scheduledRepository.schedule(channel, hit.programme, currentTimeMillis())
                }.onFailure { error -> Logger.e(TAG, "Failed to schedule", error) }
            }
        }
    }

    private fun Channel.matches(query: String, playlistName: String?): Boolean =
        name.contains(query, ignoreCase = true) ||
            category.contains(query, ignoreCase = true) ||
            tvgName?.contains(query, ignoreCase = true) == true ||
            tvgId?.contains(query, ignoreCase = true) == true ||
            playlistName?.contains(query, ignoreCase = true) == true

    private companion object {
        const val TAG = "Search"
        const val DEBOUNCE_MS = 300L

        /** Raw EPG hits pulled from the database before sectioning. */
        const val EPG_SEARCH_LIMIT = 200

        /** Per-section result cap (channels / сейчас / архив / будущее / VOD). */
        const val SECTION_LIMIT = 20
    }
}
