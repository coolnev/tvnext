package com.tvnext.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.tvnext.app.core.app.AppShell
import com.tvnext.app.core.app.DefaultAppComponent
import com.tvnext.app.core.app.di.appKoinModule
import com.tvnext.app.core.common.di.commonKoinModule
import com.tvnext.app.core.common.platform.AppDataReset
import com.tvnext.app.core.database.di.databaseDesktopKoinModule
import com.tvnext.app.core.database.di.databaseKoinModule
import com.tvnext.app.core.iptv.di.iptvKoinModule
import com.tvnext.app.core.iptv.data.ScheduledReminderService
import com.tvnext.app.core.licensing.di.licensingKoinModule
import com.tvnext.app.core.network.di.networkKoinModule
import com.tvnext.app.core.tmdb.di.tmdbKoinModule
import com.tvnext.app.core.player.DesktopPlaybackController
import com.tvnext.app.core.player.MpvPlaybackController
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.di.playerDesktopKoinModule
import com.tvnext.app.core.player.di.playerKoinModule
import com.tvnext.app.core.ui.image.DefaultLogoImageLoader
import com.tvnext.app.core.ui.image.LogoFetcher
import com.tvnext.app.core.ui.image.LogoImageLoader
import com.tvnext.app.core.ui.image.ProvideLogoImageLoader
import com.tvnext.app.feature.channels.di.channelsKoinModule
import com.tvnext.app.feature.home.di.homeKoinModule
import com.tvnext.app.feature.media.di.mediaKoinModule
import com.tvnext.app.feature.search.di.searchKoinModule
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.di.settingsKoinModule
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.domain.WindowResolution
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.tmdb.TmdbSettingsStore
import com.tvnext.app.feature.tvg.di.tvgKoinModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val desktopKoinModule = module {
    single<CoroutineContext>(named("mainDispatcher")) { Dispatchers.Main }
    single<AppDataReset> { DesktopAppDataReset() }
    single<com.tvnext.app.core.common.platform.AppAutostart> { DesktopAppAutostart() }
}

/**
 * Channel-logo loader: memory + disk cache, Ktor fetch. Errors are local —
 * a failed logo never breaks playback or the UI.
 */
private val logoKoinModule = module {
    single<LogoImageLoader> {
        val client: HttpClient = get()
        DefaultLogoImageLoader(
            fetcher = LogoFetcher { url -> fetchLogo(client, url) },
            cacheDir = File(com.tvnext.app.core.common.platform.DesktopAppPaths.dataDirectory,"cache/logos").absolutePath,
        )
    }
}

private suspend fun fetchLogo(client: HttpClient, url: String): ByteArray? = runCatching {
    client.get(url) {
        header(HttpHeaders.UserAgent, com.tvnext.app.core.common.AppVersion.USER_AGENT)
        header(HttpHeaders.Accept, "image/*")
        timeout {
            requestTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
    }.bodyAsBytes()
}.getOrNull()

/** Diagnostic logging on Windows — enabled with `-Dtvnext.player.debug=true`. */
private val DEBUG_ENABLED: Boolean =
    System.getProperty("tvnext.player.debug") == "true"

internal fun startDesktopDependencies() = startKoin {
            modules(
                commonKoinModule,
                appKoinModule,
                desktopKoinModule,
                databaseKoinModule,
                databaseDesktopKoinModule,
                networkKoinModule,
                tmdbKoinModule,
                iptvKoinModule,
                playerKoinModule,
                playerDesktopKoinModule,
                licensingKoinModule,
                homeKoinModule,
                channelsKoinModule,
                settingsKoinModule,
                mediaKoinModule,
                tvgKoinModule,
                searchKoinModule,
                logoKoinModule,
                desktopProfileModule,
            )
        }.koin

fun main() {
    com.tvnext.app.core.iptv.data.AndroidCatalogBootstrap.pending.value=System.getProperty("tvnext.bootstrap")!="false"
    // Wipe the data directory if a previous «Сбросить все данные» left a
    // pending marker. Must run BEFORE the database and caches are opened.
    DesktopAppDataReset().handlePendingReset()

    // Keep the system awake while TVNext runs: the screensaver is disabled
    // and the system cannot fall asleep / turn the display off. A shutdown
    // hook restores the user's previous OS settings when the app exits.
    KeepAwake.start()
    Runtime.getRuntime().addShutdownHook(Thread { KeepAwake.stop() })

    application {
    // Enable interop blending so Compose overlays (channel name, error,    // controls) keep rendering above the native HWND video surface used by the
    // hardware decode path. Must be set before the first Compose scene is
    // created. (Note: Compose content cannot be drawn ABOVE a D3D11 video
    // surface, so in hardware mode overlays live in the bar above the video;
    // blending still matters for GDI-based interop surfaces.)
    System.setProperty("compose.interop.blending", "true")
    // mpv publishes ordinary Compose images; let Skiko select the supported GPU backend.
    // An explicit skiko.renderApi remains available for diagnostics.

    val koin = remember {startDesktopDependencies()}

    // First-run convenience: seed the user's playlists and TMDB key when the
    // library is empty (existing data is never touched).
    val demoScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) {
        demoScope.launch {
            try {
            if(System.getProperty("tvnext.bootstrap")=="false")return@launch
            runCatching {
                val playlistRepository = koin.get<PlaylistRepository>()
                // Seed the user's sources by URL: each missing playlist is added
                // (works after an app UPDATE too, not only on an empty library).
                val existingUrls = playlistRepository.getPlaylists()
                    .map { it.url.trimEnd('/') }
                    .toSet()
                // LIVE: vivamax (крупный EPG для проверки).
                if (LIVE_PLAYLIST_URL !in existingUrls) {
                    playlistRepository.addPlaylist(
                        url = LIVE_PLAYLIST_URL,
                        type = PlaylistType.LIVE,
                        name = "vivamax",
                    )
                }
                // Медиатека (VOD).
                if (VOD_PLAYLIST_URL !in existingUrls) {
                    playlistRepository.addPlaylist(
                        url = VOD_PLAYLIST_URL,
                        type = PlaylistType.VOD,
                        name = "viplimeVOD",
                    )
                }
                val tmdbSettings = koin.get<TmdbSettingsStore>()
                if (tmdbSettings.getApiKey().isBlank()) {
                    tmdbSettings.setEnabled(true)
                    tmdbSettings.setApiKey("698bff9ae128f80d8e6bff80ae075549")
                }
            }
            }finally {com.tvnext.app.core.iptv.data.AndroidCatalogBootstrap.pending.value=false}
        }
        onDispose {demoScope.cancel();com.tvnext.app.core.iptv.data.AndroidCatalogBootstrap.pending.value=false}
    }

    val startupSettings = remember {
        // Read the small local settings snapshot before constructing the first tab.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { koin.get<SettingsRepository>().load() }
    }
    val lifecycle = remember { LifecycleRegistry() }
    val rootComponent = remember {
        DefaultAppComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            factory = koin.get(),
            settingsRepository = koin.get(),
            reminderService = koin.get(),
            playlistRepository = koin.get(),
            epgRepository = koin.get(),
            initialSettings = startupSettings,
            initialTab = when(startupSettings.startScreen) {
                com.tvnext.app.feature.settings.domain.StartScreen.HOME -> com.tvnext.app.core.app.tab.AppTab.HOME
                com.tvnext.app.feature.settings.domain.StartScreen.CHANNELS -> com.tvnext.app.core.app.tab.AppTab.CHANNELS
                com.tvnext.app.feature.settings.domain.StartScreen.TV_GUIDE -> com.tvnext.app.core.app.tab.AppTab.TV_GUIDE
                com.tvnext.app.feature.settings.domain.StartScreen.MEDIA_LIBRARY -> com.tvnext.app.core.app.tab.AppTab.MEDIA_LIBRARY
            },
            onExitRequested = ::exitApplication,
            mainContext = koin.get(named("mainDispatcher")),
        )
    }

    // Wire the playback settings (decode mode, network buffer) into the desktop
    // playback controller. Both apply on the next play() (safe, non-disruptive).
    val desktopController = remember {
        koin.get<PlaybackController>() as? com.tvnext.app.core.player.PlaybackSettingsSink
    }
    val settingsRepository = remember { koin.get<SettingsRepository>() }
    val settingsScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) {
        val job = settingsScope.launch {
            var epgKey:Pair<Boolean,String>?=null
            settingsRepository.observe().collect { settings ->
                val nextEpg=settings.epgEnabled to settings.epgUrl
                if(epgKey!=nextEpg) {
                    epgKey=nextEpg
                    (koin.get<com.tvnext.app.core.iptv.data.EpgRepository>() as? com.tvnext.app.core.iptv.data.DesktopEpgRepository)?.configurationChanged()
                }
                desktopController?.updateDecodeMode(settings.videoDecodeMode)
                desktopController?.updateNetworkCachingMs(settings.networkCachingMs.toLong())
                (desktopController as? MpvPlaybackController)?.updateAudioOutputMode(settings.audioOutputMode)
            }
        }
        onDispose {
            job.cancel()
            settingsScope.cancel()
        }
    }

    // Scheduled-programme reminders (in-app banner, no channel auto-switching).
    val reminderService = remember { koin.get<ScheduledReminderService>() }
    DisposableEffect(Unit) {
        reminderService.start()
        onDispose { reminderService.stop() }
    }

    DisposableEffect(lifecycle) {
        lifecycle.create()
        lifecycle.resume()
        onDispose {
            koin.getOrNull<DesktopServices>()?.close()
            koin.getOrNull<PlaybackController>()?.release()
            lifecycle.destroy()
        }
    }

    var fullscreen by remember { mutableStateOf(false) }
    var startFullscreenApplied by remember { mutableStateOf(false) }
    // Hoisted above the window so channel-list visibility (immersive, L)
    // survives fullscreen toggles.
    var immersive by remember { mutableStateOf(false) }
    // On-screen diagnostics toggle (key I), global across channels/media.
    var showDiagnostics by remember { mutableStateOf(false) }

    // Desktop window size: AUTO adapts to the actual monitor resolution
    // (large video area on 4K), the fixed modes force HD/FullHD/4K windows.
    val settingsState by rootComponent.settings.collectAsState()
    val windowResolution = settingsState.windowResolution
    val windowState = rememberWindowState(
        width = initialWindowSize(windowResolution).first,
        height = initialWindowSize(windowResolution).second,
    )
    LaunchedEffect(windowResolution) {
        val (width, height) = initialWindowSize(windowResolution)
        windowState.size = DpSize(width, height)
    }

    // The mpv render thread keeps writing Skia bitmaps while the window is
    // being re-created on F11. If it races the destruction of the old window's
    // OpenGL surface, Skiko corrupts the heap (Windows 0xC0000374 → crash).
    // Pause rendering around the window round-trip and resume afterwards.
    val mpvController = remember {
        koin.getOrNull<PlaybackController>() as? MpvPlaybackController
    }
    LaunchedEffect(fullscreen) {
        delay(150) // let the new window compose
        mpvController?.resumeRendering()
    }
    fun toggleFullscreen() {
        mpvController?.suspendRendering()
        fullscreen = !fullscreen
    }

    // Window/taskbar icon, loaded from the bundled resource (tvnext.png).
    val appIcon = remember {
        runCatching {
            val resource = javaClass.classLoader?.getResource("tvnext.png")
            if (resource == null) {
                null
            } else {
                javax.imageio.ImageIO.read(resource).toComposeImageBitmap()
            }
        }.getOrNull()
    }

    // Fullscreen switches to a dedicated undecorated window (the design that
    // works): `WindowPlacement.Fullscreen` on the same window leaves the OS
    // frame (minimize/maximize/close buttons) visible — that must not happen.
    // (Double-click → fullscreen is handled by Compose itself on the software/
    // libmpv render path; the libVLC backend wires onVideoDoubleClick above.)

    if (fullscreen) {
        FullscreenWindow(
            rootComponent = rootComponent,
            onCloseRequest = ::exitApplication,
            onExitFullscreen = { toggleFullscreen() },
            appIcon = appIcon,
            immersive = immersive,
            onImmersiveChange = { immersive = it },
            showDiagnostics = showDiagnostics,
            onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
            logoLoader = koin.getOrNull(),
        )
    } else {
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "TVNext",
            icon = appIcon?.let { BitmapPainter(it) },
        ) {
            DisposableEffect(Unit) {
                if (DEBUG_ENABLED) println("[TVNext] normal window CREATED")
                onDispose {
                    if (DEBUG_ENABLED) println("[TVNext] normal window DISPOSED")
                }
            }
            AppContent(
                hostWindow = window,
                rootComponent = rootComponent,
                onToggleFullscreen = { toggleFullscreen() },
                isFullscreen = false,
                startFullscreenApplied = startFullscreenApplied,
                onStartFullscreenApplied = { startFullscreenApplied = true },
                immersive = immersive,
                onImmersiveChange = { immersive = it },
                showDiagnostics = showDiagnostics,
                onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                logoLoader = koin.getOrNull(),
            )
        }
    }
}
}

/** Debug-seed: LIVE playlist (vivamax, крупный EPG для проверки). */
private const val LIVE_PLAYLIST_URL = "https://bittv.ltd/playlists/b0c7hew623:uw8qk62ugl/playlist.m3u"

/** Debug-seed: VOD (медиатека). */
private const val VOD_PLAYLIST_URL = "https://m.cdntv.online/f/9y6xwink2s/playlist.m3u8"

/**
 * Preferred desktop window size for a resolution mode. Sizes are real physical
 * pixels converted to dp using the monitor DPI scaling, so the resulting
 * window matches the target resolution on any display (including 4K).
 *
 *  - AUTO: ~92 % of the actual monitor size (large video area on 4K);
 *  - HD / FullHD: fixed classic window sizes;
 *  - UHD_4K: full 4K UHD (3840×2160), clamped to the monitor when smaller.
 */
private fun initialWindowSize(
    resolution: WindowResolution,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val configuration=GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets=java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val maximumWidth=(configuration.bounds.width-insets.left-insets.right).coerceAtLeast(320)
    val maximumHeight=(configuration.bounds.height-insets.top-insets.bottom).coerceAtLeast(240)
    val scaleX=configuration.defaultTransform.scaleX.coerceAtLeast(1.0)
    val scaleY=configuration.defaultTransform.scaleY.coerceAtLeast(1.0)
    val size=when(resolution) {
        WindowResolution.AUTO->maximumWidth*.92 to maximumHeight*.92
        WindowResolution.HD->1280.0/scaleX to 720.0/scaleY
        WindowResolution.FULL_HD->1920.0/scaleX to 1080.0/scaleY
        WindowResolution.UHD_4K->3840.0/scaleX to 2160.0/scaleY
    }
    return size.first.coerceAtMost(maximumWidth.toDouble()).toFloat().dp to
        size.second.coerceAtMost(maximumHeight.toDouble()).toFloat().dp
}

@Composable
private fun FullscreenWindow(
    rootComponent: DefaultAppComponent,
    onCloseRequest: () -> Unit,
    onExitFullscreen: () -> Unit,
    appIcon: androidx.compose.ui.graphics.ImageBitmap?,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    showDiagnostics: Boolean,
    onToggleDiagnostics: () -> Unit,
    logoLoader: LogoImageLoader?,
) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "TVNext",
        undecorated = true,
        icon = appIcon?.let { BitmapPainter(it) },
    ) {
        DisposableEffect(Unit) {
            if (DEBUG_ENABLED) println("[TVNext] fullscreen window CREATED")
            onDispose {
                if (DEBUG_ENABLED) println("[TVNext] fullscreen window DISPOSED")
            }
        }
        val screenBounds = remember {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        }
        LaunchedEffect(Unit) {
            window.setBounds(screenBounds)
            window.toFront()
            // The undecorated fullscreen window does not automatically get
            // KEYBOARD focus: without this, no key event reaches the app.
            window.requestFocus()
        }
        AppContent(
            hostWindow = window,
            rootComponent = rootComponent,
            onToggleFullscreen = onExitFullscreen,
            isFullscreen = true,
            startFullscreenApplied = true,
            onStartFullscreenApplied = {},
            immersive = immersive,
            onImmersiveChange = onImmersiveChange,
            showDiagnostics = showDiagnostics,
            onToggleDiagnostics = onToggleDiagnostics,
            logoLoader = logoLoader,
        )
    }
}

@Composable
private fun AppContent(
    hostWindow: java.awt.Window,
    rootComponent: DefaultAppComponent,
    onToggleFullscreen: () -> Unit,
    isFullscreen: Boolean,
    startFullscreenApplied: Boolean,
    onStartFullscreenApplied: () -> Unit,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    showDiagnostics: Boolean,
    onToggleDiagnostics: () -> Unit,
    logoLoader: LogoImageLoader?,
) {
    val focusManager = LocalFocusManager.current
    val keyboardRoot = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboardRoot.requestFocus() }

    // The key-event dispatcher below is registered once (DisposableEffect key =
    // Unit) and its closures capture values at creation time. `rememberUpdatedState`
    // keeps the LATEST immersive value readable from inside those closures, so
    // L toggles back and forth instead of always applying `!initial`.
    val currentImmersive by rememberUpdatedState(immersive)

    LaunchedEffect(Unit) {
        if (!startFullscreenApplied) {
            val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) { org.koin.core.context.GlobalContext.get().get<SettingsRepository>().load() }
            onStartFullscreenApplied()
            if (loaded?.startFullscreen == true) {
                onStartFullscreenApplied()
                onToggleFullscreen()
            }
        }
    }

    ProvideLogoImageLoader(loader = logoLoader) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(keyboardRoot)
                // onKeyEvent (bottom-up) instead of onPreviewKeyEvent: a focused
                // text field (search) must be able to consume Escape first to
                // clear its text and release focus.
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        rootComponent.handleBack()
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        ) {
            DesktopExperience(hostWindow,rootComponent,onToggleFullscreen,isFullscreen,immersive,onImmersiveChange,
                showDiagnostics,onToggleDiagnostics)
        }
    }
}
