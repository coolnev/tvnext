package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.WatchContentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {

    private fun store() = SqlDelightHistoryStore(createTestDatabase())

    @Test
    fun addIsNewestFirst() = runBlocking {
        val store = store()
        store.add("c1", WatchContentType.LIVE, 1000L)
        store.add("c2", WatchContentType.LIVE, 2000L)
        store.add("c3", WatchContentType.VOD, 3000L)

        val recent = store.getRecent(10)
        assertEquals(listOf("c3", "c2", "c1"), recent.map { it.itemId })
    }

    @Test
    fun reAddMovesToTopWithoutDuplicates() = runBlocking {
        val store = store()
        store.add("c1", WatchContentType.LIVE, 1000L)
        store.add("c2", WatchContentType.LIVE, 2000L)
        store.add("c3", WatchContentType.LIVE, 3000L)
        store.add("c1", WatchContentType.LIVE, 4000L)

        val recent = store.getRecent(10)
        assertEquals(listOf("c1", "c3", "c2"), recent.map { it.itemId })
        assertEquals(3, recent.size)
    }

    @Test
    fun sameIdDifferentTypesAreSeparateEntries() = runBlocking {
        val store = store()
        store.add("item-1", WatchContentType.LIVE, 1000L)
        store.add("item-1", WatchContentType.VOD, 2000L)

        val recent = store.getRecent(10)
        assertEquals(2, recent.size)
        val byType = recent.groupBy { it.contentType }
        assertEquals(1, byType[WatchContentType.LIVE]?.size)
        assertEquals(1, byType[WatchContentType.VOD]?.size)
    }

    @Test
    fun getRecentByTypeFilters() = runBlocking {
        val store = store()
        store.add("c1", WatchContentType.LIVE, 1000L)
        store.add("m1", WatchContentType.VOD, 2000L)
        store.add("m2", WatchContentType.VOD, 3000L)

        val vod = store.getRecentByType(WatchContentType.VOD, 10)
        assertEquals(listOf("m2", "m1"), vod.map { it.itemId })

        val live = store.getRecentByType(WatchContentType.LIVE, 10)
        assertEquals(listOf("c1"), live.map { it.itemId })
    }

    @Test
    fun cappedAtTwenty() = runBlocking {
        val store = store()
        repeat(25) { index ->
            store.add("c$index", WatchContentType.LIVE, index * 1000L)
        }

        val recent = store.getRecent(100)
        assertEquals(20, recent.size)
        // The newest 20 (c24 down to c5) survive.
        assertEquals("c24", recent.first().itemId)
        assertEquals("c5", recent.last().itemId)
    }

    @Test
    fun getRecentRespectsLimit() = runBlocking {
        val store = store()
        repeat(10) { index -> store.add("c$index", WatchContentType.LIVE, index * 1000L) }

        assertEquals(3, store.getRecent(3).size)
        assertEquals(listOf("c9", "c8", "c7"), store.getRecent(3).map { it.itemId })
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val store = store()
        store.add("c1", WatchContentType.LIVE, 1000L)
        store.add("m1", WatchContentType.VOD, 2000L)
        store.clear()

        assertTrue(store.getRecent(10).isEmpty())
    }

    @Test
    fun repositoryOrderAndDedupWithClock() = runBlocking {
        val store = store()
        var tick = 1000L
        val repo = DefaultHistoryRepository(store, clock = { tick })

        repo.add("a", WatchContentType.LIVE); tick += 1000
        repo.add("b", WatchContentType.LIVE); tick += 1000
        repo.add("a", WatchContentType.LIVE); tick += 1000
        repo.add("c", WatchContentType.LIVE)

        val recent = repo.getRecent()
        assertEquals(listOf("c", "a", "b"), recent.map { it.itemId })
    }
}
