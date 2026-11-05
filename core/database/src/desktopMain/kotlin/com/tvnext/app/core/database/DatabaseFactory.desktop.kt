package com.tvnext.app.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.sql.DriverManager

actual class DatabaseFactory {
    actual fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), ".tvnext")
        dir.mkdirs()
        val file = File(dir, "tvnext.db")
        // WAL lets readers run concurrently with a long writer transaction —
        // otherwise a huge EPG import (1.6M rows) locks the whole database and
        // every concurrent read (channels, settings) fails with SQLITE_BUSY.
        // WAL mode is a persistent property of the database file.
        val setup = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        // Fresh database? Detect while `setup` is still open: repeated full EPG
        // replacements (delete + insert of millions of rows) leave the SQLite
        // file bloated with free pages — it only grows, never shrinks, and can
        // end up >1 GB with "database error" symptoms. auto_vacuum=FULL makes a
        // new database track the live row count instead of accumulating dead
        // pages. It must be set FIRST, on the still-empty file: SQLite silently
        // ignores auto_vacuum on a non-empty database (and WAL mode already
        // wrote pages), so the order below matters.
        val fresh = file.length() == 0L
        try {
            if (fresh) {
                setup.createStatement().use { it.execute("PRAGMA auto_vacuum=FULL;") }
            }
            setup.createStatement().use { it.execute("PRAGMA journal_mode=WAL;") }
        } finally {
            setup.close()
        }
        val driver = JdbcSqliteDriver(url = "jdbc:sqlite:${file.absolutePath}")
        // Make competing writes WAIT (up to 60 s) instead of failing during a
        // long import. EPG imports now commit in batches, so a waiting writer
        // gets its turn between batches.
        driver.execute(null, "PRAGMA busy_timeout=60000;", 0) { }
        if (fresh) {
            // Create the full schema.
            TvNextDatabase.Schema.create(driver)
        } else {
            // Existing database created by an older app version.
            //
            // The actual schema version is detected from the TABLE STRUCTURE,
            // not from PRAGMA user_version: older releases created the schema
            // without persisting user_version (it stayed 0), so a naive
            // migrate(user_version -> latest) would re-run early ALTER TABLE
            // statements and crash on duplicate columns.
            TvNextDatabase.Schema.migrate(
                driver = driver,
                oldVersion = detectVersion(driver),
                newVersion = TvNextDatabase.Schema.version,
            )
        }
        // Let the EPG store manage the programme indexes (fast bulk imports).
        registerRawSqlDriver(driver)
        return driver
    }

    /**
     * Structure-based schema version:
     *  - 1: playlist without `url_tvg` (early layout) → migrations 1..6 run;
     *  - 3: `url_tvg` present but no `playlist.type` → only 3→4→5→6 runs;
     *  - 4: `type` present but no `channel.catchup` → only 4→5→6 runs;
     *  - 5: `catchup` present but no `epg_programme.lower_title` → 5→6 runs;
     *  - 6: `tmdb_cache` present but no `epg_programme.batch_id` → 6→7→8 runs;
     *  - 7: `batch_id` missing → migration 8 runs;
     *  - latest: same as 8 for the current schema.
     */
    private fun detectVersion(driver: SqlDriver): Long {
        if (!tableExists(driver, "playlist")) return 1L
        if (!columnExists(driver, "playlist", "url_tvg")) return 1L
        if (!columnExists(driver, "playlist", "type")) return 3L
        if (!columnExists(driver, "channel", "catchup")) return 4L
        if (!columnExists(driver, "epg_programme", "lower_title")) return 5L
        if (!tableExists(driver, "tmdb_cache")) return 6L
        if (!columnExists(driver, "epg_programme", "batch_id")) return 7L
        return TvNextDatabase.Schema.version
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean {
        val result = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> QueryResult.Value(cursor.getString(0)) },
            parameters = 1,
            binders = { bindString(0, table) },
        )
        return result.value != null
    }

    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
        val result = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = ?",
            mapper = { cursor -> QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L) },
            parameters = 1,
            binders = { bindString(0, column) },
        )
        return result.value
    }
}
