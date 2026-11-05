package com.tvnext.app.feature.home.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.feature.home.data.DefaultHomeRepository
import com.tvnext.app.feature.home.data.HomeRepository
import com.tvnext.app.feature.home.domain.HomeQuickAccess
import com.tvnext.app.feature.home.ui.DefaultHomeComponent
import com.tvnext.app.feature.home.ui.HomeComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val homeKoinModule: Module = module {
    single<HomeRepository> {
        DefaultHomeRepository(
            channelsRepository = get(),
            historyRepository = get(),
            epgRepository = get(),
            mediaLibrary = get(),
        )
    }
    factory { (componentContext: ComponentContext, onQuickAccess: (HomeQuickAccess) -> Unit) ->
        DefaultHomeComponent(
            componentContext = componentContext,
            repository = get(),
            channelsRepository = get(),
            historyRepository = get(),
            settingsRepository = get(),
            playbackController = get(),
            epgRepository = get(),
            mediaLibrary = get(),
            onQuickAccess = onQuickAccess,
            mainContext = get(named("mainDispatcher")),
        ) as HomeComponent
    }
}
