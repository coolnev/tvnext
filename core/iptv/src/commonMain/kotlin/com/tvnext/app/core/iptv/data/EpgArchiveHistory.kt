package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.xmltv.EpgProgramme

/**
 * Merge a rolling XMLTV update with previously cached archive schedules.
 *
 * XMLTV feeds frequently drop yesterday's programme entries at midnight, even
 * though the channel still offers that programme through catch-up. Keep those
 * ACTUAL old entries until the effective archive cutoff. New EPG data replaces
 * old data with the same start time, including corrected titles/descriptions.
 * No programme is synthesized when it was never received from the provider.
 */
internal fun mergeEpgArchiveHistory(
    previous: List<EpgProgramme>,
    incoming: List<EpgProgramme>,
    now: Long,
    cutoff: Long,
): List<EpgProgramme> {
    if (previous.isEmpty()) return incoming
    val incomingStarts = incoming.mapTo(HashSet()) { it.start }
    val retained = previous.filter { programme ->
        programme.start < now && programme.stop > cutoff &&
            programme.start !in incomingStarts
    }
    return if (retained.isEmpty()) incoming else retained + incoming
}
