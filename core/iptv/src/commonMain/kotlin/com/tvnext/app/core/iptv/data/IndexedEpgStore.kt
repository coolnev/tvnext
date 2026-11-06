package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.xmltv.EpgProgramme

/** A separate WAL database: imports never lock settings, playlists or playback history. */
class IndexedEpgStore(private val database: TvNextDatabase, private val driver: SqlDriver) {
    private val q = database.epgQueries

    init {
        // These objects belong only to the dedicated EPG DB, never the app DB.
        driver.execute(null, "CREATE TABLE IF NOT EXISTS epg_index_channel (source TEXT NOT NULL, generation INTEGER NOT NULL, channel_key TEXT NOT NULL, PRIMARY KEY(source,generation,channel_key))", 0)
        driver.execute(null, "DROP INDEX IF EXISTS idx_epg_programme_channel_start", 0)
        driver.execute(null, "DROP INDEX IF EXISTS idx_epg_programme_playlist_start", 0)
        driver.execute(null, "CREATE UNIQUE INDEX IF NOT EXISTS epg_index_identity ON epg_programme(playlist_id,batch_id,channel_id,start_time)", 0)
    }

    data class Snapshot(val generation: Long, val fetchedAt: Long, val signature: String,
        val rows: Long, val channels: Long, val retryAt: Long)

    fun snapshot(source: String): Snapshot? = q.selectEpgCacheMeta(source).executeAsOneOrNull()?.let {
        Snapshot(it.active_batch, it.fetched_at ?: 0L, it.source, it.programme_count,
            it.channel_count, it.last_error?.substringBefore(':')?.toLongOrNull() ?: 0L)
    }

    fun begin(source: String): Long = driver.executeQuery(null,
        "SELECT MAX(COALESCE((SELECT batch_id FROM epg_programme WHERE playlist_id=? ORDER BY batch_id DESC LIMIT 1),0),COALESCE((SELECT active_batch FROM epg_cache WHERE playlist_id=?),0))+1",
        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 1L) }, 2) {
        bindString(0, source); bindString(1, source)
    }.value

    private fun writeKeys(source: String, generation: Long, keys: Collection<String>) {
        for (key in keys) driver.execute(null, "INSERT OR IGNORE INTO epg_index_channel(source,generation,channel_key) VALUES (?,?,?)", 3) {
            bindString(0, source); bindLong(1, generation); bindString(2, key)
        }
    }

    fun channelKeys(source: String, generation: Long): Set<String> {
        val keys = driver.executeQuery(null,
            "SELECT channel_key FROM epg_index_channel WHERE source=? AND generation=?",
            { cursor ->
                val result = linkedSetOf<String>()
                while (cursor.next().value) cursor.getString(0)?.let(result::add)
                QueryResult.Value(result)
            }, 2) { bindString(0, source); bindLong(1, generation) }.value
        if (keys.isNotEmpty()) return keys
        // One-time upgrade of an existing 0.5.32 index; subsequent starts read only this small table.
        val previous = q.selectDistinctProgrammeChannelIds(source, generation).executeAsList().toSet()
        if (previous.isNotEmpty()) database.transaction { writeKeys(source, generation, previous) }
        return previous
    }

    /** Only obsolete generations, in bounded transactions; never delete a newer in-flight import. */
    fun purgeInactive(source: String, active: Long, checkpoint: () -> Unit = {}) {
        while (true) {
            checkpoint()
            val deleted = database.transactionWithResult {
                driver.execute(null, "DELETE FROM epg_programme WHERE rowid IN (SELECT rowid FROM epg_programme WHERE playlist_id=? AND batch_id<? LIMIT 4096)", 2) {
                    bindString(0, source); bindLong(1, active)
                }.value
            }
            if (deleted == 0L) break
        }
        driver.execute(null, "DELETE FROM epg_index_channel WHERE source=? AND generation<?", 2) {
            bindString(0, source); bindLong(1, active)
        }
    }

    fun write(source: String, generation: Long, rows: List<Pair<String, EpgProgramme>>, publishCold: Boolean,
        newChannelKeys: Collection<String> = rows.map { it.first }.distinct()) {
        database.transaction {
            // Stay below SQLite's 999-variable limit on Android 7/8. One SQL
            // statement per 64 rows amortizes execution and query invalidation.
            rows.chunked(64).forEach { part ->
                val placeholders = List(part.size) { "(?,?,?,?,?,?,?,?,?,?)" }.joinToString(",")
                driver.execute(null, "INSERT OR REPLACE INTO epg_programme " +
                    "(playlist_id,channel_id,start_time,stop_time,title,lower_title,description,category,episode_num,batch_id) VALUES $placeholders", part.size * 10) {
                    part.forEachIndexed { row, (key, p) ->
                        val at = row * 10
                        bindString(at, source); bindString(at + 1, key)
                        bindLong(at + 2, p.start); bindLong(at + 3, p.stop)
                        bindString(at + 4, p.title); bindString(at + 5, p.title.lowercase())
                        bindString(at + 6, p.description); bindString(at + 7, p.category)
                        bindString(at + 8, p.episodeNum); bindLong(at + 9, generation)
                    }
                }
            }
            writeKeys(source, generation, newChannelKeys)
            if (publishCold) {
                val old = snapshot(source)
                if (old == null || old.generation == 0L) {
                    q.upsertEpgCacheMeta(source, "", null, "", null, 0, 0, generation)
                }
            }
        }
    }

    fun complete(source: String, generation: Long, signature: String, fetchedAt: Long, now: Long,
        checkpoint: (Int, Int) -> Unit = { _, _ -> }) {
        val previous = snapshot(source)?.generation ?: 0L
        if (previous != 0L && previous != generation) {
            // Copy one indexed channel at a time, without one huge INSERT/SELECT temp table.
            // The previous generation stays active until every archive batch is ready.
            val oldKeys = channelKeys(source, previous).toList()
            oldKeys.forEachIndexed { index, key ->
                checkpoint(index, oldKeys.size)
                database.transaction {
                    driver.execute(null, """
                        INSERT OR IGNORE INTO epg_programme
                        (playlist_id,channel_id,start_time,stop_time,title,lower_title,description,category,episode_num,batch_id)
                        SELECT playlist_id,channel_id,start_time,stop_time,title,lower_title,description,category,episode_num,?
                        FROM epg_programme WHERE playlist_id=? AND batch_id=? AND channel_id=? AND start_time<? AND stop_time>?
                    """.trimIndent(), 6) {
                        bindLong(0, generation); bindString(1, source); bindLong(2, previous); bindString(3, key)
                        bindLong(4, now); bindLong(5, now - 90L * 86_400_000L)
                    }
                }
            }
        }
        checkpoint(1, 1)
        // Include retained archive-only channels in the persisted coverage table.
        val allKeys = q.selectDistinctProgrammeChannelIds(source, generation).executeAsList()
        database.transaction {
            writeKeys(source, generation, allKeys)
            val count = q.countEpgProgrammes(source, generation).executeAsOne()
            q.upsertEpgCacheMeta(source, "", fetchedAt, signature, null, allKeys.size.toLong(), count, generation)
        }
        // Old rows are cleaned later. Switching the active pointer must not wait for their deletion.
    }

    fun failed(source: String, generation: Long, retryAt: Long) {
        database.transaction {
            val old = snapshot(source)
            // A failed refresh keeps the last complete snapshot. A first import
            // may retain real partial rows, but is never marked as fresh.
            val partial = old?.generation == generation
            val rows = if (partial) q.countEpgProgrammes(source, generation).executeAsOne() else old?.rows ?: 0L
            val channels = if (partial) channelKeys(source, generation).size.toLong() else old?.channels ?: 0L
            q.upsertEpgCacheMeta(source, "", old?.fetchedAt?.takeIf { it > 0L }, old?.signature.orEmpty(),
                "$retryAt:import", channels, rows, old?.generation ?: 0L)
        }
    }

    fun discardUncommitted(source: String, active: Long, before: Long, checkpoint: () -> Unit) {
        while (true) {
            checkpoint()
            val deleted = database.transactionWithResult {
                driver.execute(null, "DELETE FROM epg_programme WHERE rowid IN (SELECT rowid FROM epg_programme WHERE playlist_id=? AND batch_id>? AND batch_id<? LIMIT 4096)", 3) {
                    bindString(0, source); bindLong(1, active); bindLong(2, before)
                }.value
            }
            if (deleted == 0L) break
        }
        driver.execute(null, "DELETE FROM epg_index_channel WHERE source=? AND generation>? AND generation<?", 3) {
            bindString(0, source); bindLong(1, active); bindLong(2, before)
        }
    }

    fun now(source: String, keys: Collection<String>, at: Long, generation: Long? = null): Map<String, EpgProgramme> {
        if (keys.isEmpty()) return emptyMap()
        return keys.chunked(200).flatMap {
            if (generation == null) q.selectIndexedCurrent(source, it, at).executeAsList()
            else q.selectNowPlayingByChannels(source, generation, it, at, at).executeAsList()
        }
            .associate { it.channel_id to domain(it) }
    }

    fun upcoming(source: String, key: String, at: Long, limit: Int, generation: Long? = null): List<EpgProgramme> {
        val rows = if (generation == null) q.selectIndexedUpcoming(source, key, at, limit.toLong()).executeAsList()
            else q.selectUpcomingProgrammes(source, key, generation, at, limit.toLong()).executeAsList()
        return rows.map(::domain)
    }

    fun range(source: String, keys: Collection<String>, from: Long, to: Long, generation: Long? = null): Map<String, List<EpgProgramme>> {
        if (keys.isEmpty()) return emptyMap()
        return keys.chunked(200).flatMap {
            if (generation == null) q.selectIndexedRange(source, it, to, from).executeAsList()
            else q.selectProgrammesInRangeByChannels(source, generation, it, to, from).executeAsList()
        }
            .map(::domain).groupBy { it.channelId }
    }

    fun bounds(source: String, keys: Collection<String>): LongRange? {
        var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
        // Indexed endpoints only; no programme/description materialization.
        for (key in keys) {
            val row = q.selectIndexedEpgBounds(source, key).executeAsOne()
            row.first_start?.let { first = minOf(first, it) }
            row.last_stop?.let { last = maxOf(last, it) }
        }
        return if (last > first) first..last else null
    }

    fun search(source: String, query: String, limit: Int): List<EpgProgramme> {
        return q.selectIndexedSearch(source, query.lowercase(), limit.toLong()).executeAsList().map(::domain)
    }

    private fun domain(row: com.tvnext.app.core.database.Epg_programme) = EpgProgramme(
        row.channel_id, row.start_time, row.stop_time, row.title, row.description, row.category, row.episode_num)
}
