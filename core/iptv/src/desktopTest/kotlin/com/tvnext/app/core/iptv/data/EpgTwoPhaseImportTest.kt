package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.xmltv.EpgChannel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Two-phase EPG import: rows are written with a fresh batch id while readers
 * keep seeing the previous (active) batch. This is what keeps the TV Guide /
 * "now playing" populated during a long import and lets an aborted refresh
 * leave the existing cache intact.
 */
class EpgTwoPhaseImportTest {

    private fun channel(id: String = "epg1") = EpgChannel(id = id, displayNames = listOf("Канал 1"))

    private fun programme(id: String = "epg1", start: Long, stop: Long, title: String) =
        EpgProgramme(channelId = id, start = start, stop = stop, title = title)

    private fun store(): SqlDelightEpgStore = SqlDelightEpgStore(createTestDatabase())

    @Test
    fun freshImportBecomesVisibleImmediately() = runBlocking {
        val store = store()
        val batch = store.beginReplace("pl")
        assertEquals(1L, batch, "first batch is 1")

        // Fresh import (no previous cache): make the new batch visible right
        // away, so rows appear progressively while the parse is still running.
        store.setActiveBatch("pl", batch)
        store.insertChannels("pl", batch, listOf(channel()))
        store.insertProgrammes("pl", batch, listOf(programme(start = 100, stop = 200, title = "Новости")))

        val current = store.getCurrentProgramme("pl", "epg1", at = 150)
        assertNotNull(current, "fresh batch rows are visible before commit")
        assertEquals("Новости", current.title)

        store.commitReplace("pl", batch)
        assertEquals("Новости", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)
    }

    @Test
    fun updateKeepsOldCacheVisibleUntilCommit() = runBlocking {
        val store = store()
        // First import (batch 1), committed.
        val first = store.beginReplace("pl")
        store.setActiveBatch("pl", first)
        store.insertChannels("pl", first, listOf(channel()))
        store.insertProgrammes("pl", first, listOf(programme(start = 100, stop = 200, title = "Старое")))
        store.commitReplace("pl", first)
        assertEquals("Старое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)

        // Second import writes batch 2 WITHOUT touching the visible batch 1.
        val second = store.beginReplace("pl")
        assertEquals(2L, second, "second batch increments")
        store.insertChannels("pl", second, listOf(channel()))
        store.insertProgrammes("pl", second, listOf(programme(start = 100, stop = 200, title = "Новое")))

        // Readers still see the OLD data while the new batch is being written.
        assertEquals("Старое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)

        store.commitReplace("pl", second)
        assertEquals("Новое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)
        assertEquals(1, store.getProgrammeCount("pl"), "only the committed batch survives")
    }

    @Test
    fun abortLeavesOldCacheIntact() = runBlocking {
        val store = store()
        val first = store.beginReplace("pl")
        store.setActiveBatch("pl", first)
        store.insertChannels("pl", first, listOf(channel()))
        store.insertProgrammes("pl", first, listOf(programme(start = 100, stop = 200, title = "Старое")))
        store.commitReplace("pl", first)

        // A refresh fails half-way: its partial batch must not break anything.
        val second = store.beginReplace("pl")
        store.insertProgrammes("pl", second, listOf(programme(start = 300, stop = 400, title = "Частичное")))
        store.abortReplace("pl", second)

        assertEquals("Старое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)
        assertEquals(1, store.getProgrammeCount("pl"), "aborted rows are dropped, old cache intact")
        assertNull(store.getCurrentProgramme("pl", "epg1", at = 350), "partial new rows are gone")
    }

    @Test
    fun abortedFreshImportFallsBackToEmpty() = runBlocking {
        val store = store()
        val batch = store.beginReplace("pl")
        store.setActiveBatch("pl", batch)
        store.insertChannels("pl", batch, listOf(channel()))
        store.insertProgrammes("pl", batch, listOf(programme(start = 100, stop = 200, title = "Частичное")))

        store.abortReplace("pl", batch)
        assertEquals(0, store.getProgrammeCount("pl"), "no stale half-imported rows")
        assertNull(store.getCurrentProgramme("pl", "epg1", at = 150))
    }

    @Test
    fun replaceAllIsACleanTwoPhaseReplace() = runBlocking {
        val store = store()
        store.replaceAll(
            "pl",
            listOf(channel()),
            listOf(programme(start = 100, stop = 200, title = "Старое")),
        )
        assertEquals("Старое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)

        store.replaceAll(
            "pl",
            listOf(channel()),
            listOf(programme(start = 100, stop = 200, title = "Новое")),
        )
        assertEquals("Новое", store.getCurrentProgramme("pl", "epg1", at = 150)?.title)
        assertEquals(1, store.getProgrammeCount("pl"), "replace replaces, does not accumulate")
    }
}
