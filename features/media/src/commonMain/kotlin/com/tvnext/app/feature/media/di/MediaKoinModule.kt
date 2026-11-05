package com.tvnext.app.feature.media.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.feature.media.data.VodProgressStore
import com.tvnext.app.feature.media.ui.DefaultMediaComponent
import com.tvnext.app.feature.media.ui.MediaComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val mediaKoinModule: Module = module {
    single { VodProgressStore(get()) }
    factory { (componentContext: ComponentContext) ->
        DefaultMediaComponent(
            componentContext = componentContext,
            library = get(),
            historyRepository = get(),
            progressStore = get(),
            tmdbRepository = get(),
            settingsRepository = get(),
            playbackController = get(),
            mainContext = get(named("mainDispatcher")),
        ) as MediaComponent
    }
}
