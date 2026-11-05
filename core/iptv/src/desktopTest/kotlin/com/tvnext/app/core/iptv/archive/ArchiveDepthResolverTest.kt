package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveDepthResolverTest {

    private val DAY = 86_400_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_700_000_000_000L

    /** Probe that parses `utc=` (Unix SECONDS) from the built URL. */
    private class FakeProbe(
        val now: Long,
        var reachableOffsetMs: Long? = null,
        var networkErrorsAtOffsets: Set<Long> = emptySet(),
    ) : ArchiveProbe {
        var calls = 0
        val probedStarts = mutableListOf<Long>()

        override suspend fun probe(url: String): ArchiveProbeResult {
            calls++
            val startSeconds = Regex("utc=(\\d+)").find(url)?.groupValues?.get(1)?.toLong()
                ?: return ArchiveProbeResult.NotAvailable
            probedStarts += startSeconds
            return when {
                startSeconds in networkErrorsAtOffsets -> ArchiveProbeResult.NetworkError
                reachableOffsetMs == null -> ArchiveProbeResult.NotAvailable
                startSeconds >= (now - reachableOffsetMs!!) / 1000 -> ArchiveProbeResult.Reachable
                else -> ArchiveProbeResult.NotAvailable
            }
        }
    }

    private fun channel(
        catchupDays: Long? = null,
        catchupSource: String? = "http://example.com/arch.m3u8?utc={utc}",
    ) = Channel(
        id = "ch1",
        name = "Канал",
        number = 1,
        category = "Тест",
        streamUrl = "http://example.com/live.m3u8",
        playlistId = "pl",
        catchup = "default",
        catchupDays = catchupDays,
        catchupSource = catchupSource,
    )

    private fun resolver(probe: ArchiveProbe) = ArchiveDepthResolver(
        probe = probe,
        builder = CatchupUrlBuilder(),
        clock = { NOW },
    )

    @Test
    fun noMechanismIsNone() {
        val probe = FakeProbe(now = NOW)
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://example.com/live.m3u8",
            playlistId = "pl",
        )
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.NONE, result.status)
        assertEquals(0, probe.calls, "NONE must not touch the network")
    }

    @Test
    fun declaredDaysUsedWithoutProbing() {
        val probe = FakeProbe(now = NOW, reachableOffsetMs = null) // would be NotAvailable
        val ch = channel(catchupDays = 7)
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.AVAILABLE, result.status)
        assertNull(result.measuredMs, "declared days are the claimed maximum, no measurement")
        assertEquals(0, probe.calls, "declared days must not probe")
    }

    @Test
    fun depthMeasuredBySequentialSearch() {
        val probe = FakeProbe(now = NOW, reachableOffsetMs = 12 * DAY)
        val ch = channel() // no declared days → measure
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.AVAILABLE, result.status)
        val measured = assertNotNull(result.measuredMs)
        // Sequential grid (1,2,3,5,7,10 reachable, 14 not) + fill 11..13 finds
        // exactly 12 days — the last consecutively reachable day.
        assertEquals(12 * DAY, measured)
        assertTrue(probe.calls <= 15, "bounded probes, got ${probe.calls}")
    }

    @Test
    fun holeInTheMiddleStopsAtLastConfirmedDay() {
        // The provider keeps days 1..3 and 5..10 but day 4 is GONE (a hole).
        // The depth must be conservative (3 days) — no monotonicity assumption,
        // no jumping over the hole.
        val probe = object : ArchiveProbe {
            var calls = 0
            override suspend fun probe(url: String): ArchiveProbeResult {
                calls++
                val startSeconds = Regex("utc=(\\d+)").find(url)?.groupValues?.get(1)?.toLong()
                    ?: return ArchiveProbeResult.NotAvailable
                val offsetMs = NOW - startSeconds * 1000
                val day = (offsetMs - 1) / DAY + 1
                return if (day in 1..3 || day in 5..10) {
                    ArchiveProbeResult.Reachable
                } else {
                    ArchiveProbeResult.NotAvailable
                }
            }
        }
        val ch = channel()
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.AVAILABLE, result.status)
        assertEquals(3 * DAY, result.measuredMs, "hole on day 4 must cap the depth at 3 days")
        assertTrue(probe.calls <= 15, "bounded probes, got ${probe.calls}")
    }

    @Test
    fun shallowArchiveMeasuredInHours() {
        // Reachable only within 30 minutes.
        val probe = FakeProbe(now = NOW, reachableOffsetMs = 30 * 60_000L)
        val ch = channel()
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.AVAILABLE, result.status)
        val measured = assertNotNull(result.measuredMs)
        assertTrue(measured <= 30 * 60_000L, "measured=$measured")
    }

    @Test
    fun networkErrorsYieldUnknownNotInventedNumber() {
        // The closest probe (now-1h) hits a network error — the whole
        // measurement is inconclusive → UNKNOWN (the set holds Unix SECONDS,
        // matching the utc= values the probe parses).
        val probe = FakeProbe(
            now = NOW,
            reachableOffsetMs = 3 * DAY,
            networkErrorsAtOffsets = setOf((NOW - HOUR) / 1000),
        )
        val ch = channel()
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.UNKNOWN, result.status)
        assertNull(result.measuredMs, "never invent a number on network failure")
    }

    @Test
    fun nothingReachableYieldsUnknown() {
        val probe = FakeProbe(now = NOW, reachableOffsetMs = null)
        val ch = channel()
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.UNKNOWN, result.status)
        assertNull(result.measuredMs)
    }

    @Test
    fun unsupportedFormatYieldsUnknown() {
        // Mechanism exists but no known format builds a URL (catchup-days only).
        val probe = FakeProbe(now = NOW, reachableOffsetMs = 3 * DAY)
        val ch = channel(catchupDays = null, catchupSource = null)
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.UNKNOWN, result.status)
        assertEquals(0, probe.calls)
    }

    @Test
    fun declaredDaysWithUnsupportedFormatDoNotClaimWorkingArchive() {
        // Regression: `catchup-days="7"` must NOT be reported as AVAILABLE when
        // no supported format can build an archive URL — otherwise the UI would
        // offer "смотреть архив" and every attempt would fail.
        val probe = FakeProbe(now = NOW, reachableOffsetMs = 3 * DAY)
        val ch = channel(catchupDays = 7, catchupSource = null)
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.UNKNOWN, result.status)
        assertEquals(0, probe.calls, "must not probe either — the format is unsupported")
    }

    @Test
    fun declaredDaysWithPlayableFormatRemainAvailable() {
        // The same declared days DO yield AVAILABLE when a supported format
        // (the {utc} template) can build the archive URL.
        val probe = FakeProbe(now = NOW, reachableOffsetMs = null)
        val ch = channel(catchupDays = 7, catchupSource = "http://example.com/arch.m3u8?utc={utc}")
        val result = kotlinx.coroutines.runBlocking { resolver(probe).resolve(ch) }
        assertEquals(ArchiveStatus.AVAILABLE, result.status)
        assertEquals(0, probe.calls)
    }
}
