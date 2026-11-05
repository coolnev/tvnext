package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveCandidatesTest {

    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L

    private fun channel(
        catchup: String? = "default",
        catchupDays: Long? = null,
        catchupSource: String? = "http://example.com/arch.m3u8?utc={utc}",
    ) = Channel(
        id = "ch1",
        name = "Канал",
        number = 1,
        category = "Тест",
        streamUrl = "http://example.com/live.m3u8",
        playlistId = "pl",
        catchup = catchup,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
    )

    private fun capability(
        status: ArchiveStatus,
        declaredDays: Long? = null,
        measuredMs: Long? = null,
    ) = ArchiveCapability(
        channelId = "ch1",
        status = status,
        declaredDays = declaredDays,
        measuredMs = measuredMs,
        lastProbedAt = NOW,
    )

    @Test
    fun noMechanismIsNeverCandidate() {
        val ch = channel(catchup = null, catchupSource = null)
        assertFalse(
            isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, capability(ArchiveStatus.AVAILABLE, measuredMs = 7 * DAY)),
        )
    }

    @Test
    fun futureProgrammeIsNotArchive() {
        val ch = channel()
        assertFalse(isArchiveCandidate(ch, NOW + DAY, NOW + 2 * DAY, NOW, capability(ArchiveStatus.AVAILABLE, measuredMs = 7 * DAY)))
    }

    @Test
    fun availableWithinMeasuredDepthIsCandidate() {
        val ch = channel()
        assertTrue(
            isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, capability(ArchiveStatus.AVAILABLE, measuredMs = 7 * DAY)),
        )
    }

    @Test
    fun availableBeyondMeasuredDepthIsNotCandidate() {
        val ch = channel()
        assertFalse(
            isArchiveCandidate(ch, NOW - 10 * DAY, NOW - 9 * DAY, NOW, capability(ArchiveStatus.AVAILABLE, measuredMs = 7 * DAY)),
        )
    }

    @Test
    fun availableWithoutDepthAcceptsAnyPastProgramme() {
        val ch = channel()
        // declared days give a depth even when not measured
        assertTrue(
            isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, capability(ArchiveStatus.AVAILABLE, declaredDays = 7)),
        )
    }

    @Test
    fun unknownStatusOffersProgrammeWithoutInventedDepth() {
        val ch = channel()
        assertTrue(
            isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, capability(ArchiveStatus.UNKNOWN)),
        )
    }

    @Test
    fun noneStatusIsNotCandidate() {
        val ch = channel()
        assertFalse(isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, capability(ArchiveStatus.NONE)))
    }

    @Test
    fun missingCapabilityWithMechanismOffersProgramme() {
        val ch = channel()
        assertTrue(isArchiveCandidate(ch, NOW - 2 * DAY, NOW - DAY, NOW, null))
    }

    @Test
    fun userLimitNarrowsTheArchiveWindow() {
        // Provider depth 30d, user limit 7d → only the last 7 days are
        // candidates; a 10-day-old programme is excluded.
        val ch = channel(catchupDays = 30)
        val capability = capability(ArchiveStatus.AVAILABLE, declaredDays = 30)
        assertTrue(
            isArchiveCandidate(ch, NOW - 5 * DAY, NOW - 4 * DAY, NOW, capability, userLimitDays = 7),
        )
        assertFalse(
            isArchiveCandidate(ch, NOW - 10 * DAY, NOW - 9 * DAY, NOW, capability, userLimitDays = 7),
        )
    }

    @Test
    fun userLimitNeverWidensTheProviderDepth() {
        val ch = channel(catchupDays = 2)
        val capability = capability(ArchiveStatus.AVAILABLE, declaredDays = 2)
        // The provider keeps only 2 days; a 30-day user limit cannot resurrect
        // a 10-day-old programme.
        assertFalse(
            isArchiveCandidate(ch, NOW - 10 * DAY, NOW - 9 * DAY, NOW, capability, userLimitDays = 30),
        )
    }

    @Test
    fun noUserLimitUsesProviderDepth() {
        val ch = channel(catchupDays = 30)
        val capability = capability(ArchiveStatus.AVAILABLE, declaredDays = 30)
        assertTrue(
            isArchiveCandidate(ch, NOW - 10 * DAY, NOW - 9 * DAY, NOW, capability, userLimitDays = 0),
        )
    }
}
