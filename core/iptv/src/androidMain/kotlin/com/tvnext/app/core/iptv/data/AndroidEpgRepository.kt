package com.tvnext.app.core.iptv.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.tvnext.app.core.database.TvNextDatabase
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
                indexed.refreshState()
            }
        }
    }
    fun fallbackSettingsChanged() = indexed.invalidateConfiguration()
}

private fun createIndex(client: HttpClient, playlists: PlaylistRepository, channels: ChannelsRepository,
    config: EpgConfigProvider, context: Context): IndexedEpgRepository {
    val app = context.applicationContext
    val fallback = AndroidFallbackEpgSettings(app)
    return IndexedEpgRepository(
        storeProvider = {
            val path = File(app.noBackupFilesDir, "epg-index-v1.db").absolutePath
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(app).name(path)
                    .callback(AndroidSqliteDriver.Callback(TvNextDatabase.Schema)).build())
            helper.setWriteAheadLoggingEnabled(true)
            val driver = AndroidSqliteDriver(helper)
            driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
            driver.execute(null, "PRAGMA busy_timeout=3000", 0)
            IndexedEpgStore(TvNextDatabase(driver), driver)
        },
        playlists = playlists, channels = channels, config = config,
        fallbackUrls = { if (fallback.enabled()) fallback.urls() else emptyList() },
        stream = { url, consume -> withEpgStream(client, url, consume) },
        legacyDirectories = { url, ids ->
            platformEpgCacheDir()?.let { root ->
                (ids.map { File(root, IndexedEpgRepository.sourceId("$it\u0000$url")) } +
                    File(root, "external-" + IndexedEpgRepository.sourceId(url)))
                    .filter { it.isDirectory }.map { it.absolutePath }
            }.orEmpty()
        },
        awaitImport = { AndroidEpgPlaybackGate.awaitForeground() },
    )
}
