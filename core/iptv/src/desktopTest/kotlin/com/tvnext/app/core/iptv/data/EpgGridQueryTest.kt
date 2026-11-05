package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.xmltv.EpgChannel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TV Guide grid query correctness after the large-EPG optimisation: duplicate
 * rows are collapsed in Kotlin, range boundaries are respected, and playlists
 * never bleed into each other.
 */
class EpgGridQueryTest {

    private fun store(): Pair<SqlDelightEpgStore, com.tvnext.app.core.database.TvNextDatabase> {
        val database = createTestDatabase()
        return SqlDelightEpgStore(database) to database
    }

    @Test
    fun duplicateRowsAreCollapsedByChannelAndStart() = runBlocking {
        val (store, _) = store()
        // Real XMLTV sources can list the same programme twice.
        val programmes = listOf(
            EpgProgramme(channelId = "epg1", start = 1_000, stop = 2_000, title = "Дубль"),
            EpgProgramme(channelId = "epg1", start = 1_000, stop = 2_000, title = "Дубль"),
            EpgProgramme(channelId = "epg1", start = 2_000, stop = 3_000, title = "Дальше"),
        )
        store.replaceAll("pl1", listOf(EpgChannel(id = "epg1", displayNames = listOf("Канал"))), programmes)

        val rows = store.getProgrammesInRangeByPlaylist("pl1", from = 0, to = 10_000)
        assertEquals(2, rows.size, "duplicate rows must collapse to one programme")
        assertEquals(listOf(1_000L, 2_000L), rows.map { it.start })
    }

    @Test
    fun rangeBoundariesAreRespected() = runBlocking {
        val (store, _) = store()
        val programmes = listOf(
            // Overlaps [500, 1500) — partially inside [1000, 2000): must match.
            EpgProgramme(channelId = "epg1", start = 500, stop = 1_500, title = "Пересекает начало"),
            // Fully inside.
            EpgProgramme(channelId = "epg1", start = 1_200, stop = 1_800, title = "Внутри"),
            // Ends exactly at from → outside.
            EpgProgramme(channelId = "epg1", start = 100, stop = 1_000, title = "Закончился ровно на границе"),
            // Starts exactly at to → outside.
            EpgProgramme(channelId = "epg1", start = 2_000, stop = 3_000, title = "Начинается ровно на границе"),
            // Overlaps the END boundary: starts before to, stops after.
            EpgProgramme(channelId = "epg1", start = 1_900, stop = 2_100, title = "Пересекает конец"),
        )
        store.replaceAll("pl1", listOf(EpgChannel(id = "epg1", displayNames = listOf("Канал"))), programmes)

        val rows = store.getProgrammesInRangeByPlaylist("pl1", from = 1_000, to = 2_000)
        assertEquals(3, rows.size, "only programmes overlapping [from, to) are returned")
        assertEquals(
            listOf("Пересекает начало", "Внутри", "Пересекает конец"),
            rows.map { it.title },
        )
    }

    @Test
    fun rangeQueryIsScopedPerPlaylist() = runBlocking {
        val (store, _) = store()
        val programmesA = listOf(
            EpgProgramme(channelId = "epgA", start = 1_000, stop = 2_000, title = "Программа A"),
        )
        val programmesB = listOf(
            EpgProgramme(channelId = "epgA", start = 1_000, stop = 2_000, title = "Программа B"),
        )
        store.replaceAll("pl-a", listOf(EpgChannel(id = "epgA", displayNames = listOf("A"))), programmesA)
        store.replaceAll("pl-b", listOf(EpgChannel(id = "epgA", displayNames = listOf("B"))), programmesB)

        val rowsA = store.getProgrammesInRangeByPlaylist("pl-a", from = 0, to = 10_000)
        val rowsB = store.getProgrammesInRangeByPlaylist("pl-b", from = 0, to = 10_000)
        assertEquals(listOf("Программа A"), rowsA.map { it.title })
        assertEquals(listOf("Программа B"), rowsB.map { it.title })
        // Identical tvg-ids in different playlists stay separate.
        assertTrue(rowsA.single().channelId == rowsB.single().channelId)
    }

    @Test
    fun emptyRangeYieldsNothing() = runBlocking {
        val (store, _) = store()
        store.replaceAll(
            "pl1",
            listOf(EpgChannel(id = "epg1", displayNames = listOf("Канал"))),
            listOf(EpgProgramme(channelId = "epg1", start = 1_000, stop = 2_000, title = "Х")) ,
        )
        val rows = store.getProgrammesInRangeByPlaylist("pl1", from = 5_000, to = 6_000)
        assertTrue(rows.isEmpty())
    }
}
