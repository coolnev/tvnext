package com.tvnext.app.core.player.di

import com.tvnext.app.core.player.MpvPlaybackController
import com.tvnext.app.core.player.PlaybackController
import org.koin.core.module.Module
import org.koin.dsl.module

val playerDesktopKoinModule: Module = module {
    // One libmpv instance publishes Compose frames across shared video scenes.
    single<PlaybackController> { MpvPlaybackController(channelsRepository=get(),archiveRepository=get()) }
}
