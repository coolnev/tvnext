package com.tvnext.app.feature.channels.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.feature.channels.ui.ChannelsComponent
import com.tvnext.app.feature.channels.ui.DefaultChannelsComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val channelsKoinModule: Module = module {
    factory { (componentContext: ComponentContext, onForceImmersive: () -> Unit) ->
        DefaultChannelsComponent(
            componentContext = componentContext,
            repository = get(),
            playbackController = get(),
            settingsRepository = get(),
            historyRepository = get(),
            epgRepository = get(),
            archiveRepository = get(),
            mainContext = get(named("mainDispatcher")),
            onForceImmersive = onForceImmersive,
        ) as ChannelsComponent
    }
}
