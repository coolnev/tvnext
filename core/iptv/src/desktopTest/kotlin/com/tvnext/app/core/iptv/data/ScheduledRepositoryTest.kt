package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledRepositoryTest {

    private val NOW = 1_700_000_000_000L
    private val HOUR = 3_600_000L

    private fun channel(id: String = "ch1", playlistId: String = "pl") = Channel(
        id = id,
        name = "Канал $id",
        number = 1,
        category = "Тест",
        streamUrl = "http://example.com/$id.m3u8",
        playlistId = playlistId,
    )

    private fun programme(start: Long, stop: Long, title: String = "Передача") = EpgProgramme(
        channelId = "epg1",
        start = start,
        stop = stop,
        title = title,
    )

    private fun createRepo(): ScheduledRepository {
        val database = createTestDatabase()
        return SqlDelightScheduledRepository(database)
    }

    @Test
    fun createAndListSchedule() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            val result = repo.schedule(channel(), programme(start, start + HOUR), NOW)
            assertEquals(ScheduleResult.SCHEDULED, result)

            val all = repo.getAll()
            assertEquals(1, all.size)
            assertEquals("Передача", all.single().title)
            assertEquals(start, all.single().startTime)
        }
    }

    @Test
    fun pastProgrammeIsRejected() {
        runBlocking {
            val repo = createRepo()
            val result = repo.schedule(channel(), programme(NOW - 2 * HOUR, NOW - HOUR), NOW)
            assertEquals(ScheduleResult.REJECTED_PAST, result)
            assertTrue(repo.getAll().isEmpty())
        }
    }

    @Test
    fun duplicateScheduleIsDetected() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            assertEquals(ScheduleResult.SCHEDULED, repo.schedule(channel(), programme(start, start + HOUR), NOW))
            assertEquals(
                ScheduleResult.ALREADY_SCHEDULED,
                repo.schedule(channel(), programme(start, start + HOUR), NOW),
            )
            assertEquals(1, repo.getAll().size)
        }
    }

    @Test
    fun unscheduleRemovesEntry() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            repo.schedule(channel(), programme(start, start + HOUR), NOW)
            assertTrue(repo.isScheduled("pl", "ch1", start))

            repo.unschedule("ch1", "pl", start)
            assertFalse(repo.isScheduled("pl", "ch1", start))
            assertTrue(repo.getAll().isEmpty())
        }
    }

    @Test
    fun sameProgrammeOnDifferentChannelsDoNotCollide() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            repo.schedule(channel(id = "ch1"), programme(start, start + HOUR), NOW)
            repo.schedule(channel(id = "ch2"), programme(start, start + HOUR), NOW)
            assertEquals(2, repo.getAll().size)
        }
    }

    @Test
    fun sameChannelAndStartDifferentPlaylistsDoNotCollide() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            repo.schedule(channel(playlistId = "pl1"), programme(start, start + HOUR), NOW)
            repo.schedule(channel(playlistId = "pl2"), programme(start, start + HOUR), NOW)
            assertEquals(2, repo.getAll().size)
        }
    }

    @Test
    fun deleteExpiredRemovesFinishedProgrammes() {
        runBlocking {
            val repo = createRepo()
            val soon = NOW + HOUR
            repo.schedule(channel(), programme(soon, soon + HOUR), NOW)

            // Nothing is expired yet.
            repo.deleteExpired(NOW)
            assertEquals(1, repo.getAll().size)

            // After the programme ended, housekeeping removes it.
            repo.deleteExpired(soon + HOUR + 1_000)
            assertTrue(repo.getAll().isEmpty())
        }
    }

    @Test
    fun deleteByPlaylistCleansOnlyThatPlaylist() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            repo.schedule(channel(playlistId = "pl1"), programme(start, start + HOUR), NOW)
            repo.schedule(channel(playlistId = "pl2"), programme(start, start + HOUR), NOW)

            repo.deleteByPlaylist("pl1")
            val all = repo.getAll()
            assertEquals(1, all.size)
            assertEquals("pl2", all.single().playlistId)
        }
    }

    @Test
    fun observeEmitsCurrentList() {
        runBlocking {
            val repo = createRepo()
            val start = NOW + 2 * HOUR
            repo.schedule(channel(), programme(start, start + HOUR), NOW)

            val emitted = repo.observeAll().first()
            assertEquals(1, emitted.size)
        }
    }
}
