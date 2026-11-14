package com.tvnext.app.feature.settings.ui

import com.tvnext.app.core.ui.i18n.uiText

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.tvnext.app.core.common.log.Logger
import com.tvnext.app.core.common.mvi.MviComponent
import com.tvnext.app.core.common.mvi.MviEffect
import com.tvnext.app.core.common.mvi.MviIntent
import com.tvnext.app.core.common.mvi.MviState
import com.tvnext.app.core.common.platform.AppDataReset
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.fold
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.archive.ArchiveDepthChanges
import com.tvnext.app.core.iptv.data.EpgState
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.tmdb.TmdbConnectionResult
import com.tvnext.app.core.tmdb.TmdbRepository
import com.tvnext.app.core.tmdb.TmdbSettingsStore
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.domain.AppInfo
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.domain.StartScreen
import com.tvnext.app.feature.settings.domain.ThemePreference
import com.tvnext.app.feature.settings.domain.WindowResolution
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val appInfo: AppInfo = AppInfo(),
    val isLoading: Boolean = true,
    val error: AppError? = null,
    // Playlists (all sources, LIVE + VOD)
    val playlists: List<Playlist> = emptyList(),
    val playlistUrl: String = "",
    val playlistName: String = "",
    val playlistType: PlaylistType = PlaylistType.LIVE,
    val playlistLoading: Boolean = false,
    val playlistError: AppError? = null,
    /** Playlist id currently being renamed (inline edit field shown). */
    val renamingPlaylistId: String? = null,
    /** EPG (Settings → EPG) */
    val epgState: EpgState = EpgState(),
    /** Local EPG URL edit field (persisted on refresh). */
    val epgUrlField: String = "",
    val epgRefreshing: Boolean = false,
    val epgStateError: String? = null,
    val tmdbEnabled: Boolean = false,
    val tmdbApiKey: String = "",
    val tmdbTesting: Boolean = false,
    val tmdbCacheMessage: String? = null,
    val kinopoiskCacheMessage: String? = null,
    val tmdbConnectionResult: TmdbConnectionResult? = null,
    /** True while the «Сбросить все данные» confirmation is shown. */
    val confirmDataReset: Boolean = false,
) : MviState

sealed interface SettingsIntent : MviIntent {
    data class MediaPreferencesChanged(val preferences: com.tvnext.app.feature.settings.domain.MediaPreferences): SettingsIntent
    data class KinopoiskEnabledChanged(val enabled:Boolean): SettingsIntent
    data object Load : SettingsIntent
    data class LanguageChanged(val language: com.tvnext.app.core.common.UiLanguage) : SettingsIntent
    data class ThemeChanged(val theme: ThemePreference) : SettingsIntent
    data class AccentColorChanged(val accent: AccentColor) : SettingsIntent
    data class PanelTransparencyChanged(val percent: Int) : SettingsIntent
    data class StartScreenChanged(val screen: StartScreen) : SettingsIntent
    data class ClockPositionChanged(val position: ClockPosition) : SettingsIntent
    data class ClockWithoutBackgroundChanged(val enabled: Boolean) : SettingsIntent
    data class ShowArchiveLabelChanged(val enabled: Boolean) : SettingsIntent
    data class ProgrammeDescriptionPlacementChanged(val right: Boolean) : SettingsIntent
    data class StartFullscreenChanged(val enabled: Boolean) : SettingsIntent
    data class ShowChannelNumbersChanged(val enabled: Boolean) : SettingsIntent
    data class OpenLastChannelChanged(val enabled: Boolean) : SettingsIntent
    data class AutoFrameRateChanged(val preferences: com.tvnext.app.core.player.AutoFrameRatePreferences): SettingsIntent
    data class VideoDecodeModeChanged(val mode: VideoDecodeMode) : SettingsIntent
    /** Maximum archive depth in days; 0 = automatic (provider maximum). */
    data class ArchiveLimitChanged(val days: Int) : SettingsIntent
    /** Network buffer for playback (ms). */
    data class NetworkCachingChanged(val ms: Int) : SettingsIntent
    data class ArchiveSeekStepChanged(val ms: Long) : SettingsIntent
    /** Max cards per Home section. */
    data class HomeCardLimitChanged(val limit: Int) : SettingsIntent
    data class ShowHomeNowPlayingChanged(val enabled: Boolean) : SettingsIntent
    data class ShowHomeUpcomingChanged(val enabled: Boolean) : SettingsIntent
    data class ShowHomeRecentChanged(val enabled: Boolean) : SettingsIntent
    data class ShowHomeFavoritesChanged(val enabled: Boolean) : SettingsIntent
    data class ShowHomeRecentVodChanged(val enabled: Boolean) : SettingsIntent
    data class ShowHomeFavoriteVodChanged(val enabled: Boolean) : SettingsIntent

    /** Full-screen «Телепрограмма» block duration: null=off, 0=always, 1..7 s. */
    data class EpgInfoDurationChanged(val seconds: Int?) : SettingsIntent
    data class WindowResolutionChanged(val resolution: WindowResolution) : SettingsIntent
    /** Windows only (hidden on Android): autostart at OS login. */
    data class LaunchOnWindowsStartupChanged(val enabled: Boolean) : SettingsIntent

    // Playlists
    data class PlaylistUrlChanged(val url: String) : SettingsIntent
    data class PlaylistNameChanged(val name: String) : SettingsIntent
    data class PlaylistTypeChanged(val type: PlaylistType) : SettingsIntent
    data object PlaylistAdd : SettingsIntent
    data class PlaylistRefresh(val id: String) : SettingsIntent
    data class PlaylistRemove(val id: String) : SettingsIntent
    data class PlaylistSetDefault(val id: String?) : SettingsIntent
    data class PlaylistRenameStart(val id: String) : SettingsIntent
    data class PlaylistRenameApply(val id: String, val name: String) : SettingsIntent
    data object PlaylistRenameCancel : SettingsIntent

    // EPG
    data class EpgEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class EpgUrlChanged(val url: String) : SettingsIntent
    data object EpgRefresh : SettingsIntent
    data class TmdbEnabledChanged(val enabled: Boolean) : SettingsIntent
    data class TmdbApiKeyChanged(val apiKey: String) : SettingsIntent
    data object TmdbTestConnection : SettingsIntent
    data object TmdbClearCache : SettingsIntent
    data object KinopoiskClearCache : SettingsIntent

    // Data reset
    data object DataResetStart : SettingsIntent
    data object DataResetCancel : SettingsIntent
    data object DataResetConfirm : SettingsIntent
}

sealed interface SettingsEffect : MviEffect

interface SettingsComponent {
    val state: StateFlow<SettingsState>
    fun dispatch(intent: SettingsIntent)
}

class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val repository: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val epgRepository: EpgRepository,
    private val tmdbSettings: TmdbSettingsStore,
    private val tmdbRepository: TmdbRepository,
    private val dataReset: AppDataReset,
    private val autostart: com.tvnext.app.core.common.platform.AppAutostart,
    mainContext: CoroutineContext,
    private val kinopoiskLookup: com.tvnext.app.core.tmdb.KinopoiskProgrammeLookup? = null,
    private val programmeMetadata: com.tvnext.app.core.tmdb.ProgrammeMetadataRepository? = null,
) : SettingsComponent,
    ComponentContext by componentContext,
    MviComponent<SettingsState, SettingsIntent, SettingsEffect>(
        initialState = SettingsState(),
        scope = componentContext.coroutineScope(mainContext),
    ) {

    override fun reduce(state: SettingsState, intent: SettingsIntent): SettingsState = when (intent) {
        is SettingsIntent.MediaPreferencesChanged -> state.copy(settings=state.settings.copy(media=intent.preferences))
        is SettingsIntent.KinopoiskEnabledChanged -> state.copy(settings=state.settings.copy(kinopoiskEnabled=intent.enabled))
        SettingsIntent.Load -> state.copy(isLoading = true)
        is SettingsIntent.LanguageChanged -> state.copy(settings = state.settings.copy(language = intent.language))
        is SettingsIntent.ThemeChanged -> state.copy(settings = state.settings.copy(theme = intent.theme))
        is SettingsIntent.AccentColorChanged ->
            state.copy(settings = state.settings.copy(accentColor = intent.accent))
        is SettingsIntent.PanelTransparencyChanged ->
            state.copy(settings = state.settings.copy(panelTransparencyPercent = intent.percent.coerceIn(0, 80)))
        is SettingsIntent.StartScreenChanged ->
            state.copy(settings = state.settings.copy(startScreen = intent.screen))
        is SettingsIntent.ClockPositionChanged ->
            state.copy(settings = state.settings.copy(clockPosition = intent.position))
        is SettingsIntent.ClockWithoutBackgroundChanged ->
            state.copy(settings = state.settings.copy(clockWithoutBackground = intent.enabled))
        is SettingsIntent.ShowArchiveLabelChanged ->
            state.copy(settings = state.settings.copy(showArchiveLabel = intent.enabled))
        is SettingsIntent.ProgrammeDescriptionPlacementChanged ->
            state.copy(settings = state.settings.copy(programmeDescriptionRight = intent.right))
        is SettingsIntent.StartFullscreenChanged ->
            state.copy(settings = state.settings.copy(startFullscreen = intent.enabled))
        is SettingsIntent.ShowChannelNumbersChanged ->
            state.copy(settings = state.settings.copy(showChannelNumbers = intent.enabled))
        is SettingsIntent.OpenLastChannelChanged ->
            state.copy(settings = state.settings.copy(openLastChannel = intent.enabled))
        is SettingsIntent.AutoFrameRateChanged -> state.copy(settings=state.settings.copy(autoFrameRate=intent.preferences.normalized()))
        is SettingsIntent.VideoDecodeModeChanged ->
            state.copy(settings = state.settings.copy(videoDecodeMode = intent.mode))
        is SettingsIntent.ArchiveLimitChanged ->
            state.copy(settings = state.settings.copy(archiveLimitDays = intent.days))
        is SettingsIntent.NetworkCachingChanged ->
            state.copy(settings = state.settings.copy(networkCachingMs = intent.ms))
        is SettingsIntent.ArchiveSeekStepChanged ->
            state.copy(settings = state.settings.copy(archiveSeekStepMs = intent.ms))
        is SettingsIntent.HomeCardLimitChanged ->
            state.copy(settings = state.settings.copy(homeCardLimit = intent.limit))
        is SettingsIntent.ShowHomeNowPlayingChanged ->
            state.copy(settings = state.settings.copy(showHomeNowPlaying = intent.enabled))
        is SettingsIntent.ShowHomeUpcomingChanged ->
            state.copy(settings = state.settings.copy(showHomeUpcoming = intent.enabled))
        is SettingsIntent.ShowHomeRecentChanged ->
            state.copy(settings = state.settings.copy(showHomeRecent = intent.enabled))
        is SettingsIntent.ShowHomeFavoritesChanged ->
            state.copy(settings = state.settings.copy(showHomeFavorites = intent.enabled))
        is SettingsIntent.ShowHomeRecentVodChanged ->
            state.copy(settings = state.settings.copy(showHomeRecentVod = intent.enabled))
        is SettingsIntent.ShowHomeFavoriteVodChanged ->
            state.copy(settings = state.settings.copy(showHomeFavoriteVod = intent.enabled))
        is SettingsIntent.EpgInfoDurationChanged ->
            state.copy(settings = state.settings.copy(epgInfoDurationSeconds = intent.seconds))
        is SettingsIntent.WindowResolutionChanged ->
            state.copy(settings = state.settings.copy(windowResolution = intent.resolution))
        is SettingsIntent.LaunchOnWindowsStartupChanged ->
            state.copy(settings = state.settings.copy(launchOnWindowsStartup = intent.enabled))
        is SettingsIntent.PlaylistUrlChanged -> state.copy(playlistUrl = intent.url, playlistError = null)
        is SettingsIntent.PlaylistNameChanged -> state.copy(playlistName = intent.name)
        is SettingsIntent.PlaylistTypeChanged -> state.copy(playlistType = intent.type)
        SettingsIntent.PlaylistAdd -> state
        is SettingsIntent.PlaylistRefresh -> state
        is SettingsIntent.PlaylistRemove -> state
        is SettingsIntent.PlaylistSetDefault -> state
        is SettingsIntent.PlaylistRenameStart -> state.copy(renamingPlaylistId = intent.id)
        is SettingsIntent.PlaylistRenameApply -> state.copy(renamingPlaylistId = null)
        SettingsIntent.PlaylistRenameCancel -> state.copy(renamingPlaylistId = null)
        is SettingsIntent.EpgEnabledChanged ->
            state.copy(settings = state.settings.copy(epgEnabled = intent.enabled))
        is SettingsIntent.EpgUrlChanged -> state.copy(epgUrlField = intent.url)
        SettingsIntent.EpgRefresh -> state
        is SettingsIntent.TmdbEnabledChanged -> state.copy(tmdbEnabled = intent.enabled, tmdbConnectionResult = null)
        is SettingsIntent.TmdbApiKeyChanged -> state.copy(tmdbApiKey = intent.apiKey, tmdbConnectionResult = null)
        SettingsIntent.TmdbTestConnection -> state.copy(tmdbTesting = true, tmdbConnectionResult = null)
        SettingsIntent.TmdbClearCache -> state.copy(tmdbCacheMessage = null)
        SettingsIntent.KinopoiskClearCache -> state.copy(kinopoiskCacheMessage = null)
        SettingsIntent.DataResetStart -> state.copy(confirmDataReset = true)
        SettingsIntent.DataResetCancel -> state.copy(confirmDataReset = false)
        SettingsIntent.DataResetConfirm -> state.copy(confirmDataReset = false)
    }

    override fun handle(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Load -> load()
            is SettingsIntent.MediaPreferencesChanged,
            is SettingsIntent.KinopoiskEnabledChanged,
            is SettingsIntent.LanguageChanged,
            is SettingsIntent.ThemeChanged,
            is SettingsIntent.AccentColorChanged,
            is SettingsIntent.PanelTransparencyChanged,
            is SettingsIntent.StartScreenChanged,
            is SettingsIntent.ClockPositionChanged,
            is SettingsIntent.ClockWithoutBackgroundChanged,
            is SettingsIntent.ShowArchiveLabelChanged,
            is SettingsIntent.ProgrammeDescriptionPlacementChanged,
            is SettingsIntent.StartFullscreenChanged,
            is SettingsIntent.ShowChannelNumbersChanged,
            is SettingsIntent.OpenLastChannelChanged,
            is SettingsIntent.AutoFrameRateChanged,
            is SettingsIntent.VideoDecodeModeChanged,
            is SettingsIntent.NetworkCachingChanged,
            is SettingsIntent.ArchiveSeekStepChanged,
            is SettingsIntent.HomeCardLimitChanged,
            is SettingsIntent.ShowHomeNowPlayingChanged,
            is SettingsIntent.ShowHomeUpcomingChanged,
            is SettingsIntent.ShowHomeRecentChanged,
            is SettingsIntent.ShowHomeFavoritesChanged,
            is SettingsIntent.ShowHomeRecentVodChanged,
            is SettingsIntent.ShowHomeFavoriteVodChanged,
            is SettingsIntent.EpgInfoDurationChanged,
            is SettingsIntent.WindowResolutionChanged,
            -> save()
            is SettingsIntent.ArchiveLimitChanged -> save(notifyArchive = true)
            is SettingsIntent.LaunchOnWindowsStartupChanged -> {
                launch {
                    withContext(Dispatchers.Default) {
                        runCatching { autostart.setEnabled(intent.enabled) }
                            .onFailure { error -> Logger.e(TAG, "Failed to set Windows autostart", error) }
                    }
                }
                save()
            }
            is SettingsIntent.PlaylistUrlChanged,
            is SettingsIntent.PlaylistNameChanged,
            is SettingsIntent.PlaylistTypeChanged,
            -> Unit
            SettingsIntent.PlaylistAdd -> addPlaylist()
            is SettingsIntent.PlaylistRefresh -> refreshPlaylist(intent.id)
            is SettingsIntent.PlaylistRemove -> removePlaylist(intent.id)
            is SettingsIntent.PlaylistSetDefault -> setDefault(intent.id)
            is SettingsIntent.PlaylistRenameStart -> Unit
            is SettingsIntent.PlaylistRenameApply -> renamePlaylist(intent.id, intent.name)
            SettingsIntent.PlaylistRenameCancel -> Unit
            is SettingsIntent.EpgEnabledChanged -> {
                save()
                refreshEpgState()
            }
            is SettingsIntent.EpgUrlChanged -> Unit
            SettingsIntent.EpgRefresh -> refreshEpg(force = true)
            is SettingsIntent.TmdbEnabledChanged -> launch {
                runCatching { withContext(Dispatchers.Default) { tmdbSettings.setEnabled(intent.enabled) } }
                    .onFailure { error -> Logger.e(TAG, "Failed to save TMDB enabled", error) }
            }
            is SettingsIntent.TmdbApiKeyChanged -> launch {
                runCatching { withContext(Dispatchers.Default) { tmdbSettings.setApiKey(intent.apiKey) } }
                    .onFailure { error -> Logger.e(TAG, "Failed to save TMDB API key", error) }
            }
            SettingsIntent.TmdbTestConnection -> testTmdbConnection()
            SettingsIntent.TmdbClearCache -> clearMetadataCache(kinopoisk = false)
            SettingsIntent.KinopoiskClearCache -> clearMetadataCache(kinopoisk = true)
            SettingsIntent.DataResetStart -> Unit
            SettingsIntent.DataResetCancel -> Unit
            SettingsIntent.DataResetConfirm -> dataReset.requestResetAndRestart()
        }
    }

    private fun load() {
        launch {
            val tmdbEnabled = try { tmdbSettings.isEnabled() } catch (_: Throwable) { false }
            val tmdbApiKey = try { tmdbSettings.getApiKey() } catch (_: Throwable) { "" }
            runCatching {
                val appInfo = repository.getAppInfo()
                var settings = repository.load()
                // Reconcile with the ACTUAL OS state: the user may have
                // removed the Run-key entry by hand (e.g. Task Manager's
                // Startup tab) without going through this Settings screen —
                // in that case the stored preference would keep showing the
                // toggle as ON even though nothing launches at login anymore.
                val actualAutostart = runCatching {
                    withContext(Dispatchers.Default) { autostart.isEnabled() }
                }.getOrDefault(settings.launchOnWindowsStartup)
                if (actualAutostart != settings.launchOnWindowsStartup) {
                    settings = settings.copy(launchOnWindowsStartup = actualAutostart)
                    runCatching { repository.save(settings) }
                }
                setState {
                    it.copy(
                        isLoading = false,
                        appInfo = appInfo,
                        settings = settings,
                        epgUrlField = settings.epgUrl,
                        tmdbEnabled = tmdbEnabled,
                        tmdbApiKey = tmdbApiKey,
                    )
                }
            }.onFailure { error ->
                Logger.e(TAG, "Failed to load settings", error)
                setState { it.copy(isLoading = false, error = AppError.Database(error)) }
            }
        }
        launch {
            playlistRepository.observePlaylists().collect { playlists ->
                setState { state -> state.copy(playlists = playlists) }
            }
        }
        launch {
            readEpgStateSafely()
            epgRepository.observeState().collect { epgState ->
                setState { state ->
                    state.copy(
                        epgState = epgState,
                        epgUrlField = state.epgUrlField.ifBlank { epgState.overrideUrl },
                        epgRefreshing = epgState.isLoading,
                    )
                }
            }
        }
    }

    private fun save(notifyArchive: Boolean = false) {
        val settings = state.value.settings
        launch {
            runCatching {
                repository.save(settings)
                if (notifyArchive) ArchiveDepthChanges.changed()
            }.onFailure { error ->
                Logger.e(TAG, "Failed to save settings", error)
                setState { it.copy(error = AppError.Database(error)) }
            }
        }
    }

    private fun addPlaylist() {
        val url = state.value.playlistUrl.trim()
        if (url.isBlank()) {
            setState { it.copy(playlistError = AppError.InvalidPlaylist(uiText("Введите URL M3U-источника"))) }
            return
        }
        val name = state.value.playlistName.trim().takeIf { it.isNotEmpty() }
        launch {
            setState { it.copy(playlistLoading = true, playlistError = null) }
            playlistRepository.addPlaylist(
                url = url,
                type = state.value.playlistType,
                name = name,
            ).fold(
                onSuccess = { playlist ->
                    setState {
                        it.copy(
                            playlistLoading = false,
                            playlistUrl = "",
                            playlistName = "",
                            playlistError = null,
                        )
                    }
                    // Auto-load XMLTV declared in a LIVE playlist.
                    if (playlist.type == PlaylistType.LIVE) {
                        refreshEpg(force = false)
                    }
                },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to add playlist", null)
                    setState { it.copy(playlistLoading = false, playlistError = error) }
                },
            )
        }
    }

    private fun refreshPlaylist(id: String) {
        launch {
            playlistRepository.refreshPlaylist(id).fold(
                onSuccess = {
                    if (state.value.playlists.firstOrNull { it.id == id }?.type == PlaylistType.LIVE) {
                        refreshEpg(force = false)
                    }
                },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to refresh playlist", null)
                    setState { it.copy(playlistError = error) }
                },
            )
        }
    }

    private fun removePlaylist(id: String) {
        launch {
            playlistRepository.removePlaylist(id).fold(
                onSuccess = { Unit },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to remove playlist", null)
                    setState { it.copy(playlistError = error) }
                },
            )
        }
    }

    private fun setDefault(id: String?) {
        launch {
            playlistRepository.setDefaultPlaylist(id).fold(
                onSuccess = { Unit },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to set default playlist", null)
                    setState { it.copy(playlistError = error) }
                },
            )
        }
    }

    private fun renamePlaylist(id: String, name: String) {
        launch {
            playlistRepository.renamePlaylist(id, name).fold(
                onSuccess = { Unit },
                onFailure = { error ->
                    Logger.e(TAG, "Failed to rename playlist", null)
                    setState { it.copy(playlistError = error) }
                },
            )
        }
    }

    private fun testTmdbConnection() {
        launch {
            val result = runCatching { tmdbRepository.testConnection(state.value.tmdbApiKey) }
                .getOrElse { error ->
                    Logger.e(TAG, "TMDB test connection failed", error)
                    TmdbConnectionResult(ok = false, kind = TmdbConnectionResult.Kind.ERROR)
                }
            setState { it.copy(tmdbTesting = false, tmdbConnectionResult = result) }
        }
    }

    private fun clearMetadataCache(kinopoisk: Boolean) {
        launch {
            try {
                if (kinopoisk) kinopoiskLookup?.clearCache() else tmdbRepository.clearCache()
                programmeMetadata?.clearCache()
                setState { if (kinopoisk) it.copy(kinopoiskCacheMessage = "Кэш очищен")
                    else it.copy(tmdbCacheMessage = "Кэш очищен") }
            } catch (error: Throwable) {
                error.rethrowAndroidCancellation()
                Logger.e(TAG, "Failed to clear movie information cache", error)
                setState { if (kinopoisk) it.copy(kinopoiskCacheMessage = "Не удалось очистить кэш")
                    else it.copy(tmdbCacheMessage = "Не удалось очистить кэш") }
            }
        }
    }

    /** Persists the EPG URL field, then refreshes EPG (force or TTL-guarded). */
    private fun refreshEpg(force: Boolean) {
        launch {
            val url = state.value.epgUrlField.trim()
            val settings = state.value.settings.copy(epgUrl = url)
            setState { it.copy(settings = settings) }
            runCatching { repository.save(settings) }
                .onFailure { error ->
                    Logger.e(TAG, "Failed to save epg url", error)
                    setState { it.copy(error = AppError.Database(error)) }
                }
            epgRepository.refreshIfStale(force = force).fold(
                onSuccess = {},
                onFailure = { error ->
                    Logger.e(TAG, "Failed to refresh EPG", null)
                },
            )
        }
    }

    private fun refreshEpgState() {
        launch {
            readEpgStateSafely()
        }
    }

    private suspend fun readEpgStateSafely() {
        runCatching { epgRepository.refreshState() }
            .onSuccess { setState { it.copy(epgStateError = null) } }
            .onFailure { error ->
                Logger.e(TAG, "Failed to read EPG cache", error)
                setState { it.copy(epgStateError = uiText("Не удалось прочитать кэш EPG"), epgRefreshing = false) }
            }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
