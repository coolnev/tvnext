package com.tvnext.app.feature.home.ui

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
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import com.tvnext.app.feature.home.data.HomeRepository
import com.tvnext.app.feature.home.domain.HomeContent
import com.tvnext.app.feature.home.domain.HomeQuickAccess
import com.tvnext.app.feature.settings.data.SettingsRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class HomeState(
    val isLoading: Boolean = false,
    val content: HomeContent = HomeContent(),
    val error: AppError? = null,
) : MviState

sealed interface HomeIntent : MviIntent {
    data object ContinueChannel : HomeIntent
    data object ContinueVod : HomeIntent
    data object Load : HomeIntent
    data class ChannelClicked(val channelId: String) : HomeIntent
    data class VodClicked(val itemId: String) : HomeIntent
    data class QuickAccessClicked(val action: HomeQuickAccess) : HomeIntent
}

sealed interface HomeEffect : MviEffect

interface HomeComponent {
    val state: StateFlow<HomeState>
    fun dispatch(intent: HomeIntent)
}

class DefaultHomeComponent(
    componentContext: ComponentContext,
    private val repository: HomeRepository,
    private val channelsRepository: ChannelsRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    private val epgRepository: EpgRepository,
    private val mediaLibrary: MediaLibraryRepository,
    private val onQuickAccess: (HomeQuickAccess) -> Unit,
    mainContext: CoroutineContext,
) : HomeComponent,
    ComponentContext by componentContext,
    MviComponent<HomeState, HomeIntent, HomeEffect>(
        initialState = HomeState(),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        // The component loads itself ONCE at creation; the screen must NOT
        // re-dispatch Load on every mount (a fullscreen F11 window switch
        // recreates the screen and would re-run the heavy EPG scans).
        load()
        if (isAndroidPlatform()) launch {
            com.tvnext.app.core.player.AndroidPlaybackSession.ids.collect { load() }
        }
        // Re-load Home content after an EPG refresh so cards pick up the new
        // current/next programmes without an app restart.
        launch {
            epgRepository.observeVersion().distinctUntilChanged().collect { version ->
                // Version 0 is the process-local initial value; it is not a new
                // EPG import. Reload only after an actual refresh notification.
                if (version > 0L) dispatch(HomeIntent.Load)
            }
        }
    }

    override fun reduce(state: HomeState, intent: HomeIntent): HomeState = when (intent) {
        HomeIntent.Load -> state.copy(isLoading = true, error = null)
        HomeIntent.ContinueChannel, HomeIntent.ContinueVod -> state
        is HomeIntent.ChannelClicked -> state
        is HomeIntent.VodClicked -> state
        is HomeIntent.QuickAccessClicked -> state
    }

    override fun handle(intent: HomeIntent) {
        when (intent) {
            HomeIntent.Load -> load()
            HomeIntent.ContinueChannel -> (com.tvnext.app.core.player.AndroidPlaybackSession.ids.value.channelId
                ?: state.value.content.continueWatching?.id)?.let(::openChannel)
            HomeIntent.ContinueVod -> (com.tvnext.app.core.player.AndroidPlaybackSession.ids.value.vodId
                ?: state.value.content.continueWatchingVod?.id)?.let(::openVod)
            is HomeIntent.ChannelClicked -> openChannel(intent.channelId)
            is HomeIntent.VodClicked -> openVod(intent.itemId)
            is HomeIntent.QuickAccessClicked -> onQuickAccess(intent.action)
        }
    }

    private fun load() {
        if (isAndroidPlatform()) loadJob?.cancel()
        loadJob = launch {
            if (isAndroidPlatform()) kotlinx.coroutines.delay(150)
            setState { it.copy(isLoading = true, error = null) }
            // 0) Show the cached content INSTANTLY (the Home tab is recreated
            //    on every tab switch — a cold recompute costs ~1-2 s of EPG
            //    DISTINCT scans, which reads as a frozen screen).
            HomeContentHolder.content?.takeIf { !isAndroidPlatform() }?.let { cached ->
                setState { it.copy(isLoading = false, content = cached, error = null) }
            }
            // 1) Recompute from the local DB (fast; the now-next EPG pass is
            //    cached for a minute).
            try {
                val content = repository.getContent()
                HomeContentHolder.content = content
                setState { it.copy(isLoading = false, content = content, error = null) }
            } catch (error: Exception) {
                error.rethrowAndroidCancellation()
                Logger.e(TAG, "Failed to load home content", error)
                setState { it.copy(isLoading = false, error = AppError.Database(error)) }
            }
            // NOTE: no EPG refresh here. HomeComponent is recreated on every
            // tab switch, and on Android TV the cold import of a multi-hundred-
            // MB XMLTV kills a low-end box within seconds (the OS silently
            // drops the process — "the app just closes"). The single deferred
            // app-level import lives in DefaultAppComponent (process lifetime);
            // Home re-loads automatically via observeVersion() when it lands.
        }
    }

    /**
     * Opens a channel through the existing (singleton) playback controller and
     * switches to the Channels tab, which restores its watching state from the
     * playing item (existing mechanism).
     */
    private var playbackRequestJob: kotlinx.coroutines.Job? = null

    private fun launchPlayback(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (isAndroidPlatform()) playbackRequestJob?.cancel()
        val job = launch(block)
        if (isAndroidPlatform()) playbackRequestJob = job
    }

    private fun openChannel(channelId: String) {
        launchPlayback {
            val current = playbackController.state.value.item
            if (isAndroidPlatform() && current?.id == channelId && current.kind != com.tvnext.app.core.player.PlaybackContentKind.VOD) {
                onQuickAccess(HomeQuickAccess.ALL_CHANNELS)
                return@launchPlayback
            }
            val channel = channelsRepository.getChannels().firstOrNull { it.id == channelId }
                ?: return@launchPlayback
            if (!com.tvnext.app.core.iptv.domain.ParentalChannelGate.request(channel) {
                    openChannel(channelId)
                }) return@launchPlayback
            runCatching { settingsRepository.setLastChannelId(channelId) }
                .onFailure { error -> Logger.e(TAG, "Failed to save last channel", error) }
            runCatching { historyRepository.add(channelId, WatchContentType.LIVE) }
                .onFailure { error -> Logger.e(TAG, "Failed to record history", error) }
            playbackController.play(
                PlaybackItem(id = channel.id, title = channel.name, url = channel.streamUrl),
            )
            onQuickAccess(HomeQuickAccess.ALL_CHANNELS)
        }
    }

    /**
     * Opens a VOD item: plays it and switches to the Media Library tab, which
     * restores the watching state (and the saved resume position).
     */
    private fun openVod(itemId: String) {
        launchPlayback {
            val current = playbackController.state.value.item
            if (isAndroidPlatform() && current?.id == itemId && current.kind == com.tvnext.app.core.player.PlaybackContentKind.VOD) {
                onQuickAccess(HomeQuickAccess.MEDIA_LIBRARY)
                return@launchPlayback
            }
            val resume = if (isAndroidPlatform()) com.tvnext.app.core.player.AndroidPlaybackSession.vodPosition(itemId) else null
            val item = mediaLibrary.getItemById(itemId) ?: return@launchPlayback
            runCatching { historyRepository.add(itemId, WatchContentType.VOD) }
                .onFailure { error -> Logger.e(TAG, "Failed to record VOD history", error) }
            playbackController.play(
                PlaybackItem(id = item.id, title = item.name, url = item.streamUrl,
                    kind = if (isAndroidPlatform()) com.tvnext.app.core.player.PlaybackContentKind.VOD
                        else com.tvnext.app.core.player.PlaybackContentKind.LIVE),
            )
            if (resume != null && resume > 0) playbackController.seekTo(resume)
            onQuickAccess(HomeQuickAccess.MEDIA_LIBRARY)
        }
    }

    private companion object {
        const val TAG = "Home"
    }
}

/** Process-lifetime Home content cache (survives tab switches). */
internal object HomeContentHolder {
    @Volatile
    var content: HomeContent? = null
}
