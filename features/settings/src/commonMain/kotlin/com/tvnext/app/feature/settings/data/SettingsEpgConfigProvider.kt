package com.tvnext.app.feature.settings.data

import com.tvnext.app.core.iptv.data.EpgConfigProvider
import com.tvnext.app.feature.settings.domain.AppSettings

/**
 * Bridges the user's EPG preferences (Settings) into the core EPG layer.
 * `features:settings` may implement core interfaces (feature → core), while
 * core never depends on a feature.
 */
class SettingsEpgConfigProvider(
    private val repository: SettingsRepository,
) : EpgConfigProvider {

    override suspend fun isEnabled(): Boolean =
        loadSettings().epgEnabled

    override suspend fun userEpgUrl(): String? =
        loadSettings().epgUrl.trim().takeIf { it.isNotEmpty() }

    override suspend fun userArchiveLimitDays(): Int =
        loadSettings().archiveLimitDays

    private suspend fun loadSettings(): AppSettings =
        runCatching { repository.load() }.getOrDefault(AppSettings())
}
