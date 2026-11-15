package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.common.platform.DesktopAppPaths
import com.tvnext.app.core.database.TvNextDatabase
import io.ktor.client.HttpClient
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Same persisted index, bounded download and viewport priorities as Android. */
class DesktopEpgRepository private constructor(private val indexed:IndexedEpgRepository):EpgRepository by indexed {
    fun configurationChanged()=indexed.invalidateConfiguration()
    constructor(client:HttpClient,playlists:PlaylistRepository,channels:ChannelsRepository,config:EpgConfigProvider):
        this(createDesktopIndex(client,playlists,channels,config)) {
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            playlists.observePlaylists().drop(1).collect {
                indexed.invalidateConfiguration()
                try {indexed.refreshState()} catch(cancelled:CancellationException){throw cancelled}
            }
        }
    }
}

private fun createDesktopIndex(client:HttpClient,playlists:PlaylistRepository,channels:ChannelsRepository,
    config:EpgConfigProvider):IndexedEpgRepository {
    val root=DesktopAppPaths.dataDirectory.apply {mkdirs()}
    val transport=HttpClient(client.engine) {install(io.ktor.client.plugins.HttpTimeout)}
    val downloads=EpgSourceDownload(transport,File(root,"epg-downloads-v1"),keepSource=false,maxBytes=256L*1024*1024)
    lateinit var repository:IndexedEpgRepository
    repository=IndexedEpgRepository(
        storeProvider={
            val file=File(root,"epg-index-v1.db")
            val fresh=!file.exists() || file.length()==0L
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use {db ->
                db.createStatement().use {s ->
                    if(fresh)s.execute("PRAGMA auto_vacuum=INCREMENTAL")
                    s.execute("PRAGMA journal_mode=WAL")
                    s.execute("PRAGMA journal_size_limit=8388608")
                }
            }
            val driver=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}",java.util.Properties().apply {
                setProperty("busy_timeout","5000")
            })
            if(fresh)TvNextDatabase.Schema.create(driver)
            IndexedEpgStore(TvNextDatabase(driver),driver,EpgStoragePolicy(usableBytes={root.usableSpace}))
        },playlists=playlists,channels=channels,config=config,
        stream={url,consume ->
            downloads.consume(url,repository::downloadProgress,repository::downloadReady,
                allowNotModified=repository.canConfirmUnchanged(url)) {file ->
                val context=currentCoroutineContext()
                openEpgTextSource(file.absolutePath).use {source ->
                    consume(object:EpgTextSource {
                        override fun read(into:CharArray,offset:Int,length:Int):Int {
                            context.ensureActive();return source.read(into,offset,length)
                        }
                        override fun close()=Unit
                    })
                }
            }
        },onSourceImported=downloads::acknowledge,
        onMaintenance={populated ->downloads.cleanup();if(populated)File(root,"cache/epg").deleteRecursively()},
        legacyDirectories={url,ids ->File(root,"cache/epg").let {old ->
            (ids.map {File(old,IndexedEpgRepository.sourceId("$it\u0000$url"))}+
                File(old,"external-"+IndexedEpgRepository.sourceId(url))).filter {it.isDirectory}.map {it.absolutePath}
        }},
    )
    return repository
}
