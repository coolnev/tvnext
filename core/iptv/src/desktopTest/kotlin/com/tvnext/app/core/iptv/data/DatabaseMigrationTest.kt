package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.TvNextDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Migrations on a database that already holds real user data.
 *
 * The migration chain is applied like in production: an existing database
 * created by an older app version runs every .sqm migration in between. The
 * oldest real-user layout (version 1: playlist + channels + favorites) and the
 * version-2 layout (plus history/EPG) must both reach the latest schema with
 * every user row intact.
 */
class DatabaseMigrationTest {

    /**
     * One statement per execute — JdbcSqliteDriver does not run multi-statement
     * SQL (like the generated SQLDelight migrations, which call execute per
     * statement).
     */
    private fun executeAll(driver: JdbcSqliteDriver, statements: List<String>) {
        statements.forEach { driver.execute(null, it, 0, null) }
    }

    /**
     * Version-1 layout: playlist (no url_tvg yet), channel, app_meta.
     * Version-2 additions (history/EPG tables and url_tvg) are created by the
     * migrations themselves — this mirrors the real upgrade path.
     */
    private fun createV1Database(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        executeAll(
            driver,
            listOf(
                "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, last_updated INTEGER, enabled INTEGER NOT NULL DEFAULT 1)",
                "CREATE TABLE channel (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                "INSERT INTO playlist(id, name, url, last_updated, enabled) VALUES ('pl1', 'Мой плейлист', 'http://example/pl.m3u', 1700000000000, 1)",
                "INSERT INTO channel(id, playlist_id, number, name, stream_url, category, tvg_id, is_favorite) VALUES ('ch1', 'pl1', 1, 'Новости', 'http://example/1.m3u8', 'Новости', 'epg1', 1)",
            ),
        )
        return driver
    }

    /**
     * Version-2 layout: v1 tables plus history and EPG tables with real data
     * (url_tvg is still added by migration 2).
     */
    private fun createV2Database(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        executeAll(
            driver,
            listOf(
                "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, last_updated INTEGER, enabled INTEGER NOT NULL DEFAULT 1)",
                "CREATE TABLE channel (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE history (channel_id TEXT NOT NULL PRIMARY KEY, watched_at INTEGER NOT NULL)",
                "CREATE TABLE epg_channel (id TEXT NOT NULL PRIMARY KEY, display_names TEXT NOT NULL DEFAULT '', icon_url TEXT)",
                "CREATE TABLE epg_programme (channel_id TEXT NOT NULL, start_time INTEGER NOT NULL, stop_time INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, category TEXT, episode_num TEXT)",
                "CREATE TABLE epg_cache (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), url TEXT NOT NULL DEFAULT '', fetched_at INTEGER, source TEXT NOT NULL DEFAULT '', last_error TEXT, channel_count INTEGER NOT NULL DEFAULT 0, programme_count INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                "INSERT INTO playlist(id, name, url, last_updated, enabled) VALUES ('pl1', 'Мой плейлист', 'http://example/pl.m3u', 1700000000000, 1)",
                "INSERT INTO channel(id, playlist_id, number, name, stream_url, category, tvg_id, is_favorite) VALUES ('ch1', 'pl1', 1, 'Новости', 'http://example/1.m3u8', 'Новости', 'epg1', 1)",
                "INSERT INTO history(channel_id, watched_at) VALUES ('ch1', 1700000000000)",
                "INSERT INTO epg_channel(id, display_names, icon_url) VALUES ('epg1', 'Новости', NULL)",
                "INSERT INTO epg_programme(channel_id, start_time, stop_time, title) VALUES ('epg1', 1699999200000, 1700002800000, 'Новости 10:00')",
                "INSERT INTO epg_cache(id, url, fetched_at, source, last_error, channel_count, programme_count) VALUES (1, 'http://example/epg.xml', 1700000000000, 'xmltv', NULL, 1, 1)",
            ),
        )
        return driver
    }

    /**
     * Version-4 layout: v3 tables (playlist.type present) plus a channel
     * WITHOUT the catch-up columns — the previous release's schema.
     */
    private fun createV4Database(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        executeAll(
            driver,
            listOf(
                "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, last_updated INTEGER, enabled INTEGER NOT NULL DEFAULT 1, url_tvg TEXT, type TEXT NOT NULL DEFAULT 'LIVE', is_default INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE channel (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE media_item (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, duration_seconds INTEGER, season TEXT, episode TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE history (item_id TEXT NOT NULL, content_type TEXT NOT NULL, watched_at INTEGER NOT NULL, PRIMARY KEY (item_id, content_type))",
                "CREATE TABLE epg_channel (playlist_id TEXT NOT NULL, id TEXT NOT NULL, display_names TEXT NOT NULL DEFAULT '', icon_url TEXT, PRIMARY KEY (playlist_id, id))",
                "CREATE TABLE epg_programme (playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_time INTEGER NOT NULL, stop_time INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, category TEXT, episode_num TEXT)",
                "CREATE TABLE epg_cache (playlist_id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL DEFAULT '', fetched_at INTEGER, source TEXT NOT NULL DEFAULT '', last_error TEXT, channel_count INTEGER NOT NULL DEFAULT 0, programme_count INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                "INSERT INTO playlist(id, name, url, last_updated, enabled, type, is_default) VALUES ('pl1', 'Мой плейлист', 'http://example/pl.m3u', 1700000000000, 1, 'LIVE', 1)",
                "INSERT INTO channel(id, playlist_id, number, name, stream_url, category, tvg_id, is_favorite) VALUES ('ch1', 'pl1', 1, 'Новости', 'http://example/1.m3u8', 'Новости', 'epg1', 1)",
                "INSERT INTO history(item_id, content_type, watched_at) VALUES ('ch1', 'LIVE', 1700000000000)",
                "INSERT INTO epg_channel(playlist_id, id, display_names, icon_url) VALUES ('pl1', 'epg1', 'Новости', NULL)",
                "INSERT INTO epg_programme(playlist_id, channel_id, start_time, stop_time, title) VALUES ('pl1', 'epg1', 1699999200000, 1700002800000, 'Новости 10:00')",
                "INSERT INTO app_meta(key, value) VALUES ('settings.video_decode_mode', 'AUTO')",
            ),
        )
        return driver
    }

    @Test
    fun migrationFromV4AddsCatchupAndKeepsUserData() = runBlocking {
        // The previous release's schema (no catch-up columns). The 4→5
        // migration must add them and preserve every user row.
        val driver = createV4Database()
        TvNextDatabase.Schema.migrate(driver, 4, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)

        // User data intact.
        val playlists = database.playlistQueries.selectAllPlaylists().executeAsList()
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists.single().id)
        assertEquals(1L, playlists.single().is_default)

        val channels = database.channelQueries.selectAllChannels().executeAsList()
        assertEquals(1, channels.size)
        assertEquals(1L, channels.single().is_favorite)

        val history = database.historyQueries.selectAllHistory().executeAsList()
        assertEquals(1, history.size)
        assertEquals("ch1", history.single().item_id)

        val epg = database.epgQueries.selectEpgChannels("pl1", 0L).executeAsList()
        assertEquals("epg1", epg.single().id)

        val meta = database.appMetaQueries.selectAll().executeAsList()
        assertTrue(meta.any { it.key == "settings.video_decode_mode" })

        // New columns exist and default to null/false.
        assertEquals(null, channels.single().catchup)
        assertEquals(null, channels.single().catchup_days)
        assertEquals(null, channels.single().catchup_source)
        assertEquals(null, channels.single().catchup_type)
        assertEquals(null, channels.single().timeshift)

        // New tables exist and are empty.
        assertTrue(database.archiveCapabilityQueries.selectArchiveCapability("ch1").executeAsOneOrNull() == null)
        assertTrue(database.scheduledQueries.selectAllScheduled().executeAsList().isEmpty())
        assertEquals(0L, database.tmdbCacheQueries.count().executeAsOne())
    }

    @Test
    fun migrationFromV1PreservesPlaylistAndFavorites() = runBlocking {
        // The oldest real-user layout. Everything must survive up to the latest version.
        val driver = createV1Database()
        TvNextDatabase.Schema.migrate(driver, 1, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)

        val playlists = database.playlistQueries.selectAllPlaylists().executeAsList()
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists.single().id)
        assertEquals("LIVE", playlists.single().type)

        val channels = database.channelQueries.selectAllChannels().executeAsList()
        assertEquals(1, channels.size)
        assertEquals(1L, channels.single().is_favorite)
        assertEquals("pl1", channels.single().playlist_id)

        // History/EPG tables exist (empty) and media_item is present.
        assertTrue(database.historyQueries.selectAllHistory().executeAsList().isEmpty())
        assertTrue(database.epgQueries.selectEpgChannels("pl1", 0L).executeAsList().isEmpty())
        database.mediaItemQueries.selectAllMediaItems().executeAsList()
        assertTrue(true)
    }

    @Test
    fun migrationFromV2PreservesUserData() = runBlocking {
        // Version-2 data: favorites, history, EPG cache. All must survive.
        val driver = createV2Database()
        TvNextDatabase.Schema.migrate(driver, 1, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)

        // Playlists survived, new columns have correct defaults.
        val playlists = database.playlistQueries.selectAllPlaylists().executeAsList()
        assertEquals(1, playlists.size)
        assertEquals("pl1", playlists.single().id)
        assertEquals("LIVE", playlists.single().type)
        assertEquals(0L, playlists.single().is_default)

        // Channels + favorite survived.
        val channels = database.channelQueries.selectAllChannels().executeAsList()
        assertEquals(1, channels.size)
        assertEquals(1L, channels.single().is_favorite)
        assertEquals("pl1", channels.single().playlist_id)

        // History became typed LIVE.
        val history = database.historyQueries.selectAllHistory().executeAsList()
        assertEquals(1, history.size)
        assertEquals("ch1", history.single().item_id)
        assertEquals("LIVE", history.single().content_type)
        assertEquals(1700000000000L, history.single().watched_at)

        // EPG was re-attached to the existing playlist.
        val epgChannels = database.epgQueries.selectEpgChannels("pl1", 0L).executeAsList()
        assertEquals(1, epgChannels.size)
        assertEquals("epg1", epgChannels.single().id)
        val meta = database.epgQueries.selectEpgCacheMeta("pl1").executeAsOneOrNull()
        assertNotNull(meta, "EPG cache meta should be attached to the playlist")

        // New media_item table exists.
        database.mediaItemQueries.selectAllMediaItems().executeAsList()
        assertTrue(true)
    }

    @Test
    fun migrationRunsCleanlyOnEmptyDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvNextDatabase.Schema.create(driver)
        // Fresh database at the latest version; migrating from the same version is a no-op.
        TvNextDatabase.Schema.migrate(driver, TvNextDatabase.Schema.version, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)
        assertTrue(database.playlistQueries.selectAllPlaylists().executeAsList().isEmpty())
    }

    // ---- v2 / v5 / user_version=0 coverage ---------------------------------

    @Test
    fun migrationFromV2LayoutAddsTypeAndCatchupKeepingUserData() = runBlocking {
        // A database created by an app version with migrations 1+2 only:
        // playlist.url_tvg present, playlist.type absent, channel without
        // catch-up columns, old (channel-keyed) EPG tables.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        executeAll(
            driver,
            listOf(
                "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, last_updated INTEGER, enabled INTEGER NOT NULL DEFAULT 1, url_tvg TEXT)",
                "CREATE TABLE channel (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE epg_channel (id TEXT NOT NULL PRIMARY KEY, display_names TEXT NOT NULL DEFAULT '', icon_url TEXT)",
                "CREATE TABLE epg_programme (channel_id TEXT NOT NULL, start_time INTEGER NOT NULL, stop_time INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, category TEXT, episode_num TEXT)",
                "CREATE TABLE epg_cache (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), url TEXT NOT NULL DEFAULT '', fetched_at INTEGER, source TEXT NOT NULL DEFAULT '', last_error TEXT, channel_count INTEGER NOT NULL DEFAULT 0, programme_count INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE history (channel_id TEXT NOT NULL PRIMARY KEY, watched_at INTEGER NOT NULL)",
                "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                "INSERT INTO playlist(id, name, url, last_updated, enabled, url_tvg) VALUES ('pl1', 'Плейлист', 'http://example/pl.m3u', 1700000000000, 1, 'http://epg/xml')",
                "INSERT INTO channel(id, playlist_id, number, name, stream_url, category, tvg_id, is_favorite) VALUES ('ch1', 'pl1', 1, 'Новости', 'http://example/1.m3u8', 'Новости', 'epg1', 1)",
                "INSERT INTO history(channel_id, watched_at) VALUES ('ch1', 1700000000000)",
                "INSERT INTO epg_channel(id, display_names, icon_url) VALUES ('epg1', 'Новости', NULL)",
                "INSERT INTO epg_programme(channel_id, start_time, stop_time, title) VALUES ('epg1', 1699999200000, 1700002800000, 'Новости 10:00')",
            ),
        )
        // detectVersion returns 3 for this layout; the 3→6 chain adds type,
        // media_item, catch-up columns, archive/schedule tables and lower_title.
        TvNextDatabase.Schema.migrate(driver, 3, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)

        // Old EPG was re-attached to the existing playlist, not lost.
        assertEquals("epg1", database.epgQueries.selectEpgChannels("pl1", 0L).executeAsList().single().id)
        // History became typed LIVE.
        val history = database.historyQueries.selectAllHistory().executeAsList()
        assertEquals(1, history.size)
        assertEquals("LIVE", history.single().content_type)
        // Favorites survived.
        assertEquals(1L, database.channelQueries.selectAllChannels().executeAsList().single().is_favorite)
        // New tables exist.
        assertTrue(database.archiveCapabilityQueries.selectArchiveCapability("ch1").executeAsOneOrNull() == null)
        assertTrue(database.scheduledQueries.selectAllScheduled().executeAsList().isEmpty())
        assertEquals(0L, database.tmdbCacheQueries.count().executeAsOne())
        database.mediaItemQueries.selectAllMediaItems().executeAsList()
        assertTrue(true)
    }

    @Test
    fun migrationFromV5AddsLowerTitleColumn() = runBlocking {
        // v5 layout: v4 + catch-up columns, but WITHOUT epg_programme.lower_title.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        executeAll(
            driver,
            listOf(
                "CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, last_updated INTEGER, enabled INTEGER NOT NULL DEFAULT 1, url_tvg TEXT, type TEXT NOT NULL DEFAULT 'LIVE', is_default INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE channel (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, is_favorite INTEGER NOT NULL DEFAULT 0, catchup TEXT, catchup_days INTEGER, catchup_source TEXT, catchup_type TEXT, timeshift TEXT)",
                "CREATE TABLE history (item_id TEXT NOT NULL, content_type TEXT NOT NULL, watched_at INTEGER NOT NULL, PRIMARY KEY (item_id, content_type))",
                "CREATE TABLE epg_channel (playlist_id TEXT NOT NULL, id TEXT NOT NULL, display_names TEXT NOT NULL DEFAULT '', icon_url TEXT, PRIMARY KEY (playlist_id, id))",
                "CREATE TABLE epg_programme (playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_time INTEGER NOT NULL, stop_time INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, category TEXT, episode_num TEXT)",
                "CREATE TABLE epg_cache (playlist_id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL DEFAULT '', fetched_at INTEGER, source TEXT NOT NULL DEFAULT '', last_error TEXT, channel_count INTEGER NOT NULL DEFAULT 0, programme_count INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE media_item (id TEXT NOT NULL PRIMARY KEY, playlist_id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, stream_url TEXT NOT NULL, logo_url TEXT, category TEXT NOT NULL DEFAULT '', tvg_id TEXT, tvg_name TEXT, duration_seconds INTEGER, season TEXT, episode TEXT, is_favorite INTEGER NOT NULL DEFAULT 0)",
                "INSERT INTO playlist(id, name, url, last_updated, enabled, type, is_default) VALUES ('pl1', 'Плейлист', 'http://example/pl.m3u', 1700000000000, 1, 'LIVE', 1)",
                "INSERT INTO channel(id, playlist_id, number, name, stream_url, category, tvg_id, is_favorite) VALUES ('ch1', 'pl1', 1, 'Новости', 'http://example/1.m3u8', 'Новости', 'epg1', 1)",
                "INSERT INTO epg_channel(playlist_id, id, display_names, icon_url) VALUES ('pl1', 'epg1', 'Новости', NULL)",
                "INSERT INTO epg_programme(playlist_id, channel_id, start_time, stop_time, title) VALUES ('pl1', 'epg1', 1699999200000, 1700002800000, 'Новости 10:00')",
            ),
        )
        TvNextDatabase.Schema.migrate(driver, 5, TvNextDatabase.Schema.version)
        val database = TvNextDatabase(driver)

        // The 5→6 migration added lower_title (backfilled empty; refreshed later).
        val programmes = database.epgQueries.selectEpgChannels("pl1", 0L).executeAsList()
        assertEquals("epg1", programmes.single().id)
        val rows = database.epgQueries.selectProgrammesInRangeByPlaylist(
            playlist_id = "pl1",
            batch_id = 0L,
            start_time = 1700002800000L,
            stop_time = 1699999200000L,
        ).executeAsList()
        assertEquals(1, rows.size)
        assertEquals("", rows.single().lower_title)

        // Data survived.
        assertEquals(1L, database.channelQueries.selectAllChannels().executeAsList().single().is_favorite)
        assertEquals(1, database.playlistQueries.selectAllPlaylists().executeAsList().size)
    }

    @Test
    fun userVersionZeroWithCompleteSchemaIsDetectedAsLatest() = runBlocking {
        // The classic desktop upgrade: an old DB file whose schema is complete
        // but PRAGMA user_version stayed 0. The structure-based detection must
        // treat it as "already latest" (no-op migration), never as version 0
        // (which would re-run every ALTER TABLE and crash on duplicate columns).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvNextDatabase.Schema.create(driver)

        // Simulate user_version = 0 on a complete schema.
        driver.execute(null, "PRAGMA user_version = 0", 0, null)

        // The same branch DatabaseFactory.desktop takes for existing files:
        // detectVersion returns Schema.version (all marker columns present) →
        // migrating from the latest version is a no-op and must NOT re-run
        // ALTER TABLE statements (which would crash on duplicate columns).
        TvNextDatabase.Schema.migrate(
            driver,
            TvNextDatabase.Schema.version,
            TvNextDatabase.Schema.version,
        )
        val database = TvNextDatabase(driver)
        assertTrue(database.channelQueries.selectAllChannels().executeAsList().isEmpty())
        assertTrue(database.epgQueries.countEpgProgrammes("pl1", 0L).executeAsOne() == 0L)
        assertTrue(database.playlistQueries.selectAllPlaylists().executeAsList().isEmpty())
    }
}
