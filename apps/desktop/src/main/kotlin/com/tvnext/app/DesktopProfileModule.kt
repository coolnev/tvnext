package com.tvnext.app

import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.tmdb.*
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.media.data.VodProgressStore
import org.koin.dsl.module

internal val desktopProfileModule=module {
    single {DesktopStorageContext()}
    single {LocalRecoveryStore(get())}
    single {ProfilePersonalData(get(),get())}
    single {ParentalProtection(get())}
    single<ChannelsRepository>{AndroidProfiledChannelsRepository(get(),get(),get())}
    single<MediaLibraryRepository>{AndroidProfiledMediaRepository(get(),get(),get())}
    single<HistoryRepository>{AndroidProfiledHistoryRepository(get(),get())}
    single<SettingsRepository>{AndroidProfiledSettingsRepository(get(),get())}
    single {val store:LocalRecoveryStore=get();VodProgressStore(get(),{store.activeProfileId()},AndroidProfileVodPositionStore(get()))}
    single<KinopoiskKeyStore>{SqlDelightKinopoiskKeyStore(get(),DesktopDefaults.KINOPOISK_KEY)}
    single<EpgRepository>{DesktopEpgRepository(get(),get(),IptvChannelsRepository(get(),get()),get())}
    single {DesktopServices(get(),get(),get(),get(),get(),get(),get())}
}
