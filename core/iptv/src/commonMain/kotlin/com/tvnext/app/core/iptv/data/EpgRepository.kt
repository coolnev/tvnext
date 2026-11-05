package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UI-facing state of the EPG cache for the focused playlist (Settings → EPG). */
data class EpgState(
    val enabled: Boolean = true,
    /** Playlist this state describes (default or first LIVE playlist). */
    val playlistId: String? = null,
    val playlistName: String? = null,
    /** EPG URL detected from the playlist header (`url-tvg`). */
    val detectedUrl: String? = null,
    /** User-provided EPG URL from Settings (global override). */
    val overrideUrl: String = "",
    /** The URL that is actually used (override > playlist). */
    val effectiveUrl: String? = null,
    val lastUpdated: Long? = null,
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    val isLoading: Boolean = false,
    val lastError: String? = null,
    /** Non-error hint for the UI (e.g. an import retry is being throttled). */
    val notice: String? = null,
    val diagnostics: EpgDiagnostics? = null,
)

/** Current + next programme for a channel at a point in time. */
data class NowNext(
    val current: EpgProgramme? = null,
    val next: EpgProgramme? = null,
)

/**
 * A programme found by the global Search, resolved back to the M3U channel
 * (when it still matches) so a click can open the channel directly.
 */
data class ProgrammeSearchHit(
    val playlistId: String,
    val channelId: String?,
    val channelName: String,
    val programme: EpgProgramme,
)

/**
 * EPG (electronic programme guide) contract.
 *
 * Implemented by the lightweight in-memory engine ([LiteEpgRepository], a port
 * of the LiteTV approach): the schedule lives in a `Map` keyed by playlist
 * channel, is streamed from the XMLTV with a sliding buffer (only our channels,
 * only title+times), and is cached on disk in a compact format that loads
 * almost instantly on the next launch. No SQLite, no native parser, no giant
 * documents in memory — the old pipeline silently killed low-end Android TV
 * boxes during import.
 */
interface EpgRepository {

    /** Observed EPG state (cache meta, loading, errors) for the Settings UI. */
    fun observeState(): StateFlow<EpgState>

    /** Increments after every successful EPG refresh (for reactive UI updates). */
    fun observeVersion(): Flow<Long>

    /** Re-reads persisted cache/config into [observeState] without any network. */
    suspend fun refreshState()

    /**
     * Refreshes EPG for every enabled playlist with an effective source,
     * guarded by a per-playlist freshness check unless [force]. Never blocks
     * the caller for long: a fresh cache is loaded from disk instead.
     */
    suspend fun refreshIfStale(force: Boolean = false): AppResult<Unit>

    /** Refreshes EPG of a single playlist (freshness-guarded unless [force]). */
    suspend fun refreshPlaylist(playlistId: String, force: Boolean = false): AppResult<Unit>

    /** Upcoming programmes of the channel's EPG counterpart starting at [from]. */
    suspend fun getUpcomingProgrammes(channel: Channel, from: Long, limit: Int): List<EpgProgramme>

    /** Current and next programme for the channel at [at]. */
    suspend fun getNowNext(channel: Channel, at: Long): NowNext

    /**
     * Current+next programme for EVERY given channel at [at], keyed by the M3U
     * channel id (Home screen path).
     */
    suspend fun getNowNextForChannels(channels: List<Channel>, at: Long): Map<String, NowNext>

    /**
     * The currently playing programme for every given channel at [at], keyed by
     * the M3U channel id.
     */
    suspend fun getNowPlaying(channels: List<Channel>, at: Long): Map<String, EpgProgramme>

    /**
     * All programmes overlapping [from, to] per channel for the given channels,
     * keyed by channel id (TV Guide grid).
     */
    suspend fun getProgrammesInRange(
        channels: List<Channel>,
        from: Long,
        to: Long,
    ): Map<String, List<EpgProgramme>>

    /**
     * Returns the earliest programme start and latest programme stop available
     * for the supplied channels. Null means there is no cached EPG for them.
     */
    suspend fun getAvailableProgrammeRange(channels: List<Channel>): LongRange?

    /** Matching diagnostics against the focused playlist, or null when no EPG cached. */
    suspend fun getMatchingDiagnostics(): EpgDiagnostics?

    /**
     * Title search over every enabled playlist's cached EPG. Each hit is
     * resolved back to the M3U channel (when it still matches).
     */
    suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit>

    companion object {
        const val TTL_MS = 12L * 60 * 60 * 1000
        const val UPCOMING_LIMIT = 20

        /** Look-ahead window for bulk current+next resolution (Home cards). */
        const val NOW_NEXT_WINDOW_MS = 24L * 60 * 60 * 1000

        /** Time bucket for the "current/next" cache (60 s). */
        const val NOW_NEXT_CACHE_BUCKET_MS = 60_000L

        /** Below this size, "current/next" is resolved per-channel (fast). */
        const val PER_CHANNEL_NOW_NEXT_THRESHOLD = 50
    }
}
