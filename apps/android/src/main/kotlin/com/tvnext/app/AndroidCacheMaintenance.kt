package com.tvnext.app

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.tvnext.app.core.common.log.AndroidPerformanceCounters
import com.tvnext.app.core.database.CacheStorageMaintenance
import java.io.File

/** Explicit cache paths only; user recordings, playlists and settings are never deleted. */
internal object AndroidCacheMaintenance {
    fun files(context: Context, now: Long = System.currentTimeMillis()) {
        val downloads=File(context.noBackupFilesDir,"epg-downloads-v1")
        for(file in downloads.listFiles().orEmpty()) {
            val age=now-file.lastModified()
            if(file.name.endsWith(".cached") || file.name.endsWith(".cached-meta") ||
                (age>60*60_000L && listOf(".part",".ready",".meta",".tmp").any(file.name::endsWith))) file.delete()
        }
        val logos=File(context.cacheDir,"logos").listFiles().orEmpty().sortedByDescending {it.lastModified()}
        var total=0L
        for(file in logos) {
            if(now-file.lastModified()>24*60*60_000L || total+file.length()>8L*1024*1024) file.delete()
            else total+=file.length()
        }
        for(name in listOf("crash.log","tvnext_diag.log.previous","tvnext_performance.previous.log")) {
            val file=File(context.filesDir,name)
            if(file.length()>1024*1024) file.delete()
        }
        AndroidPerformanceCounters.set("storage.epgIndexBytes",File(context.noBackupFilesDir,"epg-index-v1.db").length())
        AndroidPerformanceCounters.set("storage.epgDownloadBytes",downloads.listFiles().orEmpty().sumOf {it.length()})
        AndroidPerformanceCounters.set("storage.logoBytes",total)
        AndroidPerformanceCounters.set("storage.mainDbBytes",context.getDatabasePath("tvnext.db").length())
    }
    fun database(driver: SqlDriver, now: Long, checkpoint: () -> Unit) = CacheStorageMaintenance.run(driver,now,checkpoint)
}
