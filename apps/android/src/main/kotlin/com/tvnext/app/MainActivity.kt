package com.tvnext.app

import android.view.KeyEvent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModelProvider
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.domain.StartScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.tvnext.app.core.common.util.RecordingPanelEvents
import com.tvnext.app.core.common.util.PlaybackUiRequests
import com.tvnext.app.core.common.util.PreviousChannelHistory
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.AndroidVideoAspectSettings
import com.tvnext.app.core.app.AppShell
import com.tvnext.app.core.app.BackExitState
import com.tvnext.app.core.app.DefaultAppComponent
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.app.tab.AppTabComponent
import com.tvnext.app.feature.home.ui.HomeIntent
import com.tvnext.app.feature.channels.ui.ChannelsIntent
import com.tvnext.app.core.ui.image.LogoImageLoader
import com.tvnext.app.core.ui.image.ProvideLogoImageLoader
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named

class MainActivity : ComponentActivity() {

    private var appComponent: DefaultAppComponent? = null

    private lateinit var playbackViewModel: PlaybackViewModel
    private var recoveryScreen by mutableStateOf(RecoveryScreen.NONE)
    private var menuHomeRequested by mutableStateOf(false)
    private var parentalPrompt by mutableStateOf<Pair<String, () -> Unit>?>(null)
    private lateinit var parentalProtection: ParentalProtection
    private var epgRecordingRequest by mutableStateOf<com.tvnext.app.core.common.util.RecordingScheduleRequest?>(null)
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingExport: Pair<String,String>? = null
    private val zipExportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingExport
        pendingExport = null
        if (result.resultCode != Activity.RESULT_OK || pending == null) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        exportScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { RecordingExport.toUri(this@MainActivity,pending.first,pending.second,uri) }
            }
            Toast.makeText(this@MainActivity,
                if (outcome.isSuccess) "Запись сохранена" else "Не удалось сохранить запись",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun exportThroughFilePicker(id: String, owner: String): Boolean {
        val chooser = RecordingFileContracts.zipExport(this,"TVNext_${id.take(8)}.zip") ?: return false
        return runCatching {
            pendingExport = id to owner
            zipExportLauncher.launch(chooser)
            true
        }.getOrElse { pendingExport = null; false }
    }

    /**
     * Android TV: CH+ (KEYCODE_CHANNEL_UP = 166) — следующий канал,
     * CH- (KEYCODE_CHANNEL_DOWN = 167) — предыдущий канал.
     * Compose Multiplatform не знает эти keyCode, поэтому преобразуем их
     * в DPAD_UP/DPAD_DOWN (19/20), которые Compose обрабатывает штатно.
     * CH+ → DPAD_UP, CH- → DPAD_DOWN (keep CH+ as "next channel"
     * after the fullscreen UP/DOWN semantics are swapped).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The Activity owns BACK on Android TV. Close the PIN prompt before
        // handing it to the app navigation stack, otherwise Back can leave the
        // restricted channel dialog open while navigating behind it.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && parentalPrompt != null) {
            if (event.action == KeyEvent.ACTION_UP) parentalPrompt = null
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && epgRecordingRequest != null) {
            if (event.action == KeyEvent.ACTION_UP) epgRecordingRequest = null
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && recoveryScreen != RecoveryScreen.NONE) {
            if (event.action == KeyEvent.ACTION_UP) recoveryScreen =
                RecoveryScreen.NONE
            return true
        }
        // Recovery-only entry point; does not alter the original navigation tree.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                recoveryScreen = RecoveryScreen.NONE
                menuHomeRequested = true
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onBackPressedDispatcher.onBackPressed()
            }
            // Consume the Android-TV BACK key here. The app-level state machine
            // is the single owner of BACK, so a guide/timeline can never lose
            // the first press to a focused Compose child and fall through to
            // HOME on the second press.
            return appComponent != null
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                val mapped = mapChannelKey(event, KeyEvent.KEYCODE_DPAD_UP)
                return super.dispatchKeyEvent(mapped)
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val mapped = mapChannelKey(event, KeyEvent.KEYCODE_DPAD_DOWN)
                return super.dispatchKeyEvent(mapped)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun mapChannelKey(event: KeyEvent, code: Int) = KeyEvent(
        event.downTime, event.eventTime, event.action, code, event.repeatCount,
        event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackViewModel = ViewModelProvider(this)[PlaybackViewModel::class.java]
        parentalProtection = ParentalProtection(this)
        com.tvnext.app.core.iptv.domain.ParentalChannelGate.handler = { channel, granted ->
            if (!parentalProtection.enabled || parentalProtection.unlocked ||
                !com.tvnext.app.core.iptv.domain.ParentalChannelGate.isRestricted(channel)) {
                true
            } else {
                parentalPrompt = channel.name to granted
                false
            }
        }

        // Android TV runs ALWAYS full-screen: hide the status and navigation
        // bars (edge-to-edge). The in-app channel/library drawer slides in over
        // the video via D-pad LEFT / OK instead of system UI.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Media player: keep the screen on and block the system screensaver /
        // display sleep while the app is in the foreground (Android TV).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val koin = GlobalContext.get()
        // Live playback and EPG updates run together. Track decoder activity
        // so the dedicated low-priority XMLTV worker can yield more CPU while
        // preserving uninterrupted playback and the last published EPG.
        exportScope.launch {
            koin.get<com.tvnext.app.core.player.PlaybackController>().state.collect { player ->
                com.tvnext.app.core.iptv.data.AndroidEpgPlaybackGate.setVideoActive(
                    player.item != null && player.state != com.tvnext.app.core.player.PlaybackState.IDLE &&
                        player.state != com.tvnext.app.core.player.PlaybackState.ENDED &&
                        player.state != com.tvnext.app.core.player.PlaybackState.ERROR
                )
            }
        }
        // Existing SettingsRepository is the single authoritative source.
        // No duplicate settings screen / SharedPreferences configuration.
        exportScope.launch {
            koin.get<SettingsRepository>().observe().collect { settings ->
                (koin.get<com.tvnext.app.core.player.PlaybackController>()
                    as? com.tvnext.app.core.player.AndroidPlaybackController)
                    ?.updateDecodeMode(settings.videoDecodeMode)
            }
        }
        val rootComponent = DefaultAppComponent(
            componentContext = defaultComponentContext(),
            factory = koin.get(),
            settingsRepository = koin.get(),
            reminderService = koin.get(),
            playlistRepository = koin.get(),
            epgRepository = koin.get(),
            onExitRequested = {
                // Second Back on Home (or a single Back on Home after the first
                // confirmation) closes the app entirely.
                finishAndRemoveTask()
            },
            mainContext = koin.get(named("mainDispatcher")),
        )
        appComponent = rootComponent
        // Apply the persisted start-tab choice once at process start. No
        // setting is fetched on the UI thread; the default remains Channels.
        exportScope.launch {
            val chosen = withContext(Dispatchers.IO) {
                runCatching { koin.get<SettingsRepository>().load().startScreen }
                    .getOrDefault(StartScreen.CHANNELS)
            }
            val tab = when (chosen) {
                StartScreen.HOME -> AppTab.HOME
                StartScreen.CHANNELS -> AppTab.CHANNELS
                StartScreen.TV_GUIDE -> AppTab.TV_GUIDE
                StartScreen.MEDIA_LIBRARY -> AppTab.MEDIA_LIBRARY
            }
            rootComponent.selectTab(tab)
        }
        // Android's dispatcher is the authoritative BACK path for TV remotes
        // and gesture/system back. The same state machine handles it exactly
        // once, so a guide/timeline cannot consume the first press and fall
        // through to HOME on the second.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                rootComponent.handleBack()
            }
        })
        // Scheduled-programme reminders (in-app banner; no auto channel switch).
        val reminderService = koin.getOrNull<com.tvnext.app.core.iptv.data.ScheduledReminderService>()
        reminderService?.start()
        val logoLoader = koin.getOrNull<LogoImageLoader>()

        setContent {
            LaunchedEffect(Unit) {
                RecordingPanelEvents.requests.collect { recoveryScreen = RecoveryScreen.RECORDINGS }
            }
            LaunchedEffect(Unit) {
                com.tvnext.app.core.common.util.RecordingScheduleRequests.requests.collect {
                    epgRecordingRequest = it
                }
            }
            LaunchedEffect(menuHomeRequested) {
                if (menuHomeRequested) {
                    recoveryScreen = RecoveryScreen.NONE
                    rootComponent.selectTab(AppTab.HOME)
                    menuHomeRequested = false
                }
            }
            LaunchedEffect(Unit) {
                PlaybackUiRequests.aspectLabel.value = AndroidVideoAspectSettings.get(this@MainActivity).displayName
                PlaybackUiRequests.aspectRequests.collect {
                    recoveryScreen = RecoveryScreen.VIDEO_ASPECT
                }
            }
            LaunchedEffect(Unit) {
                PlaybackUiRequests.recordRequests.collect {
                    val playback = koin.get<com.tvnext.app.core.player.PlaybackController>()
                    val item = playback.state.value.item
                    val store = LocalRecoveryStore(this@MainActivity)
                    val owner = store.activeProfileId()
                    if (store.recordings(owner).any { it.status == "recording" && !it.scheduled }) {
                        RecordingService.stop(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Останавливаем запись", Toast.LENGTH_SHORT).show()
                    } else if (item?.kind == PlaybackContentKind.LIVE && item.url.startsWith("http")) {
                        runCatching { RecordingService.start(this@MainActivity, item.url, item.title, owner) }
                            .onSuccess { Toast.makeText(this@MainActivity, "Запись начата", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@MainActivity, "Не удалось начать запись", Toast.LENGTH_SHORT).show() }
                    } else Toast.makeText(this@MainActivity, "Сначала включите прямой эфир", Toast.LENGTH_SHORT).show()
                }
            }
            LaunchedEffect(Unit) {
                PlaybackUiRequests.previousRequests.collect {
                    val id = PreviousChannelHistory.target()
                    if (id != null) {
                        val children = rootComponent.childStack.value
                        val channels = (children.backStack + children.active).mapNotNull { it.instance as? AppTabComponent.Channels }.firstOrNull()
                        if (channels != null) {
                            channels.component.dispatch(ChannelsIntent.SelectChannel(id))
                            rootComponent.selectTab(AppTab.CHANNELS)
                        }
                    }
                }
            }
            // Android TV immersive state: the Channels/Media screens drive it
            // themselves (auto full-screen while watching), so `onImmersiveChange`
            // just stores it here. Leaving a video tab returns to the normal
            // app chrome.
            var immersive by remember { mutableStateOf(false) }
            DisposableEffect(rootComponent) {
                rootComponent.setForceImmersiveHandler { immersive = true }
                onDispose { rootComponent.setForceImmersiveHandler {} }
            }
            val childStack by rootComponent.childStack.subscribeAsState()
            val activeTab = childStack.active.configuration
            LaunchedEffect(activeTab) {
                if (activeTab != AppTab.CHANNELS && activeTab != AppTab.MEDIA_LIBRARY) {
                    immersive = false
                }
            }

            // First Back on the Home tab arms the double-back-to-exit: show a
            // toast hint. A second Back (while armed) triggers onExitRequested.
            LaunchedEffect(rootComponent) {
                rootComponent.backExit.collect { state ->
                    if (state == BackExitState.CONFIRM_EXIT) {
                        Toast.makeText(
                            this@MainActivity,
                            "Еще раз нажмите Назад (Back), чтобы выйти из TVNext",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // Reads are refreshed on Activity recreation after a profile switch.
            // A revision is also bumped after closing the profile editor.
            var profileRevision by remember { mutableStateOf(0) }
            val profileStore = remember { LocalRecoveryStore(this@MainActivity) }
            val activeProfile = remember(profileRevision) { profileStore.activeProfileId() }
            val profileChoices = remember(profileRevision) {
                profileStore.profiles().map { it.id to it.name }
            }
            val epgStatus by remember { koin.get<com.tvnext.app.core.iptv.data.EpgRepository>()
                .observeState() }.collectAsState()
            ProvideLogoImageLoader(loader = logoLoader) {
                    AppShell(
                        component = rootComponent,
                        immersive = immersive,
                        onImmersiveChange = { immersive = it },
                        onOpenMainMenu = { rootComponent.selectTab(AppTab.HOME) },
                        profileChoices = profileChoices,
                        activeProfileId = activeProfile,
                        onProfileSelected = { id ->
                            if (id != profileStore.activeProfileId()) {
                                val owner = profileStore.activeProfileId()
                                if (profileStore.recordings(owner).any {
                                        it.status == "recording" && !it.scheduled
                                    }) RecordingService.stop(this@MainActivity)
                                koin.get<com.tvnext.app.core.player.PlaybackController>().stop()
                                if (profileStore.switchProfile(id)) {
                                    PreviousChannelHistory.clear()
                                    parentalProtection.lock()
                                    com.tvnext.app.core.player.AndroidPlaybackSession.clearPersonalSession()
                                    // Rebuild Decompose components with the new profile data.
                                    recreate()
                                } else {
                                    Toast.makeText(this@MainActivity, "Не удалось выбрать профиль", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        androidSettingsContent = { settingsComponent, onBack ->
                            AndroidReferenceSettingsScreen(
                                component = settingsComponent,
                                onBackHome = onBack,
                                onOpen = { action ->
                                    recoveryScreen = when (action) {
                                        "profiles" -> RecoveryScreen.PROFILES
                                        "editor" -> RecoveryScreen.CHANNEL_EDITOR
                                        "recordings" -> RecoveryScreen.RECORDINGS
                                        "recording_settings" -> RecoveryScreen.RECORDING_SETTINGS
                                        "backup" -> RecoveryScreen.BACKUP
                                        "video_aspect" -> RecoveryScreen.VIDEO_ASPECT
                                        else -> RecoveryScreen.NONE
                                    }
                                },
                            )
                        },
                        onOpenRecoverySection = { section ->
                            recoveryScreen = when(section) {
                                "editor" -> RecoveryScreen.CHANNEL_EDITOR
                                "recordings" -> RecoveryScreen.RECORDINGS
                                "profiles" -> RecoveryScreen.PROFILES
                                "recording_settings" -> RecoveryScreen.RECORDING_SETTINGS
                                "backup" -> RecoveryScreen.BACKUP
                                else -> RecoveryScreen.NONE
                            }
                        },
                        epgLoading = epgStatus.isLoading,
                        epgLoadedChannels = epgStatus.channelCount,
                        epgLoadedProgrammes = epgStatus.programmeCount,
                        overlayFullScreen = recoveryScreen == RecoveryScreen.CHANNEL_EDITOR ||
                            recoveryScreen == RecoveryScreen.RECORDINGS,
                        overlayContent = {
                    parentalPrompt?.let { request ->
                        ParentalPinEntryDialog(
                            title = "🔒 ${request.first}",
                            onCancel = { parentalPrompt = null },
                            onSubmit = { value ->
                                if (parentalProtection.verify(value)) {
                                    parentalProtection.unlock()
                                    parentalPrompt = null
                                    request.second.invoke()
                                    true
                                } else false
                            },
                        )
                    }
                    epgRecordingRequest?.let { request ->
                        EpgRecordingScheduleDialog(request) { epgRecordingRequest = null }
                    }
                    when (recoveryScreen) {
                        RecoveryScreen.NONE -> Unit
                        RecoveryScreen.CHANNEL_EDITOR -> ProfileChannelEditor(onClose = { changed ->
                            if (changed) refreshEditedChannels()
                            recoveryScreen = RecoveryScreen.NONE
                        })
                        RecoveryScreen.VIDEO_ASPECT -> VideoAspectSettingsDialog(
                            onDismiss = {
                                PlaybackUiRequests.aspectLabel.value = AndroidVideoAspectSettings.get(this@MainActivity).displayName
                                recoveryScreen = RecoveryScreen.NONE
                            },
                        )
                        RecoveryScreen.BACKUP -> ProfileBackupDialog(
                            onDismiss = { recoveryScreen = RecoveryScreen.NONE },
                        )
                        RecoveryScreen.RECORDINGS -> ProfileRecordingsScreen(
                            playback = koin.get<com.tvnext.app.core.player.PlaybackController>(),
                            onExportWithPicker = ::exportThroughFilePicker,
                            onBack = { recoveryScreen = RecoveryScreen.NONE },
                        )
                        else -> RecoveryControlPanel(
                            playback = koin.get<com.tvnext.app.core.player.PlaybackController>(),
                            onExportWithPicker = ::exportThroughFilePicker,
                            onDismiss = {
                                if (recoveryScreen == RecoveryScreen.PROFILES) profileRevision++
                                recoveryScreen = RecoveryScreen.NONE
                            },
                            section = when (recoveryScreen) {
                                RecoveryScreen.PROFILES -> RecoverySection.PROFILES
                                RecoveryScreen.RECORDINGS -> RecoverySection.RECORDINGS
                                else -> RecoverySection.RECORDING_SETTINGS
                            },
                        )
                    }
                        },
                    )
            }
        }
    }

    /** Refresh retained tab views after editing channels without restarting video or EPG. */
    private fun refreshEditedChannels() {
        val tabs = appComponent?.childStack?.value ?: return
        (tabs.backStack + tabs.active).forEach { child ->
            when (val tab = child.instance) {
                is AppTabComponent.Home -> tab.component.dispatch(HomeIntent.Load)
                is AppTabComponent.Channels -> tab.component.dispatch(ChannelsIntent.Load)
                else -> Unit
            }
        }
    }

    override fun onDestroy() {
        // Only a clean user exit (double-Back / finish) clears the boot marker;
        // a system kill (LMK/native crash) leaves it, so the next launch can
        // detect the crash and skip the heavy automatic EPG import.
        if (isFinishing) {
            AndroidBootDiagnostics.onCleanExit(this)
            if (::parentalProtection.isInitialized) parentalProtection.lock()
        }
        com.tvnext.app.core.iptv.domain.ParentalChannelGate.handler = null
        appComponent = null
        exportScope.cancel()
        com.tvnext.app.core.iptv.data.AndroidEpgPlaybackGate.setAppForeground(false)
        com.tvnext.app.core.iptv.data.AndroidEpgPlaybackGate.setVideoActive(false)
        super.onDestroy()
    }

    override fun onStop() {
        com.tvnext.app.core.iptv.data.AndroidEpgPlaybackGate.setAppForeground(false)
        AndroidPerformanceDiagnostics.stop()
        super.onStop()
        // Left the foreground: drop the boot marker (a background kill after
        // Home is ordinary Android behaviour, not the reported crash).
        AndroidBootDiagnostics.onEnterBackground(this)
        playbackViewModel.onBackground()
        GlobalContext.get().getOrNull<com.tvnext.app.core.iptv.data.ScheduledReminderService>()?.stop()
    }

    override fun onStart() {
        super.onStart()
        com.tvnext.app.core.iptv.data.AndroidEpgPlaybackGate.setAppForeground(true)
        AndroidPerformanceDiagnostics.start(applicationContext)
        // Back in the foreground: re-arm the boot marker so only a FOREGROUND
        // kill (e.g. during the EPG import) is later treated as a crash, not a
        // background reap after Home.
        AndroidBootDiagnostics.onEnterForeground(this)
        playbackViewModel.onForeground()
        GlobalContext.get().getOrNull<com.tvnext.app.core.iptv.data.ScheduledReminderService>()?.start()
    }
}
