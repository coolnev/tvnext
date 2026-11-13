package com.tvnext.app.core.iptv.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.common.log.Logger
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Android adapter; the disk-first engine is also exercised against real SQLite on JVM. */
class AndroidEpgRepository private constructor(private val indexed: IndexedEpgRepository) : EpgRepository by indexed {
    constructor(client: HttpClient, playlists: PlaylistRepository, channels: ChannelsRepository,
        config: EpgConfigProvider, context: Context) : this(createIndex(client, playlists, channels, config, context)) {
        // M3U addition/import completion invalidates the small channel/source map,
        // not the persisted programme database. It never starts a playlist download.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playlists.observePlaylists().drop(1).collect {
                indexed.invalidateConfiguration()
                try {
                    indexed.refreshState()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.e("AndroidEPG", "Failed to read EPG state after playlist change", error)
                }
            }
        }
    }
    fun fallbackSettingsChanged() = indexed.invalidateConfiguration()
}

private fun createIndex(client: HttpClient, playlists: PlaylistRepository, channels: ChannelsRepository,
    config: EpgConfigProvider, context: Context): IndexedEpgRepository {
    val app = context.applicationContext
    val fallback = AndroidFallbackEpgSettings(app)
    // Share the configured transport, but keep XMLTV in its original wire
    // representation: HTTP gzip decoding must not stage gigabytes of raw XML.
    val epgClient = HttpClient(client.engine) { install(io.ktor.client.plugins.HttpTimeout) }
    val downloads = EpgSourceDownload(epgClient, File(app.noBackupFilesDir, "epg-downloads-v1"), keepSource=false, maxBytes=256L*1024*1024)
    lateinit var repository: IndexedEpgRepository
    repository = IndexedEpgRepository(
        storeProvider = {
            val path = File(app.noBackupFilesDir, "epg-index-v1.db").absolutePath
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(app).name(path)
                    .callback(AndroidSqliteDriver.Callback(TvNextDatabase.Schema)).build())
            helper.setWriteAheadLoggingEnabled(true)
            val driver = AndroidSqliteDriver(helper)
            // FrameworkSQLite owns the WAL connection pool and its connection settings.
            // In particular busy_timeout returns a row: SQLDelight's Android execute()
            // uses executeUpdateDelete(), which rejects it and prevents the index opening.
            runCatching { helper.writableDatabase.query("PRAGMA journal_size_limit=8388608").close() }
            IndexedEpgStore(TvNextDatabase(driver), driver, EpgStoragePolicy(usableBytes={app.noBackupFilesDir.usableSpace}))
        },
        playlists = playlists, channels = channels, config = config,
        fallbackUrls = { if (fallback.enabled()) fallback.urls() else emptyList() },
        stream = { url, consume ->
            downloads.consume(url, repository::downloadProgress, repository::downloadReady, allowNotModified=repository.canConfirmUnchanged(url)) { file ->
                val context = currentCoroutineContext()
                openEpgTextSource(file.absolutePath).use { source ->
                    consume(object : EpgTextSource {
                        override fun read(into: CharArray, offset: Int, length: Int): Int {
                            context.ensureActive()
                            return source.read(into, offset, length)
                        }
                        override fun close() = Unit
                    })
                }
            }
        },
        onSourceImported = downloads::acknowledge,
        onMaintenance = { populated ->
            downloads.cleanup()
            val prefs=app.getSharedPreferences("tvnext_cache_storage_v104",Context.MODE_PRIVATE)
            if (!prefs.getBoolean("old_images_removed",false)) {
                File(app.cacheDir,"logos").deleteRecursively()
                prefs.edit().putBoolean("old_images_removed",true).apply()
            }
            if (populated) {
                File(app.noBackupFilesDir,"epg_cache").deleteRecursively()
                File(app.cacheDir,"epg_cache").deleteRecursively()
            }
        },
        legacyDirectories = { url, ids ->
            platformEpgCacheDir()?.let { root ->
                (ids.map { File(root, IndexedEpgRepository.sourceId("$it\u0000$url")) } +
                    File(root, "external-" + IndexedEpgRepository.sourceId(url)))
                    .filter { it.isDirectory }.map { it.absolutePath }
            }.orEmpty()
        },
        awaitImport = { AndroidEpgPlaybackGate.awaitForeground() },
    )
    return repository
}
