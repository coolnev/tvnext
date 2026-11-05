package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel

/** Lifecycle of a channel's archive from the app's point of view. */enum class ArchiveStatus {
    /** The provider declares no catch-up mechanism for this channel. */
    NONE,

    /**
     * Archive is available: either the provider declared `catchup-days`
     * (taken as the claimed maximum) or the app measured a reachable depth.
     */
    AVAILABLE,

    /**
     * A mechanism exists but the depth could not be reliably determined —
     * the app must NOT invent a number here ("Не удалось определить").
     */
    UNKNOWN,
    ;

    companion object {
        fun fromDb(raw: String?): ArchiveStatus =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(NONE)
    }
}

/**
 * What the app knows about one channel's archive. Persisted per channel in
 * `archive_capability` (see the SQLDelight schema).
 */
data class ArchiveCapability(
    val channelId: String,
    val status: ArchiveStatus,
    /** Provider-declared depth in days (`catchup-days`), as-is. */
    val declaredDays: Long? = null,
    /** Oldest reachable offset the app verified experimentally (ms). */
    val measuredMs: Long? = null,
    /** When the capability was last probed/resolved (epoch ms). */
    val lastProbedAt: Long? = null,
    val lastError: String? = null,
) {
    /**
     * Oldest reachable offset (ms): the measured depth wins over the declared
     * days. `null` when nothing is known yet.
     */
    val effectiveDepthMs: Long?
        get() = measuredMs ?: declaredDays?.let { it * DAY_MS }

    val isPlayable: Boolean
        get() = status == ArchiveStatus.AVAILABLE

    /** Depth in whole days (rounded down), or null when unknown. */
    val effectiveDepthDays: Long?
        get() = effectiveDepthMs?.let { it / DAY_MS }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** Internal resolution produced by [ArchiveDepthResolver]. */
internal data class ArchiveDepthResolution(
    val status: ArchiveStatus,
    val measuredMs: Long? = null,
    val error: String? = null,
)
