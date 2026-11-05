package com.tvnext.app.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the desktop database bootstrap: a FRESH database must be created
 * without exceptions (regression: the auto_vacuum PRAGMA used a connection
 * that had already been closed — every fresh launch failed with
 * "database error" and broke TMDB/EPG), the schema must exist and
 * auto_vacuum must be FULL so the file does not bloat after EPG imports.
 */
class DatabaseFactoryDesktopTest {

    private lateinit var homeDir: File
    private var originalUserHome: String? = null

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        homeDir = createTempDirectory("tvnext-db-test").toFile()
        System.setProperty("user.home", homeDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        originalUserHome?.let { System.setProperty("user.home", it) }
        homeDir.deleteRecursively()
    }

    @Test
    fun createsFreshDatabaseWithFullSchemaAndAutoVacuum() {
        val driver = DatabaseFactory().createDriver()
        try {
            val dbFile = File(homeDir, ".tvnext/tvnext.db")
            assertTrue(dbFile.isFile, "database file must be created")

            // Core tables exist (fresh schema was created).
            assertTrue(tableExists(driver, "playlist"))
            assertTrue(tableExists(driver, "channel"))
            assertTrue(tableExists(driver, "app_meta"))
            assertTrue(tableExists(driver, "tmdb_cache"))
            assertTrue(tableExists(driver, "epg_programme"))

            // auto_vacuum must be FULL on a fresh database.
            val autoVacuum = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA auto_vacuum;",
                mapper = { cursor -> QueryResult.Value(cursor.getLong(0)) },
                parameters = 0,
                binders = {},
            ).value
            assertNotNull(autoVacuum, "auto_vacuum pragma must return a value")
            assertEquals(1L, autoVacuum, "auto_vacuum must be FULL (1)")

            // WAL stays enabled (persistent journal mode).
            val journal = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA journal_mode;",
                mapper = { cursor -> QueryResult.Value(cursor.getString(0)) },
                parameters = 0,
                binders = {},
            ).value
            assertEquals("wal", journal, "journal_mode must be WAL")
        } finally {
            driver.close()
        }
    }

    @Test
    fun opensExistingDatabaseWithoutMigratingFreshSchema() {
        // First launch creates the schema; the SECOND launch opens the same
        // file and must NOT run migrations on the already-current schema.
        DatabaseFactory().createDriver().close()
        val driver = DatabaseFactory().createDriver()
        try {
            assertTrue(tableExists(driver, "tmdb_cache"))
        } finally {
            driver.close()
        }
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
}
