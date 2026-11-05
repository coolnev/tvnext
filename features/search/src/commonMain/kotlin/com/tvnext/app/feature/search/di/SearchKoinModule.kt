package com.tvnext.app.feature.search.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.feature.search.ui.DefaultSearchComponent
import com.tvnext.app.feature.search.ui.SearchComponent
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val searchKoinModule: Module = module {
    factory {
        (
            componentContext: ComponentContext,
            onOpenChannel: (Channel) -> Unit,
            onOpenMediaItem: (MediaItem) -> Unit,
            onForceImmersive: () -> Unit,
            ) ->
        DefaultSearchComponent(
            componentContext = componentContext,
            channelsRepository = get(),
            mediaLibrary = get(),
            epgRepository = get(),
            playlistRepository = get(),
            archiveRepository = get(),
            scheduledRepository = get(),
            historyRepository = get(),
            playbackController = get(),
            onOpenChannel = onOpenChannel,
            onOpenMediaItem = onOpenMediaItem,
            onForceImmersive = onForceImmersive,
            mainContext = get(named("mainDispatcher")),
        ) as SearchComponent
    }
}
