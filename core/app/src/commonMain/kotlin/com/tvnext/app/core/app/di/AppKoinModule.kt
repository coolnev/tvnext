package com.tvnext.app.core.app.di

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.core.app.TabComponentFactory
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.feature.channels.ui.ChannelsComponent
import com.tvnext.app.feature.home.domain.HomeQuickAccess
import com.tvnext.app.feature.home.ui.HomeComponent
import com.tvnext.app.feature.media.ui.MediaComponent
import com.tvnext.app.feature.search.ui.SearchComponent
import com.tvnext.app.feature.settings.ui.SettingsComponent
import com.tvnext.app.feature.tvg.ui.TvgComponent
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val appKoinModule: Module = module {
    single<TabComponentFactory> { KoinTabComponentFactory() }
}

class KoinTabComponentFactory : TabComponentFactory {
    private val koin = GlobalContext.get()

    override fun createHome(
        componentContext: ComponentContext,
        onQuickAccess: (HomeQuickAccess) -> Unit,
    ): HomeComponent =
        koin.get(parameters = { parametersOf(componentContext, onQuickAccess) })

    override fun createChannels(
        componentContext: ComponentContext,
        onForceImmersive: () -> Unit,
    ): ChannelsComponent =
        koin.get(parameters = { parametersOf(componentContext, onForceImmersive) })

    override fun createTvg(
        componentContext: ComponentContext,
        onOpenChannel: (Channel) -> Unit,
        onForceImmersive: () -> Unit,
    ): TvgComponent =
        koin.get(parameters = { parametersOf(componentContext, onOpenChannel, onForceImmersive) })

    override fun createMedia(componentContext: ComponentContext): MediaComponent =
        koin.get(parameters = { parametersOf(componentContext) })

    override fun createSearch(
        componentContext: ComponentContext,
        onOpenChannel: (Channel) -> Unit,
        onOpenMediaItem: (MediaItem) -> Unit,
        onForceImmersive: () -> Unit,
    ): SearchComponent =
        koin.get(parameters = { parametersOf(componentContext, onOpenChannel, onOpenMediaItem, onForceImmersive) })

    override fun createSettings(componentContext: ComponentContext): SettingsComponent =
        koin.get(parameters = { parametersOf(componentContext) })
}
