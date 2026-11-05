package com.tvnext.app.core.iptv.di

import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.iptv.archive.ArchiveCapabilityStore
import com.tvnext.app.core.iptv.archive.ArchiveConfigProvider
import com.tvnext.app.core.iptv.archive.ArchiveDepthResolver
import com.tvnext.app.core.iptv.archive.ArchiveProbe
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.archive.CatchupUrlBuilder
import com.tvnext.app.core.iptv.archive.IptvArchiveRepository
import com.tvnext.app.core.iptv.archive.KtorArchiveProbe
import com.tvnext.app.core.iptv.archive.SqlDelightArchiveCapabilityStore
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.ChannelIdMigration
import com.tvnext.app.core.iptv.data.ChannelStore
import com.tvnext.app.core.iptv.data.DefaultHistoryRepository
import com.tvnext.app.core.iptv.data.EpgConfigProvider
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.EpgStore
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.HistoryStore
import com.tvnext.app.core.iptv.data.IptvChannelsRepository
import com.tvnext.app.core.iptv.data.IptvMediaLibraryRepository
import com.tvnext.app.core.iptv.data.IptvPlaylistRepository
import com.tvnext.app.core.iptv.data.KtorM3uSource
import com.tvnext.app.core.iptv.data.LiteEpgRepository
import com.tvnext.app.core.iptv.data.M3uSource
import com.tvnext.app.core.iptv.data.MediaItemStore
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.data.ScheduledReminderService
import com.tvnext.app.core.iptv.data.ScheduledRepository
import com.tvnext.app.core.iptv.data.SqlDelightChannelStore
import com.tvnext.app.core.iptv.data.SqlDelightEpgStore
import com.tvnext.app.core.iptv.data.SqlDelightHistoryStore
import com.tvnext.app.core.iptv.data.SqlDelightMediaItemStore
import com.tvnext.app.core.iptv.data.SqlDelightScheduledRepository
import com.tvnext.app.core.iptv.m3u.M3uParser
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

val iptvKoinModule: Module = module {
    single { M3uParser() }
    single<M3uSource> { KtorM3uSource(get<HttpClient>()) }
    single<ChannelStore> { SqlDelightChannelStore(get()) }
    // Retained only for playlist deletion cleanup (IptvPlaylistRepository);
    // the EPG engine itself no longer writes programmes into SQLite.
    single<EpgStore> {
        SqlDelightEpgStore(
            database = get(),
            dropIndexesDuringImport = !isAndroidPlatform(),
        )
    }
    single<HistoryStore> { SqlDelightHistoryStore(get()) }
    single<MediaItemStore> { SqlDelightMediaItemStore(get()) }
    single<ArchiveCapabilityStore> { SqlDelightArchiveCapabilityStore(get()) }
    single<ScheduledRepository> { SqlDelightScheduledRepository(get()) }
    single {
        ScheduledReminderService(
            repository = get(),
            mainContext = get(org.koin.core.qualifier.named("mainDispatcher")),
        )
    }
    single<PlaylistRepository> {
        IptvPlaylistRepository(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    single { ChannelIdMigration(get()) }
    single<ChannelsRepository> { IptvChannelsRepository(get(), get()) }
    single<MediaLibraryRepository> { IptvMediaLibraryRepository(get(), get()) }
    single<HistoryRepository> { DefaultHistoryRepository(get()) }
    // Lightweight EPG engine (LiteTV port): in-memory schedule + compact disk
    // cache + streaming parser. Bounded memory, instant cache load on launch —
    // no SQLite import that could silently kill a low-end Android TV box.
    single<EpgRepository> {
        LiteEpgRepository(
            client = get<HttpClient>(),
            playlistRepository = get(),
            channelsRepository = get(),
            configProvider = get<EpgConfigProvider>(),
        )
    }

    // ---- Archive / catch-up ------------------------------------------------
    single { CatchupUrlBuilder() }
    single<ArchiveProbe> { KtorArchiveProbe(get<HttpClient>()) }
    single {
        ArchiveDepthResolver(
            probe = get<ArchiveProbe>(),
            builder = get(),
            clock = { com.tvnext.app.core.common.util.currentTimeMillis() },
        )
    }
    single<ArchiveRepository> {
        IptvArchiveRepository(
            store = get(),
            builder = get(),
            resolver = get(),
            probe = get<ArchiveProbe>(),
            configProvider = get<ArchiveConfigProvider>(),
            clock = { com.tvnext.app.core.common.util.currentTimeMillis() },
        )
    }
}
