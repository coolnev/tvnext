package com.tvnext.app.feature.tvg.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.feature.tvg.ui.DefaultTvgComponent
import com.tvnext.app.feature.tvg.ui.TvgComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val tvgKoinModule: Module = module {
    factory { (componentContext: ComponentContext, onOpenChannel: (Channel) -> Unit, onForceImmersive: () -> Unit) ->
        DefaultTvgComponent(
            componentContext = componentContext,
            channelsRepository = get(),
            playlistRepository = get(),
            epgRepository = get(),
            archiveRepository = get(),
            scheduledRepository = get(),
            playbackController = get(),
            onOpenChannel = onOpenChannel,
            onForceImmersive = onForceImmersive,
            mainContext = get(named("mainDispatcher")),
        ) as TvgComponent
    }
}
