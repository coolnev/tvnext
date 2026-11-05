package com.tvnext.app.core.iptv.data

/**
 * Provides the user's EPG preferences to [EpgRepository].
 *
 * Implemented in `features:settings` (where `SettingsRepository` lives); the
 * core layer only depends on this interface, keeping feature isolation intact.
 */
interface EpgConfigProvider {
    /** Global EPG switch from Settings. When false, EPG is not refreshed. */
    suspend fun isEnabled(): Boolean

    /** User-provided XMLTV URL from Settings (override of the playlist `url-tvg`). */
    suspend fun userEpgUrl(): String?

    /** 0 = provider-declared archive depth, or existing EPG history when unknown. */
    suspend fun userArchiveLimitDays(): Int = 0
}
