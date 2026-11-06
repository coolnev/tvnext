package com.tvnext.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.tvnext.app.core.common.di.commonKoinModule
import com.tvnext.app.core.common.platform.AppDataReset
import com.tvnext.app.core.database.di.databaseAndroidKoinModule
import com.tvnext.app.core.database.di.databaseKoinModule
import com.tvnext.app.core.iptv.di.iptvKoinModule
import com.tvnext.app.core.licensing.di.licensingKoinModule
import com.tvnext.app.core.network.di.networkKoinModule
import com.tvnext.app.core.tmdb.di.tmdbKoinModule
import com.tvnext.app.core.player.di.playerAndroidKoinModule
import com.tvnext.app.core.player.di.playerKoinModule
import com.tvnext.app.core.ui.image.DefaultLogoImageLoader
import com.tvnext.app.core.ui.image.LogoFetcher
import com.tvnext.app.core.ui.image.LogoImageLoader
import com.tvnext.app.feature.channels.di.channelsKoinModule
import com.tvnext.app.feature.home.di.homeKoinModule
import com.tvnext.app.feature.media.di.mediaKoinModule
import com.tvnext.app.feature.search.di.searchKoinModule
import com.tvnext.app.feature.settings.di.settingsKoinModule
import com.tvnext.app.feature.tvg.di.tvgKoinModule
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.tmdb.TmdbSettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import java.io.File
import java.util.Date
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

class TVNextApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private val defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()

    private val applicationKoinModule: Module = module {
        single<Context> { applicationContext }
        single<CoroutineContext>(named("mainDispatcher")) { Dispatchers.Main }
        single<AppDataReset> { AndroidAppDataReset(applicationContext) }
        // No OS-level autostart concept on Android TV; the setting itself is
        // hidden from the Android UI (see SettingsScreen isAndroidPlatform()
        // check), this binding only exists so Koin can satisfy the shared
        // SettingsComponent constructor.
        single<com.tvnext.app.core.common.platform.AppAutostart> {
            com.tvnext.app.core.common.platform.NoOpAppAutostart()
        }
    }

    /** Channel-logo loader: memory + disk cache, Ktor fetch. Errors are local. */
    private val logoKoinModule: Module = module {
        // Immediately available on clean install/update, before asynchronous startup work.
        single<com.tvnext.app.core.tmdb.KinopoiskKeyStore> {
            com.tvnext.app.core.tmdb.SqlDelightKinopoiskKeyStore(get(), BuildConfig.DEFAULT_KINOPOISK_API_KEY)
        }
        // Android source isolation; desktop retains its existing repository binding.
        single<com.tvnext.app.core.iptv.data.EpgRepository> {
            // EPG must match provider metadata, including channels hidden/renamed
            // in a personal profile; the profile view is only for display.
            com.tvnext.app.core.iptv.data.AndroidEpgRepository(
                get(), get(),
                com.tvnext.app.core.iptv.data.IptvChannelsRepository(get(), get()),
                get(), applicationContext,
            )
        }
        single<LogoImageLoader> {
            val client: HttpClient = get()
            DefaultLogoImageLoader(
                fetcher = LogoFetcher { url -> fetchLogo(client, url) },
                cacheDir = File(applicationContext.cacheDir, "logos").absolutePath,
            )
        }
    }

    /** Android-only personal overlays; the desktop DI graph is unchanged. */
    private val profileKoinModule: Module = module {
        single { LocalRecoveryStore(applicationContext) }
        single { ProfilePersonalData(applicationContext, get()) }
        // Separate VOD resume positions per profile; the main profile keeps legacy data.
        single {
            val profiles: LocalRecoveryStore = get()
            com.tvnext.app.feature.media.data.VodProgressStore(
                get(), { profiles.activeProfileId() }, AndroidProfileVodPositionStore(applicationContext))
        }
        single<com.tvnext.app.core.iptv.data.ChannelsRepository> {
            AndroidProfiledChannelsRepository(get(), get(), get())
        }
        single<com.tvnext.app.core.iptv.data.MediaLibraryRepository> {
            AndroidProfiledMediaRepository(get(), get(), get())
        }
        single<com.tvnext.app.core.iptv.data.HistoryRepository> {
            AndroidProfiledHistoryRepository(get(), get())
        }
        single<com.tvnext.app.feature.settings.data.SettingsRepository> {
            AndroidProfiledSettingsRepository(get(), get())
        }
    }

    override fun onCreate() {
        super.onCreate()

        // The EPG downloader needs a writable directory for its temp file
        // (java.io.tmpdir is not reliably set on Android) — must run before
        // any EPG refresh can happen. See EpgIo.android.kt for details.
        com.tvnext.app.core.iptv.data.installEpgAndroidContext(applicationContext)
        // A force-stop/crash can leave the decompressed XMLTV temp file in
        // cacheDir. It can be hundreds of MB; remove stale copies before the
        // database is opened so EPG can never silently accumulate on disk.
        com.tvnext.app.core.iptv.data.cleanupOrphanedEpgTempFiles(applicationContext)

        // Wipe the database/cache if a previous «Сбросить все данные» left a
        // pending marker. Must run BEFORE Koin opens the database.
        AndroidAppDataReset(applicationContext).handlePendingReset()

        // Boot marker + diagnostics: detects when the OS killed the previous
        // process (LMK / silent native crash) so the host can skip the heavy
        // automatic EPG import and break the "closes after ~15-25 s" loop.
        AndroidBootDiagnostics.onAppCreate(applicationContext)

        // Capture any uncaught crash to logcat AND to filesDir/crash.log so a
        // startup crash on a TV without adb access can still be diagnosed.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = Log.getStackTraceString(throwable)
            runCatching {
                File(filesDir, "crash.log").appendText(
                    "=== ${Date()} [${thread.name}] ===\n$trace\n\n",
                )
            }
            Log.e("TVNext", "Uncaught exception", throwable)
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }

        startKoin {
            modules(
                commonKoinModule,
                com.tvnext.app.core.app.di.appKoinModule,
                applicationKoinModule,
                databaseKoinModule,
                databaseAndroidKoinModule,
                networkKoinModule,
                tmdbKoinModule,
                iptvKoinModule,
                playerKoinModule,
                playerAndroidKoinModule,
                licensingKoinModule,
                homeKoinModule,
                channelsKoinModule,
                settingsKoinModule,
                mediaKoinModule,
                tvgKoinModule,
                searchKoinModule,
                logoKoinModule,
                profileKoinModule,
            )
        }

        // No automatic EPG diagnostic e-mails: removed in the 0.5 release.
        applicationScope.launch {
            GlobalContext.get().get<com.tvnext.app.feature.settings.data.SettingsRepository>().observe()
                .map { it.epgEnabled to it.epgUrl }.distinctUntilChanged().collect {
                    (GlobalContext.get().get<com.tvnext.app.core.iptv.data.EpgRepository>()
                        as? com.tvnext.app.core.iptv.data.AndroidEpgRepository)?.fallbackSettingsChanged()
                }
        }
        LocalRecoveryStore(this).recoverInterrupted()
        seedDemoData()
    }

    /**
     * First-run convenience: when the library has NO playlists at all, the
     * user's sources and TMDB key are pre-populated so a fresh install works
     * immediately. Anything already configured is left untouched.
     */
    private fun seedDemoData() {
        com.tvnext.app.core.iptv.data.AndroidCatalogBootstrap.pending.value = true
        applicationScope.launch {
            try { runCatching {
                val playlistRepository = GlobalContext.get().get<PlaylistRepository>()
                // Seed only a genuinely empty installation. An ordinary startup
                // must not download/re-add sources the user already configured or removed.
                val existingUrls = playlistRepository.getPlaylists()
                    .map { it.url.trimEnd('/') }
                    .toSet()
                // LIVE: vivamax (крупный EPG для проверки телепрограммы).
                if (existingUrls.isEmpty()) {
                    playlistRepository.addPlaylist(
                        url = LIVE_PLAYLIST_URL,
                        type = PlaylistType.LIVE,
                        name = "vivamax",
                    )
                }
                // Медиатека (VOD).
                if (existingUrls.isEmpty()) {
                    playlistRepository.addPlaylist(
                        url = VOD_PLAYLIST_URL,
                        type = PlaylistType.VOD,
                        name = "viplimeVOD",
                    )
                }
                val tmdbSettings = GlobalContext.get().get<TmdbSettingsStore>()
                if (tmdbSettings.getApiKey().isBlank()) {
                    tmdbSettings.setEnabled(true)
                    tmdbSettings.setApiKey("698bff9ae128f80d8e6bff80ae075549")
                }
            } } finally {
                com.tvnext.app.core.iptv.data.AndroidCatalogBootstrap.pending.value = false
            }
        }
    }
}

/** Debug-seed: LIVE playlist (vivamax, крупный EPG для проверки). */
private const val LIVE_PLAYLIST_URL = "https://bittv.ltd/playlists/b0c7hew623:uw8qk62ugl/playlist.m3u"

/** Debug-seed: VOD (медиатека). */
private const val VOD_PLAYLIST_URL = "https://m.cdntv.online/f/9y6xwink2s/playlist.m3u8"

private suspend fun fetchLogo(client: HttpClient, url: String): ByteArray? =
    com.tvnext.app.core.network.fetchArtworkBytes(client, url)
