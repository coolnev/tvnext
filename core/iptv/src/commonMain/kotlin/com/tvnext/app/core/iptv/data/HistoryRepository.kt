package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.iptv.domain.WatchContentType
import kotlinx.coroutines.flow.Flow

/**
 * Recently watched content, typed by kind (LIVE channel / VOD item), persisted
 * across app restarts.
 *
 * Rules:
 *  - a new watch is added to the top;
 *  - re-watching moves the item to the top (no duplicates);
 *  - the list is capped at [MAX_HISTORY] entries;
 *  - ids are stable — content that disappeared from a playlist is simply
 *    filtered out by the UI, never crashes it.
 */
interface HistoryRepository {
    suspend fun add(itemId: String, contentType: WatchContentType)
    suspend fun getRecent(limit: Int = MAX_HISTORY): List<HistoryEntry>
    suspend fun getRecentByType(contentType: WatchContentType, limit: Int = MAX_HISTORY): List<HistoryEntry>
    fun observeRecent(limit: Int = MAX_HISTORY): Flow<List<HistoryEntry>>
    suspend fun clear()
    companion object {
        const val MAX_HISTORY = 20
    }
}

class DefaultHistoryRepository(
    private val store: HistoryStore,
    private val clock: () -> Long = ::currentTimeMillis,
) : HistoryRepository {

    override suspend fun add(itemId: String, contentType: WatchContentType) {
        store.add(itemId = itemId, contentType = contentType, watchedAt = clock())
    }

    override suspend fun getRecent(limit: Int): List<HistoryEntry> =
        store.getRecent(limit)

    override suspend fun getRecentByType(contentType: WatchContentType, limit: Int): List<HistoryEntry> =
        store.getRecentByType(contentType, limit)

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
        store.observeRecent(limit)

    override suspend fun clear() {
        store.clear()
    }
}
