package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.database.Scheduled_programme as DbScheduled
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.coroutines.EmptyCoroutineContext

/** A future programme the user asked to be reminded about. */
data class ScheduledProgramme(
    val id: Long = 0L,
    val playlistId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val createdAt: Long,
) {
    /** Stable identity used by the UI (also the DB unique key without the id). */
    val key: String get() = "$playlistId|$channelId|$startTime"
}

enum class ScheduleResult {
    SCHEDULED,
    ALREADY_SCHEDULED,
    /** The programme already ended — nothing is scheduled. */
    REJECTED_PAST,
}

interface ScheduledRepository {

    /**
     * Schedules [programme] on [channel]. Returns [ScheduleResult.REJECTED_PAST]
     * when the programme already ended (no stale reminders are ever created).
     */
    suspend fun schedule(channel: Channel, programme: EpgProgramme, now: Long): ScheduleResult

    /** Removes the schedule (if any) for this channel+programme. */
    suspend fun unschedule(channelId: String, playlistId: String, programmeStart: Long)

    suspend fun isScheduled(playlistId: String, channelId: String, programmeStart: Long): Boolean

    suspend fun getAll(): List<ScheduledProgramme>

    /** Live list of scheduled programmes (drives the reminder UI). */
    fun observeAll(): Flow<List<ScheduledProgramme>>

    /** Housekeeping: drops reminders whose programme already ended. */
    suspend fun deleteExpired(now: Long)

    /** Removes every reminder of a playlist (used when the playlist is deleted). */
    suspend fun deleteByPlaylist(playlistId: String)
}

class SqlDelightScheduledRepository(
    private val database: TvNextDatabase,
) : ScheduledRepository {

    private val queries = database.scheduledQueries

    override suspend fun schedule(channel: Channel, programme: EpgProgramme, now: Long): ScheduleResult =
        withContext(Dispatchers.Default) {
            if (programme.stop <= now) return@withContext ScheduleResult.REJECTED_PAST
            val exists = queries.selectScheduled(channel.playlistId, channel.id, programme.start)
                .executeAsOneOrNull() != null
            if (exists) return@withContext ScheduleResult.ALREADY_SCHEDULED
            queries.insertScheduled(
                playlist_id = channel.playlistId,
                channel_id = channel.id,
                channel_name = channel.name,
                title = programme.title,
                start_time = programme.start,
                end_time = programme.stop,
                created_at = now,
            )
            ScheduleResult.SCHEDULED
        }

    override suspend fun unschedule(channelId: String, playlistId: String, programmeStart: Long) =
        withContext(Dispatchers.Default) {
            queries.deleteScheduled(playlistId, channelId, programmeStart)
            Unit
        }

    override suspend fun isScheduled(playlistId: String, channelId: String, programmeStart: Long): Boolean =
        withContext(Dispatchers.Default) {
            queries.selectScheduled(playlistId, channelId, programmeStart).executeAsOneOrNull() != null
        }

    override suspend fun getAll(): List<ScheduledProgramme> = withContext(Dispatchers.Default) {
        queries.selectAllScheduled().executeAsList().map(::toDomain)
    }

    override fun observeAll(): Flow<List<ScheduledProgramme>> =
        queries.selectAllScheduled().asFlow()
            .mapToList(EmptyCoroutineContext)
            .map { rows -> rows.map(::toDomain) }
            .flowOn(Dispatchers.Default)

    override suspend fun deleteExpired(now: Long) = withContext(Dispatchers.Default) {
        queries.deleteExpiredScheduled(now)
        Unit
    }

    override suspend fun deleteByPlaylist(playlistId: String) = withContext(Dispatchers.Default) {
        queries.deleteScheduledByPlaylist(playlistId)
        Unit
    }

    private fun toDomain(row: DbScheduled): ScheduledProgramme = ScheduledProgramme(
        id = row.id,
        playlistId = row.playlist_id,
        channelId = row.channel_id,
        channelName = row.channel_name,
        title = row.title,
        startTime = row.start_time,
        endTime = row.end_time,
        createdAt = row.created_at,
    )
}
