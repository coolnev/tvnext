package com.tvnext.app.feature.channels.ui

import androidx.compose.ui.platform.testTag
import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.input.OkInputGate
import com.tvnext.app.core.ui.component.AutoScrollText
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.common.util.PreviousChannelHistory
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.common.util.formatDate
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.player.TimeShiftController
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.player.PlayerDiagnostics
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvPlayerPanel
import com.tvnext.app.core.ui.component.TvPlayPauseIcon
import com.tvnext.app.core.ui.component.TvSeekButton
import com.tvnext.app.core.ui.component.PlayerSeekBar
import com.tvnext.app.core.ui.component.TvProgressBar
import com.tvnext.app.core.ui.component.TvSearchField
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.theme.TVNextDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.tvnext.app.core.ui.component.BackArrowIcon
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.component.TvListState
import com.tvnext.app.core.ui.component.hoverEnterExit

@Composable
fun ChannelsScreen(
    component: ChannelsComponent,
    modifier: Modifier = Modifier,
    showChannelNumbers: Boolean = true,
    immersive: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
    onToggleFullscreen: (() -> Unit)? = null,
    onBackHome: (() -> Unit)? = null,
    /** Long-press OK opens the TV Guide (scoped to the currently watched channel). */
    onOpenTvg: (() -> Unit)? = null,
    clockPosition: ClockPosition = ClockPosition.OFF,
    showDiagnostics: Boolean = true,
    onToggleDiagnostics: () -> Unit = {},
    epgInfoDurationSeconds: Int? = 3,
    programmeDescriptionRight: Boolean = false,
) {
    androidx.compose.runtime.DisposableEffect(component) {
        if (isAndroidPlatform()) component.setScreenVisible(true)
        onDispose { if (isAndroidPlatform()) component.setScreenVisible(false) }
    }
    if (isAndroidPlatform()) androidx.compose.runtime.SideEffect {
        com.tvnext.app.core.common.log.AndroidPerformanceCounters.increment("compose.channels")
    }
    val state by component.state.collectAsState()
    val activePlaybackItem by remember(component) {
        component.playbackController.state.map { it.item }.distinctUntilChanged()
    }.collectAsState(initial = component.playbackController.state.value.item)
    // Timeline is deliberately outside ChannelsState: opening/closing and loading
    // a guide panel must not invalidate the whole channel-list state object.
    val timelineState by component.timeline.collectAsState()
    var transientError by remember { mutableStateOf<String?>(null) }

    // NOTE: the component loads itself on creation (see ChannelsComponent.init).
    // Do NOT dispatch Load from here — the screen is recreated on every
    // fullscreen F11 window switch, and re-running the heavy EPG scans there
    // made the switch stall for ~17 s.

    // Surface asynchronous errors (e.g. a failed favorite save) without crashing.
    LaunchedEffect(Unit) {
        component.effects.collect { effect ->
            when (effect) {
                is ChannelsEffect.ShowError -> {
                    transientError = effect.error.toUserMessage()
                    delay(5_000)
                    transientError = null
                }
            }
        }
    }

    // Immersive is a persistent user preference (toggled with L) — closing the
    // player does NOT reset it. Only the Android-TV auto-immersive block below
    // may change it, so on Windows the mode survives tab switches and player
    // close until the user explicitly turns it off.
    // Android TV: auto-enter the full-screen (immersive) surface when a channel
    // STARTS playing (the auto-open on tab entry / app start). Manual channel
    // switches inside the browse layout keep the user in browse mode — the
    // remote Up/Down + OK surfing flow must not yank the screen back to
    // full-screen after every selection.
    var wasWatchingChannel by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedChannelId, activePlaybackItem) {
        if (isAndroidPlatform()) {
            val watching = activePlaybackItem != null && activePlaybackItem?.kind != PlaybackContentKind.VOD
            if (watching && !wasWatchingChannel && !immersive) {
                onImmersiveChange(true)
            }
            if (!watching && immersive) onImmersiveChange(false)
            wasWatchingChannel = watching
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(if (immersive) 0.dp else TVNextDimens.ScreenPadding),
    ) {
        // Android TV: no screen header — the remote's Back button leaves the
        // screen and the space is used for the list/video (dense 4K layout).
        if (!immersive && !isAndroidPlatform()) {
            ScreenHeader(
                title = uiText("Каналы"),
                onBack = onBackHome,
                trailing = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        }


        val displayChannelId = if (isAndroidPlatform()) activePlaybackItem
            ?.takeIf { it.kind != PlaybackContentKind.VOD }?.id else state.selectedChannelId
        val selectedChannel = state.channels.firstOrNull { it.id == displayChannelId }
        LaunchedEffect(selectedChannel?.id) {
            if (isAndroidPlatform()) selectedChannel?.id?.let(PreviousChannelHistory::entered)
        }
        val now = rememberEpgTicker()
        if (selectedChannel != null) {
            WatchingLayout(
                component = component,
                state = state,
                selectedChannel = selectedChannel,
                showChannelNumbers = showChannelNumbers,
                controller = component.playbackController,
                immersive = immersive,
                now = now,
                clockPosition = clockPosition,
                showDiagnostics = showDiagnostics,
                programmeDescriptionRight = programmeDescriptionRight,
                onToggleImmersive = { onImmersiveChange(!immersive) },
                onImmersiveChange = onImmersiveChange,
                onToggleMute = {
                    val current = component.playbackController.state.value
                    component.playbackController.setMuted(!current.isMuted)
                },
                onSearch = { component.dispatch(ChannelsIntent.SearchChanged(it)) },
                onSearchClear = { component.dispatch(ChannelsIntent.SearchChanged("")) },
                onCategorySelected = { component.dispatch(ChannelsIntent.CategorySelected(it)) },
                onSelectChannel = { component.dispatch(ChannelsIntent.SelectChannel(it)) },
                onToggleFavorite = { component.dispatch(ChannelsIntent.FavoriteToggled(it)) },
                onBackHome = onBackHome,
                onOpenTvg = onOpenTvg,
                onClosePlayer = { component.dispatch(ChannelsIntent.ClosePlayer) },
                onTogglePlayback = { component.dispatch(ChannelsIntent.TogglePlayback) },
                onToggleFullscreen = onToggleFullscreen,
                onArchiveProgramme = { programme ->
                    component.dispatch(ChannelsIntent.ArchiveProgrammeClicked(programme))
                },
                onShowTimeline = { channelId ->
                    component.dispatch(ChannelsIntent.ShowChannelTimeline(channelId))
                },
                onHideTimeline = { component.dispatch(ChannelsIntent.HideChannelTimeline) },
                onVisibleChannelsChanged = { component.dispatch(ChannelsIntent.VisibleChannelsChanged(it)) },
                epgInfoDurationSeconds = epgInfoDurationSeconds,
                modifier = Modifier.weight(1f),
            )
        } else {
            RegularLayout(
                component = component,
                state = state,
                showChannelNumbers = showChannelNumbers,
                now = now,
                onSearch = { component.dispatch(ChannelsIntent.SearchChanged(it)) },
                onSearchClear = { component.dispatch(ChannelsIntent.SearchChanged("")) },
                onCategorySelected = { component.dispatch(ChannelsIntent.CategorySelected(it)) },
                onSelectChannel = { component.dispatch(ChannelsIntent.SelectChannel(it)) },
                onToggleFavorite = { component.dispatch(ChannelsIntent.FavoriteToggled(it)) },
                onRefresh = { component.dispatch(ChannelsIntent.Refresh) },
                onVisibleChannelsChanged = { component.dispatch(ChannelsIntent.VisibleChannelsChanged(it)) },
                modifier = Modifier.weight(1f),
            )
        }

        transientError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }
    }
}

@Composable
private fun WatchingLayout(
    component: ChannelsComponent,
    state: ChannelsState,
    selectedChannel: Channel,
    showChannelNumbers: Boolean,
    controller: PlaybackController,
    immersive: Boolean,
    now: Long,
    clockPosition: ClockPosition,
    showDiagnostics: Boolean,
    programmeDescriptionRight: Boolean,
    onToggleImmersive: () -> Unit,
    onImmersiveChange: (Boolean) -> Unit = {},
    onToggleMute: () -> Unit,
    onSearch: (String) -> Unit,
    onSearchClear: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onSelectChannel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onBackHome: (() -> Unit)? = null,
    onOpenTvg: (() -> Unit)? = null,
    onClosePlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onArchiveProgramme: (EpgProgramme) -> Unit,
    onShowTimeline: (String) -> Unit = {},
    onHideTimeline: () -> Unit = {},
    onVisibleChannelsChanged: (Set<String>) -> Unit = {},
    epgInfoDurationSeconds: Int? = 3,
    modifier: Modifier = Modifier,
) {
    val nowPlaying by component.nowPlaying.collectAsState()
    val actuallyPlayingId by remember(component) {
        component.playbackController.state.map { player ->
            player.item?.takeIf { player.state == PlaybackState.PLAYING && it.kind != PlaybackContentKind.VOD }?.id
        }.distinctUntilChanged()
    }.collectAsState(initial = null)
    // These values change independently of the channel list. Keep them local to
    // the watching surface so a 3-second name overlay or EPG refresh cannot
    // recompose the entire list/drawer tree.
    val channelEpg by component.channelEpg.collectAsState()
    val playingEpg by component.playingEpg.collectAsState()
    var infoPanelFocused by remember { mutableStateOf(false) }
    var archivePreview by remember { mutableStateOf<EpgProgramme?>(null) }
    val channelNameVisible by component.channelNameVisible.collectAsState()
    // Long-press OK on the VIDEO opens the TV Guide; short OK opens the browse
    // layout. A timer distinguishes them (Android TV remotes auto-repeat KeyDown).
    var okDownHeld by remember { mutableStateOf(false) }
    val windowedRemote = remember { WindowedRemoteInput() }
    var okLongFired by remember { mutableStateOf(false) }
    var okTimer by remember { mutableStateOf<Job?>(null) }
    val okScope = rememberCoroutineScope()
    // Панель программы текущего канала (справа от списка): RIGHT переводит
    // на неё фокус со списка каналов.
    val timelineFocusRequester = remember { FocusRequester() }
    val timelineState by component.timeline.collectAsState()
    var timelineFocused by remember { mutableStateOf(false) }

    var searchFocused by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    // Android TV only (desktop keeps the existing accordion drawer): a second
    // LEFT press while the channel list (drawer) is open, with the list
    // itself focused, slides in a dedicated category picker. Fullscreen: it
    // pushes the list to the right (both visible). Windowed browse mode
    // reuses the same flag to replace the always-visible list panel.
    var categoriesOpen by remember { mutableStateOf(false) }
    // Bumped every time the categories panel closes with Back: the channel list
    // re-focuses the row of the currently playing channel.
    var focusReturnNonce by remember { mutableStateOf(0) }
    // Bumped every time the categories panel closes with RIGHT or after picking
    // a category: the channel list re-focuses the TOP channel of the chosen
    // category (the playing channel is NOT switched — that only happens on OK).
    var focusTopNonce by remember { mutableStateOf(0) }
    // The channel the user currently has highlighted (drawer/browse list focus,
    // falling back to the playing channel). The bottom-right EPG card shows the
    // CURRENT programme of THIS channel — not necessarily the one that is
    // playing, which may be a different channel.
    var highlightedChannelId by remember { mutableStateOf<String?>(null) }

    var edgeHovered by remember { mutableStateOf(false) }
    var drawerHovered by remember { mutableStateOf(false) }
    // True while the video area holds focus (the OUTER watching container OR the
    // player's own video surface): used to decide whether OK/Enter toggles the
    // full-screen <-> browse modes on Android TV. When a list row is focused the
    // key is NOT intercepted — OK selects the channel instead.
    var rootVideoFocused by remember { mutableStateOf(false) }
    var surfaceVideoFocused by remember { mutableStateOf(false) }
    val videoFocused = rootVideoFocused || surfaceVideoFocused
    val videoFocusRequester = remember { FocusRequester() }
    var guidePlaybackTimeMs by remember { mutableStateOf<Long?>(null) }
    // Full-screen info overlay opened with the RIGHT arrow (Android TV): the
    // current programme / movie info, with a seek bar for VOD/archive content.
    var rightInfoVisible by remember { mutableStateOf(false) }
    // An explicit OK opens focusable transport controls. RIGHT once shows a
    // timed, passive information strip; RIGHT twice opens persistent details.
    var transportControlsVisible by remember { mutableStateOf(false) }
    var rightDetailsVisible by remember { mutableStateOf(false) }
    var rightKeyHeld by remember { mutableStateOf(false) }
    val hoverScope = rememberCoroutineScope()
    var drawerCloseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ONE list state and ONE expanded category are shared by the windowed
    // channel list and the full-screen drawer: they stay in sync (same category,
    // same scroll position), no matter which surface is visible. Both live in
    // TvListState (process lifetime) so F11 / double-click fullscreen does not
    // reset them and the list does not jump back to the top.
    val channelsExpandedCategory = TvListState.channelsExpandedCategory.value

    // Windows: the channel list (ChannelListPanel) is unmounted while
    // `immersive` is true and remounted every time the user reopens it (L /
    // the list-toggle key). Force «Все каналы» open on every reopen so the
    // playing channel's row always exists in the flat entry list below —
    // otherwise a category/«Избранное» left expanded from a PREVIOUS visit
    // could hide the playing channel entirely and the centering effect in
    // ChannelListPanel would silently fall back to the first row instead of
    // the one actually playing.
    LaunchedEffect(immersive) {
        if (!immersive && !isAndroidPlatform()) {
            TvListState.channelsExpandedCategory.value = "__all"
        }
    }

    // Full-screen «Телепрограмма» info block: shown on every channel switch
    // for epgInfoDurationSeconds (null = off, 0 = always, 1..7 = timeout).
    var epgInfoVisible by remember { mutableStateOf(false) }

    // Full-screen ARCHIVE playback UI: the seek bar overlay appears when the
    // user rewinds (UP/DOWN) or toggles play/pause (OK), then auto-hides after
    // 3 s. DOWN while the bar is visible activates the seek handle (LEFT/RIGHT
    // then scrub); centred play/pause icons also fade out after 3 s.
    var archiveOverlayVisible by remember { mutableStateOf(false) }
    var fullscreenInfoHeightPx by remember { mutableIntStateOf(0) }
    val overlayDensity = LocalDensity.current
    var seekMode by remember { mutableStateOf(false) }
    var playPauseIconVisible by remember { mutableStateOf(false) }
    var playPauseIconPlaying by remember { mutableStateOf(true) }
    var archiveOverlayJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var playPauseIconJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun showArchiveOverlay() {
        archiveOverlayVisible = true
        archiveOverlayJob?.cancel()
        archiveOverlayJob = hoverScope.launch {
            delay(3000)
            while (isAndroidPlatform() && (infoPanelFocused || controller.state.value.state == PlaybackState.PAUSED)) delay(500)
            archiveOverlayVisible = false
            seekMode = false
        }
    }

    fun showPlayPauseIcon(playing: Boolean) {
        playPauseIconPlaying = playing
        playPauseIconVisible = true
        playPauseIconJob?.cancel()
        playPauseIconJob = hoverScope.launch {
            delay(3000)
            playPauseIconVisible = false
        }
    }

    fun openDrawer() {
        drawerCloseJob?.cancel()
        if (drawerOpen) return
        drawerOpen = true
        if (isAndroidPlatform()) {
            val playback = controller.state.value
            guidePlaybackTimeMs = playback.item?.takeIf { it.kind == PlaybackContentKind.ARCHIVE }
                ?.archiveEpgAnchorMs?.plus(playback.positionMs)
            // A new browsing session starts at playback, never at an unplayed preview.
            highlightedChannelId = selectedChannel.id
            archivePreview = null
            if (state.filteredChannels.none { it.id == selectedChannel.id }) onCategorySelected(null)
            // Reset any pending "focus the TOP of the chosen category" /
            // "focus on return" request from the previous session — otherwise
            // focusTopNonce > 0 would re-run its LaunchedEffect when the
            // category below changes and override the "focus the PLAYING
            // channel" we are about to do here.
            focusTopNonce = 0
            focusReturnNonce = 0
            // Preview focus belongs only to this open drawer session.
            categoriesOpen = false
            // Рядом со списком каналов (справа от него) показываем программу
            // текущего канала: текущая передача + весь день = архив. Открываем
            // автоматически при открытии списка, если ещё не открыта.
            if (component.timeline.value.channelId != selectedChannel.id) {
                onShowTimeline(selectedChannel.id)
            }
        }
    }

    /**
     * Applies the category picked in the categories panel. On Android TV the
     * channel list shows ONLY the channels of the chosen category, but the
     * playing channel is NOT switched — the user only picks a category here;
     * the actual channel switch happens later, on OK in the channel list. The
     * list focus moves to the TOP channel of the chosen category (the remote's
     * RIGHT arrow behaves the same way).
     */
    fun selectCategory(category: String?) {
        // A windowed programme pane replaces the channel list. It belongs to
        // the old browsing session and must close before categories disappear.
        onHideTimeline()
        timelineFocused = false
        archivePreview = null
        onCategorySelected(category)
        categoriesOpen = false
        if (isAndroidPlatform() || category == null || selectedChannel.category == category) {
            focusReturnNonce++
        } else {
            focusTopNonce++
        }
        // Clear the stale highlight: the EPG card must now describe the chosen
        // category's channels, not a channel that may not even be in it.
        highlightedChannelId = null
        // Скрываем панели программ при смене категории
        epgInfoVisible = false
        rightInfoVisible = false
    }

    fun scheduleDrawerCloseIfAway() {
        if (edgeHovered || drawerHovered) return
        drawerCloseJob?.cancel()
        drawerCloseJob = hoverScope.launch {
            delay(2000)
            if (!edgeHovered && !drawerHovered) {
                drawerOpen = false
                categoriesOpen = false
            }
        }
    }
    val focusRequester = remember { FocusRequester() }
    // The first RIGHT is a transient info strip, not a focusable transport bar.

    // Android TV owns Back as a UI-layer operation first. Close every transient
    // layer in one press (categories + channel drawer + programme timeline +
    // browse/windowed mode). Only when there is nothing left to close does the
    // app-level double-back navigation get a chance to leave Channels.
    DisposableEffect(
        immersive,
        drawerOpen,
        categoriesOpen,
        rightInfoVisible,
        rightDetailsVisible,
        transportControlsVisible,
        archiveOverlayVisible,
        timelineState.channelId,
        timelineFocused,
        searchFocused,
    ) {
        component.setBackHandler {
            if (!isAndroidPlatform()) return@setBackHandler false
            when {
                rightDetailsVisible || transportControlsVisible || rightInfoVisible || archiveOverlayVisible -> {
                    rightDetailsVisible = false; transportControlsVisible = false
                    rightInfoVisible = false; archiveOverlayVisible = false; epgInfoVisible = false
                    infoPanelFocused = false
                    runCatching { focusRequester.requestFocus() }
                    true
                }
                searchFocused -> {
                    searchFocused = false
                    focusReturnNonce++
                    true
                }
                categoriesOpen -> {
                    categoriesOpen = false
                    focusReturnNonce++
                    true
                }
                // In fullscreen, Back first closes a focused EPG overlay or drawer.
                // In windowed mode a normal Back leaves Channels for the main menu:
                // merely having a timeline loaded must not trap the user here.
                immersive && timelineFocused -> {
                    timelineFocused = false
                    component.dispatch(ChannelsIntent.HideChannelTimeline)
                    runCatching { focusRequester.requestFocus() }
                    true
                }
                immersive && (drawerOpen || timelineState.channelId != null) -> {
                    drawerOpen = false
                    highlightedChannelId = null
                    component.dispatch(ChannelsIntent.HideChannelTimeline)
                    runCatching { focusRequester.requestFocus() }
                    true
                }
                immersive -> { onImmersiveChange(false); true }
                !immersive -> {
                    // AppShell supplies this callback; fall through to the app
                    // navigator if the screen is used without a home callback.
                    onBackHome?.invoke()
                    onBackHome != null
                }
                else -> false
            }
        }
        onDispose {
            component.setBackHandler { false }
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val channelListState = if (isAndroidPlatform()) rememberLazyListState() else TvListState.channelsListState
    val drawerListState = if (isAndroidPlatform()) rememberLazyListState() else channelListState
    val playerState by controller.state.collectAsState()
    val archiveSeekStepMs by component.archiveSeekStepMs.collectAsState()
    // WatchingLayout only cares about item.kind and diagnostics here — NOT
    // positionMs/durationMs (EpgInfoOverlay below reads those directly via its
    // own collectAsState(), which is the right scope for that). PlayerState is
    // a single data class covering everything including position, and that
    // field now ticks once a second during VOD/Archive playback (see
    // AndroidPlaybackController's position ticker) — without this
    // derivedStateOf, reading `playerState` directly here would mark this
    // entire ~1000-line composable (drawer, categories panel, EPG overlay
    // layout) dirty every second, purely because of a field it never uses.
    val playerItemKind by remember { derivedStateOf { playerState.item?.kind } }
    val playerDiagnostics by remember { derivedStateOf { playerState.diagnostics } }

    // Show the full-screen «Телепрограмма» info block on every channel switch.
    LaunchedEffect(state.selectedChannelId, epgInfoDurationSeconds) {
        val duration = epgInfoDurationSeconds ?: return@LaunchedEffect
        if (state.selectedChannelId == null) return@LaunchedEffect
        epgInfoVisible = true
        if (duration > 0) {
            delay(duration * 1000L)
            epgInfoVisible = false
        }
    }

    // A single RIGHT obeys the same timeout as the info setting. An explicit
    // details view (second RIGHT) and OK-opened transport controls never expire.
    LaunchedEffect(rightInfoVisible, rightDetailsVisible, transportControlsVisible, epgInfoDurationSeconds) {
        if (rightInfoVisible && !rightDetailsVisible && !transportControlsVisible) {
            val seconds = epgInfoDurationSeconds
            if (seconds == null) {
                rightInfoVisible = false
            } else if (seconds > 0) {
                delay(seconds * 1000L)
                rightInfoVisible = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Never auto-focus the search field on Android TV: focusing the
        // TextField opens the on-screen keyboard immediately. On Android TV the
        // focus goes to the first channel row (so the remote's Up/Down + OK work
        // right away); on desktop the video container keeps the focus.
        if (!isAndroidPlatform()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Android TV: when the surface becomes immersive (auto on channel select)
    // the channel list / search are not composed, so give focus to the video
    // container — OK toggles to the browse layout, LEFT slides the drawer in
    // over the video, RIGHT shows the programme info. The delay lets the new
    // (immersive) composition mount before the focus request lands.
    LaunchedEffect(immersive) {
        if (immersive && isAndroidPlatform()) {
            if (!drawerOpen && component.timeline.value.channelId != null) onHideTimeline()
            // The bottom-right EPG card belongs only to the channel-list focus.
            // When the user presses OK on the already-playing channel in the
            // browse layout, that row loses focus as we enter fullscreen; clear
            // the old highlight immediately so the card cannot remain stuck.
            highlightedChannelId = null
            rightInfoVisible = false
            rightDetailsVisible = false
            transportControlsVisible = false
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Windows: closing the channel list (immersive -> true, ChannelListPanel
    // unmounts) must hand keyboard focus back to the video, or Up/Down stops
    // zapping channels until the user clicks the video area by hand.
    LaunchedEffect(immersive) {
        if (immersive && !isAndroidPlatform()) {
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // If the video focus is dropped while immersive (surface recreation during
    // a channel switch, overlay redraw, …) re-claim it, so the remote works
    // without a preliminary OK press. The open drawer keeps its own focus.
    LaunchedEffect(videoFocused, immersive, drawerOpen, categoriesOpen, timelineState.channelId, searchFocused, infoPanelFocused) {
        if (isAndroidPlatform() && immersive && !videoFocused && !infoPanelFocused &&
            !transportControlsVisible && !drawerOpen &&
            !categoriesOpen && timelineState.channelId == null && !searchFocused) {
            delay(60)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .then(if (isAndroidPlatform()) Modifier.onFocusChanged {
                rootVideoFocused = it.isFocused
                if (!it.isFocused) {
                    okTimer?.cancel()
                    okTimer = null
                    okDownHeld = false
                }
            }.focusable() else Modifier.onFocusChanged { rootVideoFocused = it.isFocused }.focusable())
            .onPreviewKeyEvent { event ->
                // When the transport controls own focus, arrows/OK must reach
                // the individual buttons; never intercept them at the video root.
                if (isAndroidPlatform() && infoPanelFocused) return@onPreviewKeyEvent false
                if (isAndroidPlatform() && !immersive && windowedRemote.handle(
                    event = event,
                    pane = when {
                        categoriesOpen -> WindowedPane.CATEGORIES
                        videoFocused -> WindowedPane.VIDEO
                        component.timeline.value.channelId != null -> WindowedPane.PROGRAMMES
                        else -> WindowedPane.CHANNELS
                    },
                    onChannels = { categoriesOpen = false; focusReturnNonce++ },
                    onProgrammes = { onShowTimeline(highlightedChannelId ?: selectedChannel.id) },
                    onVideo = { videoFocusRequester.requestFocus() },
                    onFullscreen = {
                            if (component.timeline.value.channelId != null) onHideTimeline()
                            archivePreview = null
                            onToggleImmersive()
                    },
                )) return@onPreviewKeyEvent true
                val isInfo = event.key == Key.Info
                if (isInfo && event.type == KeyEventType.KeyUp && !drawerOpen && !categoriesOpen) {
                    if (rightInfoVisible) rightDetailsVisible = true else rightInfoVisible = true
                    epgInfoVisible = false
                    true
                } else if (isInfo) {
                    true
                } else {
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter
                // Android fullscreen OK: short = transport controls; long = TV guide.
                // длинное (≥ 500 мс) = ТВ-гид. Нельзя отдавать KeyDown обычному
                // обработчику: он сразу откроет browse, и длинное нажатие не
                // успеет сработать. (В browse/списке OK не трогаем — там своё
                // поведение строк каналов.)
                if (onOpenTvg != null && isOk && immersive && !drawerOpen && !categoriesOpen && component.timeline.value.channelId == null) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (!okDownHeld) {
                                okDownHeld = true
                                okLongFired = false
                                okTimer = okScope.launch {
                                    delay(LONG_PRESS_DELAY_MS)
                                    okLongFired = true
                                    // The screen changes while the physical OK is still held.
                                    // The following KeyUp must be consumed by the newly opened Guide,
                                    // otherwise it can be mistaken for a fresh short press.
                                    OkInputGate.suppressNextKeyUp = true
                                    onOpenTvg()
                                }
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            val wasHeld = okDownHeld
                            okDownHeld = false
                            okTimer?.cancel()
                            okTimer = null
                            // Короткое нажатие — обычное поведение.
                            if (wasHeld && !okLongFired) {
                                transportControlsVisible = true
                                rightInfoVisible = false
                                rightDetailsVisible = false
                                epgInfoVisible = false
                            }
                            true
                        }
                        else -> true
                    }
                } else {
                    if (isAndroidPlatform() && immersive && !drawerOpen &&
                        event.key == Key.DirectionRight && event.type == KeyEventType.KeyUp) {
                        rightKeyHeld = false
                        true
                    } else if (event.type == KeyEventType.KeyDown) {
                    when {
                        isAndroidPlatform() && !drawerOpen && !categoriesOpen &&
                            component.timeline.value.channelId == null &&
                            playerItemKind != PlaybackContentKind.VOD && androidHasArchive(selectedChannel) &&
                            (event.key == Key.MediaRewind || event.key == Key.MediaFastForward) -> {
                            val backward = event.key == Key.MediaRewind
                            controller.seekBy(if (backward) -archiveSeekStepMs else archiveSeekStepMs)
                            showArchiveOverlay()
                            true
                        }
                        // Android TV Back hierarchy: collapse ONE level per
                        // press instead of immediately leaving the screen.
                        // Level order (innermost first): categories panel ->
                        // channel list/drawer -> (nothing left) -> only then
                        // is the key NOT consumed, so it falls through to the
                        // normal navigation (Decompose) which exits the tab.
                        isAndroidPlatform() && immersive && categoriesOpen &&
                            event.key == Key.Back -> {
                            categoriesOpen = false
                            focusReturnNonce++
                            true
                        }
                        // Android TV fullscreen: один Back закрывает и список
                        // каналов, и панель передач вместе (они открываются
                        // одной кнопкой LEFT). Категории закрываются отдельным
                        // первым Back выше.
                        isAndroidPlatform() && immersive &&
                            (component.timeline.value.channelId != null || drawerOpen) &&
                            event.key == Key.Back -> {
                            if (component.timeline.value.channelId != null) onHideTimeline()
                            drawerCloseJob?.cancel()
                            drawerOpen = false
                            highlightedChannelId = null
                            timelineFocused = false
                            runCatching { focusRequester.requestFocus() }
                            true
                        }
                        isAndroidPlatform() && !immersive && categoriesOpen &&
                            event.key == Key.Back -> {
                            categoriesOpen = false
                            focusReturnNonce++
                            true
                        }
                        // Windowed/browse mode: Back returns to the main menu,
                        // whether or not EPG data is currently visible. The
                        // fullscreen toggle belongs to OK on the video only.
                        isAndroidPlatform() && !immersive && event.key == Key.Back -> {
                            onBackHome?.invoke()
                            onBackHome != null
                        }
                        // Android TV full-screen (immersive):
                        //  - OK/Enter opens transport controls (video
                        //    shrinks, channel list on the left, EPG panel below);
                        //  - LEFT slides the channel drawer in over the video;
                        //  - RIGHT toggles the programme/movie info overlay;
                        //  - UP/DOWN zaps to the previous/next channel.
                        // These fire ONLY while the video area holds focus: when
                        // a list row (drawer or browse list) is focused the key
                        // is left to the row's own OK handler.
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            component.timeline.value.channelId == null &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            transportControlsVisible = true
                            rightInfoVisible = false
                            rightDetailsVisible = false
                            epgInfoVisible = false
                            true
                        }
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            event.key == Key.DirectionLeft -> {
                            rightInfoVisible = false
                            openDrawer()
                            true
                        }
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            event.key == Key.DirectionRight -> {
                            // Handle a physical press once, never the remote's
                            // auto-repeat KeyDown events while the key is held.
                            if (!rightKeyHeld) {
                                rightKeyHeld = true
                                if (rightInfoVisible || rightDetailsVisible) {
                                    // Second RIGHT: persistent details.
                                    rightDetailsVisible = true
                                } else {
                                    rightInfoVisible = epgInfoDurationSeconds != null
                                    rightDetailsVisible = false
                                }
                                epgInfoVisible = false
                            }
                            true
                        }
                        // Android TV: RIGHT closes ONE level at a time — the
                        // categories panel first (if open), then the drawer —
                        // same "innermost first" order as Back above. Closing
                        // the categories with RIGHT moves the list focus to the
                        // TOP channel of the chosen category (the playing
                        // channel is not switched; that happens on OK).
                        isAndroidPlatform() && immersive && drawerOpen && categoriesOpen &&
                            event.key == Key.DirectionRight -> {
                            categoriesOpen = false
                            // Фокус возвращается на играющий канал, а не на верхний
                            focusReturnNonce++
                            true
                        }
                        // Wide Android guide: RIGHT is delegated to Compose focus navigation.
                        // The user can move channels -> programmes -> dates without closing the guide.
                        // Android TV: LEFT while the drawer is open but NOT the
                        // video (i.e. the channel list itself holds focus) opens
                        // the category picker — a second, distinct LEFT press
                        // from the one that opened the drawer in the first place.
                        // If the timeline panel happened to be open instead, LEFT
                        // switches to categories too (the two side panels are
                        // mutually exclusive — see ChannelCategoriesOrListPanel).
                        // Выделяем текущую выбранную категорию при открытии.
                        // LEFT inside the wide guide is handled by the focused channel row,
                        // so LEFT from the programme list can return to channels normally.
                        // Android TV: LEFT when the drawer is hidden but the
                        // programme panel is open (after RIGHT) brings the
                        // channel list back next to it.
                        isAndroidPlatform() && immersive && !drawerOpen && !categoriesOpen &&
                            component.timeline.value.channelId != null && !videoFocused && !searchFocused &&
                            event.key == Key.DirectionLeft -> {
                            openDrawer()
                            true
                        }
                        // Android TV fullscreen archive: UP/DOWN seek inside the
                        // archived programme. LEFT/RIGHT keep their universal
                        // fullscreen meanings: drawer and programme information.
                        // Once archive playback automatically reaches LIVE, this
                        // branch stops matching and UP/DOWN immediately return to
                        // channel zapping.
                        isAndroidPlatform() && immersive && !drawerOpen && !categoriesOpen &&
                            playerItemKind == PlaybackContentKind.ARCHIVE &&
                            (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                            val delta = if (event.key == Key.DirectionUp) archiveSeekStepMs else -archiveSeekStepMs
                            rightInfoVisible = false
                            // Показываем полосу перемотки на 3 секунды.
                            showArchiveOverlay()
                            controller.seekBy(delta)
                            true
                        }
                        // Android TV fullscreen ARCHIVE: DOWN while the seek bar
                        // overlay is visible activates the seek handle — LEFT/RIGHT
                        // then scrub instead of closing panels / toggling overlays.
                        isAndroidPlatform() && immersive && !drawerOpen && !categoriesOpen &&
                            playerItemKind == PlaybackContentKind.ARCHIVE &&
                            archiveOverlayVisible && !seekMode &&
                            event.key == Key.DirectionDown -> {
                            seekMode = true
                            showArchiveOverlay()
                            true
                        }
                        // Android TV fullscreen ARCHIVE: with the seek handle
                        // active, LEFT/RIGHT scrub the programme.
                        isAndroidPlatform() && immersive && !drawerOpen && !categoriesOpen &&
                            playerItemKind == PlaybackContentKind.ARCHIVE && seekMode &&
                            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
                            val delta = if (event.key == Key.DirectionRight) archiveSeekStepMs else -archiveSeekStepMs
                            rightInfoVisible = false
                            controller.seekBy(delta)
                            showArchiveOverlay()
                            true
                        }

                        // Android TV (full-screen): переключение каналов по UP/DOWN
                        // и CH+/CH- (преобразованы в DPAD в MainActivity).
                        // НЕ используем videoFocused — SurfaceView/AndroidView
                        // на Android TV может не получить Compose-фокус,
                        // поэтому videoFocused остаётся false и zapping не работает.
                        // LEFT/RIGHT и OK работали, потому что не проверяли videoFocused.
                        // Desktop: оставляем оригинальную логику с videoFocused.
                        if (isAndroidPlatform()) {
                            isAndroidPlatform() && immersive && !drawerOpen && !categoriesOpen &&
                                component.timeline.value.channelId == null && !timelineFocused &&
                                (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                        } else {
                            videoFocused &&
                                (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                        } -> {
                            val list = state.filteredChannels
                            val index = list.indexOfFirst { it.id == selectedChannel.id }
                            if (index >= 0) {
                                // With the list hidden, UP is the next channel and
                                // DOWN is the previous channel. The visible-list
                                // navigation is handled by the row itself and is
                                // intentionally left unchanged.
                                val target = if (event.key == Key.DirectionUp) index + 1 else index - 1
                                if (target in list.indices) onSelectChannel(list[target].id)
                            }
                            true
                        }
                        // LEFT/RIGHT keep their original fullscreen navigation:
                        // LEFT opens the channel list, RIGHT shows the programme
                        // description/info. This also applies while watching archive.
                        // Archive seeking is handled exclusively by UP/DOWN above.
                        // Space toggles play/pause (media library and archive).
                        event.key == Key.Spacebar -> {
                            onTogglePlayback()
                            true
                        }
                        isMediaPlayPauseKey(event) -> {
                            onTogglePlayback()
                            true
                        }
                        isMediaStopKey(event) -> {
                            onClosePlayer()
                            true
                        }
                        isChannelListToggle(event) && !searchFocused -> {
                            onToggleImmersive()
                            true
                        }
                        isMuteKey(event) && !searchFocused -> {
                            onToggleMute()
                            true
                        }
                        event.key == Key.PageUp && !searchFocused -> {
                            val list = state.filteredChannels
                            val index = list.indexOfFirst { it.id == selectedChannel.id }
                            if (index > 0) onSelectChannel(list[index - 1].id)
                            true
                        }
                        event.key == Key.PageDown && !searchFocused -> {
                            val list = state.filteredChannels
                            val index = list.indexOfFirst { it.id == selectedChannel.id }
                            if (index >= 0 && index < list.lastIndex) onSelectChannel(list[index + 1].id)
                            true
                        }
                        // Android TV windowed/browse mode: the channel list is
                        // always visible (no drawer to open first) — LEFT on
                        // the list itself opens the category picker, which
                        // REPLACES the list here (single panel, no room to
                        // push it aside like fullscreen does). If the timeline
                        // panel was open instead, LEFT switches to categories
                        // (the two side panels are mutually exclusive).
                        isAndroidPlatform() && !immersive && !categoriesOpen &&
                            !videoFocused && !searchFocused &&
                            event.key == Key.DirectionLeft -> {
                            if (component.timeline.value.channelId != null) {
                                onHideTimeline()
                                archivePreview = null
                                focusReturnNonce++
                            } else categoriesOpen = true
                            true
                        }
                        isAndroidPlatform() && !immersive && videoFocused && event.key == Key.DirectionLeft -> {
                            if (component.timeline.value.channelId != null) timelineFocusRequester.requestFocus()
                            else focusReturnNonce++
                            true
                        }
                        event.isCtrlPressed && event.key == Key.F -> {
                            runCatching { searchFocusRequester.requestFocus() }
                            true
                        }
                        else -> false
                    }
                } else false
                }
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (!immersive) {
                // Android TV browse layout: OK on the currently playing channel
                // row returns to the full-screen video (the "repeated OK");
                // OK on another channel plays it.
                val onBrowseSelectChannel: (String) -> Unit = { id ->
                    if (component.timeline.value.channelId != null) onHideTimeline()
                    if (isAndroidPlatform() && !immersive && id == selectedChannel.id) {
                        highlightedChannelId = null
                        onToggleImmersive()
                    } else {
                        onSelectChannel(id)
                    }
                }
                ChannelCategoriesOrListPanel(
                    categoriesOpen = isAndroidPlatform() && categoriesOpen,
                    onSwipeOpenCategories = { categoriesOpen = true },
                    state = state,
                    component = component,
                    now = now,
                    onCategorySelected = { category ->
                        selectCategory(category)
                    },
                    onArchiveProgramme = onArchiveProgramme,
                    onProgrammeHighlighted = { archivePreview = it },
                    onTimelineClosed = { onHideTimeline(); archivePreview = null; focusReturnNonce++ },
                    timelineFocusRequester = timelineFocusRequester,
                ) {
                    ChannelListPanel(
                        component = component,
                        state = state,
                        selectedChannel = selectedChannel,
                        showChannelNumbers = showChannelNumbers,
                        now = now,
                        channelListState = channelListState,
                        searchFocusRequester = searchFocusRequester,
                        expandedCategory = channelsExpandedCategory,
                        androidCategory = if (isAndroidPlatform()) {
                            // The list shows the channels of the selected
                            // category (or the playing channel's category when
                            // «Все» is active), never the accordion.
                            state.selectedCategory
                        } else {
                            null
                        },
                        focusReturnNonce = focusReturnNonce,
                        focusTopNonce = focusTopNonce,
                        onChannelHighlighted = { id -> highlightedChannelId = id },
                        onVisibleChannelsChanged = onVisibleChannelsChanged,
                        onCategoryToggle = { key ->
                            TvListState.channelsExpandedCategory.value =
                                if (TvListState.channelsExpandedCategory.value == key) null else key
                        },
                        onSearch = onSearch,
                        onSearchClear = onSearchClear,
                        onSearchFocusChanged = { focused ->
                            searchFocused = focused
                            if (!focused) runCatching { focusRequester.requestFocus() }
                        },
                        onSelectChannel = onBrowseSelectChannel,
                        onToggleFavorite = onToggleFavorite,
                        // Windows: the same «centre + focus the playing
                        // channel» behaviour Android TV already had — needed
                        // so reopening the list always lands back on the
                        // channel that's actually on air instead of wherever
                        // it was scrolled to last time.
                        autoFocusChannelId = selectedChannel.id,
                        onShowTimeline = if (isAndroidPlatform()) onShowTimeline else null,
                    )
                }
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // The video owns a fixed 16:9 area. EPG text, controls and
                // metadata scroll below it and never take height from the video.
                val windowedVideoHeight = minOf(maxWidth * 9f / 16f, maxHeight * 0.60f)
                Column(modifier = Modifier.fillMaxSize()) {
                    TvPlayerPanel(
                        controller = controller,
                        channelName = selectedChannel.name,
                        programmeStartMs = if (isAndroidPlatform() && playerItemKind == PlaybackContentKind.ARCHIVE) playingEpg.current?.start else null,
                        programmeDurationMs = if (isAndroidPlatform() && playerItemKind == PlaybackContentKind.ARCHIVE)
                            playingEpg.current?.let { (it.stop - it.start).coerceAtLeast(0) } ?: 0 else null,
                        onProgrammeSeek = if (isAndroidPlatform() && playerItemKind == PlaybackContentKind.ARCHIVE)
                            { position -> playingEpg.current?.let { (controller as? TimeShiftController)?.seekToTime(it.start + position) }; Unit } else null,
                        statusBottomInset = if (isAndroidPlatform() && immersive && !categoriesOpen && !drawerOpen &&
                            (epgInfoVisible || rightInfoVisible || rightDetailsVisible || archiveOverlayVisible || transportControlsVisible))
                            with(overlayDensity) { fullscreenInfoHeightPx.toDp() }.takeIf { it > 0.dp } ?: 200.dp else 0.dp,
                        channelNameVisible = channelNameVisible,
                        clockPosition = clockPosition,
                        showControls = !immersive && !isAndroidPlatform(),
                        rounded = !immersive,
                        showDiagnostics = showDiagnostics,
                        onVideoFocusedChange = { surfaceVideoFocused = it },
                        videoFocusRequester = videoFocusRequester,
                        onVideoTap = if (isAndroidPlatform()) onToggleImmersive else null,
                        onRetry = { onSelectChannel(selectedChannel.id) },
                        onClose = onClosePlayer,
                        onTogglePlayback = onTogglePlayback,
                        onToggleFullscreen = onToggleFullscreen,
                        channelFavorite = selectedChannel.isFavorite,
                        onToggleFavorite = { onToggleFavorite(selectedChannel.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!immersive && isAndroidPlatform()) Modifier.height(windowedVideoHeight) else Modifier.weight(1f)),
                    )
                    Column(
                        if (!immersive && isAndroidPlatform()) Modifier.weight(1f).fillMaxWidth() else Modifier,
                    ) {
                        if (!immersive && isAndroidPlatform()) {
                            val programme = playingEpg.current
                            val at = if (playerItemKind == PlaybackContentKind.ARCHIVE)
                                (playerState.item?.archiveEpgAnchorMs ?: now) + playerState.positionMs else now
                            val duration = programme?.let { (it.stop - it.start).coerceAtLeast(0) } ?: 0L
                            AndroidWindowedPlaybackInfo(
                                programme = windowedDescriptionProgramme(
                                    selectedChannel.id, highlightedChannelId ?: state.focusedChannelId,
                                    timelineState, archivePreview, channelEpg, playingEpg),
                                paused = playerState.state == PlaybackState.PAUSED,
                                positionMs = programme?.let { (at - it.start).coerceAtLeast(0) } ?: 0L,
                                durationMs = if (androidHasArchive(selectedChannel)) duration else 0L,
                                onTogglePlayback = onTogglePlayback,
                                onSeekBy = controller::seekBy,
                                seekStepMs = archiveSeekStepMs,
                                canPause = playerItemKind == PlaybackContentKind.ARCHIVE,
                                isLiveChannel = playerItemKind == PlaybackContentKind.LIVE,
                                diagnostics = playerState.diagnostics,
                                onLive = if (playerItemKind == PlaybackContentKind.ARCHIVE)
                                    ({ (controller as? TimeShiftController)?.returnToLive(); Unit }) else null,
                                liveEdgeFraction = if (playerItemKind == PlaybackContentKind.ARCHIVE &&
                                    programme != null && now in programme.start..programme.stop && duration > 0)
                                    ((now - programme.start).toFloat() / duration).coerceIn(0f, 1f) else null,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                                    .onFocusChanged { infoPanelFocused = it.hasFocus },
                            )
                        }
                        if (!immersive && !isAndroidPlatform()) {
                            EpgPanel(
                                epg = channelEpg,
                                isArchive = playerItemKind == PlaybackContentKind.ARCHIVE,
                                archiveAvailable = selectedChannel.hasCatchupMechanism,
                                onArchiveProgramme = onArchiveProgramme,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (immersive) {
                    // Hover zone on the left edge: the channel drawer slides in
                    // on mouse entry and hides ~2 s after the pointer leaves.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(28.dp)
                            .fillMaxHeight()
                            .zIndex(5f)
                            .hoverEnterExit(
                                enter = {
                                    edgeHovered = true
                                    openDrawer()
                                },
                                exit = {
                                    edgeHovered = false
                                    scheduleDrawerCloseIfAway()
                                },
                            ),
                    )

                    // A timeline query can be expensive on TV hardware. Wait
                    // until D-pad movement settles; cancellation prevents a
                    // backlog of EPG jobs and avoids stale-day flicker.
                    if (isAndroidPlatform() && drawerOpen && !categoriesOpen) {
                        LaunchedEffect(highlightedChannelId, selectedChannel.id) {
                            val id = highlightedChannelId ?: selectedChannel.id
                            delay(160)
                            if (component.timeline.value.channelId != id) onShowTimeline(id)
                        }
                    }
                    if (isAndroidPlatform() && drawerOpen) {
                        AndroidWideGuide(
                            channels = state.filteredChannels,
                            playingChannelId = selectedChannel.id,
                            focusedChannelId = highlightedChannelId,
                            timeline = timelineState,
                            initialProgrammeTimeMs = guidePlaybackTimeMs,
                            nowPlaying = nowPlaying,
                            now = now,
                            showNumbers = showChannelNumbers,
                            showCategories = categoriesOpen,
                            descriptionOnRight = programmeDescriptionRight,
                            categories = state.categories,
                            selectedCategory = state.selectedCategory,
                            onToggleCategories = { categoriesOpen = true },
                            onChooseCategory = { category ->
                                selectCategory(category)
                                categoriesOpen = false
                                focusReturnNonce++
                                highlightedChannelId = null
                            },
                            onVisibleChannelsChanged = onVisibleChannelsChanged,
                            onChannelFocus = { id ->
                                // Never open a new database timeline on every
                                // key repeat; the effect below coalesces input.
                                highlightedChannelId = id
                            },
                            onPlayChannel = { id ->
                                drawerOpen = false
                                categoriesOpen = false
                                highlightedChannelId = null
                                if (component.timeline.value.channelId != null) onHideTimeline()
                                if (id != selectedChannel.id) onSelectChannel(id)
                            },
                            onPlayArchive = { id, programme ->
                                drawerOpen = false
                                categoriesOpen = false
                                highlightedChannelId = null
                                onHideTimeline()
                                component.dispatch(ChannelsIntent.ArchiveProgrammeClicked(programme, id))
                            },
                            onClose = {
                                drawerOpen = false
                                categoriesOpen = false
                                onHideTimeline()
                            },
                            modifier = Modifier.fillMaxSize().zIndex(10f),
                        )
                    }
                    if (!isAndroidPlatform()) Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .zIndex(10f)
                            .then(if (isAndroidPlatform()) Modifier.pointerInput(drawerOpen, categoriesOpen) {
                                var fromEdge = false
                                var distance = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { position ->
                                        fromEdge = drawerOpen && !categoriesOpen && position.x < 42.dp.toPx()
                                        distance = 0f
                                    },
                                    onHorizontalDrag = { change, delta ->
                                        if (fromEdge) {
                                            distance += delta
                                            if (distance > 90.dp.toPx()) {
                                                categoriesOpen = true
                                                fromEdge = false
                                                change.consume()
                                            }
                                        }
                                    },
                                )
                            } else Modifier)
                            .hoverEnterExit(
                                enter = {
                                    drawerHovered = true
                                    openDrawer()
                                },
                                exit = {
                                    drawerHovered = false
                                    scheduleDrawerCloseIfAway()
                                },
                            ),
                    ) {
                        // Android TV: categories are the LEFTMOST panel.
                        // Keeping this block first in the Row is important: Compose
                        // lays the panels out in declaration order, so putting the
                        // category picker after the channel/timeline panels made it
                        // slide in from the RIGHT.
                        // Android TV only: a second LEFT (list already focused,
                        // drawer open) slides this in from the LEFT, before the
                        // channel list. All three panels therefore read
                        // categories -> channels -> programmes.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isAndroidPlatform() && drawerOpen && categoriesOpen,
                            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                        ) {
                            ChannelCategoriesPanel(
                                state = state,
                                onCategorySelected = { category ->
                                    selectCategory(category)
                                },
                            )
                        }


                        androidx.compose.animation.AnimatedVisibility(
                            visible = drawerOpen,
                            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                        ) {
                            ChannelImmersiveDrawer(
                        component = component,
                        state = state,
                                selectedChannel = selectedChannel,
                                showChannelNumbers = showChannelNumbers,
                                now = now,
                                searchFocusRequester = searchFocusRequester,
                                listState = drawerListState,
                                expandedCategory = channelsExpandedCategory,
                                androidCategory = if (isAndroidPlatform()) {
                                    state.selectedCategory
                                } else {
                                    null
                                },
                                focusReturnNonce = focusReturnNonce,
                                focusTopNonce = focusTopNonce,
                                onChannelHighlighted = { id ->
                                    highlightedChannelId = id
                                    // Панель передач СЛЕДУЕТ за фокусом в списке
                                    // каналов: при листании (без OK) показываем
                                    // программу канала, на котором стоит фокус.
                                    if (isAndroidPlatform() && id != null && drawerOpen) {
                                        onShowTimeline(id)
                                    }
                                },
                                onCategoryToggle = { key ->
                                    TvListState.channelsExpandedCategory.value =
                                        if (TvListState.channelsExpandedCategory.value == key) null else key
                                },
                                onSearch = onSearch,
                                onSearchClear = onSearchClear,
                                onSearchFocusChanged = { searchFocused = it },
                                onSelectChannel = { id ->
                                    drawerOpen = false
                                    categoriesOpen = false
                                    highlightedChannelId = null
                                    if (component.timeline.value.channelId != null) onHideTimeline()
                                    // The already-playing channel is not re-selected:
                                    // clicking its row only closes the drawer (no
                                    // stream restart).
                                    if (id != selectedChannel.id) onSelectChannel(id)
                                },
                                onToggleFavorite = onToggleFavorite,
                                onBackHome = onBackHome,
                                onShowTimeline = onShowTimeline,
                            )
                        }

                        // Android TV only: the clock icon on a channel row opens
                        // this instead — mutually exclusive with the categories
                        // panel above (see the LEFT/RIGHT handlers), same
                        // push-layout slot. Stays visible after RIGHT hides the
                        // channel list (plain programme list over the video).
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isAndroidPlatform() && component.timeline.value.channelId != null,
                            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                        ) {
                            val timelineChannel = state.channels.firstOrNull { it.id == timelineState.channelId }
                            if (timelineChannel != null) {
                                ChannelTimelinePanel(
                                    channel = timelineChannel,
                                    programmes = timelineState.programmes,
                                    loading = timelineState.loading,
                                    now = now,
                                    archiveAvailable = timelineChannel.hasCatchupMechanism,
                                    focusRequester = timelineFocusRequester,
                                    onArchiveProgramme = { programme ->
                                        // Запускаем архив: убираем список каналов и
                                        // панель передач с экрана, остаётся видео.
                                        drawerCloseJob?.cancel()
                                        drawerOpen = false
                                        highlightedChannelId = null
                                        timelineFocused = false
                                        onHideTimeline()
                                        // The focused channel in the drawer is not
                                        // necessarily the currently playing one.
                                        component.dispatch(
                                            ChannelsIntent.ArchiveProgrammeClicked(
                                                programme = programme,
                                                channelId = timelineChannel.id,
                                            ),
                                        )
                                    },
                                    onBack = {
                                        // Programme-only mode: a single Back closes
                                        // only the programme panel and immediately
                                        // restores the video focus. Do not navigate
                                        // to another app/tab and do not require a
                                        // second Back press.
                                        onHideTimeline()
                                        timelineFocused = false
                                        rightInfoVisible = false
                                        runCatching { focusRequester.requestFocus() }
                                    },
                                    onRestartCurrent = {
                                        // Перезапуск текущей передачи: скрываем
                                        // панели, переключаем канал заново (стрим
                                        // стартует с начала).
                                        drawerCloseJob?.cancel()
                                        drawerOpen = false
                                        highlightedChannelId = null
                                        timelineFocused = false
                                        onHideTimeline()
                                        onSelectChannel(selectedChannel.id)
                                    },
                                    transparentBackground = true,
                                )
                            }
                        }
                    }

                    // Full-screen info overlay (нижняя полоса на всю ширину) —
                    // показывается при переключении канала (auto-hide) и по
                    // RIGHT; для LIVE — текущая передача, для архив/VOD — плюс
                    // шкала перемотки. Угловая карточка (EpgCornerPanel) убрана:
                    // описание передачи теперь живёт в панели передач справа от
                    // списка каналов.
                    // Полоса перемотки/инфо в полноэкранном режиме: обычная
                    // инфо-полоса (LIVE при смене канала, RIGHT) + оверлей
                    // перемотки архива (появляется при UP/DOWN/OK, гаснет через 3 с).
                    // НЕ показываем инфо-панель когда открыты категории.
                    if (immersive && !categoriesOpen && (!isAndroidPlatform() || !drawerOpen) &&
                        (epgInfoVisible || rightInfoVisible || rightDetailsVisible || archiveOverlayVisible || transportControlsVisible)) {
                        if (isAndroidPlatform()) AndroidProgrammeOverlay(
                            channel = selectedChannel,
                            epg = playingEpg.takeIf { it.channelId == selectedChannel.id } ?: ChannelEpg(channelId = selectedChannel.id),
                            controller = controller, seekStepMs = archiveSeekStepMs,
                            interactive = transportControlsVisible,
                            detailsVisible = rightDetailsVisible,
                            onTogglePlayback = {
                                showPlayPauseIcon(playerState.state == PlaybackState.PLAYING)
                                onTogglePlayback()
                            },
                            onFocus = { infoPanelFocused = it },
                            onClose = {
                                rightInfoVisible = false; rightDetailsVisible = false
                                transportControlsVisible = false
                                epgInfoVisible = false; archiveOverlayVisible = false
                                infoPanelFocused = false
                                focusRequester.requestFocus()
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(8f)
                                .onSizeChanged { fullscreenInfoHeightPx = it.height },
                        ) else EpgInfoOverlay(
                            channel = selectedChannel,
                            programme = nowPlaying[selectedChannel.id],
                            diagnostics = playerDiagnostics,
                            controller = controller,
                            seekStepMs = archiveSeekStepMs,
                            showSeekBar = playerItemKind != PlaybackContentKind.LIVE,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .zIndex(8f),
                        )
                    }

                    // Значок паузы/воспроизведения по центру (полноэкранный
                    // архив), гаснет через 3 секунды.
                    if (immersive && playPauseIconVisible && !isAndroidPlatform()) {
                        TvPlayPauseIcon(
                            paused = if (isAndroidPlatform()) playerState.state == PlaybackState.PAUSED else !playPauseIconPlaying,
                            modifier = Modifier.align(Alignment.Center).zIndex(9f),
                            circleSize = 116.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelListPanel(
    component: ChannelsComponent,
    state: ChannelsState,
    selectedChannel: Channel,
    showChannelNumbers: Boolean,
    now: Long,
    channelListState: LazyListState,
    searchFocusRequester: FocusRequester,
    expandedCategory: String?,
    /**
     * Android TV: when set, the list renders ONLY the channels of this category
     * (the selected category, or the playing channel's category when «Все») as
     * a flat list — no accordion, no «Избранное», no category headers.
     * Categories and favorites live exclusively in the categories panel.
     * Desktop keeps the accordion.
     */
    androidCategory: String? = null,
    /**
     * Bumped whenever the categories panel closes with Back; the row of the
     * currently playing channel is (re)focused so the remote keeps working.
     */
    focusReturnNonce: Int = 0,
    /**
     * Bumped whenever the categories panel closes with RIGHT / after picking a
     * category; the TOP channel of the chosen category is focused (the playing
     * channel is NOT switched).
     */
    focusTopNonce: Int = 0,
    /** Reports the channel whose row currently holds the D-pad focus. */
    onChannelHighlighted: (String?) -> Unit = {},
    /** Reports only channel rows currently inside the LazyColumn viewport. */
    onVisibleChannelsChanged: (Set<String>) -> Unit = {},
    onCategoryToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchClear: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSelectChannel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    /**
     * When set (Android TV "browse" layout), the row of this channel is focused
     * on mount instead of the first row. The watching surface passes the
     * currently playing channel, so a repeated OK (on the same row) returns to
     * the full-screen video without touching any other channel.
     */
    autoFocusChannelId: String? = null,
    /** Android TV: clock icon on a row — opens the EPG timeline for that channel. */
    onShowTimeline: ((String) -> Unit)? = null,
) {
    val nowPlaying by component.nowPlaying.collectAsState()
    val actuallyPlayingId by remember(component) {
        component.playbackController.state.map { player ->
            player.item?.takeIf { player.state == PlaybackState.PLAYING && it.kind != PlaybackContentKind.VOD }?.id
        }.distinctUntilChanged()
    }.collectAsState(initial = null)

    // Android TV: focus the channel row so the remote's Up/Down + OK work
    // immediately without the on-screen keyboard popping up. The target is the
    // row of [autoFocusChannelId] (falling back to the first channel row).
    val channelFocusRequester = remember { FocusRequester() }
    val androidRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val androidFocusManager = LocalFocusManager.current
    val topChannelFocusRequester = remember { FocusRequester() }
    // Precomputed in @Composable scope (LazyListScope is not composable).
    val queryBlankEntries = remember(
        state.channels, state.categories, state.categoryCounts, state.favoritesCount,
        expandedCategory, androidCategory,
    ) {
        buildChannelEntries(state, expandedCategory, androidCategory)
    }

    // Real viewport-driven EPG: only rows actually visible on Android TV are
    // requested. snapshotFlow observes scrolling without putting the viewport
    // into ChannelsState, so scrolling does not recompose the whole screen.
    LaunchedEffect(channelListState, queryBlankEntries, androidCategory, state.query, state.filteredChannels) {
        if (!isAndroidPlatform()) return@LaunchedEffect
        snapshotFlow {
            channelListState.layoutInfo.visibleItemsInfo
                .mapNotNull { info ->
                    if (state.query.isNotBlank()) state.filteredChannels.getOrNull(info.index)?.id
                    else (queryBlankEntries.getOrNull(info.index) as? ChannelEntry)?.channel?.id
                }
                .toSet()
        }.distinctUntilChanged().collect { ids ->
            onVisibleChannelsChanged(ids)
        }
    }
    val focusRowIndex = remember(queryBlankEntries, state.filteredChannels, autoFocusChannelId) {
        if (state.query.isNotBlank()) {
            state.filteredChannels.indexOfFirst { it.id == autoFocusChannelId }
                .takeIf { it >= 0 }
                ?: 0
        } else {
            queryBlankEntries.indexOfFirst { it is ChannelEntry && it.channel.id == autoFocusChannelId }
                .takeIf { it >= 0 }
                ?: queryBlankEntries.indexOfFirst { it is ChannelEntry }
        }
    }
    // Channel row indices for wrap-around navigation (flat Android list).
    val channelRowIndices = remember(queryBlankEntries) {
        queryBlankEntries.mapIndexedNotNull { index, entry -> if (entry is ChannelEntry) index else null }
    }
    var focusedRow by remember(if (isAndroidPlatform()) state.query else queryBlankEntries, autoFocusChannelId) { mutableIntStateOf(focusRowIndex) }
    val listScope = rememberCoroutineScope()
    var listScrollJob by remember { mutableStateOf<Job?>(null) }

    fun scrollListToCentered(index: Int, animate: Boolean, onSettled: (() -> Unit)? = null) {
        listScrollJob?.cancel()
        val state = channelListState
        listScrollJob = listScope.launch {
            if (index < 0) return@launch
            if (isAndroidPlatform()) withFrameNanos { }
            val info = state.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
            val rowHeight = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            val target = if (rowHeight > 0) ((viewport - rowHeight) / 2).coerceAtLeast(0) else 0
            if (animate) {
                state.animateScrollToItem(index, -target)
            } else {
                runCatching { state.scrollToItem(index, -target) }
            }
            withFrameNanos { }
            onSettled?.invoke()
        }
    }
    // Initial focus: the currently playing channel's row (autoFocusChannelId),
    // centred on screen, falling back to the first row. Android: also
    // restore saved position if available, or use currently playing channel.
    LaunchedEffect(state.filteredChannels, state.categories, autoFocusChannelId, state.selectedCategory) {
        if (state.channels.isEmpty() || isAndroidPlatform()) return@LaunchedEffect
        // На Android TV пробуем восстановить сохраненную позицию для текущей категории
        val targetIndex = if (isAndroidPlatform()) {
            val saved = TvListState.channelsFocusPosition[androidCategory]
            if (saved != null && saved in channelRowIndices.indices) {
                channelRowIndices.getOrNull(saved)
            } else {
                // Fallback: focus the playing channel or the first one
                queryBlankEntries.indexOfFirst { it is ChannelEntry && it.channel.id == autoFocusChannelId }
                    .takeIf { it >= 0 }
                    ?: queryBlankEntries.indexOfFirst { it is ChannelEntry }
            }
        } else {
            queryBlankEntries.indexOfFirst { it is ChannelEntry && it.channel.id == autoFocusChannelId }
                .takeIf { it >= 0 }
                ?: queryBlankEntries.indexOfFirst { it is ChannelEntry }
        }
        if (targetIndex != null && targetIndex >= 0) {
            focusedRow = targetIndex
            scrollListToCentered(targetIndex, animate = false) {
                runCatching { channelFocusRequester.requestFocus() }
            }
        }
    }
    // reacts to expandedCategory/androidCategory changes (category-picker
    // driven refocus). Kept keyed exactly as before — expanding this to
    // desktop would refire on every manual category-accordion click too
    // (see the Windows-only effect right below, which fires once per panel
    // mount instead).
    val androidRowIds = if (state.query.isNotBlank()) state.filteredChannels.map { it.id }
        else queryBlankEntries.mapNotNull { (it as? ChannelEntry)?.channel?.id }
    var handledTopNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(androidRowIds, androidCategory, state.query, focusReturnNonce, focusTopNonce) {
        if (!isAndroidPlatform() || androidRowIds.isEmpty()) return@LaunchedEffect
        val forceTop = focusTopNonce != handledTopNonce
        handledTopNonce = focusTopNonce
        // Opening the drawer must restore the REAL playing channel, not the
        // last row that happened to have D-pad focus. FocusChannel only records
        // navigation focus and must never become the source of truth for playback.
        val playingId = component.playbackController.state.value.item
            ?.takeIf { it.kind != PlaybackContentKind.VOD }?.id
        val savedId = state.focusedChannelIds[state.selectedCategory] ?: state.focusedChannelId
        val id = if (!forceTop) {
            resolveAndroidChannelFocus(androidRowIds, playingId, autoFocusChannelId, false)
        } else {
            resolveAndroidChannelFocus(androidRowIds, savedId, autoFocusChannelId, true)
        }
        focusedRow = if (state.query.isNotBlank()) androidRowIds.indexOf(id)
            else queryBlankEntries.indexOfFirst { it is ChannelEntry && it.channel.id == id }
        scrollListToCentered(focusedRow, animate = false) {
            androidRequesters[id]?.let { runCatching { it.requestFocus() } }
        }
    }
    // Windows: centre + focus the playing channel exactly once when this
    // panel mounts — i.e. when the list is actually (re)opened (immersive
    // toggled off, or the Channels tab re-entered). Deliberately `Unit`-keyed
    // rather than sharing the Android effect's keys above: reacting to
    // `expandedCategory` would re-jump to the playing channel every time the
    // user manually expands/collapses a DIFFERENT category by hand, which is
    // not what was asked for and would fight normal accordion browsing.
    LaunchedEffect(Unit) {
        if (!isAndroidPlatform() && state.channels.isNotEmpty()) {
            focusedRow = focusRowIndex
            scrollListToCentered(focusRowIndex, animate = false) {
                runCatching { channelFocusRequester.requestFocus() }
            }
        }
    }
    Column(
        modifier = Modifier
            // Android TV: wider list — channel names must fit; the category
            // count lives next to the category name in the category picker.
            .width(if (isAndroidPlatform()) 400.dp else 380.dp)
            .fillMaxHeight(),
    ) {
        // Android TV: no header — the list uses the full height (the category
        // count is shown in the category picker instead).
        if (!isAndroidPlatform()) {
            Text(
                text = if (androidCategory != null) {
                    "${state.channels.count { it.category == androidCategory }} · $androidCategory"
                } else {
                    uiText("{0} каналов", state.channels.size)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
            )
        }
        // Android TV: no in-list search — the dedicated Search screen (main
        // menu) covers it; the space is used for more channel rows.
        if (!isAndroidPlatform()) {
            TvSearchField(
                value = state.query,
                onValueChange = onSearch,
                onClear = onSearchClear,
                placeholder = uiText("Поиск каналов"),
                modifier = Modifier
                    .padding(bottom = TVNextDimens.ItemSpacingSmall)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onSearchFocusChanged(it.isFocused) },
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            state = channelListState,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (!isAndroidPlatform() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val rows = if (state.query.isNotBlank()) {
                        state.filteredChannels.indices.toList()
                    } else {
                        channelRowIndices
                    }
                    if (rows.isEmpty()) return@onPreviewKeyEvent false
                    val currentPosition = rows.indexOf(focusedRow).let { if (it >= 0) it else 0 }
                    val targetPosition = (currentPosition + direction).mod(rows.size)
                    val targetRow = rows[targetPosition]
                    if (targetRow == focusedRow) return@onPreviewKeyEvent true
                    focusedRow = targetRow
                    val targetId = if (state.query.isNotBlank()) state.filteredChannels[targetRow].id
                        else (queryBlankEntries[targetRow] as ChannelEntry).channel.id
                    listScrollJob?.cancel()
                    val visible = channelListState.layoutInfo.visibleItemsInfo.any { it.index == targetRow }
                    if (visible) {
                        androidRequesters[targetId]?.requestFocus()
                    } else {
                        // Remove the old ring before moving an off-screen row into view.
                        androidFocusManager.clearFocus(force = true)
                        listScrollJob = listScope.launch {
                            channelListState.scrollToItem(targetRow)
                            withFrameNanos { }
                            androidRequesters[targetId]?.let { runCatching { it.requestFocus() } }
                        }
                    }
                    true
                },
        ) {
            if (state.query.isNotBlank()) {
                itemsIndexed(state.filteredChannels, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        selected = channel.id == selectedChannel.id,
                        playing = isAndroidPlatform() && channel.id == actuallyPlayingId,
                        showNumber = showChannelNumbers,
                        nowPlaying = nowPlaying[channel.id],
                        now = now,
                        onClick = { onSelectChannel(channel.id) },
                        onToggleFavorite = { onToggleFavorite(channel.id) },
                        onHighlighted = { highlighted ->
                            if (highlighted) {
                                focusedRow = index
                                onChannelHighlighted(channel.id)
                                component.dispatch(ChannelsIntent.FocusChannel(channel.id))
                            }
                        },
                        // Archive support does not depend on a current EPG entry.
                        onShowTimeline = if (if (isAndroidPlatform()) androidHasArchive(channel) else channel.hasCatchupMechanism && nowPlaying[channel.id] != null) {
                            onShowTimeline?.let { show -> { show(channel.id) } }
                        } else {
                            null
                        },
                        modifier = when {
                            isAndroidPlatform() -> Modifier.focusRequester(androidRequesters.getOrPut(channel.id) { FocusRequester() })
                            index == 0 && focusTopNonce > 0 && !isAndroidPlatform() -> {
                                Modifier.focusRequester(topChannelFocusRequester)
                            }
                            index == focusedRow -> Modifier.focusRequester(channelFocusRequester)
                            else -> Modifier
                        },
                    )
                }
                if (state.filteredChannels.isEmpty()) {
                    item {
                        Text(
                            text = uiText("Ничего не найдено"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = TVNextDimens.SectionSpacing),
                        )
                    }
                }
            } else {
                val entries = queryBlankEntries
                itemsIndexed(
                    entries,
                    key = { _, entry ->
                        when (entry) {
                            is CategoryEntry -> "cat:${entry.key}"
                            is ChannelEntry -> "ch:${entry.channel.id}"
                            else -> entry.toString()
                        }
                    },
                ) { index, entry ->
                    when (entry) {
                        is CategoryEntry -> CategoryHeader(
                            title = entry.title,
                            count = entry.count,
                            expanded = entry.expanded,
                            onClick = {
                                // Toggle driven from the stable watching layer
                                // so both the window list and the full-screen
                                // drawer share the same category. The toggle
                                // reads the CURRENT state there, so a stale
                                // composition snapshot can never swallow a click.
                                onCategoryToggle(entry.key)
                            },
                        )
                        is ChannelEntry -> ChannelRow(
                            channel = entry.channel,
                            selected = entry.channel.id == selectedChannel.id,
                            playing = isAndroidPlatform() && entry.channel.id == actuallyPlayingId,
                            showNumber = showChannelNumbers,
                            nowPlaying = nowPlaying[entry.channel.id],
                            now = now,
                            onClick = { onSelectChannel(entry.channel.id) },
                            onToggleFavorite = { onToggleFavorite(entry.channel.id) },
                            onHighlighted = { highlighted ->
                            if (highlighted) {
                                focusedRow = index
                                onChannelHighlighted(entry.channel.id)
                                if (isAndroidPlatform()) component.dispatch(ChannelsIntent.FocusChannel(entry.channel.id))
                            }
                        },
                            // Archive support does not depend on a current EPG entry.
                            onShowTimeline = if (if (isAndroidPlatform()) androidHasArchive(entry.channel) else entry.channel.hasCatchupMechanism && nowPlaying[entry.channel.id] != null) {
                                onShowTimeline?.let { show -> { show(entry.channel.id) } }
                            } else {
                                null
                            },
                            modifier = when {
                            isAndroidPlatform() -> Modifier.focusRequester(androidRequesters.getOrPut(entry.channel.id) { FocusRequester() })
                                index == 0 && focusTopNonce > 0 && !isAndroidPlatform() -> {
                                Modifier.focusRequester(topChannelFocusRequester)
                            }
                                index == focusedRow -> Modifier.focusRequester(channelFocusRequester)
                                else -> Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelImmersiveDrawer(
    component: ChannelsComponent,
    state: ChannelsState,
    selectedChannel: Channel,
    showChannelNumbers: Boolean,
    now: Long,
    searchFocusRequester: FocusRequester,
    listState: LazyListState,
    expandedCategory: String?,
    /**
     * Android TV: when set, the drawer renders ONLY the channels of this
     * category (the selected category, or the playing channel's category when
     * «Все») as a flat list — no accordion, no «Избранное», no category
     * headers. Categories and favorites live exclusively in the categories
     * panel. Desktop keeps the accordion.
     */
    androidCategory: String? = null,
    /**
     * Bumped whenever the categories panel closes with Back; the row of the
     * currently playing channel is (re)focused so the remote keeps working.
     */
    focusReturnNonce: Int = 0,
    /**
     * Bumped whenever the categories panel closes with RIGHT / after picking a
     * category; the TOP channel of the chosen category is focused (the playing
     * channel is NOT switched).
     */
    focusTopNonce: Int = 0,
    /** Reports the channel whose row currently holds the D-pad focus. */
    onChannelHighlighted: (String?) -> Unit = {},
    onCategoryToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchClear: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSelectChannel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onBackHome: (() -> Unit)? = null,
    /** Android TV: clock icon on a row — opens the EPG timeline for that channel. */
    onShowTimeline: ((String) -> Unit)? = null,
) {
    val nowPlaying by component.nowPlaying.collectAsState()
    val actuallyPlayingId by remember(component) {
        component.playbackController.state.map { player ->
            player.item?.takeIf { player.state == PlaybackState.PLAYING && it.kind != PlaybackContentKind.VOD }?.id
        }.distinctUntilChanged()
    }.collectAsState(initial = null)

    // Android TV: when the drawer opens, focus the row of the currently playing
    // channel — not the search field (which would pop the on-screen keyboard
    // over the video).
    val currentChannelFocusRequester = remember { FocusRequester() }
    val androidRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val androidFocusManager = LocalFocusManager.current
    val topChannelFocusRequester = remember { FocusRequester() }
    val entries = remember(
        state.channels, state.categories, state.categoryCounts, state.favoritesCount,
        expandedCategory, androidCategory,
    ) {
        buildChannelEntries(state, expandedCategory, androidCategory)
    }
    // Indices of the channel rows inside [entries] (flat list on Android TV,
    // category headers interleaved on desktop). Used for wrap-around focus.
    val channelRowIndices = remember(entries) {
        entries.mapIndexedNotNull { index, entry -> if (entry is ChannelEntry) index else null }
    }
    val currentChannelIndex = remember(entries, selectedChannel.id) {
        if (state.query.isNotBlank() || isAndroidPlatform()) {
            entries.indexOfFirst { it is ChannelEntry && it.channel.id == selectedChannel.id }
                .takeIf { it >= 0 }
                ?: 0
        } else {
            entries.indexOfFirst { it is ChannelEntry }
        }
    }
    // Keeps the row the user is currently browsing; drives the wrap-around.
    var focusedRow by remember(if (isAndroidPlatform()) state.query else entries, selectedChannel.id) { mutableIntStateOf(currentChannelIndex) }
    val drawerScope = rememberCoroutineScope()
    var drawerScrollJob by remember { mutableStateOf<Job?>(null) }

    // Centres the given channel row vertically so the focused channel sits in
    // the middle of the screen (per requirement), not at the top. Computes the
    // centring offset FIRST (from the current layout), then scrolls ONCE —
    // either instantly (auto-repeat) or animated (single press) — straight to
    // the centred position. No "to top then jump back" flicker.
    fun scrollDrawerToCentered(index: Int, animate: Boolean, onSettled: (() -> Unit)? = null) {
        drawerScrollJob?.cancel()
        val state = listState
        drawerScrollJob = drawerScope.launch {
            if (index < 0) return@launch
            if (isAndroidPlatform()) withFrameNanos { }
            val info = state.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
            // Rows have a uniform height; use any visible row's size, or fall
            // back to a plain scroll if nothing is laid out yet.
            val rowHeight = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            val target = if (rowHeight > 0) ((viewport - rowHeight) / 2).coerceAtLeast(0) else 0
            if (animate) {
                state.animateScrollToItem(index, -target)
            } else {
                runCatching { state.scrollToItem(index, -target) }
            }
            withFrameNanos { }
            onSettled?.invoke()
        }
    }
    val androidDrawerIds = if (state.query.isNotBlank()) state.filteredChannels.map { it.id }
        else entries.mapNotNull { (it as? ChannelEntry)?.channel?.id }
    var handledTopNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(androidDrawerIds, androidCategory, state.query, focusReturnNonce, focusTopNonce) {
        if (!isAndroidPlatform() || androidDrawerIds.isEmpty()) return@LaunchedEffect
        val forceTop = focusTopNonce != handledTopNonce
        handledTopNonce = focusTopNonce
        val savedId = state.focusedChannelIds[state.selectedCategory] ?: state.focusedChannelId
        val id = resolveAndroidChannelFocus(androidDrawerIds, savedId, selectedChannel.id, forceTop)
        focusedRow = if (state.query.isNotBlank()) androidDrawerIds.indexOf(id)
            else entries.indexOfFirst { it is ChannelEntry && it.channel.id == id }
        scrollDrawerToCentered(focusedRow, animate = false) {
            androidRequesters[id]?.let { runCatching { it.requestFocus() } }
        }
    }
    LaunchedEffect(listState, androidDrawerIds, state.query) {
        if (!isAndroidPlatform()) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                if (state.query.isNotBlank()) state.filteredChannels.getOrNull(info.index)?.id
                else (entries.getOrNull(info.index) as? ChannelEntry)?.channel?.id
            }.toSet()
        }.distinctUntilChanged().collect { component.dispatch(ChannelsIntent.VisibleChannelsChanged(it)) }
    }

    Column(
        modifier = Modifier
            // Android TV: the channel list is wider — channel names must fit.
            .width(if (isAndroidPlatform()) 400.dp else 410.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(0.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .then(
                if (isAndroidPlatform()) Modifier else Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    RoundedCornerShape(0.dp),
                )
            )
            .padding(18.dp),
    ) {
        // Android TV: no header — the channel list uses the full panel height
        // (categories and their channel counts live in the category picker).
        Spacer(modifier = Modifier.height(0.dp))
        // Android TV: no in-list search (dedicated Search screen instead).
        if (!isAndroidPlatform()) {
            TvSearchField(
                value = state.query,
                onValueChange = onSearch,
                onClear = onSearchClear,
                placeholder = uiText("Поиск каналов"),
                modifier = Modifier
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { onSearchFocusChanged(it.isFocused) },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            state = listState,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (!isAndroidPlatform() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val rows = if (state.query.isNotBlank()) {
                        state.filteredChannels.indices.toList()
                    } else {
                        channelRowIndices
                    }
                    if (rows.isEmpty()) return@onPreviewKeyEvent false
                    val currentPosition = rows.indexOf(focusedRow).let { if (it >= 0) it else 0 }
                    val targetPosition = (currentPosition + direction).mod(rows.size)
                    val targetRow = rows[targetPosition]
                    if (targetRow == focusedRow) return@onPreviewKeyEvent true
                    focusedRow = targetRow
                    val targetId = if (state.query.isNotBlank()) state.filteredChannels[targetRow].id
                        else (entries[targetRow] as ChannelEntry).channel.id
                    drawerScrollJob?.cancel()
                    val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetRow }
                    if (visible) {
                        androidRequesters[targetId]?.requestFocus()
                    } else {
                        // Remove the old ring before moving an off-screen row into view.
                        androidFocusManager.clearFocus(force = true)
                        drawerScrollJob = drawerScope.launch {
                            listState.scrollToItem(targetRow)
                            withFrameNanos { }
                            androidRequesters[targetId]?.let { runCatching { it.requestFocus() } }
                        }
                    }
                    true
                },
        ) {
            if (state.query.isNotBlank()) {
                itemsIndexed(state.filteredChannels, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        selected = channel.id == selectedChannel.id,
                        playing = isAndroidPlatform() && channel.id == actuallyPlayingId,
                        showNumber = showChannelNumbers,
                        nowPlaying = nowPlaying[channel.id],
                        now = now,
                        onClick = { onSelectChannel(channel.id) },
                        onToggleFavorite = { onToggleFavorite(channel.id) },
                        onHighlighted = { highlighted ->
                            if (highlighted) {
                                focusedRow = index
                                onChannelHighlighted(channel.id)
                                component.dispatch(ChannelsIntent.FocusChannel(channel.id))
                            }
                        },
                        onShowTimeline = if (!isAndroidPlatform() || androidHasArchive(channel)) onShowTimeline?.let { show -> { show(channel.id) } } else null,
                        modifier = when {
                            isAndroidPlatform() -> Modifier.focusRequester(androidRequesters.getOrPut(channel.id) { FocusRequester() })
                            index == 0 && focusTopNonce > 0 && !isAndroidPlatform() -> {
                                Modifier.focusRequester(topChannelFocusRequester)
                            }
                            index == focusedRow -> {
                                Modifier.focusRequester(currentChannelFocusRequester)
                            }
                            else -> Modifier
                        },
                    )
                }
            } else {
                itemsIndexed(
                    buildChannelEntries(state, expandedCategory, androidCategory),
                    key = { _, entry ->
                        when (entry) {
                            is CategoryEntry -> "cat:${entry.key}"
                            is ChannelEntry -> "ch:${entry.channel.id}"
                            else -> entry.toString()
                        }
                    },
                ) { index, entry ->
                    when (entry) {
                        is CategoryEntry -> CategoryHeader(
                            title = entry.title,
                            count = entry.count,
                            expanded = entry.expanded,
                            onClick = {
                                // Toggle driven from the stable watching layer
                                // so the category survives drawer close/open.
                                onCategoryToggle(entry.key)
                            },
                        )
                        is ChannelEntry -> ChannelRow(
                            channel = entry.channel,
                            selected = entry.channel.id == selectedChannel.id,
                            playing = isAndroidPlatform() && entry.channel.id == actuallyPlayingId,
                            showNumber = showChannelNumbers,
                            nowPlaying = nowPlaying[entry.channel.id],
                            now = now,
                            onClick = { onSelectChannel(entry.channel.id) },
                            onToggleFavorite = { onToggleFavorite(entry.channel.id) },
                            onHighlighted = { highlighted ->
                            if (highlighted) {
                                focusedRow = index
                                onChannelHighlighted(entry.channel.id)
                                if (isAndroidPlatform()) component.dispatch(ChannelsIntent.FocusChannel(entry.channel.id))
                            }
                        },
                            // Archive support does not depend on a current EPG entry.
                            onShowTimeline = if (if (isAndroidPlatform()) androidHasArchive(entry.channel) else entry.channel.hasCatchupMechanism && nowPlaying[entry.channel.id] != null) {
                                onShowTimeline?.let { show -> { show(entry.channel.id) } }
                            } else {
                                null
                            },
                            modifier = when {
                            isAndroidPlatform() -> Modifier.focusRequester(androidRequesters.getOrPut(entry.channel.id) { FocusRequester() })
                                index == 0 && focusTopNonce > 0 && !isAndroidPlatform() -> {
                                Modifier.focusRequester(topChannelFocusRequester)
                            }
                                index == focusedRow -> {
                                    Modifier.focusRequester(currentChannelFocusRequester)
                                }
                                else -> Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

private class CategoryEntry(
    val key: String,
    val title: String,
    val count: Int,
    val expanded: Boolean,
)

private class ChannelEntry(val channel: Channel)

private const val ALL_CATEGORIES = "__all"

/** Android TV: how long OK must be held to toggle the favorite instead of opening the channel. */
private const val LONG_PRESS_DELAY_MS = 500L

/**
 * Builds the channel list LazyColumn entries (category headers + channel rows)
 * in EXACTLY the same order the list renders them. The selected-channel
 * scroll uses this to compute the real row position (channel indices in
 * `filteredChannels` do not match the list, which contains category headers).
 *
 * When [androidCategory] is non-null (Android TV), the list is FLAT: only the
 * channels of that single category, no accordion headers and no «Избранное» —
 * categories and favorites are owned exclusively by the categories panel.
 */
private fun buildChannelEntries(
    state: ChannelsState,
    expandedCategory: String?,
    androidCategory: String? = null,
): List<Any> = buildList {
    if (isAndroidPlatform() && androidCategory == null) {
        state.channels.forEach { add(ChannelEntry(it)) }
        return@buildList
    }
    if (androidCategory != null) {
        // «Избранное» is a virtual category (filter on isFavorite), not a real
        // channel category — must be handled separately or favorites never
        // show up in the flat list.
        if (androidCategory == FAVORITES_CATEGORY) {
            state.channels.filter { it.isFavorite }.forEach { add(ChannelEntry(it)) }
        } else {
            state.channels
                .filter { it.category == androidCategory }
                .forEach { add(ChannelEntry(it)) }
        }
        return@buildList
    }
    add(
        CategoryEntry(
            key = FAVORITES_CATEGORY,
            title = uiText("Избранное"),
            count = state.favoritesCount,
            expanded = expandedCategory == FAVORITES_CATEGORY,
        ),
    )
    if (expandedCategory == FAVORITES_CATEGORY) {
        state.channels.filter { it.isFavorite }.forEach { add(ChannelEntry(it)) }
    }
    add(
        CategoryEntry(
            key = ALL_CATEGORIES,
            title = uiText("Все каналы"),
            count = state.channels.size,
            expanded = expandedCategory == ALL_CATEGORIES,
        ),
    )
    if (expandedCategory == ALL_CATEGORIES) {
        state.channels.forEach { add(ChannelEntry(it)) }
    }
    state.categories.forEach { category ->
        add(
            CategoryEntry(
                key = category,
                title = category,
                count = state.categoryCounts[category] ?: 0,
                expanded = expandedCategory == category,
            ),
        )
        if (expandedCategory == category) {
            state.channels.filter { it.category == category }.forEach { add(ChannelEntry(it)) }
        }
    }
}

@Composable
private fun CategoryHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = expanded,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(TVNextDimens.CardCornerRadius),
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacing,
            vertical = TVNextDimens.ItemSpacingSmall,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun isChannelListToggle(event: KeyEvent): Boolean {
    // Physical L key, independent of the keyboard layout (also matches Cyrillic «Ļ).
    return event.key == Key.L || event.utf16CodePoint == '\u0414'.code
}

private fun isMediaPlayPauseKey(event: KeyEvent): Boolean =
    event.key == Key.MediaPlayPause ||
        event.key == Key.MediaPlay ||
        event.key == Key.MediaPause

private fun isMediaStopKey(event: KeyEvent): Boolean =
    event.key == Key.MediaStop

private fun isMuteKey(event: KeyEvent): Boolean {
    // Physical M key, independent of the keyboard layout (also matches Cyrillic ���).
    return event.key == Key.M || event.utf16CodePoint == '\u044C'.code
}

@Composable
private fun RegularLayout(
    component: ChannelsComponent,
    state: ChannelsState,
    showChannelNumbers: Boolean,
    now: Long,
    onSearch: (String) -> Unit,
    onSearchClear: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onSelectChannel: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onVisibleChannelsChanged: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val nowPlaying by component.nowPlaying.collectAsState()
    val actuallyPlayingId by remember(component) {
        component.playbackController.state.map { player ->
            player.item?.takeIf { player.state == PlaybackState.PLAYING && it.kind != PlaybackContentKind.VOD }?.id
        }.distinctUntilChanged()
    }.collectAsState(initial = null)

    val searchFocusRequester = remember { FocusRequester() }
    val searchVisible = !state.isLoading && state.error == null && state.channels.isNotEmpty()

    // Android TV: focus the FIRST channel row instead of the search field —
    // auto-focusing the search TextField would open the on-screen keyboard
    // the moment the tab opens. Desktop keeps its own default focus behaviour.
    val firstChannelFocusRequester = remember { FocusRequester() }
    val regularListState = rememberLazyListState()
    LaunchedEffect(regularListState, state.filteredChannels) {
        if (!isAndroidPlatform()) return@LaunchedEffect
        snapshotFlow {
            regularListState.layoutInfo.visibleItemsInfo
                .mapNotNull { info -> state.filteredChannels.getOrNull(info.index)?.id }
                .toSet()
        }.distinctUntilChanged().collect { ids -> onVisibleChannelsChanged(ids) }
    }
    // Desktop: after the last category/channel is restored, scroll the list so
    // the selected channel is visible — re-opening the Channels tab returns to
    // the same place instead of the top. Fires on restore and on selection
    // changes (jumping to a channel that was clicked elsewhere).
    LaunchedEffect(state.filteredChannels, state.selectedChannelId, state.selectedCategory) {
        if (isAndroidPlatform()) return@LaunchedEffect
        val selectedId = state.selectedChannelId ?: return@LaunchedEffect
        val index = state.filteredChannels.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            runCatching { regularListState.scrollToItem(index) }
        }
    }
    val regularIds = remember(state.filteredChannels) { state.filteredChannels.map { it.id } }
    var regularFocusId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(regularIds, state.selectedCategory, state.query) {
        if (isAndroidPlatform() && regularIds.isNotEmpty()) {
            regularFocusId = resolveAndroidChannelFocus(regularIds,
                state.focusedChannelIds[state.selectedCategory] ?: state.focusedChannelId, state.selectedChannelId)
            val index = regularIds.indexOf(regularFocusId)
            withFrameNanos { }
            regularListState.scrollToItem(index.coerceAtLeast(0))
            withFrameNanos { }
            runCatching { firstChannelFocusRequester.requestFocus() }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            state.isLoading && state.channels.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null && state.channels.isEmpty() -> {
                EmptyStateBox(
                    message = state.error?.toUserMessage().orEmpty().ifBlank { uiText("Ошибка загрузки") },
                    onRetry = onRefresh,
                    modifier = Modifier.weight(1f),
                )
            }

            !state.isLoading && state.channels.isEmpty() -> {
                EmptyStateBox(
                    message = if (state.hasPlaylist) {
                        uiText("Каналы не загружены. Нажмите «Обновить».")
                    } else {
                        uiText("Добавьте M3U-плейлист в настройках.")
                    },
                    onRetry = onRefresh,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                // Android TV: no in-list search (dedicated Search screen).
                if (!isAndroidPlatform()) {
                    TvSearchField(
                        value = state.query,
                        onValueChange = onSearch,
                        onClear = onSearchClear,
                        placeholder = uiText("Поиск каналов"),
                        modifier = Modifier.focusRequester(searchFocusRequester),
                    )
                }

                Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacing))

                CategoryRow(
                    categories = state.categories,
                    selected = state.selectedCategory,
                    favoritesSelected = state.selectedCategory == FAVORITES_CATEGORY,
                    onFavoritesSelect = { onCategorySelected(FAVORITES_CATEGORY) },
                    onSelect = onCategorySelected,
                )

                Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacing))

                Text(
                    text = uiText("{0} каналов", state.filteredChannels.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                    state = regularListState,
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(state.filteredChannels, key = { _, channel -> channel.id }) { index, channel ->
                        ChannelRow(
                            channel = channel,
                            selected = if (isAndroidPlatform()) channel.id == state.selectedChannelId
                                else channel.id == state.focusedChannelId || channel.id == state.selectedChannelId,
                            showNumber = showChannelNumbers,
                            nowPlaying = nowPlaying[channel.id],
                            now = now,
                            onClick = { onSelectChannel(channel.id) },
                            onHighlighted = { focused -> if (focused) component.dispatch(ChannelsIntent.FocusChannel(channel.id)) },
                            onToggleFavorite = { onToggleFavorite(channel.id) },
                            modifier = if (if (isAndroidPlatform()) channel.id == regularFocusId else index == 0) {
                                Modifier.focusRequester(firstChannelFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                    if (state.filteredChannels.isEmpty()) {
                        item {
                            Text(
                                text = uiText("Ничего не найдено"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = TVNextDimens.SectionSpacing),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateBox(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
        )
        TvCardButton(
            onClick = onRetry,
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(
                horizontal = TVNextDimens.ItemSpacing,
                vertical = TVNextDimens.ItemSpacingSmall,
            ),
        ) {
            Text(
                text = uiText("Обновить"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Android TV only: the category picker slid in from the left (see
 * [WatchingLayout]'s Back/LEFT/RIGHT key handling). Selecting a category
 * dispatches it and hands focus back to whatever opened the panel — the
 * caller is responsible for closing [categoriesOpen] on selection.
 */
@Composable
internal fun ChannelCategoriesPanel(
    state: ChannelsState,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    expandToVideo: Boolean = false,
) {
    val focusRequesters = remember { mutableMapOf<String?, FocusRequester>() }
    val allFocusRequester = remember { FocusRequester() }
    val favoritesFocusRequester = remember { FocusRequester() }
    
    val categoryListState = rememberLazyListState()
    LaunchedEffect(state.selectedCategory, state.categories) {
        val index = when (state.selectedCategory) {
            null -> 0
            FAVORITES_CATEGORY -> 1
            else -> (state.categories.indexOf(state.selectedCategory) + 2).coerceAtLeast(0)
        }
        categoryListState.scrollToItem(index)
        withFrameNanos { }
        val requester = when (state.selectedCategory) {
            null -> allFocusRequester
            FAVORITES_CATEGORY -> favoritesFocusRequester
            else -> focusRequesters[state.selectedCategory]
        }
        requester?.let { runCatching { it.requestFocus() } }
    }

    Column(
        modifier = modifier
            .then(if (expandToVideo) Modifier.fillMaxWidth() else Modifier.width(if(isAndroidPlatform())200.dp else 260.dp))
            .testTag("windowed-categories-panel")
            .fillMaxHeight()
            .clip(RoundedCornerShape(0.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                RoundedCornerShape(0.dp),
            )
            .padding(18.dp),
    ) {
        Text(
            text = uiText("Категории"),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(state = categoryListState, verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall)) {
            item {
                CategoryListEntry(
                    label = uiText("Все"),
                    count = state.channels.size,
                    selected = state.selectedCategory == null,
                    onClick = { onCategorySelected(null) },
                    modifier = Modifier.focusRequester(allFocusRequester),
                )
            }
            item {
                CategoryListEntry(
                    label = uiText("Избранное"),
                    count = state.favoritesCount,
                    selected = state.selectedCategory == FAVORITES_CATEGORY,
                    onClick = { onCategorySelected(FAVORITES_CATEGORY) },
                    modifier = Modifier.focusRequester(favoritesFocusRequester),
                )
            }
            items(state.categories, key = { it }) { category ->
                val categoryFocusRequester = remember { FocusRequester() }
                focusRequesters[category] = categoryFocusRequester
                CategoryListEntry(
                    label = category,
                    count = state.categoryCounts[category] ?: 0,
                    selected = state.selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    modifier = Modifier.focusRequester(categoryFocusRequester),
                )
            }
        }
    }
}

@Composable
private fun CategoryListEntry(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        // Same translucency as the categories panel itself: the resting entry
        // background is transparent (no double-tint over the panel); selection
        // and focus still render on top.
        containerAlpha = if (isAndroidPlatform()) 0f else 0.92f,
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacing,
            vertical = TVNextDimens.ItemSpacingSmall,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "($count)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Windowed/browse mode: the categories panel REPLACES [content] (the channel
 * list) rather than pushing it aside — there is only one panel's worth of
 * width available next to the video. Fullscreen builds its own push layout
 * directly (see the Row around [ChannelCategoriesPanel] in [WatchingLayout])
 * because it needs both panels mounted at once for the slide animation.
 */
@Composable
private fun ChannelCategoriesOrListPanel(
    categoriesOpen: Boolean,
    onSwipeOpenCategories: () -> Unit,
    state: ChannelsState,
    component: ChannelsComponent,
    now: Long,
    onCategorySelected: (String?) -> Unit,
    onArchiveProgramme: (EpgProgramme) -> Unit,
    onProgrammeHighlighted: (EpgProgramme) -> Unit = {},
    onTimelineClosed: () -> Unit = {},
    timelineFocusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val timelineState by component.timeline.collectAsState()
    val timelineChannel = state.channels.firstOrNull { it.id == timelineState.channelId }
    val initialProgrammeTimeMs = remember(timelineState.channelId) {
        val playback = component.playbackController.state.value
        playback.item?.takeIf { it.id == timelineState.channelId && it.kind == PlaybackContentKind.ARCHIVE }
            ?.archiveEpgAnchorMs?.plus(playback.positionMs)
    }
    Box(
        modifier = (if (isAndroidPlatform()) Modifier.width(400.dp).fillMaxHeight() else Modifier)
            .then(if (isAndroidPlatform()) Modifier.pointerInput(categoriesOpen) {
                var startedNearEdge = false
                var horizontalDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startedNearEdge = !categoriesOpen && offset.x < 42.dp.toPx()
                        horizontalDistance = 0f
                    },
                    onHorizontalDrag = { change, delta ->
                        if (startedNearEdge) {
                            horizontalDistance += delta
                            if (horizontalDistance > 90.dp.toPx()) {
                                onSwipeOpenCategories()
                                startedNearEdge = false
                                change.consume()
                            }
                        }
                    },
                )
            } else Modifier),
    ) {
    when {
        categoriesOpen -> ChannelCategoriesPanel(state = state, onCategorySelected = onCategorySelected, expandToVideo = true)
        timelineChannel != null -> ChannelTimelinePanel(
            channel = timelineChannel,
            requestInitialFocus = isAndroidPlatform(),
            showDetail = !isAndroidPlatform(),
            onProgrammeHighlighted = onProgrammeHighlighted,
            onBack = onTimelineClosed,
            focusRequester = timelineFocusRequester,
            initialProgrammeTimeMs = initialProgrammeTimeMs,
            programmes = timelineState.programmes,
            loading = timelineState.loading,
            now = now,
            archiveAvailable = if (isAndroidPlatform()) androidHasArchive(timelineChannel) else timelineChannel.hasCatchupMechanism,
            onArchiveProgramme = { programme ->
                if (isAndroidPlatform()) component.dispatch(ChannelsIntent.ArchiveProgrammeClicked(programme, timelineChannel.id))
                else onArchiveProgramme(programme)
            },
        )
        else -> content()
    }
    }
}

@Composable
private fun TimelineOutlinedText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // A real text stroke keeps the programme text readable directly over the
    // video, without bringing back an opaque/grey card behind it.
    Box(modifier = modifier) {
        Text(
            text = text,
            style = style.copy(
                color = Color.Black,
                drawStyle = Stroke(width = 3.2f),
            ),
            maxLines = maxLines,
            overflow = overflow,
        )
        Text(
            text = text,
            style = style.copy(color = color),
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * Android TV only: full-day EPG timeline for one channel — opened via the
 * clock icon on a channel row (see [ChannelRow]). List of time slots on the
 * left (current one highlighted, auto-focused on open), detail card for
 * whichever entry has D-pad focus on the right — kept in the SAME panel
 * (rather than lifted to an overlay elsewhere in the tree) so the "focused
 * entry" state stays local and simple. Past entries are clickable to start
 * archive playback when the provider supports it; future entries are
 * informational only (nothing to play yet).
 */
@Composable
private fun ChannelTimelinePanel(
    channel: Channel,
    programmes: List<EpgProgramme>,
    loading: Boolean,
    now: Long,
    archiveAvailable: Boolean,
    onArchiveProgramme: (EpgProgramme) -> Unit,
    /** Android TV: Back closes the programme-only panel and returns to video. */
    onBack: () -> Unit = {},
    /** RIGHT из списка каналов переводит фокус на текущую передачу панели. */
    focusRequester: FocusRequester? = null,
    /** OK на текущей передаче — начать её сначала (перезапуск канала). */
    onRestartCurrent: () -> Unit = {},
    /** Fullscreen: plain text over the video, no translucent backing panel. */
    transparentBackground: Boolean = false,
    showDetail: Boolean = true,
    requestInitialFocus: Boolean = false,
    initialProgrammeTimeMs: Long? = null,
    onProgrammeHighlighted: (EpgProgramme) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (isAndroidPlatform() && !showDetail) {
        AndroidWindowedProgrammeList(
            channel = channel, programmes = programmes, loading = loading, now = now,
            initialProgrammeTimeMs = initialProgrammeTimeMs,
            focusRequester = focusRequester,
            requestInitialFocus = requestInitialFocus,
            onProgrammeHighlighted = onProgrammeHighlighted,
            onPlayProgramme = { if (archiveAvailable && it.start <= now) onArchiveProgramme(it) },
            modifier = modifier.width(400.dp).fillMaxHeight(),
        )
        return
    }
    val visibleProgrammes = if (isAndroidPlatform()) remember(programmes) {
        programmes.distinctBy { Triple(it.channelId, it.start, it.stop) }
    } else programmes
    val currentIndex = remember(visibleProgrammes, now) {
        visibleProgrammes.indexOfFirst { now >= it.start && now < it.stop }
    }
    var focusedProgramme by remember(channel.id) {
        mutableStateOf(visibleProgrammes.getOrNull(currentIndex))
    }
    LaunchedEffect(visibleProgrammes, currentIndex) {
        if (focusedProgramme == null || focusedProgramme !in visibleProgrammes) {
            focusedProgramme = visibleProgrammes.getOrNull(currentIndex)
        }
    }
    val listState = rememberLazyListState()
    val currentFocusRequester = remember { FocusRequester() }
    LaunchedEffect(visibleProgrammes) {
        if (visibleProgrammes.isEmpty()) return@LaunchedEffect
        val targetProgrammeIndex = currentIndex.takeIf { it >= 0 } ?: 0
        var targetListIndex = 0
        var previousDate: String? = null
        visibleProgrammes.forEachIndexed { index, programme ->
            val date = formatDate(programme.start)
            if (date != previousDate) {
                if (index <= targetProgrammeIndex) targetListIndex++
                previousDate = date
            }
            if (index < targetProgrammeIndex) targetListIndex++
        }
        listState.scrollToItem(targetListIndex.coerceAtLeast(0))
        if (requestInitialFocus) {
            withFrameNanos { }
            (focusRequester ?: currentFocusRequester).requestFocus()
        }
    }

    Row(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                // The programme-only panel can contain a focused LazyColumn item.
                // Intercept Back at the panel root so the first Back press is
                // consumed here instead of being lost to the focused child or
                // bubbling to app navigation. One press = close visibleProgrammes =
                // leave only the video visible.
                if (isAndroidPlatform() && event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .width(if (isAndroidPlatform() && !showDetail) 400.dp else if (showDetail) 560.dp else 300.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
            .background(
                if (transparentBackground && isAndroidPlatform()) {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
                        0.42f to MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    )
                } else if (transparentBackground) {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.Transparent,
                    )
                } else {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    )
                },
            )
            .then(
                if (transparentBackground && !isAndroidPlatform()) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(18.dp),
    ) {
        Column(modifier = Modifier.width(260.dp).fillMaxHeight()) {
            TimelineOutlinedText(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TimelineOutlinedText(
                text = uiText("Программа передач"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                visibleProgrammes.isEmpty() -> {
                    Text(
                        text = uiText("Программа передач недоступна"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        var previousDate: String? = null
                        visibleProgrammes.forEachIndexed { index, programme ->
                            val date = formatDate(programme.start)
                            if (date != previousDate) {
                                item(key = "date:$date") {
                                    Text(
                                        text = date,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                previousDate = date
                            }
                            item(key = if (isAndroidPlatform()) "${programme.channelId}:${programme.start}:${programme.stop}" else "programme:$index") {
                            val isCurrent = index == currentIndex
                            val isPast = programme.stop <= now
                            val isFuture = programme.start > now
                            TvCardButton(
                                onClick = {
                                    when {
                                        (isCurrent || isPast) && archiveAvailable -> onArchiveProgramme(programme)
                                        // Future programme: deliberately no action.
                                    }
                                },
                                selected = isCurrent,
                                shape = RoundedCornerShape(8.dp),
                                // Android TV: the whole programme column has one
                                // shared gradient, so individual programme cards
                                // stay transparent. Desktop keeps its existing card
                                // surface.
                                containerAlpha = if (isAndroidPlatform()) 0f else 0.72f,
                                contentPadding = PaddingValues(
                                    horizontal = TVNextDimens.ItemSpacingSmall,
                                    vertical = 6.dp,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (index == (currentIndex.takeIf { it >= 0 } ?: 0)) {
                                            Modifier.focusRequester(focusRequester ?: currentFocusRequester)
                                        } else Modifier,
                                    )
                                    .onFocusChanged { if (it.isFocused) { focusedProgramme = programme; onProgrammeHighlighted(programme) } }
                                    .onPreviewKeyEvent { event ->
                                        if (isAndroidPlatform() && event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                                            onBack()
                                            true
                                        } else false
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TimelineOutlinedText(
                                        text = formatTimeOfDay(programme.start),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = when {
                                            isCurrent -> MaterialTheme.colorScheme.primary
                                            isFuture -> MaterialTheme.colorScheme.onSurface
                                            isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.width(52.dp),
                                    )
                                    TimelineOutlinedText(
                                        text = programme.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = when {
                                            isCurrent -> MaterialTheme.colorScheme.primary
                                            isFuture -> MaterialTheme.colorScheme.onSurface
                                            isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isPast && archiveAvailable) {
                                        Text(
                                            text = "▶",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showDetail) {
        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))

        // Detail card for whichever entry currently has D-pad focus in the list.
        val detail = focusedProgramme
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (detail != null) {
                TimelineOutlinedText(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TimelineOutlinedText(
                    text = "${formatTimeOfDay(detail.start)} — ${formatTimeOfDay(detail.stop)}" +
                        uiText(" · {0} мин", ((detail.stop - detail.start) / 60_000L).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (detail.start <= now && now < detail.stop) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TvProgressBar(
                        progress = { programmeProgress(detail, now) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
                detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(10.dp))
                    if (isAndroidPlatform()) AutoScrollText(description, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface, 6)
                    else TimelineOutlinedText(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }
    }
}
}

@Composable
private fun CategoryRow(
    categories: List<String>,
    selected: String?,
    favoritesSelected: Boolean = false,
    onFavoritesSelect: (() -> Unit)? = null,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
        modifier = modifier,
    ) {
        item {
            CategoryChip(
                label = uiText("Все"),
                selected = selected == null && !favoritesSelected,
                onClick = { onSelect(null) },
            )
        }
        if (onFavoritesSelect != null) {
            item {
                CategoryChip(
                    label = uiText("Избранное"),
                    selected = favoritesSelected,
                    onClick = onFavoritesSelect,
                )
            }
        }
        items(categories, key = { it }) { category ->
            CategoryChip(
                label = category,
                selected = selected == category,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacing,
            vertical = TVNextDimens.ItemSpacingSmall,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    playing: Boolean = false,
    showNumber: Boolean,
    nowPlaying: EpgProgramme?,
    now: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Android TV: opens the full-day EPG timeline panel for THIS channel (see
     * [ChannelTimelinePanel]) without touching playback. Null hides the
     * clock-icon button entirely — only the drawer/list rows the timeline
     * panel is actually wired up for pass this.
     */
    onShowTimeline: (() -> Unit)? = null,
    /**
     * Called when this row gains/loses the D-pad focus; the watching layer uses
     * it to drive the bottom-right EPG card (the highlighted channel's current
     * programme, which may differ from the playing channel).
     */
    onHighlighted: (Boolean) -> Unit = {},
) {
    if (isAndroidPlatform()) androidx.compose.runtime.SideEffect {
        com.tvnext.app.core.common.log.AndroidPerformanceCounters.increment("compose.channelRows")
    }

    val parentalAccess by com.tvnext.app.core.iptv.domain.ParentalChannelGate.session.collectAsState()
    val locked = isAndroidPlatform() && parentalAccess.restrictionsActive &&
        com.tvnext.app.core.iptv.domain.ParentalChannelGate.isRestricted(channel)

    // Rectangular list rows on Android TV (the panels are rectangular too);
    // desktop keeps the rounded card look.
    val rowShape = if (isAndroidPlatform()) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(TVNextDimens.CardCornerRadius)
    }
    // Android TV: the OK/Center key has two meanings in the channel row:
    // short press = open the channel, long press = toggle favorite. The
    // handler is attached to the ROW (the button's parent) so its
    // onPreviewKeyEvent fires BEFORE TvCardButton's own OK handler, which
    // otherwise swallows KeyDown and clicks immediately — that would both open
    // the channel on a long press AND never toggle the favorite. Desktop keeps
    // the existing button behavior unchanged.
    //
    // Long-press uses a TIMER, not a KeyUp measurement: Android TV remotes send
    // auto-repeat KeyDown events while the button is held (and can deliver the
    // KeyUp late/only once), so a duration check on KeyUp could fire twice —
    // first adding the favorite, then (after the second key cycle) removing it
    // again. The timer is armed on the FIRST KeyDown only and stays armed until
    // KeyUp (the `okDownHeld` flag is set for the whole physical press), so
    // exactly ONE toggle fires no matter how long the button is held.
    var okTimer by remember { mutableStateOf<Job?>(null) }
    var okLongFired by remember { mutableStateOf(false) }
    var okDownHeld by remember { mutableStateOf(false) }
    val rowScope = rememberCoroutineScope()
    val latestFavoriteToggle by rememberUpdatedState(onToggleFavorite)
    var channelButtonFocused by remember { mutableStateOf(false) }
    val androidChannelRowModifier = if (isAndroidPlatform()) {
        Modifier.onPreviewKeyEvent { event ->
            val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter
            if (!isOk || !channelButtonFocused) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    // Only the FIRST KeyDown of a physical press arms the timer.
                    // Auto-repeat KeyDowns (and repeats arriving AFTER the timer
                    // already fired) must not re-arm it.
                    if (!okDownHeld) {
                        okDownHeld = true
                        okLongFired = false
                        okTimer = rowScope.launch {
                            delay(LONG_PRESS_DELAY_MS)
                            okLongFired = true
                            latestFavoriteToggle()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    // Release ends the physical press: cancel the pending timer
                    // and, if the long-press threshold was not reached, treat it
                    // as a plain click.
                    val wasHeld = okDownHeld
                    okDownHeld = false
                    val timer = okTimer
                    okTimer = null
                    timer?.cancel()
                    if (wasHeld && !okLongFired) onClick()
                    true
                }
                else -> true
            }
        }
    } else {
        Modifier
    }

    // Android TV: rows sit on the same translucent panel as the drawer. The row
    // itself must NOT add a second translucent layer on top of the panel — the
    // double tint would make the rows look noticeably more opaque than the
    // panel around them. So the resting row background is fully transparent and
    // only the PANEL provides the translucency; selection/focus still show
    // (primary tint + focus border).
    val rowAlpha = if (isAndroidPlatform()) 0f else 0.72f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(androidChannelRowModifier)
            // The category line was removed, so the programme title + progress
            // fit in a dense 70 dp row with only 3 dp vertical padding.
            .height(if (isAndroidPlatform()) 70.dp else TVNextDimens.ChannelRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvCardButton(
            onClick = onClick,
            selected = selected,
            shape = rowShape,
            containerAlpha = rowAlpha,
            contentPadding = if (isAndroidPlatform()) PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                else PaddingValues(TVNextDimens.ScreenPaddingCompact),
            // Focus must be attached to the actual focusable button, not its
            // non-focusable Row wrapper. This is essential for Android TV D-pad
            // navigation and for requestFocus() when opening the drawer.
            modifier = modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged {
                    channelButtonFocused = it.isFocused
                    if (isAndroidPlatform() && !it.isFocused) {
                        okTimer?.cancel()
                        okTimer = null
                        okDownHeld = false
                        okLongFired = false
                    }
                    onHighlighted(it.isFocused)
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (playing) {
                    Text("▶", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                ChannelLogo(
                    url = channel.logoUrl,
                    placeholderText = channel.name,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                if (showNumber) {
                    ChannelNumberBadge(number = channel.number)
                    Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // Android TV uses the channel name itself as the favorite
                            // indicator, keeping the row clean and leaving no extra
                            // star button on the right. Desktop retains the separate
                            // favorite control below.
                            text = (if (locked) "🔒 " else "") +
                                if (isAndroidPlatform() && channel.isFavorite) "★ ${channel.name}" else channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (locked) {
                                androidx.compose.ui.graphics.Color(0xFFFF6767)
                            } else if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isAndroidPlatform() && androidHasArchive(channel)) {
                            ClockIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp).size(12.dp))
                        }
                    }
                    nowPlaying?.let { programme ->
                        Text(
                            text = programme.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatTimeOfDay(programme.start),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.width(32.dp),
                            )
                            TvProgressBar(
                                progress = { programmeProgress(programme, now) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(2.dp)
                                    .offset(y = 2.dp),
                            )
                            Text(
                                text = formatTimeOfDay(programme.stop),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.width(32.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }

        if (onShowTimeline != null && !isAndroidPlatform()) {
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            TvCardButton(
                onClick = onShowTimeline,
                modifier = Modifier
                    // Narrower clock column on Android TV (no wide label inside).
                    .width(if (isAndroidPlatform()) 36.dp else 48.dp)
                    .fillMaxHeight(),
                shape = rowShape,
                containerAlpha = rowAlpha,
                contentPadding = PaddingValues(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClockIcon(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
        }

        // Android TV: favorite is toggled by long-pressing OK on the focused
        // channel. The separate star button is intentionally hidden there.
        if (!isAndroidPlatform()) {
            TvCardButton(
                onClick = onToggleFavorite,
                selected = channel.isFavorite,
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight(),
                shape = rowShape,
                containerAlpha = rowAlpha,
                contentPadding = PaddingValues(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (channel.isFavorite) "★" else "☆",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (channel.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Small clock-face glyph for the "show EPG timeline" row button — no icon library dependency. */
@Composable
private fun ClockIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.08f
        drawCircle(
            color = tint,
            radius = size.minDimension / 2f - strokeWidth,
            style = Stroke(width = strokeWidth),
        )
        val center = Offset(size.width / 2f, size.height / 2f)
        // Hour hand.
        drawLine(
            color = tint,
            start = center,
            end = Offset(center.x, center.y - size.minDimension * 0.22f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // Minute hand.
        drawLine(
            color = tint,
            start = center,
            end = Offset(center.x + size.minDimension * 0.18f, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}


@Composable
private fun ChannelNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(TVNextDimens.ItemSpacingSmall))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * EPG of the selected channel: current programme (title, time range, progress),
 * the next one and a strip of upcoming programmes. Hidden when no EPG data.
 * Archived (past) programmes in the strip are tinted blue and can be clicked to
 * start the archive playback.
 */
@Composable
private fun EpgPanel(
    epg: ChannelEpg,
    isArchive: Boolean = false,
    archiveAvailable: Boolean = false,
    onArchiveProgramme: (EpgProgramme) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val now = rememberEpgTicker()
    val current = epg.current
    if (current == null && epg.upcoming.isEmpty()) return

    Column(
        modifier = modifier
            .padding(top = TVNextDimens.ItemSpacingSmall)
            .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (isArchive) uiText("Телепрограмма · архив") else uiText("Телепрограмма"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        current?.let { programme ->
            Text(
                text = programme.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // For an archived programme there is no "live progress": hide the bar.
            if (!isArchive) {
                TvProgressBar(
                    progress = { programmeProgress(programme, now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            programme.description?.takeIf { it.isNotBlank() }?.let { description ->
                // Long descriptions auto-scroll upward (3 s delay, slow, stops at
                // the last line) instead of being truncated.
                AutoScrollText(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
        epg.next?.let { programme ->
            Text(
                text = uiText("Далее: {0} — {1} · {2}", formatTimeOfDay(programme.start), formatTimeOfDay(programme.stop), programme.title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val upcoming = epg.upcoming.filter { it.start != current?.start }
        if (upcoming.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacing)) {
                // Key by position, NOT by programme start: real EPG sources can
                // contain programmes with identical start_time, and a duplicate
                // key crashes the LazyRow.
                itemsIndexed(upcoming, key = { index, _ -> "$index" }) { _, programme ->
                    ProgrammeChip(
                        programme = programme,
                        isArchive = archiveAvailable && programme.stop <= now,
                        onClick = {
                            if (archiveAvailable && programme.stop <= now) {
                                onArchiveProgramme(programme)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Android TV only: the compact, translucent EPG card pinned to the bottom-right
 * corner of the full-screen video (see [WatchingLayout]). Shows the CURRENT
 * programme of the highlighted channel — the channel the user has selected in
 * the list, which may differ from the channel that is actually playing.
 */
@Composable
private fun EpgCornerPanel(
    channel: Channel,
    programme: EpgProgramme?,
    modifier: Modifier = Modifier,
) {
    if (programme == null) return

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(0.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                RoundedCornerShape(0.dp),
            )
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = programme.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        programme.description?.takeIf { it.isNotBlank() }?.let { description ->
            // Long descriptions auto-scroll instead of being truncated.
            // Увеличено с 3 до 6 строк — панель стала уже, но выше,
            // чтобы описание не обрезалось при открытом списке категорий.
            AutoScrollText(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun ProgrammeChip(
    programme: EpgProgramme,
    isArchive: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (isArchive) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                    )
                } else {
                    Modifier
                },
            )
            .pointerInput(programme.start) { detectTapGestures(onTap = { onClick() }) }
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTimeOfDay(programme.start),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (isArchive) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = uiText("▶ архив"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = programme.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun programmeProgress(programme: EpgProgramme, now: Long): Float {
    val duration = programme.stop - programme.start
    if (duration <= 0L) return 1f
    return ((now - programme.start).toFloat() / duration).coerceIn(0f, 1f)
}

/** Ticks every 30 s so the EPG progress bar stays live without re-querying. */
@Composable
private fun rememberEpgTicker(intervalMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = currentTimeMillis()
        }
    }
    return now
}

/**
 * Full-screen info block (translucent, like the channel drawer): the channel
 * logo (large) + current programme with description on the left, and a column
 * of live video parameters (resolution, decoding, codec, fps, bitrate, output)
 * on the right. Overlays the video across the whole window width. Shown
 * automatically on every channel switch (auto-hides after the configured
 * delay) or manually with the RIGHT arrow. For archive/VOD (catch-up)
 * playback an interactive full-width seek bar is added at the bottom.
 */
@Composable
private fun EpgInfoOverlay(
    channel: Channel,
    programme: EpgProgramme?,
    diagnostics: PlayerDiagnostics?,
    controller: PlaybackController,
    seekStepMs: Long = 60_000L,
    showSeekBar: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val playerState by controller.state.collectAsState()
    val shiftController = if (isAndroidPlatform()) controller as? TimeShiftController else null
    val shift = shiftController?.timeShiftState?.collectAsState()?.value
    val clock = if (shiftController != null) rememberEpgTicker(1_000) else 0L
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = shape,
            )
            .padding(TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Left: large channel logo.
            ChannelLogo(
                url = channel.logoUrl,
                placeholderText = channel.name,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))

            // Centre: channel name + current programme title and description.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme != null) {
                    Text(
                        text = "${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    programme.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                } else {
                    Text(
                        text = uiText("Программа недоступна"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))

            // Right: column of live video parameters (from the player diagnostics).
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = uiText("Параметры видео"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (diagnostics != null) {
                    VideoParamRow(label = uiText("Разрешение"), value = diagnostics.resolution)
                    VideoParamRow(label = uiText("Кодек"), value = diagnostics.codec ?: "—")
                    VideoParamRow(label = "FPS", value = diagnostics.fps ?: "—")
                    VideoParamRow(label = uiText("Битрейт"), value = diagnostics.bitrate ?: "—")
                    VideoParamRow(label = uiText("Декодирование"), value = diagnostics.actualMode)
                    VideoParamRow(label = uiText("Аудио"), value = diagnostics.audio ?: "—")
                } else {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (shiftController != null && playerState.item?.kind != PlaybackContentKind.VOD && androidHasArchive(channel)) {
            Spacer(Modifier.height(8.dp))
            val archived = playerState.item?.kind == PlaybackContentKind.ARCHIVE
            val timestamp = if (archived) shift?.positionMs ?: clock else clock
            Text(if (archived) uiText("АРХИВ • {0}", formatDateTime(timestamp)) else uiText("LIVE • ← отмотать назад"), color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White)
            shift?.message?.let { Text(it, color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White) }
            if (shift?.seeking == true) Text(uiText("Переход к выбранному времени…"), color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TvSeekButton(direction = com.tvnext.app.core.ui.component.TvSeekDirection.BACKWARD, onSeek = { controller.seekBy(-seekStepMs) })
                if (archived) {
                    TvCardButton(modifier = Modifier.size(44.dp), contentPadding = PaddingValues(8.dp), containerAlpha = 0f, onClick = { if (playerState.state == PlaybackState.PAUSED) controller.resume() else controller.pause() }) {
                        TvPlayPauseIcon(paused = playerState.state == PlaybackState.PAUSED, modifier = Modifier.fillMaxSize(), circleSize = 44.dp, backgroundAlpha = 0f)
                    }
                }
                TvSeekButton(direction = com.tvnext.app.core.ui.component.TvSeekDirection.FORWARD, onSeek = { controller.seekBy(seekStepMs) })
                if (archived) TvCardButton(modifier = Modifier.size(44.dp), contentPadding = PaddingValues(8.dp), containerAlpha = 0f, onClick = shiftController::returnToLive) { Text("LIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface) }
            }
            val oldest = shift?.oldestMs
            if (oldest != null && clock > oldest) {
                PlayerSeekBar(
                    positionMs = (timestamp - oldest).coerceIn(0L, clock - oldest),
                    durationMs = clock - oldest,
                    onSeekTo = { shiftController.seekToTime(oldest + it) },
                )
                Text("${formatDateTime(oldest)} — LIVE", color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White)
            }
        } else if (showSeekBar && playerState.durationMs > 0L && playerState.state != PlaybackState.IDLE) {
            Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
            PlayerSeekBar(
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                onSeekTo = { controller.seekTo(it) },
            )
        }
    }
}

/** A single label/value line of the video-parameters column. */
@Composable
private fun VideoParamRow(
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}



