package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.database.Archive_capability as DbCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistence for the per-channel archive capability cache. */
interface ArchiveCapabilityStore {
    suspend fun get(channelId: String): ArchiveCapability?
    suspend fun getByPlaylist(playlistId: String): List<ArchiveCapability>
    suspend fun upsert(capability: ArchiveCapability)
    suspend fun deleteByPlaylist(playlistId: String)
}

class SqlDelightArchiveCapabilityStore(
    private val database: TvNextDatabase,
) : ArchiveCapabilityStore {

    private val queries = database.archiveCapabilityQueries

    override suspend fun get(channelId: String): ArchiveCapability? = withContext(Dispatchers.Default) {
        queries.selectArchiveCapability(channelId).executeAsOneOrNull()?.let(::toDomain)
    }

    override suspend fun getByPlaylist(playlistId: String): List<ArchiveCapability> =
        withContext(Dispatchers.Default) {
            queries.selectArchiveCapabilitiesByPlaylist(playlistId).executeAsList().map(::toDomain)
        }

    override suspend fun upsert(capability: ArchiveCapability) = withContext(Dispatchers.Default) {
        queries.upsertArchiveCapability(
            channel_id = capability.channelId,
            status = capability.status.name,
            declared_days = capability.declaredDays,
            measured_ms = capability.measuredMs,
            last_probed_at = capability.lastProbedAt,
            last_error = capability.lastError,
        )
        Unit
    }

    override suspend fun deleteByPlaylist(playlistId: String) = withContext(Dispatchers.Default) {
        queries.deleteArchiveCapabilitiesByPlaylist(playlistId)
        Unit
    }

    private fun toDomain(row: DbCapability): ArchiveCapability = ArchiveCapability(
        channelId = row.channel_id,
        status = ArchiveStatus.fromDb(row.status),
        declaredDays = row.declared_days,
        measuredMs = row.measured_ms,
        lastProbedAt = row.last_probed_at,
        lastError = row.last_error,
    )
}
