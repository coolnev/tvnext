package com.tvnext.app.core.player.di

import com.tvnext.app.core.player.MpvPlaybackController
import com.tvnext.app.core.player.PlaybackController
import org.koin.core.module.Module
import org.koin.dsl.module

val playerDesktopKoinModule: Module = module {
    // libmpv backend (LGPL — commercial-friendly). Video renders into Compose
    // frames via the libmpv render API (no HWND/swapchain), so window resizes
    // and tab switches never freeze the UI; hwdec=auto keeps GPU decoding.
    single<PlaybackController> { MpvPlaybackController() }
}
