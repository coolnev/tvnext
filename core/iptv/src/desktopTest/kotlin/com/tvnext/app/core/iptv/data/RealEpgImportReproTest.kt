package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.database.registerRawSqlDriver
import com.tvnext.app.core.iptv.xmltv.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test

/**
 * Reproduces the «Ошибка базы данных при сохранении EPG» failure against the
 * REAL vivamax EPG (downloaded to C:\Temp\kilo\epg\epg.xml) without network.
 *
 * Set -DepgXmlPath=<path> to point at a different file, or skip when the file
 * is absent.
 */
class RealEpgImportReproTest {

    @Test
    fun importRealVivamaxEpg() = runBlocking {
        // Opt-in: the real EPG is ~728 MB XML / ~1.5M programmes and takes a
        // minute to import, so it never runs as part of the normal suite.
        //   .\gradlew :core:iptv:desktopTest \
        //     --tests "*RealEpgImportReproTest" -DepgXmlPath=C:\Temp\kilo\epg\epg.xml
        val path = System.getProperty("epgXmlPath")
        if (path == null) {
            println("SKIP: -DepgXmlPath not set (opt-in real-EPG import test)")
            return@runBlocking
        }
        val file = File(path)
        if (!file.isFile) {
            println("SKIP: EPG file not found at $path")
            return@runBlocking
        }
        val dbFile = File(System.getProperty("epgDbPath") ?: "C:\\Temp\\kilo\\epg\\repro.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        // -wal / -shm too.
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            TvNextDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA journal_mode=WAL;", 0) { }
            driver.execute(null, "PRAGMA busy_timeout=60000;", 0) { }
            driver.execute(null, "PRAGMA synchronous=NORMAL;", 0) { }
        } catch (t: Throwable) {
            println("DB setup failed: $t")
            t.printStackTrace()
            return@runBlocking
        }

        val db = TvNextDatabase(driver)
        // Let the raw-index executor see the live driver (desktop production
        // wires this in DatabaseFactory.createDriver).
        registerRawSqlDriver(driver)
        // Desktop-style store: drops the programme indexes during the import
        // and rebuilds them at commit (the production desktop behaviour).
        val store = SqlDelightEpgStore(db, dropIndexesDuringImport = true)
        val parser = XmltvParser()

        val playlistId = "repro-playlist"
        var channels = 0
        var programmes = 0L
        // Diagnostic: measure the raw XML parse cost WITHOUT any database
        // writes (`-DepgParseOnly=true`).
        val parseOnly = System.getProperty("epgParseOnly") == "true"
        val importStartedAt = System.currentTimeMillis()
        var insertTimeMs = 0L

        try {
            withContext(Dispatchers.Default) {
                val batch = store.beginReplace(playlistId)
                val channelBatch = ArrayList<com.tvnext.app.core.iptv.xmltv.EpgChannel>()
                val programmeBatch = ArrayList<com.tvnext.app.core.iptv.xmltv.EpgProgramme>()
                val memText = readEpgFileToString(path, 256L * 1024 * 1024)
                println("BENCH: memText=${memText != null} len=${memText?.length}")
                fun openSource() = if (memText != null) StringEpgTextSource(memText) else openEpgTextSource(path)
                val source = openSource()
                try {
                    parser.parseStreaming(
                        source = source,
                        onChannel = { channel ->
                            channels++
                            channelBatch.add(channel)
                            if (!parseOnly && channelBatch.size >= 500) {
                                val t0 = System.nanoTime()
                                store.insertChannels(playlistId, batch, channelBatch)
                                insertTimeMs += (System.nanoTime() - t0) / 1_000_000
                                channelBatch.clear()
                            }
                        },
                        onProgramme = { programme ->
                            programmes++
                            programmeBatch.add(programme)
                            if (!parseOnly && programmeBatch.size >= 10_000) {
                                val t0 = System.nanoTime()
                                store.insertProgrammes(playlistId, batch, programmeBatch)
                                insertTimeMs += (System.nanoTime() - t0) / 1_000_000
                                programmeBatch.clear()
                                kotlinx.coroutines.yield()
                            }
                        },
                    )
                } finally {
                    source.close()
                }
                if (!parseOnly) {
                    val t0 = System.nanoTime()
                    if (channelBatch.isNotEmpty()) store.insertChannels(playlistId, batch, channelBatch)
                    if (programmeBatch.isNotEmpty()) store.insertProgrammes(playlistId, batch, programmeBatch)
                    insertTimeMs += (System.nanoTime() - t0) / 1_000_000
                    store.commitReplace(playlistId, batch)
                    store.prune(playlistId, 0L)
                    store.saveCacheMeta(
                        playlistId,
                        EpgCacheMeta(
                            url = "file://$path",
                            fetchedAt = System.currentTimeMillis(),
                            channelCount = channels,
                            programmeCount = programmes.toInt().coerceAtMost(Int.MAX_VALUE),
                        ),
                    )
                }
            }
            val seconds = (System.currentTimeMillis() - importStartedAt) / 1000.0
            println(
                "IMPORT OK: channels=$channels programmes=$programmes " +
                    "time=${"%.1f".format(seconds)}s insert=${insertTimeMs}ms dbSize=${dbFile.length()}" +
                    (if (parseOnly) " [parse-only, no DB writes]" else ""),
            )
        } catch (t: Throwable) {
            println("IMPORT FAILED: $t")
            t.printStackTrace()
            println("channels=$channels programmes=$programmes dbSize=${dbFile.length()}")
        } finally {
            driver.close()
        }
    }
}
