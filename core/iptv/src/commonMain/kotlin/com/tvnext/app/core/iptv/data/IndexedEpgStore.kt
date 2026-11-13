package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.xmltv.EpgProgramme

/** A separate WAL database: imports never lock settings, playlists or playback history. */
class IndexedEpgStore(private val database: TvNextDatabase, private val driver: SqlDriver, private val storagePolicy: EpgStoragePolicy? = null) {
    private val q = database.epgQueries
    val storageManaged: Boolean get() = storagePolicy != null
    private var writtenSinceBudgetCheck = 0

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
        val now = storagePolicy?.clock?.invoke() ?: 0L
        val storedRows = if (storagePolicy == null) rows else rows.map { (key,p) -> key to p.copy(
            title = p.title.take(512), category = p.category?.take(256),
            description = p.description?.take(6000)?.takeIf { p.start in now-12*3_600_000L..now+12*3_600_000L }) }
        database.transaction {
            // Stay below SQLite's 999-variable limit on Android 7/8. One SQL
            // statement per 64 rows amortizes execution and query invalidation.
            storedRows.chunked(64).forEach { part ->
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
        if (storagePolicy != null) {
            writtenSinceBudgetCheck += rows.size
            if (writtenSinceBudgetCheck >= 2048) {
                writtenSinceBudgetCheck = 0
                check(source.all { it in "0123456789abcdef" })
                trimBudget(storagePolicy.maxBytes * 2, now, "playlist_id='$source' AND batch_id=$generation")
            }
        }
    }

    private fun scalar(sql: String): Long = driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value
    fun usedBytes(): Long = (scalar("PRAGMA page_count") - scalar("PRAGMA freelist_count")) * scalar("PRAGMA page_size")

    private fun changeInBatches(set: String?, where: String, order: String = "rowid", checkpoint: () -> Unit): Long {
        var total = 0L
        while (true) {
            checkpoint()
            val prefix = if (set == null) "DELETE FROM epg_programme" else "UPDATE epg_programme SET $set"
            val changed = driver.execute(null, "$prefix WHERE rowid IN (SELECT rowid FROM epg_programme WHERE $where ORDER BY $order LIMIT 1024)", 0).value
            if (changed <= 0) return total
            total += changed
            paceEpgCacheHydration()
        }
    }

    private fun trimBudget(target: Long, now: Long, scope: String = "1", checkpoint: () -> Unit = {}) {
        if (usedBytes() <= target) return
        val conditions = listOf(
            "description IS NOT NULL AND NOT(start_time<=$now AND (stop_time>$now OR (stop_time<=start_time AND start_time+1800000>$now)))" to "description=NULL",
            "start_time<${now-86_400_000L} AND stop_time<${now-86_400_000L}" to null,
            "start_time>${now+2*86_400_000L}" to null,
            "((stop_time>start_time AND stop_time<=$now) OR (stop_time<=start_time AND start_time+1800000<=$now))" to null,
            "start_time>${now+6*3_600_000L}" to null,
            "start_time>$now" to null,
            "description IS NOT NULL" to "description=NULL",
        )
        for ((condition, update) in conditions) {
            var updated = false
            while (usedBytes() > target) {
                checkpoint()
                val prefix = if (update == null) "DELETE FROM epg_programme" else "UPDATE epg_programme SET $update"
                val order = if (condition.startsWith("start_time>")) "start_time DESC" else "start_time"
                val changed = driver.execute(null, "$prefix WHERE rowid IN (SELECT rowid FROM epg_programme WHERE ($scope) AND ($condition) ORDER BY $order LIMIT 1024)", 0).value
                if (changed <= 0) break
                updated = true
                paceEpgCacheHydration()
            }
            // Removing large text can leave slack inside occupied pages even
            // when auto_vacuum has already reclaimed every whole free page.
            if (update != null && updated && usedBytes() > target &&
                (storagePolicy?.usableBytes?.invoke() ?: Long.MAX_VALUE) / 3 > usedBytes()) {
                checkpoint()
                runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)"); driver.execute(null,"VACUUM",0) }
            }
            if (usedBytes() <= target) return
        }
        // Shortened cells may leave unused space inside live SQLite pages,
        // which freelist_count cannot see. Compact before discarding live titles.
        if (usedBytes()>target) {
            checkpoint()
            runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)"); driver.execute(null,"VACUUM",0) }
        }
        check(usedBytes() <= target) { "EPG index exceeded its storage budget" }
    }

    fun sourceIds(): Set<String> = driver.executeQuery(null,"SELECT playlist_id FROM epg_cache",{c->
        val result=hashSetOf<String>();while(c.next().value)c.getString(0)?.let(result::add);QueryResult.Value(result)
    },0).value

    fun removeChannels(source: String, keys: Set<String>) {
        keys.chunked(200).forEach { part ->
            driver.execute(null,"DELETE FROM epg_programme WHERE playlist_id=? AND channel_id IN (${part.joinToString(","){"?"}})",part.size+1) {
                bindString(0,source);part.forEachIndexed {i,key->bindString(i+1,key)}
            }
        }
    }

    /** Runs with the repository import lock, never concurrently with a generation writer. */
    fun maintainStorage(activeSources: Set<String>, checkpoint: () -> Unit = {}): Boolean {
        val policy = storagePolicy ?: return false
        val now = policy.clock()
        val sources = driver.executeQuery(null, "SELECT playlist_id FROM epg_cache", { c ->
            val result = arrayListOf<String>(); while(c.next().value) c.getString(0)?.let(result::add); QueryResult.Value(result)
        }, 0).value
        var changed = changeInBatches(null,"NOT EXISTS (SELECT 1 FROM epg_cache WHERE epg_cache.playlist_id=epg_programme.playlist_id AND epg_cache.active_batch=epg_programme.batch_id)",checkpoint=checkpoint)
        for (source in sources) {
            checkpoint()
            val old = snapshot(source) ?: continue
            // Source ids are SHA-256, not SQL obtained from playlists.
            check(source.all { it in "0123456789abcdef" })
            val predicate = if (source !in activeSources) "playlist_id='$source'" else "playlist_id='$source' AND batch_id!=${old.generation}"
            changed += changeInBatches(null, predicate, checkpoint = checkpoint)
        }
        changed += changeInBatches(null, "(start_time<${now-90L*86_400_000L} AND stop_time<${now-90L*86_400_000L}) OR start_time>${now+14L*86_400_000L}", checkpoint=checkpoint)
        changed += changeInBatches("description=NULL", "description IS NOT NULL AND (start_time<${now-12*3_600_000L} OR start_time>${now+12*3_600_000L})", checkpoint=checkpoint)
        val budget=minOf(policy.maxBytes,(policy.usableBytes()/4).coerceAtLeast(2L*1024*1024))
        val freeBeforeBudget=scalar("PRAGMA freelist_count")*scalar("PRAGMA page_size")
        if ((freeBeforeBudget>16L*1024*1024 || (changed>0 && usedBytes()>budget)) &&
            policy.usableBytes()/3>usedBytes()) {
            checkpoint()
            runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)"); driver.execute(null,"VACUUM",0) }
        }
        val budgetTrimmed=usedBytes()>budget
        if (budgetTrimmed) { trimBudget(budget, now, checkpoint=checkpoint); changed++ }
        for (source in sources) {
            val old = snapshot(source) ?: continue
            if (source !in activeSources) {
                driver.execute(null,"DELETE FROM epg_cache WHERE playlist_id=?",1){bindString(0,source)}
                driver.execute(null,"DELETE FROM epg_index_channel WHERE source=?",1){bindString(0,source)}
                continue
            }
            val keys=q.selectDistinctProgrammeChannelIds(source,old.generation).executeAsList()
            driver.execute(null,"DELETE FROM epg_index_channel WHERE source=?",1){bindString(0,source)}
            database.transaction {
                writeKeys(source,old.generation,keys)
                q.upsertEpgCacheMeta(source,"",old.fetchedAt.takeIf{it>0},if(budgetTrimmed) "" else old.signature,
                    old.retryAt.takeIf{it>now}?.let{"$it:import"},keys.size.toLong(),q.countEpgProgrammes(source,old.generation).executeAsOne(),old.generation)
            }
        }
        // DELETE alone leaves a large SQLite file behind. Reclaim it off the UI thread.
        val page=scalar("PRAGMA page_size")
        if (scalar("PRAGMA freelist_count")*page > minOf(16L*1024*1024,budget/4)) {
            checkpoint()
            runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)"); driver.execute(null,"VACUUM",0) }
        }
        runCatching { scalar("PRAGMA wal_checkpoint(TRUNCATE)") }
        return changed>0
    }

    fun confirmUnchanged(source: String, signature: String, now: Long) {
        val old=snapshot(source) ?: return
        q.upsertEpgCacheMeta(source,"",now,signature,null,old.channels,old.rows,old.generation)
    }

    /** XMLTV permits an omitted stop. Infer it from the next indexed start,
     * independently of file order, with a bounded fallback for the final row. */
    fun normalizeStopTimes(source: String, generation: Long, checkpoint: () -> Unit = {}) {
        var afterRow = 0L
        while (true) {
            checkpoint()
            val ids = driver.executeQuery(null,
                "SELECT rowid FROM epg_programme WHERE playlist_id=? AND batch_id=? AND rowid>? AND stop_time<=start_time ORDER BY rowid LIMIT 512",
                { cursor ->
                    val result = arrayListOf<Long>()
                    while (cursor.next().value) cursor.getLong(0)?.let(result::add)
                    QueryResult.Value(result)
                }, 3) { bindString(0, source); bindLong(1, generation); bindLong(2, afterRow) }.value
            if (ids.isEmpty()) break
            afterRow = ids.last()
            database.transaction {
                driver.execute(null, """
                    UPDATE epg_programme SET stop_time=COALESCE(
                        (SELECT MIN(following.start_time) FROM epg_programme following
                         WHERE following.playlist_id=epg_programme.playlist_id AND following.batch_id=epg_programme.batch_id
                         AND following.channel_id=epg_programme.channel_id AND following.start_time>epg_programme.start_time),
                        start_time+1800000)
                    WHERE rowid IN (${ids.joinToString(",") { "?" }})
                """.trimIndent(), ids.size) {
                    ids.forEachIndexed { index, row -> bindLong(index, row) }
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
                        SELECT playlist_id,channel_id,start_time,stop_time,title,lower_title,
                            ${if(storagePolicy==null) "description" else "CASE WHEN start_time BETWEEN ${now-12*3_600_000L} AND ${now+12*3_600_000L} THEN substr(description,1,6000) ELSE NULL END"},category,episode_num,?
                        FROM epg_programme WHERE playlist_id=? AND batch_id=? AND channel_id=? AND start_time<? AND stop_time>?
                    """.trimIndent(), 6) {
                        bindLong(0, generation); bindString(1, source); bindLong(2, previous); bindString(3, key)
                        bindLong(4, now); bindLong(5, now - 90L * 86_400_000L)
                    }
                }
                if(storagePolicy!=null && index%16==0) trimBudget(storagePolicy.maxBytes*2,now,"playlist_id='$source' AND batch_id=$generation")
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
