package com.tvnext.app.core.app

import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.Job
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.app.tab.AppTabComponent
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.feature.channels.ui.ChannelsComponent
import com.tvnext.app.feature.home.domain.HomeQuickAccess
import com.tvnext.app.feature.home.ui.HomeComponent
import com.tvnext.app.feature.media.ui.MediaComponent
import com.tvnext.app.feature.search.ui.SearchComponent
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.ui.SettingsComponent
import com.tvnext.app.feature.tvg.ui.TvgComponent
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.ScheduledProgramme
import com.tvnext.app.core.iptv.data.ScheduledReminderService
import com.tvnext.app.core.iptv.data.PlaylistRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Double-back-to-exit state for the Android TV back button. [IDLE] initially;
 * the first Back on the Home tab flips it to [CONFIRM_EXIT] (the host shows a
 * "press Back again to exit" hint). A second Back while [CONFIRM_EXIT] exits the
 * app. The state automatically returns to [IDLE] after ~2 s — pressing Back
 * twice fast enough is required to exit.
 */
enum class BackExitState {
    IDLE,
    CONFIRM_EXIT,
}

interface AppComponent {
    val childStack: Value<ChildStack<AppTab, AppTabComponent>>
    val settings: StateFlow<AppSettings>

    /** The scheduled programme to remind about (banner), or null. */
    val reminder: StateFlow<ScheduledProgramme?>
    fun dismissReminder()

    fun selectTab(tab: AppTab)
    /** Called by TV Guide after an archive stream has actually started. */
    fun setForceImmersiveHandler(handler: () -> Unit)

    /**
     * Double-back-to-exit state (Android TV). Desktop has no system Back, so it
     * stays [BackExitState.IDLE] there.
     */
    val backExit: StateFlow<BackExitState>
}

interface TabComponentFactory {
    fun createHome(componentContext: ComponentContext, onQuickAccess: (HomeQuickAccess) -> Unit): HomeComponent
    fun createChannels(componentContext: ComponentContext, onForceImmersive: () -> Unit = {}): ChannelsComponent
    fun createTvg(componentContext: ComponentContext, onOpenChannel: (Channel) -> Unit, onForceImmersive: () -> Unit): TvgComponent
    fun createMedia(componentContext: ComponentContext): MediaComponent
    fun createSearch(
        componentContext: ComponentContext,
        onOpenChannel: (Channel) -> Unit,
        onOpenMediaItem: (MediaItem) -> Unit,
        onForceImmersive: () -> Unit = {},
    ): SearchComponent
    fun createSettings(componentContext: ComponentContext): SettingsComponent
}

class DefaultAppComponent(
    componentContext: ComponentContext,
    private val factory: TabComponentFactory,
    private val settingsRepository: SettingsRepository,
    private val reminderService: ScheduledReminderService,
    private val playlistRepository: PlaylistRepository,
    private val epgRepository: EpgRepository,
    private val initialTab: AppTab = if (isAndroidPlatform()) AppTab.CHANNELS else AppTab.HOME,
    initialSettings: AppSettings = AppSettings(),
    private val onExitRequested: () -> Unit = {},
    mainContext: CoroutineContext,
) : AppComponent, ComponentContext by componentContext {

    private val scope = componentContext.coroutineScope(mainContext)
    private val navigation = StackNavigation<AppTab>()

    private var forceImmersiveHandler: () -> Unit = {}

    override fun setForceImmersiveHandler(handler: () -> Unit) {
        forceImmersiveHandler = handler
    }

    private val _backExit = MutableStateFlow(BackExitState.IDLE)
    override val backExit: StateFlow<BackExitState> = _backExit.asStateFlow()

    /** Resets the double-back state after the confirm window elapses. */
    private var backExitTimer: Job? = null

    /**
     * App-level Back handling (Android TV):
     *  - any tab → main menu (Home);
     *  - Home → double-back-to-exit (first Back arms the confirm, second exits).
     */
    private val appBackHandler = BackCallback(onBack = ::handleAppBack)

    init {
        backHandler.register(appBackHandler)
        // After an app upgrade the M3U parser may capture new channel
        // attributes (catch-up / archive). Re-import playlists once so the
        // new data lands in the DB; no-op afterwards.
        scope.launch {
            runCatching { playlistRepository.refreshIfParserUpgraded() }
        }
        // Process-level freshness check. The repository coalesces it with
        // viewport preloads and foreground retries; fresh disk data needs no HTTP.
        scope.launch {
            // Show the persisted channel programme cache immediately on
            // startup. This is disk-only; it never opens the XMLTV URL.
            runCatching { epgRepository.refreshState() }
        }
        scope.launch {
            delay(AUTO_EPG_REFRESH_DELAY_MS)
            // A fresh durable cache is returned as-is without HTTP.
            runCatching { epgRepository.refreshIfStale(force = false) }
        }
    }

    override val settings: StateFlow<AppSettings> =
        settingsRepository.observe()
            .catch { emit(AppSettings()) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = initialSettings,
            )

    override val reminder: StateFlow<ScheduledProgramme?> = reminderService.reminder

    override fun dismissReminder() {
        reminderService.dismiss()
    }

    override val childStack: Value<ChildStack<AppTab, AppTabComponent>> =
        childStack(
            source = navigation,
            initialConfiguration = initialTab,
            serializer = AppTab.serializer(),
            childFactory = ::createTabComponent,
        )

    override fun selectTab(tab: AppTab) {
        // Tabs replace each other in the stack: navigating to a tab destroys the
        // previous one (each entry re-runs the tab's own load, e.g. Home content
        // refresh / auto-open of the last watched channel on Android TV). The
        // Back button is handled manually in [handleAppBack].
        navigation.navigate { stack ->
            if (stack.lastOrNull() == tab) {
                stack
            } else if (isAndroidPlatform()) {
                // Retain each Android tab once. Back/guide return restores its
                // component state and focus without starting another load.
                stack.filterNot { it == tab } + tab
            } else {
                stack.dropLastWhile { it != tab } + tab
            }
        }
    }

    /** Entry point used by Android's Activity back dispatcher. */
    fun handleBack() = handleAppBack()

    private fun handleAppBack() {
        val activeTab = childStack.value.active.configuration
        if (activeTab == AppTab.TV_GUIDE) {
            // TV Guide is a transient overlay over the player. One Back closes
            // it and returns directly to clean fullscreen video. It must never
            // fall through to the generic "tab -> Home" rule.
            backExitTimer?.cancel()
            _backExit.value = BackExitState.IDLE
            requestForceImmersive()
            selectTab(AppTab.CHANNELS)
            return
        }
        if (activeTab == AppTab.CHANNELS) {
            // Channels owns transient UI layers (categories, drawer, timeline,
            // browse/windowed mode). Let that screen close ALL of them atomically
            // before app-level navigation is considered. This is the single
            // source of truth for Back on Android TV.
            val channels = (childStack.value.active.instance as? AppTabComponent.Channels)?.component
            if (channels?.consumeBack() == true) {
                backExitTimer?.cancel()
                _backExit.value = BackExitState.IDLE
                return
            }
        }
        if (isAndroidPlatform() && activeTab == AppTab.MEDIA_LIBRARY) {
            val media = (childStack.value.active.instance as? AppTabComponent.Media)?.component
            if (media?.consumeBack() == true) return
        }
        if (activeTab != AppTab.HOME) {
            // Any tab → main menu. Disarm the exit-confirm state first so a
            // Back on Home does not exit right after a Back from another tab.
            backExitTimer?.cancel()
            _backExit.value = BackExitState.IDLE
            selectTab(AppTab.HOME)
        } else if (_backExit.value == BackExitState.CONFIRM_EXIT) {
            backExitTimer?.cancel()
            _backExit.value = BackExitState.IDLE
            onExitRequested()
        } else {
            _backExit.value = BackExitState.CONFIRM_EXIT
            backExitTimer?.cancel()
            backExitTimer = scope.launch {
                delay(BACK_EXIT_TIMEOUT_MS)
                _backExit.value = BackExitState.IDLE
            }
        }
    }

    private fun navigateToQuickAccess(action: HomeQuickAccess) {
        when (action) {
            HomeQuickAccess.ALL_CHANNELS,
            HomeQuickAccess.FAVORITES,
            -> selectTab(AppTab.CHANNELS)
            HomeQuickAccess.MEDIA_LIBRARY -> openMediaPlayer()
            HomeQuickAccess.SETTINGS -> selectTab(AppTab.SETTINGS)
        }
    }

    private fun requestForceImmersive() {
        forceImmersiveHandler()
    }

    /** Called by TV Guide / Search after the playback started: switch to Channels. */
    private fun openChannel(channel: Channel) {
        selectTab(AppTab.CHANNELS)
    }

    /** Called by Search after a VOD playback started: switch to Media Library. */
    private fun openMediaItem(item: MediaItem) { openMediaPlayer() }

    private fun openMediaPlayer() {
        selectTab(AppTab.MEDIA_LIBRARY)
        if (isAndroidPlatform()) scope.launch {
            (childStack.value.active.instance as? AppTabComponent.Media)?.component
                ?.dispatch(com.tvnext.app.feature.media.ui.MediaIntent.ShowPlayer)
        }
    }

    private fun createTabComponent(
        tab: AppTab,
        componentContext: ComponentContext,
    ): AppTabComponent = when (tab) {
        AppTab.HOME -> AppTabComponent.Home(
            component = factory.createHome(componentContext, ::navigateToQuickAccess),
            componentContext = componentContext,
        )
        AppTab.CHANNELS -> AppTabComponent.Channels(
            component = factory.createChannels(componentContext, ::requestForceImmersive),
            componentContext = componentContext,
        )
        AppTab.TV_GUIDE -> AppTabComponent.Tvg(
            component = factory.createTvg(componentContext, ::openChannel, ::requestForceImmersive),
            componentContext = componentContext,
        )
        AppTab.MEDIA_LIBRARY -> AppTabComponent.Media(
            component = factory.createMedia(componentContext),
            componentContext = componentContext,
        )
        AppTab.SEARCH -> AppTabComponent.Search(
            component = factory.createSearch(
                componentContext,
                ::openChannel,
                ::openMediaItem,
                ::requestForceImmersive,
            ),
            componentContext = componentContext,
        )
        AppTab.SETTINGS -> AppTabComponent.Settings(
            component = factory.createSettings(componentContext),
            componentContext = componentContext,
        )
    }

    private companion object {
        /** Window for the double-back-to-exit on Android TV (2 s). */
        const val BACK_EXIT_TIMEOUT_MS = 2_000L

        /**
         * Delay before the single automatic EPG refresh after launch — short
         * enough that the schedule appears quickly, long enough that the UI is
         * fully up first. The import itself is lightweight (streamed, bounded
         * memory), so this never stalls or crashes the app.
         */
        const val AUTO_EPG_REFRESH_DELAY_MS = 4_000L
    }
}
