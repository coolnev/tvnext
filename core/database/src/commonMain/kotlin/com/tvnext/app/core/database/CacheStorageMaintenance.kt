package com.tvnext.app.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult

/** Android's obsolete EPG tables and disposable movie cards only. */
object CacheStorageMaintenance {
    fun run(driver: SqlDriver, now: Long, checkpoint: () -> Unit = {}) {
        for (table in listOf("epg_programme", "epg_channel")) {
            while (true) {
                checkpoint()
                if (driver.execute(null,"DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table LIMIT 1024)",0).value<=0) break
            }
        }
        driver.execute(null,"DELETE FROM epg_cache",0)
        driver.execute(null,"DELETE FROM tmdb_cache WHERE updated_at<${now-5*60_000L} OR media_item_id NOT IN (SELECT media_item_id FROM tmdb_cache ORDER BY updated_at DESC LIMIT 128)",0)
        compact(driver, checkpoint)
    }

    fun compact(driver: SqlDriver, checkpoint: () -> Unit = {}) {
        fun scalar(sql: String) = driver.executeQuery(null,sql,{c->c.next();QueryResult.Value(c.getLong(0)?:0)},0).value
        checkpoint()
        // Correct query API for pragmas which return rows on Android.
        val free=scalar("PRAGMA freelist_count")*scalar("PRAGMA page_size")
        runCatching { scalar("PRAGMA journal_size_limit=8388608") }
        if (free>=8L*1024*1024) runCatching {
            scalar("PRAGMA wal_checkpoint(TRUNCATE)")
            driver.execute(null,"VACUUM",0)
        }
        runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)") }
    }
}
