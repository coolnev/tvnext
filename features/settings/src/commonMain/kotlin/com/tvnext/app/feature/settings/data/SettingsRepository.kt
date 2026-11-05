package com.tvnext.app.feature.settings.data

import com.tvnext.app.core.database.AppMetaRepository
import com.tvnext.app.core.database.App_meta
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.feature.settings.domain.AppInfo
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.domain.StartScreen
import com.tvnext.app.feature.settings.domain.ThemePreference
import com.tvnext.app.feature.settings.domain.WindowResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
    fun observe(): Flow<AppSettings>
    suspend fun getAppInfo(): AppInfo

    /** Id of the last watched channel (used by the `openLastChannel` option). */
    suspend fun getLastChannelId(): String?
    suspend fun setLastChannelId(channelId: String?)

    /**
     * Id of the last selected channel category (desktop: re-opening the
     * Channels tab restores the list to the category the user left it on).
     * Android TV does not use it.
     */
    suspend fun getLastCategoryId(): String?
    suspend fun setLastCategoryId(categoryId: String?)
}

class SqlDelightSettingsRepository(
    private val meta: AppMetaRepository,
) : SettingsRepository {

    private object Keys {
        const val THEME = "settings.theme"
        const val ACCENT_COLOR = "settings.accent_color"
        const val PANEL_TRANSPARENCY = "settings.panel_transparency"
        const val START_SCREEN = "settings.start_screen"
        const val START_FULLSCREEN = "settings.start_fullscreen"
        const val SHOW_CHANNEL_NUMBERS = "settings.show_channel_numbers"
        const val OPEN_LAST_CHANNEL = "settings.open_last_channel"
        const val VIDEO_DECODE_MODE = "settings.video_decode_mode"
        const val LAST_CHANNEL_ID = "settings.last_channel_id"
        const val LAST_CATEGORY_ID = "settings.last_category_id"
        const val EPG_ENABLED = "settings.epg_enabled"
        const val EPG_URL = "settings.epg_url"
        const val CLOCK_POSITION = "settings.clock_position"
        const val CLOCK_WITHOUT_BACKGROUND = "settings.clock_without_background"
        const val PROGRAMME_DESCRIPTION_RIGHT = "settings.programme_description_right"
        const val ARCHIVE_LIMIT_DAYS = "settings.archive_limit_days"
        const val NETWORK_CACHING_MS = "settings.network_caching_ms"
        const val ARCHIVE_SEEK_STEP_MS = "settings.archive_seek_step_ms"
        const val HOME_CARD_LIMIT = "settings.home_card_limit"
        const val SHOW_HOME_NOW_PLAYING = "settings.show_home_now_playing"
        const val SHOW_HOME_UPCOMING = "settings.show_home_upcoming"
        const val SHOW_HOME_RECENT = "settings.show_home_recent"
        const val SHOW_HOME_FAVORITES = "settings.show_home_favorites"
        const val SHOW_HOME_RECENT_VOD = "settings.show_home_recent_vod"
        const val SHOW_HOME_FAVORITE_VOD = "settings.show_home_favorite_vod"
        const val EPG_INFO_DURATION = "settings.epg_info_duration"
        const val WINDOW_RESOLUTION = "settings.window_resolution"
        const val LAUNCH_ON_WINDOWS_STARTUP = "settings.launch_on_windows_startup"
    }

    override suspend fun load(): AppSettings = observe().first()

    override suspend fun save(settings: AppSettings) {
        meta.setValue(Keys.THEME, settings.theme.name)
        meta.setValue(Keys.ACCENT_COLOR, settings.accentColor.name)
        meta.setValue(Keys.PANEL_TRANSPARENCY, settings.panelTransparencyPercent.coerceIn(0, 80).toString())
        meta.setValue(Keys.START_SCREEN, settings.startScreen.name)
        meta.setValue(Keys.START_FULLSCREEN, settings.startFullscreen.toString())
        meta.setValue(Keys.SHOW_CHANNEL_NUMBERS, settings.showChannelNumbers.toString())
        meta.setValue(Keys.OPEN_LAST_CHANNEL, settings.openLastChannel.toString())
        meta.setValue(Keys.VIDEO_DECODE_MODE, settings.videoDecodeMode.name)
        meta.setValue(Keys.EPG_ENABLED, settings.epgEnabled.toString())
        meta.setValue(Keys.EPG_URL, settings.epgUrl)
        meta.setValue(Keys.CLOCK_POSITION, settings.clockPosition.name)
        meta.setValue(Keys.CLOCK_WITHOUT_BACKGROUND, settings.clockWithoutBackground.toString())
        meta.setValue(Keys.PROGRAMME_DESCRIPTION_RIGHT, settings.programmeDescriptionRight.toString())
        meta.setValue(Keys.ARCHIVE_LIMIT_DAYS, settings.archiveLimitDays.toString())
        meta.setValue(Keys.NETWORK_CACHING_MS, settings.networkCachingMs.toString())
        meta.setValue(Keys.ARCHIVE_SEEK_STEP_MS, settings.archiveSeekStepMs.toString())
        meta.setValue(Keys.HOME_CARD_LIMIT, settings.homeCardLimit.toString())
        meta.setValue(Keys.SHOW_HOME_NOW_PLAYING, settings.showHomeNowPlaying.toString())
        meta.setValue(Keys.SHOW_HOME_UPCOMING, settings.showHomeUpcoming.toString())
        meta.setValue(Keys.SHOW_HOME_RECENT, settings.showHomeRecent.toString())
        meta.setValue(Keys.SHOW_HOME_FAVORITES, settings.showHomeFavorites.toString())
        meta.setValue(Keys.SHOW_HOME_RECENT_VOD, settings.showHomeRecentVod.toString())
        meta.setValue(Keys.SHOW_HOME_FAVORITE_VOD, settings.showHomeFavoriteVod.toString())
        meta.setValue(
            Keys.EPG_INFO_DURATION,
            settings.epgInfoDurationSeconds?.toString() ?: "off",
        )
        meta.setValue(Keys.WINDOW_RESOLUTION, settings.windowResolution.name)
        meta.setValue(Keys.LAUNCH_ON_WINDOWS_STARTUP, settings.launchOnWindowsStartup.toString())
    }

    override fun observe(): Flow<AppSettings> =
        meta.observeAll()
            .map(::fromRows)
            .distinctUntilChanged()

    override suspend fun getAppInfo(): AppInfo = AppInfo()

    override suspend fun getLastChannelId(): String? =
        meta.getValue(Keys.LAST_CHANNEL_ID)?.takeIf { it.isNotEmpty() }

    override suspend fun setLastChannelId(channelId: String?) {
        meta.setValue(Keys.LAST_CHANNEL_ID, channelId.orEmpty())
    }

    override suspend fun getLastCategoryId(): String? =
        meta.getValue(Keys.LAST_CATEGORY_ID)?.takeIf { it.isNotEmpty() }

    override suspend fun setLastCategoryId(categoryId: String?) {
        meta.setValue(Keys.LAST_CATEGORY_ID, categoryId.orEmpty())
    }

    private fun fromRows(rows: List<App_meta>): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = rows.theme() ?: defaults.theme,
            accentColor = rows.accentColor() ?: defaults.accentColor,
            panelTransparencyPercent = (rows.int(Keys.PANEL_TRANSPARENCY)
                ?: defaults.panelTransparencyPercent).coerceIn(0, 80),
            startScreen = rows.string(Keys.START_SCREEN)?.let { name ->
                runCatching { StartScreen.valueOf(name) }.getOrNull()
            } ?: defaults.startScreen,
            startFullscreen = rows.bool(Keys.START_FULLSCREEN) ?: defaults.startFullscreen,
            showChannelNumbers = rows.bool(Keys.SHOW_CHANNEL_NUMBERS) ?: defaults.showChannelNumbers,
            openLastChannel = rows.bool(Keys.OPEN_LAST_CHANNEL) ?: defaults.openLastChannel,
            videoDecodeMode = rows.videoDecodeMode() ?: defaults.videoDecodeMode,
            epgEnabled = rows.bool(Keys.EPG_ENABLED) ?: defaults.epgEnabled,
            epgUrl = rows.string(Keys.EPG_URL) ?: defaults.epgUrl,
            clockPosition = rows.clockPosition() ?: defaults.clockPosition,
            clockWithoutBackground = rows.bool(Keys.CLOCK_WITHOUT_BACKGROUND) ?: defaults.clockWithoutBackground,
            programmeDescriptionRight = rows.bool(Keys.PROGRAMME_DESCRIPTION_RIGHT) ?: defaults.programmeDescriptionRight,
            archiveLimitDays = rows.int(Keys.ARCHIVE_LIMIT_DAYS) ?: defaults.archiveLimitDays,
            networkCachingMs = rows.int(Keys.NETWORK_CACHING_MS) ?: defaults.networkCachingMs,
            archiveSeekStepMs = rows.long(Keys.ARCHIVE_SEEK_STEP_MS)
                ?.takeIf { it in setOf(60_000L, 5 * 60_000L, 10 * 60_000L, 30 * 60_000L) }
                ?: defaults.archiveSeekStepMs,
            homeCardLimit = rows.int(Keys.HOME_CARD_LIMIT) ?: defaults.homeCardLimit,
            showHomeNowPlaying = rows.bool(Keys.SHOW_HOME_NOW_PLAYING) ?: defaults.showHomeNowPlaying,
            showHomeUpcoming = rows.bool(Keys.SHOW_HOME_UPCOMING) ?: defaults.showHomeUpcoming,
            showHomeRecent = rows.bool(Keys.SHOW_HOME_RECENT) ?: defaults.showHomeRecent,
            showHomeFavorites = rows.bool(Keys.SHOW_HOME_FAVORITES) ?: defaults.showHomeFavorites,
            showHomeRecentVod = rows.bool(Keys.SHOW_HOME_RECENT_VOD) ?: defaults.showHomeRecentVod,
            showHomeFavoriteVod = rows.bool(Keys.SHOW_HOME_FAVORITE_VOD) ?: defaults.showHomeFavoriteVod,
            epgInfoDurationSeconds = rows.epgInfoDuration() ?: defaults.epgInfoDurationSeconds,
            windowResolution = rows.windowResolution() ?: defaults.windowResolution,
            launchOnWindowsStartup = rows.bool(Keys.LAUNCH_ON_WINDOWS_STARTUP) ?: defaults.launchOnWindowsStartup,
        )
    }

    private fun List<App_meta>.windowResolution(): WindowResolution? =
        string(Keys.WINDOW_RESOLUTION)?.let { raw ->
            runCatching { WindowResolution.valueOf(raw) }.getOrNull()
        }

    private fun List<App_meta>.string(key: String): String? =
        firstOrNull { it.key == key }?.value_

    private fun List<App_meta>.theme(): ThemePreference? =
        string(Keys.THEME)?.let { raw ->
            runCatching { ThemePreference.valueOf(raw) }.getOrNull()
        }

    private fun List<App_meta>.accentColor(): AccentColor? =
        string(Keys.ACCENT_COLOR)?.let { raw ->
            runCatching { AccentColor.valueOf(raw) }.getOrNull()
        }

    private fun List<App_meta>.clockPosition(): ClockPosition? =
        string(Keys.CLOCK_POSITION)?.let { raw ->
            runCatching { ClockPosition.valueOf(raw) }.getOrNull()
        }

    private fun List<App_meta>.videoDecodeMode(): VideoDecodeMode? =
        string(Keys.VIDEO_DECODE_MODE)?.let { raw ->
            runCatching { VideoDecodeMode.valueOf(raw) }.getOrNull()
        }

    private fun List<App_meta>.bool(key: String): Boolean? =
        string(key)?.toBooleanStrictOrNull()

    private fun List<App_meta>.int(key: String): Int? =
        string(key)?.toIntOrNull()

    private fun List<App_meta>.long(key: String): Long? =
        string(key)?.toLongOrNull()

    /** `off` → null (disabled), `0` → always visible, otherwise seconds (1..7). */
    private fun List<App_meta>.epgInfoDuration(): Int? =
        string(Keys.EPG_INFO_DURATION)?.let { raw ->
            when (raw) {
                "off" -> null
                else -> raw.toIntOrNull()?.coerceIn(0, 7)
            }
        }
}
