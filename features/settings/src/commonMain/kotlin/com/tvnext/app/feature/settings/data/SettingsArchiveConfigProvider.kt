package com.tvnext.app.feature.settings.data

import com.tvnext.app.core.iptv.archive.ArchiveConfigProvider
import com.tvnext.app.feature.settings.domain.AppSettings

/**
 * Bridges the user's archive-limit preference (Settings) into the core archive
 * layer. `features:settings` may implement core interfaces (feature → core),
 * while core never depends on a feature.
 */
class SettingsArchiveConfigProvider(
    private val repository: SettingsRepository,
) : ArchiveConfigProvider {

    override suspend fun userArchiveLimitDays(): Int =
        runCatching { repository.load() }.getOrDefault(AppSettings()).archiveLimitDays
}
