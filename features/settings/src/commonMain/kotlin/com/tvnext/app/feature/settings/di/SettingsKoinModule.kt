package com.tvnext.app.feature.settings.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.core.iptv.archive.ArchiveConfigProvider
import com.tvnext.app.core.iptv.data.EpgConfigProvider
import com.tvnext.app.feature.settings.data.SettingsArchiveConfigProvider
import com.tvnext.app.feature.settings.data.SettingsEpgConfigProvider
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.data.SqlDelightSettingsRepository
import com.tvnext.app.feature.settings.ui.DefaultSettingsComponent
import com.tvnext.app.feature.settings.ui.SettingsComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val settingsKoinModule: Module = module {
    single<SettingsRepository> { SqlDelightSettingsRepository(get()) }
    single<EpgConfigProvider> { SettingsEpgConfigProvider(get()) }
    single<ArchiveConfigProvider> { SettingsArchiveConfigProvider(get()) }
    factory { (componentContext: ComponentContext) ->
        DefaultSettingsComponent(
            componentContext = componentContext,
            repository = get(),
            playlistRepository = get(),
            epgRepository = get(),
            tmdbSettings = get(),
            tmdbRepository = get(),
            dataReset = get(),
            autostart = get(),
            mainContext = get(named("mainDispatcher")),
        ) as SettingsComponent
    }
}
