package com.tvnext.app.core.player.di

import com.tvnext.app.core.player.AndroidPlaybackController
import com.tvnext.app.core.player.PlaybackController
import org.koin.core.module.Module
import org.koin.dsl.module

val playerAndroidKoinModule: Module = module {
    single<PlaybackController> { AndroidPlaybackController(context = get(), channelsRepository = get(), archiveRepository = get()) }
}
