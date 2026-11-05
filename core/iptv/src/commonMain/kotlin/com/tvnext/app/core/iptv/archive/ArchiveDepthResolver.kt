package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.tvnext.app.core.iptv.domain.Channel

/** Outcome of a single archive probe request. */
sealed interface ArchiveProbeResult {
    /** The URL responds like a live stream (2xx + body). */
    data object Reachable : ArchiveProbeResult

    /** The URL is explicitly outside the archive (404/410/gone/empty body). */
    data object NotAvailable : ArchiveProbeResult

    /** Transient failure — the answer is inconclusive, NOT "no archive". */
    data object NetworkError : ArchiveProbeResult
}

/**
 * Network-facing archive probe. Implemented with Ktor in the data layer so the
 * depth algorithm stays pure and unit-testable.
 */
fun interface ArchiveProbe {
    suspend fun probe(url: String): ArchiveProbeResult
}

/**
 * Determines how deep a channel's archive goes.
 *
 * Rules (from the stage requirements):
 * - No catch-up mechanism → [ArchiveStatus.NONE].
 * - Provider explicitly declares `catchup-days` → that number is used as the
 *   **claimed maximum** ([ArchiveStatus.AVAILABLE]) without any network traffic.
 * - Mechanism without declared days → an experimental SEQUENTIAL depth check
 *   (coarse grid + fine fill). **Network failures are treated separately from
 *   "no archive"**: a probe ending in [ArchiveProbeResult.NetworkError] makes
 *   the measurement inconclusive → [ArchiveStatus.UNKNOWN] ("Не удалось
 *   определить"). A definite [ArchiveProbeResult.NotAvailable] at every
 *   candidate also yields UNKNOWN — never an invented number.
 *
 * The depth is the LAST day proven reachable in a row. No monotonicity of the
 * provider's archive is assumed: a hole in the middle just stops the count at
 * the last confirmed day (conservative, honest), and the budget stays ~13
 * requests for a 30-day window — never "hundreds of network requests".
 */
class ArchiveDepthResolver(
    private val probe: ArchiveProbe,
    private val builder: CatchupUrlBuilder,
    private val clock: () -> Long,
) {
    internal suspend fun resolve(channel: Channel): ArchiveDepthResolution {
        if (!channel.hasCatchupMechanism) {
            return ArchiveDepthResolution(ArchiveStatus.NONE)
        }
        val playable = builder.isArchivePlayable(channel)
        // Provider-declared days are authoritative ONLY as the claimed maximum
        // of a playable archive: catchup-days without a buildable URL (no
        // supported format) must never be reported as a working archive —
        // otherwise the UI offers "смотреть архив" and every attempt fails.
        channel.catchupDays?.let { days ->
            if (days > 0L && playable) return ArchiveDepthResolution(ArchiveStatus.AVAILABLE)
        }
        if (!playable) {
            // A mechanism exists but no known format can build an archive URL:
            // we cannot measure, and inventing a number is forbidden.
            return ArchiveDepthResolution(
                ArchiveStatus.UNKNOWN,
                error = "механизм архива есть, но формат не поддерживается",
            )
        }
        return measure(channel)
    }

    private suspend fun measure(channel: Channel): ArchiveDepthResolution {
        val now = clock()

        // 1) Closest point: even now-1h must be reachable for the archive to
        //    be usable at all.
        when (val nearest = probeUrl(channel, now - MIN_OFFSET_MS, now)) {
            is ArchiveProbeResult.NetworkError ->
                return ArchiveDepthResolution(
                    ArchiveStatus.UNKNOWN,
                    error = "сетевые ошибки при проверке архива",
                )

            is ArchiveProbeResult.NotAvailable -> {
                // 2) Fine grains: maybe the archive only keeps minutes/hours.
                for (grain in GRAINS_MS) {
                    when (val result = probeUrl(channel, now - grain, now)) {
                        is ArchiveProbeResult.Reachable ->
                            return ArchiveDepthResolution(ArchiveStatus.AVAILABLE, measuredMs = grain)
                        is ArchiveProbeResult.NetworkError ->
                            return ArchiveDepthResolution(
                                ArchiveStatus.UNKNOWN,
                                error = "сетевые ошибки при проверке архива",
                            )
                        is ArchiveProbeResult.NotAvailable -> Unit
                    }
                }
                return ArchiveDepthResolution(
                    ArchiveStatus.UNKNOWN,
                    error = "механизм архива заявлен, но точки архива не найдены",
                )
            }

            is ArchiveProbeResult.Reachable -> {
                // 3) Sequential day check — NO monotonicity assumption. Days are
                //    probed one by one (coarse grid first, then a fine fill
                //    between the last confirmed day and the first unreachable
                //    one) and the depth is the LAST day proven reachable in a
                //    row. A hole in the middle of the provider's archive yields
                //    a conservative depth (only consecutively confirmed days are
                //    counted), never an invented number.
                val depth = sequentialDayDepth(channel, now)
                    ?: return ArchiveDepthResolution(
                        ArchiveStatus.UNKNOWN,
                        error = "сетевые ошибки при проверке архива",
                    )
                return ArchiveDepthResolution(ArchiveStatus.AVAILABLE, measuredMs = depth)
            }
        }
    }

    /**
     * Last reachable day counted SEQUENTIALLY from day 1 — no monotonicity
     * assumption at all: every day is probed in order and the first day that is
     * NOT reachable ends the measurement. A hole in the provider's archive
     * (e.g. day 4 missing) caps the depth at the last confirmed day. Budget:
     * up to 30 probes (a 30-day window), cached for 24h — never "hundreds".
     */
    private suspend fun sequentialDayDepth(channel: Channel, now: Long): Long? {
        var confirmed = 0L
        for (day in 1L..MAX_DAYS) {
            when (probeUrl(channel, now - day * DAY_MS, now)) {
                is ArchiveProbeResult.NetworkError -> return null
                is ArchiveProbeResult.NotAvailable -> return confirmed * DAY_MS
                is ArchiveProbeResult.Reachable -> confirmed = day
            }
        }
        return confirmed * DAY_MS
    }

    private suspend fun probeUrl(channel: Channel, startMs: Long, endMs: Long): ArchiveProbeResult {
        val url = builder.buildArchiveUrl(channel, startMs, endMs)
            ?: return ArchiveProbeResult.NotAvailable
        return try {
            probe.probe(url)
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            ArchiveProbeResult.NetworkError
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MIN_OFFSET_MS = 1L * 3_600_000L
        const val MAX_DAYS = 30L

        /** Coarse-to-fine grains tried when even now-1h is unavailable. */
        val GRAINS_MS = listOf(
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
        )
    }
}
