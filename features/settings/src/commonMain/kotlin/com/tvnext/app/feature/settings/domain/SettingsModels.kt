package com.tvnext.app.feature.settings.domain

import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.ui.theme.AccentColor

data class AppSettings(
    val audioOutputMode: com.tvnext.app.core.player.AudioOutputMode = com.tvnext.app.core.player.AudioOutputMode.AUTO,
    val autoFrameRate: com.tvnext.app.core.player.AutoFrameRatePreferences = com.tvnext.app.core.player.AutoFrameRatePreferences(),
    val media: MediaPreferences = MediaPreferences(),
    val kinopoiskEnabled: Boolean = true,
    val language: com.tvnext.app.core.common.UiLanguage = com.tvnext.app.core.common.UiLanguage.RUSSIAN,
    val theme: ThemePreference = ThemePreference.DARK,
    val accentColor: AccentColor = AccentColor.GREEN,
    /** Percentage of panel translucency. Does not affect the video surface. */
    val panelTransparencyPercent: Int = 20,
    val startScreen: StartScreen = StartScreen.CHANNELS,
    val startFullscreen: Boolean = false,
    val showChannelNumbers: Boolean = true,
    /** Reopen the last watched channel when entering the Channels tab. */
    val openLastChannel: Boolean = true,
    val videoDecodeMode: VideoDecodeMode = VideoDecodeMode.AUTO,
    /** Global EPG (XMLTV) switch. */
    val epgEnabled: Boolean = true,
    /** User-provided XMLTV URL (overrides the playlist `url-tvg`). */
    val epgUrl: String = "",
    /** On-screen clock over the video (Windows). */
    val clockPosition: ClockPosition = ClockPosition.OFF,
    val clockWithoutBackground: Boolean = false,
    /** Persistent compact label while playing catch-up television. */
    val showArchiveLabel: Boolean = true,
    /** Android TV EPG description placement; the default is the full-width bottom strip. */
    val programmeDescriptionRight: Boolean = false,
    /**
     * Maximum archive depth in days. `0` = automatic (the provider maximum —
     * declared `catchup-days` or the measured depth). This is ONLY an app-side
     * limit: it never changes the provider's real archive.
     */
    val archiveLimitDays: Int = 0,
    /**
     * Network buffer for playback in ms (applies to every stream). Lower
     * values reduce live/VOD delay, higher values smooth weak HLS sources.
     */
    val networkCachingMs: Int = 8000,
    /** Archive seek step for remote LEFT/RIGHT/UP/DOWN controls, in milliseconds. */
    val archiveSeekStepMs: Long = 60_000L,
    /** Maximum cards per Home section («Сейчас идёт», «Ближайшие», …). */
    val homeCardLimit: Int = 7,
    /** Show the «Сейчас идёт» section on Home. */
    val showHomeNowPlaying: Boolean = true,
    /** Show the «Ближайшие передачи» section on Home. */
    val showHomeUpcoming: Boolean = true,
    /** Show the «Недавно просмотренные» section on Home. */
    val showHomeRecent: Boolean = true,
    /** Show the «Избранное» section on Home. */
    val showHomeFavorites: Boolean = true,
    /** Show the «Недавно просмотренные фильмы» section on Home. */
    val showHomeRecentVod: Boolean = true,
    /** Show the «Избранные фильмы и сериалы» section on Home. */
    val showHomeFavoriteVod: Boolean = true,
    /**
     * How long the full-screen «Телепрограмма» info block stays visible after
     * every channel switch (seconds). `null` = disabled, `0` = always visible,
     * `1..7` = seconds. Defaults to 3 s.
     */
    val epgInfoDurationSeconds: Int? = 3,
    /**
     * Desktop window resolution. AUTO picks the window size from the actual
     * monitor resolution; HD / FULL_HD / UHD_4K force a fixed window size
     * (used on high-DPI 4K displays where the default window is too small).
     * Android TV is always full-screen and ignores this setting.
     */
    val windowResolution: WindowResolution = WindowResolution.AUTO,
    /**
     * Launch TVNext automatically at Windows login (HKCU Run key). Desktop
     * only — hidden on Android, and [com.tvnext.app.core.common.platform.AppAutostart]
     * binds a no-op there. The stored preference and the actual OS state are
     * kept in sync by [SettingsRepository]/`SettingsComponent`, not merely
     * mirrored, so a user removing the Run key by hand (e.g. via Task
     * Manager's Startup tab) is reconciled back on next open of Settings.
     */
    val launchOnWindowsStartup: Boolean = false,
)

/** Desktop window size modes (Windows). */
enum class WindowResolution {
    AUTO,
    HD,
    FULL_HD,
    UHD_4K,
}

enum class StartScreen { HOME, CHANNELS, TV_GUIDE, MEDIA_LIBRARY }

enum class ThemePreference {
    DARK,
    LIGHT,
    SYSTEM,
}

data class AppInfo(
    val name: String = "TVNext",
    val version: String = com.tvnext.app.core.common.AppVersion.NAME,
)
