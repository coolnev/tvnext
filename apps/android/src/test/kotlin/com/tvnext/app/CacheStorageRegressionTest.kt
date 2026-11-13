package com.tvnext.app

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.database.CacheStorageMaintenance
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheStorageRegressionTest {
    private fun scalar(d: SqlDriver,sql:String)=d.executeQuery(null,sql,{c->c.next();QueryResult.Value(c.getLong(0)?:0L)},0).value
    private fun database(name: String, body: (SqlDriver,TvNextDatabase,File)->Unit) {
        val context=RuntimeEnvironment.getApplication()
        val file=context.getDatabasePath(name)
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(AndroidSqliteDriver.Callback(TvNextDatabase.Schema)).build())
        helper.setWriteAheadLoggingEnabled(true)
        val driver=AndroidSqliteDriver(helper)
        try {body(driver,TvNextDatabase(driver),file)} finally {driver.close()}
    }
    @Test fun indexMaintenanceActuallyShrinksLargeSqliteAndPreservesCurrentGuideAndArchiveTitles()=database("guide.db") {d,db,file->
        val now=System.currentTimeMillis();val source="a".repeat(64)
        val old=IndexedEpgStore(db,d)
        val generation=old.begin(source)
        val large="Описание ".repeat(1800)
        val past=(1..1300).map {i->"p|c" to EpgProgramme("c",now-3*86_400_000L-i*60_000L,now-3*86_400_000L-(i-1)*60_000L,"Архив $i",large)}
        past.chunked(128).forEach {old.write(source,generation,it,true)}
        old.write(source,generation,listOf("p|c" to EpgProgramme("c",now-60_000,now+3_600_000,"Сейчас","Подробное описание текущей передачи")),true)
        old.complete(source,generation,"signature",now,now)
        // Simulate an interrupted later import: it must not survive for days.
        val abandoned=old.begin(source)
        past.take(600).chunked(100).forEach {old.write(source,abandoned,it,true)}
        scalar(d,"PRAGMA wal_checkpoint(TRUNCATE)")
        val before=file.length();assertTrue(before>16L*1024*1024)
        val bounded=IndexedEpgStore(db,d,EpgStoragePolicy(2L*1024*1024) {now})
        bounded.maintainStorage(setOf(source))
        assertTrue("Logical index must fit budget",bounded.usedBytes()<=2L*1024*1024)
        assertTrue("DELETE must reclaim disk, not just leave free pages: $before -> ${file.length()}",file.length()<before/4)
        assertEquals("Сейчас",bounded.now(source,listOf("p|c"),now)["p|c"]?.title)
        assertEquals("Подробное описание текущей передачи",bounded.now(source,listOf("p|c"),now)["p|c"]?.description)
        assertEquals(1301L,bounded.snapshot(source)?.rows)
        assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM epg_programme WHERE batch_id=$abandoned"))
    }
    @Test fun lowFreeSpaceShrinksIndexFurtherWhileKeepingEveryCurrentProgramme()=database("low-space.db") {d,db,file->
        val now=System.currentTimeMillis();val source="b".repeat(64)
        val original=IndexedEpgStore(db,d);val generation=original.begin(source)
        val rows=(1..2000).map {i->"p|c$i" to EpgProgramme("c$i",now-60_000,now+3_600_000,"Текущая $i","x".repeat(6000))}
        rows.chunked(100).forEach {original.write(source,generation,it,true)}
        original.complete(source,generation,"full",now,now)
        scalar(d,"PRAGMA wal_checkpoint(TRUNCATE)");val before=file.length()
        val limited=IndexedEpgStore(db,d,EpgStoragePolicy(usableBytes={12L*1024*1024},clock={now}))
        limited.maintainStorage(setOf(source))
        assertTrue(limited.usedBytes()<=3L*1024*1024)
        assertTrue(file.length()<before/2)
        assertEquals(2000L,limited.snapshot(source)?.rows)
        assertEquals(2000,limited.now(source,rows.map{it.first},now).size)
    }
    @Test fun legacyAppGuideAndOldMovieCardsAreRemovedWithoutTouchingPlaylistOrSettings()=database("legacy.db") {d,db,file->
        val now=System.currentTimeMillis()
        d.execute(null,"INSERT INTO playlist(id,name,url) VALUES('user','Мой плейлист','https://fixture/list')",0)
        db.appMetaQueries.upsert("settings.example","keep")
        d.execute(null,"INSERT INTO tmdb_cache(media_item_id,updated_at,status) VALUES('old',0,'FOUND')",0)
        db.transaction {
            repeat(1200) {i->d.execute(null,"INSERT INTO epg_programme(playlist_id,channel_id,start_time,stop_time,title,description) VALUES('old','c',1,2,?,?)",2) {
                bindString(0,"P$i");bindString(1,"x".repeat(12000))
            }}
        }
        scalar(d,"PRAGMA wal_checkpoint(TRUNCATE)")
        val before=file.length()
        CacheStorageMaintenance.run(d,now)
        assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM epg_programme"))
        assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM tmdb_cache"))
        assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM playlist WHERE id='user'"))
        assertEquals("keep",db.appMetaQueries.selectByKey("settings.example").executeAsOne().value_)
        assertTrue(file.length()<before/4)
    }
    @Test fun fileCleanupBoundsImagesAndRemovesStaleEpgCopiesButKeepsRecordingsAndRecentImport() {
        val context=RuntimeEnvironment.getApplication();val now=System.currentTimeMillis()
        val downloads=File(context.noBackupFilesDir,"epg-downloads-v1").apply{mkdirs()}
        File(downloads,"old.cached").writeBytes(ByteArray(10000))
        File(downloads,"old.part").apply{writeBytes(ByteArray(10000));setLastModified(now-2*60*60_000L)}
        val current=File(downloads,"current.part").apply{writeText("active")}
        val recording=File(context.filesDir,"tvnext-recordings/user/capture.ts").apply{parentFile!!.mkdirs();writeText("user recording")}
        val logos=File(context.cacheDir,"logos").apply{mkdirs()}
        repeat(10){i->File(logos,"$i").apply{writeBytes(ByteArray(1024*1024));setLastModified(now-i*1000)}}
        AndroidCacheMaintenance.files(context,now)
        assertFalse(File(downloads,"old.cached").exists());assertFalse(File(downloads,"old.part").exists())
        assertTrue(current.exists());assertEquals("user recording",recording.readText())
        assertTrue(logos.listFiles()!!.sumOf{it.length()}<=8L*1024*1024)
    }
    @Test fun committedSourceRetainsOnlyValidatorAnd304NeverCreatesAnotherGuideFile()=runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication();val dir=File(context.noBackupFilesDir,"source-test")
        var requests=0
        val client=HttpClient(MockEngine {r->requests++
            if(requests==1) respond("<tv/>",headers=headersOf(HttpHeaders.ETag,"\"same\""))
            else {assertEquals("\"same\"",r.headers[HttpHeaders.IfNoneMatch]);respond("",HttpStatusCode.NotModified)}
        }) {install(HttpTimeout)}
        try {
            val downloads=EpgSourceDownload(client,dir,keepSource=false)
            val url="https://fixture/guide.xml"
            downloads.consume(url) {assertEquals("<tv/>",it.readText())};downloads.acknowledge(url)
            assertFalse(dir.listFiles()!!.any{it.name.endsWith(".ready")||it.name.endsWith(".cached")})
            try {downloads.consume(url,allowNotModified=true){fail("304 must use the index, not parse another file")};fail("Unchanged signal")}
            catch(e:java.io.IOException) {assertEquals("EpgSourceUnchangedException",e.javaClass.simpleName)}
            assertTrue(dir.listFiles()!!.sumOf{it.length()}<1024)
            assertEquals(2,requests)
        } finally {client.close()}
    }
}
