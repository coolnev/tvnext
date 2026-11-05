package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel

/**
 * Pure archive-candidate rule shared by Search and the EPG-facing features.
 *
 * A finished programme is an archive candidate when:
 * - the channel declares ANY catch-up mechanism, and
 * - the cached capability is AVAILABLE (then the programme must also lie within
 *   the effective depth — declared or measured, never invented) or UNKNOWN
 *   (mechanism exists, depth unverified — the programme is offered and the real
 *   availability is verified on click).
 *
 * The user archive limit narrows the depth window (it never widens it).
 *
 * This function NEVER triggers network activity and NEVER fabricates a depth.
 */
fun isArchiveCandidate(
    channel: Channel,
    programmeStart: Long,
    programmeStop: Long,
    now: Long,
    capability: ArchiveCapability?,
    userLimitDays: Int = 0,
): Boolean {
    if (!channel.hasCatchupMechanism) return false
    if (programmeStop > now) return false
    return when (capability?.status) {
        ArchiveStatus.AVAILABLE -> {
            val depth = capability.effectiveDepthMs
            val cappedDepth = if (userLimitDays > 0 && depth != null) {
                minOf(depth, userLimitDays * ArchiveCapability.DAY_MS)
            } else {
                depth
            }
            cappedDepth == null || programmeStart >= now - cappedDepth
        }
        ArchiveStatus.UNKNOWN -> true
        ArchiveStatus.NONE -> false
        null -> true
    }
}
