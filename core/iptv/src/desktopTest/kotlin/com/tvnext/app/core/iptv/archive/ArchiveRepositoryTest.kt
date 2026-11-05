package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveRepositoryTest {

    private companion object {
        val DAY = 86_400_000L
        val NOW = 1_700_000_000_000L
    }

    private class FakeProbe(
        var reachableOffsetMs: Long? = null,
    ) : ArchiveProbe {
        var calls = 0
        override suspend fun probe(url: String): ArchiveProbeResult {
            calls++
            // utc= or start= is Unix SECONDS; NOW/reachableOffset are milliseconds.
            val startSeconds = Regex("(?:utc|start)=(\\d+)").find(url)?.groupValues?.get(1)?.toLong()
                ?: return ArchiveProbeResult.NotAvailable
            return if (reachableOffsetMs == null || startSeconds < (NOW - reachableOffsetMs!!) / 1000) {
                ArchiveProbeResult.NotAvailable
            } else {
                ArchiveProbeResult.Reachable
            }
        }
    }

    private class FakeArchiveConfig(
        var limitDays: Int = 0,
    ) : ArchiveConfigProvider {
        override suspend fun userArchiveLimitDays(): Int = limitDays
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

    private fun createRepo(
        probe: FakeProbe,
        config: FakeArchiveConfig,
    ): ArchiveRepository {
        val database = com.tvnext.app.core.iptv.data.createTestDatabase()
        return IptvArchiveRepository(
            store = SqlDelightArchiveCapabilityStore(database),
            builder = CatchupUrlBuilder(),
            resolver = ArchiveDepthResolver(probe, CatchupUrlBuilder(), { NOW }),
            probe = probe,
            configProvider = config,
            clock = { NOW },
        )
    }

    @Test
    fun declaredDaysCappedByUserLimit() {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig(limitDays = 7))
        runBlocking {
            val depth = repo.effectiveArchiveDepthMs(channel(catchupDays = 30))
            assertNotNull(depth)
            assertEquals(7 * DAY, depth, "user limit 7d narrows the provider 30d")
        }
    }

    @Test
    fun userLimitWiderThanProviderKeepsProviderDepth() {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig(limitDays = 30))
        runBlocking {
            val depth = repo.effectiveArchiveDepthMs(channel(catchupDays = 7))
            assertNotNull(depth)
            assertEquals(7 * DAY, depth, "provider depth wins when smaller")
        }
    }

    @Test
    fun autoLimitUsesProviderDepth() {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig(limitDays = 0))
        runBlocking {
            val depth = repo.effectiveArchiveDepthMs(channel(catchupDays = 14))
            assertNotNull(depth)
            assertEquals(14 * DAY, depth)
        }
    }

    @Test
    fun measuredDepthCappedByUserLimit() {
        val probe = FakeProbe(reachableOffsetMs = 12 * DAY)
        val repo = createRepo(probe, FakeArchiveConfig(limitDays = 3))
        runBlocking {
            // First resolve measures 12 days, then the limit narrows it to 3.
            val capability = repo.getCapability(channel())
            assertEquals(ArchiveStatus.AVAILABLE, capability.status)
            val depth = repo.effectiveArchiveDepthMs(channel())
            assertNotNull(depth)
            assertEquals(3 * DAY, depth)
        }
    }

    @Test
    fun noMechanismHasNoDepth() {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig())
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://example.com/live.m3u8",
            playlistId = "pl",
        )
        runBlocking {
            assertNull(repo.effectiveArchiveDepthMs(ch))
            assertFalse(repo.isArchiveAvailable(ch))
        }
    }

    @Test
    fun capabilityIsCachedWithinTtl() {
        val probe = FakeProbe(reachableOffsetMs = 5 * DAY)
        val repo = createRepo(probe, FakeArchiveConfig())
        runBlocking {
            val first = repo.getCapability(channel())
            assertEquals(ArchiveStatus.AVAILABLE, first.status)
            val probesAfterFirst = probe.calls
            assertTrue(probesAfterFirst > 0)

            // Second read within TTL → no new probes.
            val second = repo.getCapability(channel())
            assertEquals(ArchiveStatus.AVAILABLE, second.status)
            assertEquals(probesAfterFirst, probe.calls, "capability must be cached")
        }
    }

    @Test
    fun declaredDaysAreUsedWithoutProbing() {
        val probe = FakeProbe()
        val repo = createRepo(probe, FakeArchiveConfig())
        runBlocking {
            val capability = repo.getCapability(channel(catchupDays = 10))
            assertEquals(ArchiveStatus.AVAILABLE, capability.status)
            assertEquals(10L, capability.declaredDays)
            assertEquals(0, probe.calls, "declared days must not probe")
        }
    }

    @Test
    fun cacheFallbackDoesNotClaimArchiveForUnsupportedFormat() = runBlocking {
        // Bulk cache path (deriveFromChannel): catchup-days without a playable
        // format must NOT be reported as AVAILABLE to the TV Guide / Search.
        val repo = createRepo(FakeProbe(), FakeArchiveConfig())
        val ch = channel(catchupDays = 7, catchupSource = null)
        val capability = repo.getCapabilitiesCached(listOf(ch)).getValue(ch.id)
        assertEquals(ArchiveStatus.NONE, capability.status, "unsupported format must not be AVAILABLE")
    }

    @Test
    fun cacheFallbackHonoursPlayableDeclaredDays() = runBlocking {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig())
        val ch = channel(catchupDays = 7)
        val capability = repo.getCapabilitiesCached(listOf(ch)).getValue(ch.id)
        assertEquals(ArchiveStatus.AVAILABLE, capability.status)
        assertEquals(7L, capability.declaredDays)
    }

    @Test
    fun unknownStatusIsStoredHonestly() {
        val probe = FakeProbe(reachableOffsetMs = null)
        val repo = createRepo(probe, FakeArchiveConfig())
        runBlocking {
            val capability = repo.getCapability(channel())
            assertEquals(ArchiveStatus.UNKNOWN, capability.status)
            assertNull(capability.measuredMs, "no invented number")
        }
    }

    @Test
    fun buildArchiveUrlSupportsUtcTemplate() {
        val repo = createRepo(FakeProbe(), FakeArchiveConfig())
        val url = repo.buildArchiveUrl(channel(), NOW - 2 * DAY, NOW - 2 * DAY + 3_600_000L)
        assertNotNull(url)
        assertTrue(url!!.contains("utc=${(NOW - 2 * DAY) / 1000}"))
    }

    @Test
    fun shiftFormatBuildsFromLiveUrl() {
        // catchup="shift" + timeshift attribute, no catchup-source → the
        // `?start=&end=` convention on the LIVE stream URL (cdntv.online style).
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://example.com/live.m3u8",
            playlistId = "pl",
            catchup = "shift",
            catchupDays = 3,
            timeshift = "3",
        )
        val repo = createRepo(FakeProbe(), FakeArchiveConfig())
        val url = repo.buildArchiveUrl(ch, NOW - 2 * DAY, NOW - 2 * DAY + 3_600_000L)
        assertNotNull(url)
        assertEquals(
            "http://example.com/live.m3u8?start=${(NOW - 2 * DAY) / 1000}&end=${(NOW - 2 * DAY + 3_600_000L) / 1000}",
            url,
        )
    }

    @Test
    fun preflightRejectsDeclaredButEmptyArchive() {
        // The provider DECLARES catchup-days but serves an empty playlist —
        // the pre-flight must refuse so the UI shows an honest message.
        val probe = FakeProbe(reachableOffsetMs = null) // everything NotAvailable
        val repo = createRepo(probe, FakeArchiveConfig())
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://example.com/live.m3u8",
            playlistId = "pl",
            catchup = "shift",
            catchupDays = 3,
            timeshift = "3",
        )
        runBlocking {
            assertFalse(repo.isArchiveUrlPlayable(ch, NOW - 2 * DAY, NOW - 2 * DAY + 3_600_000L))
        }
    }

    @Test
    fun preflightAllowsReachableArchive() {
        val probe = FakeProbe(reachableOffsetMs = 7 * DAY)
        val repo = createRepo(probe, FakeArchiveConfig())
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://example.com/live.m3u8",
            playlistId = "pl",
            catchup = "shift",
            catchupDays = 3,
            timeshift = "3",
        )
        runBlocking {
            assertTrue(repo.isArchiveUrlPlayable(ch, NOW - DAY, NOW - DAY + 3_600_000L))
        }
    }
}
