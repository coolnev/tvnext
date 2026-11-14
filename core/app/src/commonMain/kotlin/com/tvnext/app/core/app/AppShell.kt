package com.tvnext.app.core.app

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import com.tvnext.app.feature.settings.ui.SettingsComponent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.tvnext.app.core.player.LocalVideoScene
import com.tvnext.app.core.player.VideoScene
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackContentKind
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.app.tab.AppTabComponent
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.data.ScheduledProgramme
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.ui.component.PlayerClockStyle
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.theme.TVNextDimens
import com.tvnext.app.core.ui.theme.TVNextTheme
import com.tvnext.app.feature.channels.ui.ChannelsScreen
import com.tvnext.app.feature.home.ui.HomeScreen
import com.tvnext.app.feature.media.ui.MediaScreen
import com.tvnext.app.feature.search.ui.SearchScreen
import com.tvnext.app.feature.settings.domain.ThemePreference
import com.tvnext.app.feature.settings.ui.SettingsScreen
import com.tvnext.app.feature.tvg.ui.TvgScreen

@Composable
fun AppShell(
    component: AppComponent,
    onToggleFullscreen: (() -> Unit)? = null,
    /**
     * User immersive state (toggled with L): hides the VIDEO chrome — player
     * controls, channel name, EPG panel — while staying on the current tab.
     */
    immersive: Boolean = false,
    /** True while the app is in the fullscreen window. */
    isFullscreen: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
    showDiagnostics: Boolean = false,
    onToggleDiagnostics: () -> Unit = {},
    /** Android-specific entry point to the real recovery sections. Desktop unchanged. */
    onOpenMainMenu: (() -> Unit)? = null,
    onOpenRecoverySection: ((String) -> Unit)? = null,
    /** Android header selector; profile identities come from LocalRecoveryStore. */
    profileChoices: List<Pair<String, String>> = emptyList(),
    activeProfileId: String? = null,
    onProfileSelected: ((String) -> Unit)? = null,
    /** Android-only reference settings; existing Desktop screen is untouched. */
    androidSettingsContent: (@Composable (SettingsComponent, (() -> Unit)?) -> Unit)? = null,
    /** In-theme Android overlay: full-screen management screens and themed dialogs. */
    overlayContent: (@Composable () -> Unit)? = null,
    /** Hide ordinary focusable navigation while a full-screen Android page is visible. */
    overlayFullScreen: Boolean = false,
    /** Global EPG import status: visible even while a channel is playing. */
    epgLoading: Boolean = false,
    epgLoadedChannels: Int = 0,
    epgLoadedProgrammes: Int = 0,
    epgProgressText: String? = null,
    recordingActive: Boolean = false,
    textNotice: String? = null,
    sharedPlaybackController: PlaybackController? = null,
    animateVideoTransitions: Boolean = false,
) {
    val childStack by component.childStack.subscribeAsState()
    val activeTab = childStack.active.configuration
    val settings by component.settings.collectAsState()
    androidx.compose.runtime.SideEffect {
        com.tvnext.app.core.ui.i18n.UiLocale.language = settings.language
    }
    val reminder by component.reminder.collectAsState()
    LaunchedEffect(settings.clockWithoutBackground) {
        if (isAndroidPlatform()) PlayerClockStyle.withoutBackground.value = settings.clockWithoutBackground
    }

    // In fullscreen the app chrome (top bar / sidebar / footer) is always
    // hidden; the user's immersive state additionally hides the video chrome.
    val hideChrome = immersive || isFullscreen

    TVNextTheme(
        darkTheme = settings.theme.resolveDarkTheme(),
        accentColor = settings.accentColor,
        panelTransparencyPercent = settings.panelTransparencyPercent,
    ) {
        val playbackOverlays = remember { com.tvnext.app.core.ui.component.PlaybackOverlayVisibility() }
        val scene = remember(sharedPlaybackController) { if(sharedPlaybackController!=null) VideoScene() else null }
        CompositionLocalProvider(LocalVideoScene provides scene,
            com.tvnext.app.core.ui.component.LocalArchiveLabelEnabled provides settings.showArchiveLabel,
            com.tvnext.app.core.ui.component.LocalPlaybackOverlayVisibility provides playbackOverlays) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned {scene?.origin=it.positionInRoot()}) {
            if(scene!=null && sharedPlaybackController!=null) com.tvnext.app.core.ui.component.SharedVideoStage(
                scene,sharedPlaybackController,animateVideoTransitions)
            if (!overlayFullScreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if(scene!=null) Color.Transparent else MaterialTheme.colorScheme.background),
            ) {
                // Top bar (logo + settings gear): on desktop it stays visible
                // on the HOME tab in every mode and on other tabs while the
                // chrome is shown. On Android TV it is restricted to the HOME
                // tab only — every other screen uses the full height for the
                // content (settings are still reachable from HOME).
                val showTopBar = if (isAndroidPlatform()) {
                    activeTab == AppTab.HOME
                } else {
                    !hideChrome || activeTab == AppTab.HOME
                }
                if (showTopBar) {
                    AppTopBar(
                        onSettingsClick = { component.selectTab(AppTab.SETTINGS) },
                        onOpenMainMenu = null,
                        profileChoices = if (isAndroidPlatform()) profileChoices else emptyList(),
                        activeProfileId = activeProfileId,
                        onProfileSelected = onProfileSelected,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // The sidebar is ONLY shown on the HOME tab, in any mode
                    // (windowed / fullscreen / immersive): the home screen is the
                    // navigation hub. On other tabs the app chrome is hidden so
                    // the content (video, guides, lists) uses the full width.
                    if (activeTab == AppTab.HOME) {
                        AppSidebar(
                            selectedTab = activeTab,
                            onOpenRecoverySection = if (isAndroidPlatform()) onOpenRecoverySection else null,
                            onSelect = { tab ->
                                // Switching tabs must NOT reset the immersive
                                // mode: the user chooses full-screen (video on
                                // the whole window, lists hidden) once with L,
                                // and it persists across tabs until they turn it
                                // off themselves.
                                component.selectTab(tab)
                            },
                            modifier = Modifier
                                .width(TVNextDimens.SidebarWidth)
                                .fillMaxHeight(),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Children(
                            stack = component.childStack,
                        ) { child ->
                            when (val tab = child.instance) {
                                is AppTabComponent.Home -> HomeScreen(
                                    component = tab.component,
                                    homeCardLimit = settings.homeCardLimit,
                                    showHomeUpcoming = settings.showHomeUpcoming,
                                    showHomeRecent = settings.showHomeRecent,
                                    showHomeFavorites = settings.showHomeFavorites,
                                    showHomeRecentVod = settings.showHomeRecentVod,
                                    showHomeFavoriteVod = settings.showHomeFavoriteVod,
                                    playbackController = sharedPlaybackController,
                                    onOpenPlayingVideo = {
                                        onImmersiveChange(true)
                                        component.selectTab(if(sharedPlaybackController?.state?.value?.item?.kind==PlaybackContentKind.VOD)
                                            AppTab.MEDIA_LIBRARY else AppTab.CHANNELS)
                                    },
                                )
                                is AppTabComponent.Channels -> ChannelsScreen(
                                    component = tab.component,
                                    showChannelNumbers = settings.showChannelNumbers,
                                    immersive = immersive,
                                    onImmersiveChange = onImmersiveChange,
                                    onToggleFullscreen = onToggleFullscreen,
                                    onBackHome = { component.selectTab(AppTab.HOME) },
                                    onOpenTvg = { component.selectTab(AppTab.TV_GUIDE) },
                                    clockPosition = settings.clockPosition,
                                    showDiagnostics = showDiagnostics,
                                    onToggleDiagnostics = onToggleDiagnostics,
                                    epgInfoDurationSeconds = settings.epgInfoDurationSeconds,
                                    programmeDescriptionRight = settings.programmeDescriptionRight,
                                )
                                is AppTabComponent.Tvg -> TvgScreen(
                                    component = tab.component,
                                    // Closing the TV Guide returns directly to clean
                                    // full-screen video. Do not leave the user in the
                                    // browse layout after closing the programme grid.
                                    onBackHome = {
                                        onImmersiveChange(true)
                                        component.selectTab(AppTab.CHANNELS)
                                    },
                                )
                                is AppTabComponent.Media -> MediaScreen(
                                    component = tab.component,
                                    clockPosition = settings.clockPosition,
                                    immersive = immersive,
                                    onImmersiveChange = onImmersiveChange,
                                    onToggleFullscreen = onToggleFullscreen,
                                    onBackHome = { component.selectTab(AppTab.HOME) },
                                    showDiagnostics = showDiagnostics,
                                    infoDurationSeconds = settings.epgInfoDurationSeconds,
                                    onToggleDiagnostics = onToggleDiagnostics,
                                )
                                is AppTabComponent.Search -> SearchScreen(
                                    component = tab.component,
                                    onBackHome = { component.selectTab(AppTab.HOME) },
                                )
                                is AppTabComponent.Settings -> {
                                    if (isAndroidPlatform() && androidSettingsContent != null) {
                                        androidSettingsContent(tab.component) { component.selectTab(AppTab.HOME) }
                                    } else {
                                        SettingsScreen(
                                            component = tab.component,
                                            onBackHome = { component.selectTab(AppTab.HOME) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Desktop keeps the version footer while the chrome is
                // shown; Android TV shows it only on the HOME tab.
                val showFooter = if (isAndroidPlatform()) {
                    activeTab == AppTab.HOME
                } else {
                    !hideChrome
                }
                if (showFooter) {
                    AppFooter()
                }
            }

            }
            // Only the management page is focusable while it is open; prevent D-pad
            // navigation from reaching Home controls hidden behind the page.
            if (isAndroidPlatform() && epgLoading && !overlayFullScreen &&
                activeTab != AppTab.SETTINGS && textNotice == null) {
                com.tvnext.app.core.ui.component.EpgLoadingIndicator(
                    text = epgProgressText?.takeIf { it.isNotBlank() } ?: if (epgLoadedProgrammes > 0)
                        uiText("EPG: {0} каналов · {1} передач", epgLoadedChannels, epgLoadedProgrammes)
                        else uiText("Загрузка EPG…"),
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
            overlayContent?.invoke()

            // In-app reminder banner for scheduled programmes.
            if (!overlayFullScreen) reminder?.let { scheduled ->
                ScheduledReminderBanner(
                    scheduled = scheduled,
                    onDismiss = { component.dismissReminder() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(TVNextDimens.ScreenPadding),
                )
            }
            if (recordingActive) com.tvnext.app.core.ui.component.RecordingIndicator(
                Modifier.align(Alignment.TopEnd).padding(end = 16.dp,
                    top = if (settings.clockPosition == ClockPosition.TOP_RIGHT) 64.dp else 16.dp))
            textNotice?.let { message ->
                com.tvnext.app.core.ui.component.TextOnlyNotice(message,
                    Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 28.dp))
            }
        }
        }
    }
}

/** Passive in-app banner: a scheduled programme is about to start. */
@Composable
private fun ScheduledReminderBanner(
    scheduled: ScheduledProgramme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⏰",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiText("Скоро начнётся: {0}", scheduled.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "${scheduled.channelName} · ${formatTimeOfDay(scheduled.startTime)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        TvCardButton(
            onClick = onDismiss,
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(
                horizontal = TVNextDimens.ItemSpacing,
                vertical = TVNextDimens.ItemSpacingSmall,
            ),
        ) {
            Text(
                text = uiText("Понятно"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AppTopBar(
    onSettingsClick: () -> Unit,
    onOpenMainMenu: (() -> Unit)? = null,
    profileChoices: List<Pair<String, String>> = emptyList(),
    activeProfileId: String? = null,
    onProfileSelected: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TVNextDimens.TopBarHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = TVNextDimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TVNext wordmark — the product logo (SVG on desktop, PNG on Android).
        AppLogo(modifier = Modifier.height(TVNextDimens.TopBarHeight - 12.dp))
        Spacer(modifier = Modifier.weight(1f))
        if (onOpenMainMenu != null) {
            TvCardButton(
                onClick = onOpenMainMenu,
                modifier = Modifier.height(44.dp).padding(end = 12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(uiText("☰  Меню"), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // Profile selector is immediately to the LEFT of the settings gear.
        // Never appear on Desktop, where profiles are not managed here.
        if (profileChoices.isNotEmpty() && onProfileSelected != null) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TvCardButton(
                    onClick = { expanded = true },
                    modifier = Modifier.wrapContentWidth().widthIn(max = 190.dp).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profileChoices.firstOrNull { it.first == activeProfileId }?.second ?: uiText("Основной"),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 147.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("▾", color = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    profileChoices.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text((if (id == activeProfileId) "✓  " else "    ") + name) },
                            onClick = {
                                expanded = false
                                if (id != activeProfileId) onProfileSelected(id)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        // Settings lives in the top bar (top-right) as a compact square
        // gear button — not in the sidebar navigation.
        TvCardButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Graphic gear: black on light themes, white on dark ones.
                GearIcon(
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Vector gear icon drawn on a canvas (no icon library in the project).
 * A real gear shape: a solid disc whose rim is cut by tooth roots, with
 * trapezoid teeth that arc along the outer radius, plus a round centre hole.
 * Black/white via [tint] — onSurface is near-black in light themes and
 * near-white in dark themes, so the gear follows the theme automatically.
 */
@Composable
private fun GearIcon(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Canvas(modifier = modifier) {
        val teeth = 8
        val outerRadius = size.minDimension / 2f
        // Tooth root depth — a real gear has shallow roots, not a star.
        val rootRadius = outerRadius * 0.84f
        val holeRadius = outerRadius * 0.36f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepDeg = 360f / teeth
        val toothDeg = stepDeg * 0.5f // tooth arc vs root arc share the step
        val outerRect = Rect(
            left = cx - outerRadius,
            top = cy - outerRadius,
            right = cx + outerRadius,
            bottom = cy + outerRadius,
        )
        val rootRect = Rect(
            left = cx - rootRadius,
            top = cy - rootRadius,
            right = cx + rootRadius,
            bottom = cy + rootRadius,
        )
        // Contour: root arc (valley) → side line up → outer arc (tooth top) →
        // side line down → next valley. EvenOdd cuts the centre hole out.
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            var angle = -90f // start at 12 o'clock
            for (i in 0 until teeth) {
                val rootEnd = angle + toothDeg
                arcTo(rootRect, angle, toothDeg, forceMoveTo = i == 0)
                arcTo(outerRect, rootEnd, stepDeg - toothDeg, forceMoveTo = false)
                angle += stepDeg
            }
            close()
            addOval(
                Rect(
                    left = cx - holeRadius,
                    top = cy - holeRadius,
                    right = cx + holeRadius,
                    bottom = cy + holeRadius,
                ),
            )
        }
        drawPath(path, color = tint)
    }
}

@Composable
private fun AppSidebar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
    onOpenRecoverySection: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = TVNextDimens.ScreenPaddingCompact,
                vertical = TVNextDimens.SectionSpacing,
            ),
        verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
    ) {
        // Settings is NOT in the sidebar: it is reached from the top bar's
        // gear button (top-right), keeping navigation to the TV surfaces.
        AppTab.entries.filter { it != AppTab.SETTINGS }.forEach { tab ->
            AppSidebarItem(
                label = tab.label,
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
            )
        }
        // Same typography, spacing, icon marker and focus behaviour as all
        // other entries; settings remains accessible only via the top-right gear.
        if (isAndroidPlatform() && onOpenRecoverySection != null) {
            listOf(
                "editor" to uiText("Редактор каналов"),
                "recordings" to uiText("Мои записи"),
            ).forEach { (section, label) ->
                AppSidebarItem(
                    label = label,
                    selected = false,
                    onClick = { onOpenRecoverySection(section) },
                )
            }
        }
    }
}

@Composable
private fun AppSidebarItem(label: String, selected: Boolean, onClick: () -> Unit) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.fillMaxWidth().height(TVNextDimens.NavItemHeight),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacing,
            vertical = TVNextDimens.ItemSpacingSmall,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
            )
            Spacer(Modifier.width(TVNextDimens.ItemSpacingSmall))
            val labelColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                animationSpec = tween(160),
                label = "navLabel",
            )
            Text(label, style = MaterialTheme.typography.titleMedium, color = labelColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TVNextDimens.FooterHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = TVNextDimens.ScreenPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TVNext",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "v${com.tvnext.app.core.common.AppVersion.NAME}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val AppTab.label: String
    get() = when (this) {
        AppTab.HOME -> uiText("Главная")
        AppTab.CHANNELS -> uiText("Каналы")
        AppTab.TV_GUIDE -> uiText("ТВ-гид")
        AppTab.MEDIA_LIBRARY -> uiText("Медиатека")
        AppTab.SEARCH -> uiText("Поиск")
        AppTab.SETTINGS -> uiText("Настройки")
    }

@Composable
private fun ThemePreference.resolveDarkTheme(): Boolean = when (this) {
    ThemePreference.DARK -> true
    ThemePreference.LIGHT -> false
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
}
