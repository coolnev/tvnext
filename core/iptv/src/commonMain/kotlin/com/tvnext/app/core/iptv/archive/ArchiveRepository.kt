package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.tvnext.app.core.iptv.domain.Channel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import com.tvnext.app.core.common.platform.isAndroidPlatform
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-facing archive config: currently just the user archive-limit setting.
 * Implemented by `features:settings` (like [com.tvnext.app.core.iptv.data.EpgConfigProvider]).
 */
interface ArchiveConfigProvider {
    /**
     * User limit on archive depth in days. `0` means automatic — the provider
     * maximum (declared or measured) is used.
     */
    suspend fun userArchiveLimitDays(): Int
}

/**
 * Ktor-based [ArchiveProbe].
 *
 * Follows HTTP redirects like a real HLS client (some EvoStream-style CDNs
 * answer archive requests with a 302 to a segment host) and classifies the
 * FINAL response: an empty body means the provider declared catch-up but
 * serves nothing — that is "no archive", not "reachable".
 */
class KtorArchiveProbe(
    private val client: HttpClient,
) : ArchiveProbe {

    override suspend fun probe(url: String): ArchiveProbeResult =
        try {
            if (isAndroidPlatform()) {
                probeAndroidStream(url)
            } else {
            val response = requestFollowingRedirects(url)
            if (!response.status.isSuccess()) {
                if (response.status.value in 400..499) {
                    ArchiveProbeResult.NotAvailable
                } else {
                    ArchiveProbeResult.NetworkError
                }
            } else {
                // An EMPTY body is NOT a working archive: providers that
                // declare catch-up but do not serve it return an empty HLS
                // playlist with 200. Reading the body is bounded by the request
                // timeouts — archive playlists are small; a raw TS archive
                // would hit the socket timeout and be treated as a transient
                // failure (playback is the real test for those).
                try {
                    val body = response.bodyAsText()
                    if (body.isBlank()) {
                        ArchiveProbeResult.NotAvailable
                    } else {
                        ArchiveProbeResult.Reachable
                    }
                } catch (e: Exception) {
            e.rethrowAndroidCancellation()
                    ArchiveProbeResult.NetworkError
                }
            }
            }
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            ArchiveProbeResult.NetworkError
        }

    private suspend fun probeAndroidStream(url: String): ArchiveProbeResult =
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "*/*")
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }.execute { response ->
            when {
                response.status.value in 400..499 -> ArchiveProbeResult.NotAvailable
                !response.status.isSuccess() -> ArchiveProbeResult.NetworkError
                else -> {
                    // Read a bounded prefix, even when a provider returns raw TS.
                    // execute closes the response when the probe completes.
                    val bytes = ByteArray(4_096)
                    val count = response.bodyAsChannel().readAvailable(bytes, 0, bytes.size)
                    if (count > 0 && bytes.take(count).any { it.toInt() !in listOf(9, 10, 13, 32) })
                        ArchiveProbeResult.Reachable else ArchiveProbeResult.NotAvailable
                }
            }
        }

    private suspend fun requestFollowingRedirects(startUrl: String): HttpResponse {
        var currentUrl = startUrl
        var response: HttpResponse? = null
        repeat(MAX_REDIRECTS) {
            val resp = client.get(currentUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "*/*")
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
            if (resp.status.value in 300..399) {
                val location = resp.headers[HttpHeaders.Location]
                if (location.isNullOrBlank()) {
                    response = resp
                    return@repeat
                }
                currentUrl = when {
                    location.startsWith("http://", ignoreCase = true) ||
                        location.startsWith("https://", ignoreCase = true) -> location
                    location.startsWith("/") -> originOf(currentUrl) + location
                    else -> currentUrl.substringBeforeLast('/') + "/" + location
                }
            } else {
                response = resp
                return@repeat
            }
        }
        return response ?: client.get(currentUrl) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "*/*")
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }
    }

    /** `http://host[:port]` of [url]. */
    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart >= 0) url.substring(0, pathStart) else url
    }

    private companion object {
        const val USER_AGENT = "TVNext/0.1"
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 8_000L
        const val MAX_REDIRECTS = 5
    }
}

/**
 * High-level archive access used by features.
 *
 * Depth probing is deliberately lazy and bounded:
 * - [getCapabilitiesCached] never touches the network (bulk reads, cache only)
 *   — a playlist refresh must never trigger hundreds of probes;
 * - [getCapability] re-probes a single channel when its cache is stale.
 */
interface ArchiveRepository {

    /**
     * Capability for one channel; probes (once) when the cache is stale.
     * Never throws on network errors — the status reflects it instead.
     */
    suspend fun getCapability(channel: Channel): ArchiveCapability

    /** Cache-only bulk read for many channels (no network at all). */
    suspend fun getCapabilitiesCached(channels: List<Channel>): Map<String, ArchiveCapability>

    /** True when the channel's archive can be attempted right now. */
    suspend fun isArchiveAvailable(channel: Channel): Boolean

    /** Builds the playable archive URL for [startMs]..[endMs]; null when unsupported. */
    fun buildArchiveUrl(channel: Channel, startMs: Long, endMs: Long): String?

    /** User archive limit in days (0 = auto). */
    suspend fun userArchiveLimitDays(): Int

    /**
     * Best-effort pre-flight before starting an archive stream: probes whether
     * the built archive URL actually serves content. Providers sometimes
     * DECLARE catch-up (`catchup-days`) but return an empty playlist — the
     * probe catches that and the UI can show an honest "archive unavailable"
     * instead of a broken player. Transient network failures return `true`
     * (playback is the real test — we never block on flaky networks).
     */
    suspend fun isArchiveUrlPlayable(channel: Channel, startMs: Long, endMs: Long): Boolean

    /**
     * Effective max depth (ms) after applying the user limit; null when unknown.
     * Uses the cached capability — never triggers a probe.
     */
    suspend fun effectiveArchiveDepthMs(channel: Channel): Long?
}

class IptvArchiveRepository(
    private val store: ArchiveCapabilityStore,
    private val builder: CatchupUrlBuilder,
    private val resolver: ArchiveDepthResolver,
    private val probe: ArchiveProbe,
    private val configProvider: ArchiveConfigProvider,
    private val clock: () -> Long,
) : ArchiveRepository {

    override suspend fun getCapability(channel: Channel): ArchiveCapability {
        val cached = store.get(channel.id)
        if (cached != null && isFresh(cached)) return cached
        val resolution = resolver.resolve(channel)
        val capability = ArchiveCapability(
            channelId = channel.id,
            status = resolution.status,
            declaredDays = channel.catchupDays,
            measuredMs = resolution.measuredMs,
            lastProbedAt = clock(),
            lastError = resolution.error,
        )
        store.upsert(capability)
        return capability
    }

    override suspend fun getCapabilitiesCached(channels: List<Channel>): Map<String, ArchiveCapability> =
        withContext(Dispatchers.Default) {
            if (channels.isEmpty()) return@withContext emptyMap()
            val result = HashMap<String, ArchiveCapability>(channels.size)
            // One query per involved playlist (the store resolves the channel ids
            // through the playlist membership).
            channels.groupBy { it.playlistId }.forEach { (playlistId, _) ->
                store.getByPlaylist(playlistId).forEach { capability ->
                    result[capability.channelId] = capability
                }
            }
            // Channels without a stored capability default to NONE-ish derivation
            // from the channel attributes (still no network).
            channels.forEach { channel ->
                if (channel.id !in result) {
                    result[channel.id] = deriveFromChannel(channel)
                }
            }
            result
        }

    override suspend fun isArchiveAvailable(channel: Channel): Boolean =
        getCapability(channel).isPlayable

    override fun buildArchiveUrl(channel: Channel, startMs: Long, endMs: Long): String? =
        builder.buildArchiveUrl(channel, startMs, endMs)

    override suspend fun userArchiveLimitDays(): Int =
        runCatching { configProvider.userArchiveLimitDays() }.getOrDefault(0)

    override suspend fun isArchiveUrlPlayable(channel: Channel, startMs: Long, endMs: Long): Boolean {
        val url = builder.buildArchiveUrl(channel, startMs, endMs) ?: return false
        return when (runCatching { probe.probe(url) }.getOrNull()) {
            ArchiveProbeResult.Reachable -> true
            ArchiveProbeResult.NotAvailable -> false
            // Transient failure: let the player try — the archive URL is the
            // real test, we must not block playback on flaky networks.
            ArchiveProbeResult.NetworkError -> true
            null -> true
        }
    }

    override suspend fun effectiveArchiveDepthMs(channel: Channel): Long? {
        if (!channel.hasCatchupMechanism) return null
        val cached = store.get(channel.id)
        val capability = if (cached != null && isFresh(cached)) cached else deriveFromChannel(channel)
        if (capability.status != ArchiveStatus.AVAILABLE) return null
        val effective = capability.effectiveDepthMs ?: return null
        val limitDays = userArchiveLimitDays()
        // The user limit only ever narrows the provider depth, never widens it.
        val capped = if (limitDays > 0) {
            minOf(effective, limitDays * ArchiveCapability.DAY_MS)
        } else {
            effective
        }
        return capped
    }

    private fun isFresh(capability: ArchiveCapability): Boolean =
        capability.lastProbedAt?.let { clock() - it < PROBE_TTL_MS } ?: false

    /**
     * Cache-miss fallback derived purely from the channel attributes: declared
     * days are usable without a probe ONLY when a supported format can build
     * the archive URL; otherwise the status stays honest (NONE / UNKNOWN).
     */
    private fun deriveFromChannel(channel: Channel): ArchiveCapability {
        if (!channel.hasCatchupMechanism) {
            return ArchiveCapability(channelId = channel.id, status = ArchiveStatus.NONE)
        }
        val playable = builder.isArchivePlayable(channel)
        channel.catchupDays?.let { days ->
            if (days > 0L && playable) {
                return ArchiveCapability(
                    channelId = channel.id,
                    status = ArchiveStatus.AVAILABLE,
                    declaredDays = days,
                    lastProbedAt = clock(),
                )
            }
        }
        return ArchiveCapability(
            channelId = channel.id,
            status = if (playable) ArchiveStatus.UNKNOWN else ArchiveStatus.NONE,
            declaredDays = channel.catchupDays,
            lastProbedAt = clock(),
        )
    }

    private companion object {
        const val PROBE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
