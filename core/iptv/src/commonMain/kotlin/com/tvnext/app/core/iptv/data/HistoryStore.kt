package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.tvnext.app.core.database.History as DbHistory
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.WatchContentType
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class HistoryEntry(
    val itemId: String,
    val contentType: WatchContentType,
    val watchedAt: Long,
)

interface HistoryStore {
    suspend fun add(itemId: String, contentType: WatchContentType, watchedAt: Long)
    suspend fun getRecent(limit: Int): List<HistoryEntry>
    suspend fun getRecentByType(contentType: WatchContentType, limit: Int): List<HistoryEntry>
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>
    suspend fun clear()
}

class SqlDelightHistoryStore(
    private val database: TvNextDatabase,
) : HistoryStore {

    private val queries = database.historyQueries

    override suspend fun add(itemId: String, contentType: WatchContentType, watchedAt: Long) =
        withContext(Dispatchers.Default) {
            database.transaction {
                queries.insertHistory(itemId, WatchContentType.toDb(contentType), watchedAt)
                queries.deleteOldestHistory(MAX_HISTORY.toLong())
            }
        }

    override suspend fun getRecent(limit: Int): List<HistoryEntry> = withContext(Dispatchers.Default) {
        queries.selectRecentHistory(limit.toLong()).executeAsList().map(::toDomain)
    }

    override suspend fun getRecentByType(contentType: WatchContentType, limit: Int): List<HistoryEntry> =
        withContext(Dispatchers.Default) {
            queries.selectRecentHistoryByType(WatchContentType.toDb(contentType), limit.toLong())
                .executeAsList()
                .map(::toDomain)
        }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
        queries.selectRecentHistory(limit.toLong()).asFlow()
            .mapToList(EmptyCoroutineContext)
            .map { rows -> rows.map(::toDomain) }
            .flowOn(Dispatchers.Default)

    override suspend fun clear() {
        withContext(Dispatchers.Default) {
            queries.deleteAllHistory()
        }
    }

    private fun toDomain(row: DbHistory): HistoryEntry = HistoryEntry(
        itemId = row.item_id,
        contentType = WatchContentType.fromDb(row.content_type),
        watchedAt = row.watched_at,
    )

    private companion object {
        const val MAX_HISTORY = 20
    }
}
