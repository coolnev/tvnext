package com.tvnext.app.core.app.tab

import com.arkivanov.decompose.ComponentContext
import com.tvnext.app.feature.channels.ui.ChannelsComponent
import com.tvnext.app.feature.home.ui.HomeComponent
import com.tvnext.app.feature.media.ui.MediaComponent
import com.tvnext.app.feature.search.ui.SearchComponent
import com.tvnext.app.feature.settings.ui.SettingsComponent
import com.tvnext.app.feature.tvg.ui.TvgComponent

sealed interface AppTabComponent {
    val componentContext: ComponentContext

    data class Home(
        val component: HomeComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent

    data class Channels(
        val component: ChannelsComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent

    data class Tvg(
        val component: TvgComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent

    data class Media(
        val component: MediaComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent

    data class Search(
        val component: SearchComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent

    data class Settings(
        val component: SettingsComponent,
        override val componentContext: ComponentContext,
    ) : AppTabComponent
}
