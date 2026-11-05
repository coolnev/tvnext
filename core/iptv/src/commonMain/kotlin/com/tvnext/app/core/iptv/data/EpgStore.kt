package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.database.Epg_cache as DbEpgCache
import com.tvnext.app.core.database.Epg_channel as DbEpgChannel
import com.tvnext.app.core.database.Epg_programme as DbEpgProgramme
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.xmltv.EpgChannel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EpgCacheMeta(
    val url: String = "",
    val fetchedAt: Long? = null,
    val source: String = "",
    val lastError: String? = null,
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
)

/**
 * EPG (XMLTV) cache, scoped per playlist: every playlist has its own channels,
 * programmes and cache metadata, so identical tvg-ids in different playlists
 * never collide.
 *
 * Rows are versioned by [batch]. A refresh writes the new rows with a fresh
 * batch id while readers keep seeing the previous (active) batch; only
 * [commitReplace] makes the new batch visible and drops the old rows. This is
 * what keeps the TV Guide / "now playing" populated during a multi-minute
 * import and lets an aborted import leave the existing cache untouched.
 */
interface EpgStore {
    suspend fun replaceAll(playlistId: String, channels: List<EpgChannel>, programmes: List<EpgProgramme>)
    suspend fun deleteByPlaylist(playlistId: String)

    /**
     * Two-phase streaming replace (large EPG imports must not hold the whole
     * document in memory):
     * 1. [beginReplace] allocates and returns a fresh batch id for the
     *    playlist (old rows are NOT touched);
     * 2. the caller feeds parsed rows in batches via [insertChannels] /
     *    [insertProgrammes] (each batch commits separately so competing
     *    writers are not starved);
     * 3. [commitReplace] atomically switches the active batch to the new one
     *    and deletes every other batch. On failure call [abortReplace] to drop
     *    only the partial new batch.
     */
    suspend fun beginReplace(playlistId: String): Long
    suspend fun insertChannels(playlistId: String, batch: Long, channels: List<EpgChannel>)
    suspend fun insertProgrammes(playlistId: String, batch: Long, programmes: List<EpgProgramme>)
    suspend fun commitReplace(playlistId: String, batch: Long)
    suspend fun abortReplace(playlistId: String, batch: Long)

    /**
     * Makes [batch] the visible one immediately, without deleting other rows.
     * Used on a fresh import (no usable cache): the new rows then appear in
     * the UI progressively as they are parsed instead of only at the very end.
     */
    suspend fun setActiveBatch(playlistId: String, batch: Long)

    suspend fun getChannels(playlistId: String): List<EpgChannel>
    suspend fun getCurrentProgramme(playlistId: String, channelId: String, at: Long): EpgProgramme?
    suspend fun getNextProgramme(playlistId: String, channelId: String, at: Long): EpgProgramme?
    suspend fun getUpcomingProgrammes(playlistId: String, channelId: String, from: Long, limit: Int): List<EpgProgramme>

    /** Programmes of one channel overlapping [from, to] (TV Guide row). */
    suspend fun getProgrammesInRange(playlistId: String, channelId: String, from: Long, to: Long): List<EpgProgramme>

    /** Programmes of the whole playlist overlapping [from, to], one query (TV Guide). */
    suspend fun getProgrammesInRangeByPlaylist(playlistId: String, from: Long, to: Long): List<EpgProgramme>

    /**
     * Programmes overlapping [from, to] for only the supplied EPG channel ids.
     * This is the low-RAM TV Guide path: SQLite uses the channel/time index
     * instead of materialising an entire day's EPG for the whole playlist.
     */
    suspend fun getProgrammesInRangeByChannels(
        playlistId: String,
        channelIds: Collection<String>,
        from: Long,
        to: Long,
    ): List<EpgProgramme>

    /** Title search within a playlist (global Search tab). */
    suspend fun searchProgrammesByTitle(playlistId: String, query: String, limit: Int): List<EpgProgramme>

    /** All programmes on air at [at] for a whole playlist (one query). */
    suspend fun getCurrentProgrammes(playlistId: String, at: Long): List<EpgProgramme>

    /** Current programmes restricted to the supplied EPG channel ids. */
    suspend fun getCurrentProgrammes(playlistId: String, channelIds: Collection<String>, at: Long): List<EpgProgramme>

    suspend fun getCacheMeta(playlistId: String): EpgCacheMeta?
    suspend fun saveCacheMeta(playlistId: String, meta: EpgCacheMeta)
    suspend fun getChannelCount(playlistId: String): Int
    suspend fun getProgrammeCount(playlistId: String): Int

    /** Recreates missing XMLTV channel rows from existing programme channel ids. */
    suspend fun restoreChannelsFromProgrammes(playlistId: String): Int

    /**
     * Removes programmes that ended before [oldCutoff] (archive retention) —
     * keeps the historical cache bounded while retaining what archive playback
     * needs. Only the active batch is pruned.
     */
    suspend fun prune(playlistId: String, oldCutoff: Long)
}

class SqlDelightEpgStore(
    private val database: TvNextDatabase,
    /**
     * Desktop fast-import mode: the programme indexes are dropped for the
     * duration of a bulk import (~2.5x faster inserts) and rebuilt before the
     * batch is committed. Readers of the previous batch then fall back to a
     * table scan during the import — acceptable on a desktop, but NOT on
     * Android TV where the UI constantly resolves "now playing", so Android
     * keeps the indexes in place.
     */
    private val dropIndexesDuringImport: Boolean = false,
) : EpgStore {

    private val queries = database.epgQueries

    /** Cached active batch per playlist (a row-set switch during an import). */
    private val activeBatches = ConcurrentHashMap<String, Long>()

    override suspend fun replaceAll(
        playlistId: String,
        channels: List<EpgChannel>,
        programmes: List<EpgProgramme>,
    ) = withContext(Dispatchers.IO) {
        val batch = beginReplace(playlistId)
        insertChannels(playlistId, batch, channels)
        // Insert programmes in BATCHES with a commit between them. A single
        // 1.6M-row transaction holds the write lock for minutes — every other
        // writer (settings, history, resume position) would fail with
        // SQLITE_BUSY and the whole app would "lag" during an EPG import.
        // Batched commits + yields let competing writes interleave.
        programmes.chunked(INSERT_BATCH_SIZE).forEach { chunk ->
            insertProgrammes(playlistId, batch, chunk)
            kotlinx.coroutines.yield()
        }
        commitReplace(playlistId, batch)
    }

    /** Native Android importer sink: called from the IO thread, never from the UI thread. */
    internal fun insertNativeChannels(
        playlistId: String,
        batch: Long,
        ids: Array<String>,
        names: Array<String>,
        icons: Array<String?>,
    ) {
        if (ids.isEmpty()) return
        retryDatabaseBlocking {
            database.transaction {
                ids.indices.forEach { i ->
                    queries.insertEpgChannel(
                        playlist_id = playlistId,
                        id = ids[i],
                        display_names = names[i],
                        icon_url = icons[i],
                        batch_id = batch,
                    )
                }
            }
        }
    }

    /** Native Android importer sink: no EpgProgramme allocation; bind rows directly. */
    internal fun insertNativeProgrammes(
        playlistId: String,
        batch: Long,
        channelIds: Array<String>,
        starts: LongArray,
        stops: LongArray,
        titles: Array<String>,
        descriptions: Array<String?>,
        categories: Array<String?>,
        episodeNums: Array<String?>,
    ) {
        if (channelIds.isEmpty()) return
        retryDatabaseBlocking {
            database.transaction {
                channelIds.indices.forEach { i ->
                    val title = titles[i].take(MAX_TITLE_LENGTH)
                    queries.insertEpgProgramme(
                        playlist_id = playlistId,
                        channel_id = channelIds[i],
                        start_time = starts[i],
                        stop_time = stops[i],
                        title = title,
                        lower_title = title.lowercase().take(MAX_TITLE_LENGTH),
                        description = (if (com.tvnext.app.core.common.platform.isAndroidPlatform()) descriptions[i] else descriptions[i]?.take(MAX_DESCRIPTION_LENGTH)),
                        category = categories[i]?.take(MAX_CATEGORY_LENGTH),
                        episode_num = episodeNums[i]?.take(MAX_CATEGORY_LENGTH),
                        batch_id = batch,
                    )
                }
            }
        }
    }

    override suspend fun beginReplace(playlistId: String): Long = withContext(Dispatchers.IO) {
        if (dropIndexesDuringImport) dropProgrammeIndexes()
        try {
            retryDatabase {
                queries.nextEpgBatch(playlistId, playlistId).executeAsOne()
            }
        } catch (e: Exception) {
            // Never leave the indexes dropped when the batch allocation fails.
            if (dropIndexesDuringImport) runCatching { createProgrammeIndexes() }
            throw e
        }
    }

    override suspend fun insertChannels(
        playlistId: String,
        batch: Long,
        channels: List<EpgChannel>,
    ) = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext
        retryDatabase {
            database.transaction {
                channels.forEach { channel ->
                    queries.insertEpgChannel(
                        playlist_id = playlistId,
                        id = channel.id,
                        display_names = channel.displayNames.joinToString(DISPLAY_NAME_SEPARATOR),
                        icon_url = channel.iconUrl,
                        batch_id = batch,
                    )
                }
            }
        }
    }

    override suspend fun insertProgrammes(
        playlistId: String,
        batch: Long,
        programmes: List<EpgProgramme>,
    ) = withContext(Dispatchers.IO) {
        if (programmes.isEmpty()) return@withContext
        retryDatabase {
            database.transaction {
                programmes.forEach { programme ->
                    queries.insertEpgProgramme(
                        playlist_id = playlistId,
                        channel_id = programme.channelId,
                        start_time = programme.start,
                        stop_time = programme.stop,
                        title = programme.title.take(MAX_TITLE_LENGTH),
                        lower_title = programme.title.lowercase().take(MAX_TITLE_LENGTH),
                        description = (if (com.tvnext.app.core.common.platform.isAndroidPlatform()) programme.description else programme.description?.take(MAX_DESCRIPTION_LENGTH)),
                        category = programme.category?.take(MAX_CATEGORY_LENGTH),
                        episode_num = programme.episodeNum?.take(MAX_CATEGORY_LENGTH),
                        batch_id = batch,
                    )
                }
            }
        }
    }

    override suspend fun commitReplace(playlistId: String, batch: Long) = withContext(Dispatchers.IO) {
        retryDatabase {
            database.transaction {
                // Drop every other (old / partial) batch and switch the active
                // one — one transaction, so readers never see a mix.
                queries.deleteEpgOtherProgrammes(playlistId, batch)
                queries.deleteEpgOtherChannels(playlistId, batch)
                setActiveBatchLocked(playlistId, batch)
            }
        }
        // Rebuild the indexes over ONLY the surviving (committed) rows.
        if (dropIndexesDuringImport) createProgrammeIndexes()
        // A large replace writes hundreds of MB into SQLite's WAL and then
        // deletes the previous batch. Without an explicit checkpoint the WAL
        // can remain close to the peak import size and Android may report it
        // as app cache. Truncate it after the old batch is gone.
        runCatching { com.tvnext.app.core.database.checkpointWal() }
        Unit
    }

    override suspend fun abortReplace(playlistId: String, batch: Long) = withContext(Dispatchers.IO) {
        retryDatabase {
            database.transaction {
                queries.deleteEpgBatchProgrammes(playlistId, batch)
                queries.deleteEpgBatchChannels(playlistId, batch)
            }
        }
        // A fresh import that was aborted must not leave a half-empty batch
        // visible: fall back to "no data" so the next refresh starts clean.
        if (activeBatch(playlistId) == batch) {
            withContext(Dispatchers.IO) { setActiveBatchLocked(playlistId, 0L) }
        }
        if (dropIndexesDuringImport) createProgrammeIndexes()
    }

    private fun dropProgrammeIndexes() {
        com.tvnext.app.core.database.executeRawSql("DROP INDEX IF EXISTS idx_epg_programme_channel_start")
        com.tvnext.app.core.database.executeRawSql("DROP INDEX IF EXISTS idx_epg_programme_playlist_start")
        com.tvnext.app.core.database.executeRawSql("DROP INDEX IF EXISTS idx_epg_programme_lower_title")
    }

    private fun createProgrammeIndexes() {
        com.tvnext.app.core.database.executeRawSql(
            "CREATE INDEX IF NOT EXISTS idx_epg_programme_channel_start " +
                "ON epg_programme(playlist_id, batch_id, channel_id, start_time)",
        )
        com.tvnext.app.core.database.executeRawSql(
            "CREATE INDEX IF NOT EXISTS idx_epg_programme_playlist_start " +
                "ON epg_programme(playlist_id, batch_id, start_time, stop_time)",
        )
        com.tvnext.app.core.database.executeRawSql(
            "CREATE INDEX IF NOT EXISTS idx_epg_programme_lower_title " +
                "ON epg_programme(playlist_id, batch_id, lower_title)",
        )
    }

    override suspend fun setActiveBatch(playlistId: String, batch: Long) = withContext(Dispatchers.IO) {
        setActiveBatchLocked(playlistId, batch)
    }

    /** Must run inside a [database.transaction] (or be called from a transaction). */
    private fun setActiveBatchLocked(playlistId: String, batch: Long) {
        queries.updateEpgActiveBatch(active_batch = batch, playlist_id = playlistId)
        if (queries.selectEpgActiveBatch(playlistId).executeAsOneOrNull() == null) {
            // No cache metadata row yet (fresh import): create a minimal one
            // holding just the active batch. INSERT OR REPLACE is safe here
            // because the row was just shown to be missing.
            queries.insertEpgActiveBatch(playlist_id = playlistId, active_batch = batch)
        }
        activeBatches[playlistId] = batch
    }

    override suspend fun deleteByPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        activeBatches.remove(playlistId)
        retryDatabase {
            database.transaction {
                queries.deleteEpgProgrammesByPlaylist(playlistId)
                queries.deleteEpgChannelsByPlaylist(playlistId)
                queries.deleteEpgCacheByPlaylist(playlistId)
            }
        }
    }

    override suspend fun getChannels(playlistId: String): List<EpgChannel> = withContext(Dispatchers.IO) {
        queries.selectEpgChannels(playlistId, activeBatch(playlistId)).executeAsList().map(::toDomainChannel)
    }

    override suspend fun getCurrentProgramme(playlistId: String, channelId: String, at: Long): EpgProgramme? =
        withContext(Dispatchers.IO) {
            queries.selectCurrentProgramme(playlistId, channelId, activeBatch(playlistId), at, at)
                .executeAsOneOrNull()
                ?.let(::toDomainProgramme)
        }

    override suspend fun getNextProgramme(playlistId: String, channelId: String, at: Long): EpgProgramme? =
        withContext(Dispatchers.IO) {
            queries.selectNextProgramme(playlistId, channelId, activeBatch(playlistId), at)
                .executeAsOneOrNull()
                ?.let(::toDomainProgramme)
        }

    override suspend fun getUpcomingProgrammes(
        playlistId: String,
        channelId: String,
        from: Long,
        limit: Int,
    ): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            queries.selectUpcomingProgrammes(playlistId, channelId, activeBatch(playlistId), from, limit.toLong())
                .executeAsList()
                .map(::toDomainProgramme)
        }

    override suspend fun getProgrammesInRange(
        playlistId: String,
        channelId: String,
        from: Long,
        to: Long,
    ): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            queries.selectProgrammesInRange(playlistId, channelId, activeBatch(playlistId), to, from)
                .executeAsList()
                .map(::toDomainProgramme)
        }

    override suspend fun getProgrammesInRangeByPlaylist(
        playlistId: String,
        from: Long,
        to: Long,
    ): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            // Duplicate rows are collapsed here (the SQL query has no DISTINCT
            // for large-EPG performance): one entry per (channel, start).
            val seen = HashSet<String>(2048)
            val result = ArrayList<EpgProgramme>()
            queries.selectProgrammesInRangeByPlaylist(playlistId, activeBatch(playlistId), to, from)
                .executeAsList()
                .forEach { row ->
                    if (seen.add("${row.channel_id}|${row.start_time}")) {
                        result.add(toDomainProgramme(row))
                    }
                }
            result
        }

    override suspend fun getProgrammesInRangeByChannels(
        playlistId: String,
        channelIds: Collection<String>,
        from: Long,
        to: Long,
    ): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            if (channelIds.isEmpty()) return@withContext emptyList()
            val seen = HashSet<String>(256)
            val result = ArrayList<EpgProgramme>()
            queries.selectProgrammesInRangeByChannels(
                playlistId,
                activeBatch(playlistId),
                channelIds.toList(),
                to,
                from,
            ).executeAsList().forEach { row ->
                // XMLTV providers sometimes repeat the same programme.
                if (seen.add("${row.channel_id}|${row.start_time}")) {
                    result.add(toDomainProgramme(row))
                }
            }
            result
        }

    override suspend fun searchProgrammesByTitle(
        playlistId: String,
        query: String,
        limit: Int,
    ): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            queries.selectProgrammesByTitle(playlistId, activeBatch(playlistId), query, limit.toLong())
                .executeAsList()
                .map(::toDomainProgramme)
        }

    override suspend fun getCurrentProgrammes(playlistId: String, at: Long): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            queries.selectNowPlaying(playlistId, activeBatch(playlistId), at, at).executeAsList().map(::toDomainProgramme)
        }

    override suspend fun getCurrentProgrammes(playlistId: String, channelIds: Collection<String>, at: Long): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            if (channelIds.isEmpty()) return@withContext emptyList()
            queries.selectNowPlayingByChannels(playlistId, activeBatch(playlistId), channelIds.toList(), at, at)
                .executeAsList()
                .map(::toDomainProgramme)
        }

    override suspend fun getCacheMeta(playlistId: String): EpgCacheMeta? = withContext(Dispatchers.IO) {
        queries.selectEpgCacheMeta(playlistId).executeAsOneOrNull()?.let(::toCacheMeta)
    }

    override suspend fun saveCacheMeta(playlistId: String, meta: EpgCacheMeta) = withContext(Dispatchers.IO) {
        retryDatabase {
            queries.upsertEpgCacheMeta(
                playlist_id = playlistId,
                url = meta.url,
                fetched_at = meta.fetchedAt,
                source = meta.source,
                last_error = meta.lastError,
                channel_count = meta.channelCount.toLong(),
                programme_count = meta.programmeCount.toLong(),
                active_batch = activeBatch(playlistId),
            )
            Unit
        }
    }

    override suspend fun getChannelCount(playlistId: String): Int = withContext(Dispatchers.IO) {
        queries.countEpgChannels(playlistId, activeBatch(playlistId)).executeAsOne().toInt()
    }

    override suspend fun getProgrammeCount(playlistId: String): Int = withContext(Dispatchers.IO) {
        queries.countEpgProgrammes(playlistId, activeBatch(playlistId)).executeAsOne().toInt()
    }

    override suspend fun restoreChannelsFromProgrammes(playlistId: String): Int =
        withContext(Dispatchers.IO) {
            val batch = activeBatch(playlistId)
            val ids = queries.selectDistinctProgrammeChannelIds(playlistId, batch).executeAsList()
            if (ids.isEmpty()) return@withContext 0
            database.transaction {
                ids.forEach { id ->
                    queries.insertEpgChannel(
                        playlist_id = playlistId,
                        id = id,
                        display_names = id,
                        icon_url = null,
                        batch_id = batch,
                    )
                }
            }
            ids.size
        }

    override suspend fun prune(playlistId: String, oldCutoff: Long) =
        withContext(Dispatchers.IO) {
            retryDatabase {
                queries.pruneOldEpgProgrammes(playlistId, oldCutoff, activeBatch(playlistId))
                Unit
            }
        }

    /**
     * The batch visible to readers for a playlist: the persisted active batch
     * (0 when the playlist was never imported). Cached in memory; re-read from
     * epg_cache only when unknown.
     */
    private fun activeBatch(playlistId: String): Long {
        activeBatches[playlistId]?.let { return it }
        val batch = queries.selectEpgActiveBatch(playlistId).executeAsOneOrNull() ?: 0L
        activeBatches[playlistId] = batch
        return batch
    }

    /**
     * Retries a write when SQLite reports a BUSY / locked database. A large
     * streaming import (vivamax ~1.4M rows) competes with concurrent writers
     * (e.g. a playlist being removed by the user at the same moment) — with WAL
     * + busy_timeout most conflicts resolve, but a short retry makes the import
     * resilient instead of failing with «ошибка базы данных».
     */
    /**
     * Native JNI callbacks are synchronous and cannot call the suspend retryDatabase()
     * directly. Bridge to the existing common retry implementation without introducing
     * JVM-only APIs into commonMain. The native parser already runs off the UI thread.
     */
    private fun <T> retryDatabaseBlocking(attempts: Int = 6, block: () -> T): T =
        kotlinx.coroutines.runBlocking {
            retryDatabase(attempts, block)
        }

    private suspend fun <T> retryDatabase(attempts: Int = 4, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val busy = msg.contains("database is locked", ignoreCase = true) ||
                    msg.contains("SQLITE_BUSY", ignoreCase = true) ||
                    msg.contains("disk I/O", ignoreCase = true)
                attempt++
                if (!busy || attempt >= attempts) throw e
                kotlinx.coroutines.delay(300L * attempt)
            }
        }
    }

    private fun toDomainChannel(row: DbEpgChannel): EpgChannel = EpgChannel(
        id = row.id,
        displayNames = row.display_names
            .split(DISPLAY_NAME_SEPARATOR)
            .filter { it.isNotEmpty() },
        iconUrl = row.icon_url,
    )

    private fun toDomainProgramme(row: DbEpgProgramme): EpgProgramme = EpgProgramme(
        channelId = row.channel_id,
        start = row.start_time,
        stop = row.stop_time,
        title = row.title,
        description = row.description,
        category = row.category,
        episodeNum = row.episode_num,
    )

    private fun toCacheMeta(row: DbEpgCache): EpgCacheMeta = EpgCacheMeta(
        url = row.url,
        fetchedAt = row.fetched_at,
        source = row.source,
        lastError = row.last_error,
        channelCount = row.channel_count.toInt(),
        programmeCount = row.programme_count.toInt(),
    )

    private companion object {
        const val DISPLAY_NAME_SEPARATOR = "\u001F"

        /**
         * Programmes per insert transaction (write lock is released between
         * batches). Kept small so the peak of a huge import holds a bounded
         * batch — on low-end Android TV boxes the OS silently kills the app
         * when memory pressure spikes.
         */
        const val INSERT_BATCH_SIZE = 2_000

        /**
         * Field caps: large EPGs (vivamax) carry descriptions up to ~16 KB.
         * Truncating bounds the resulting database size (multi-GB otherwise)
         * which on Android TV usually means "database or disk is full" during
         * the import. UI shows at most a few paragraphs anyway.
         */
        const val MAX_TITLE_LENGTH = 300
        const val MAX_DESCRIPTION_LENGTH = 512
        const val MAX_CATEGORY_LENGTH = 120
    }
}
