package com.tvnext.app.core.iptv.data

import android.content.Context

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import io.ktor.client.HttpClient
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Each enabled source owns its memory/disk cache. Reading EPG never starts a download. */
class AndroidEpgRepository(
    private val client: HttpClient,
    private val playlists: PlaylistRepository,
    private val channels: ChannelsRepository,
    private val config: EpgConfigProvider,
    context: Context,
) : EpgRepository {
    private val lock = Mutex()
    private val refreshLock = Mutex()
    // Provider and fallback XMLTV imports share one CPU/disk budget.
    private val importLock = Mutex()
    private val pendingForce = AtomicBoolean(false)
    private val pendingDeferred = AtomicBoolean(false)
    @Volatile private var deferredRefreshJob: Job? = null
    @Volatile private var fallbackCacheWarm = false
    // Only sources completed by the background importer can serve lookups.
    // This prevents a cold fallback-cache hydration on the channel UI path.
    @Volatile private var readyFallbackSources: Set<LiteEpgRepository> = emptySet()

    private fun deferProviderUntilForeground(force: Boolean) {
        if (force) pendingForce.set(true)
        pendingDeferred.set(true)
        synchronized(this) {
            if (deferredRefreshJob?.isActive == true) return
            deferredRefreshJob = retryScope.launch {
                try {
                    // A background transition during a resumed import may set
                    // pending again. Do not lose requests while this Job runs.
                    while (pendingDeferred.getAndSet(false)) {
                        AndroidEpgPlaybackGate.awaitForeground()
                        delay(750)
                        AndroidEpgPlaybackGate.awaitForeground()
                        val forced = pendingForce.getAndSet(false)
                        refreshIfStale(force = forced)
                    }
                } finally {
                    synchronized(this@AndroidEpgRepository) {
                        deferredRefreshJob = null
                        // Handle a request racing the loop's final condition.
                        if (pendingDeferred.get()) deferProviderUntilForeground(false)
                    }
                }
            }
        }
    }
    private var retryJob: Job? = null
    private var consecutiveFailures = 0
    // Multiple entry points (startup / channel refresh / TV guide) may ask for
    // stale EPG. Never download the same large feed several times in minutes.
    // A manual refresh (force=true) deliberately bypasses this cooldown.
    private var nextAutoRefreshAt = 0L
    private companion object {
        const val AUTO_COOLDOWN_MS = 30L * 60 * 1000
        const val FAILURE_RETRY_MS = 15L * 60 * 1000
        const val FALLBACK_SUCCESS_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val FALLBACK_FAILURE_INTERVAL_MS = 30L * 60 * 1000
    }
    private val engines = mutableMapOf<String, LiteEpgRepository>()
    private val fallbackSettings = AndroidFallbackEpgSettings(context)
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fallbackLock = Mutex()
    private val fallbackEngines = linkedMapOf<String, LiteEpgRepository>()
    // Schedule one fallback fetch per 12 hours (or 30 min after a failure),
    // rather than one HTTP request per missing channel or per UI recomposition.
    private val nextFallbackFetch = java.util.concurrent.atomic.AtomicLong(0L)

    /** Invalidate a previously downloaded fallback after a settings change. */
    fun fallbackSettingsChanged() {
        nextFallbackFetch.set(0L)
        fallbackCacheWarm = false
        readyFallbackSources = emptySet()
        fallbackScope.launch {
            fallbackLock.withLock { fallbackEngines.clear() }
            _version.update { it + 1L }
        }
    }

    private suspend fun fallbackSources(): List<LiteEpgRepository> {
        if (!config.isEnabled() || !fallbackSettings.enabled()) return emptyList()
        val urls = fallbackSettings.urls()
        if (urls.isEmpty()) return emptyList()
        return fallbackLock.withLock {
            fallbackEngines.keys.retainAll(urls.toSet())
            urls.map { url ->
                fallbackEngines.getOrPut(url) {
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                    val directory = platformEpgCacheDir()?.let {
                        File(it, "external-" + hash).apply { mkdirs() }.absolutePath
                    }
                    val provider = object : EpgConfigProvider {
                        override suspend fun isEnabled() = config.isEnabled()
                        override suspend fun userEpgUrl(): String = url
                        override suspend fun userArchiveLimitDays() = config.userArchiveLimitDays()
                    }
                    LiteEpgRepository(client, playlists, channels, provider, cacheDir = directory)
                }
            }
        }
    }

    /** Cached sources queried in priority order; one failed URL cannot hide the next. */
    private suspend fun <T> fromFallback(block: suspend (LiteEpgRepository) -> T): List<T> {
        // Never hydrate a cold multi-hundred-MB backup synchronously on the
        // guide lookup path. The background importer warms it before publishing.
        if (!fallbackCacheWarm) return emptyList()
        val sources = try { fallbackSources() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
        val results = ArrayList<T>(sources.size)
        val ready = readyFallbackSources
        for (source in sources) {
            if (source !in ready) continue
            try { results.add(block(source)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Other sources remain available. */ }
        }
        return results
    }

    private fun requestFallbackIfMissing(missing: Boolean) {
        if (!missing || _state.value.isLoading || !fallbackSettings.enabled()) return
        val now = System.currentTimeMillis()
        while (true) {
            val previous = nextFallbackFetch.get()
            if (now < previous) return
            if (nextFallbackFetch.compareAndSet(previous, now + FALLBACK_SUCCESS_INTERVAL_MS)) break
        }
        fallbackScope.launch {
            // Small coalescing delay prevents duplicate fetches on initial
            // channel navigation, but video playback never blocks imports.
            AndroidEpgPlaybackGate.awaitForeground()
            delay(750)
            AndroidEpgPlaybackGate.awaitForeground()
            // Run only after the provider import is complete; avoid competing
            // with its final cache write on a low-powered Android TV device.
            if (_state.value.isLoading) {
                // Another importer owns the budget; wait until a later lookup.
                nextFallbackFetch.set(System.currentTimeMillis() + 60_000L)
                return@launch
            }
            try {
                val sources = fallbackSources()
                if (sources.isEmpty()) return@launch
                var failures = 0
                for (repo in sources) {
                    AndroidEpgPlaybackGate.awaitForeground()
                    // Import sources one at a time on the background worker;
                    // do not contend with the video decoder or cache writer.
                    val before = repo.observeVersion().first()
                    val result = importLock.withLock { repo.refreshIfStale(force = false) }
                    if (!AndroidEpgPlaybackGate.canImport && result is AppResult.Failure) {
                        // A backgrounded app must not mark the interrupted
                        // refresh as fresh for twelve hours.
                        nextFallbackFetch.set(0L)
                        return@launch
                    }
                    if (result is AppResult.Failure) failures++
                    else {
                        readyFallbackSources = readyFallbackSources + repo
                        fallbackCacheWarm = true
                        if (repo.observeVersion().first() != before) _version.update { it + 1L }
                    }
                    // Never load the next multi-week backup if the Android TV
                    // heap is already close to full. Existing sources remain.
                    val runtime = Runtime.getRuntime()
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    if (used * 100L > runtime.maxMemory() * 65L) {
                        _state.update { it.copy(notice =
                            "Дополнительные источники EPG отложены: мало памяти") }
                        break
                    }
                    delay(250)
                }
                if (failures == sources.size)
                    nextFallbackFetch.set(System.currentTimeMillis() + FALLBACK_FAILURE_INTERVAL_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                nextFallbackFetch.set(System.currentTimeMillis() + FALLBACK_FAILURE_INTERVAL_MS)
            }
        }
    }

    private val _state = MutableStateFlow(EpgState())
    private val _version = MutableStateFlow(0L)
    override fun observeState(): StateFlow<EpgState> = _state.asStateFlow()
    override fun observeVersion(): Flow<Long> = _version.asStateFlow()

    private suspend fun enabledPlaylists() = playlists.getPlaylists()
        .filter { it.enabled && it.type == PlaylistType.LIVE }

    private suspend fun engine(id: String): LiteEpgRepository? {
        val playlist = playlists.getPlaylist(id)?.takeIf { it.enabled && it.type == PlaylistType.LIVE }
            ?: return null
        val source = config.userEpgUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: playlist.epgUrl.orEmpty()
        val key = id + "\u0000" + source
        return lock.withLock {
            // A source change must not retain the old schedule in memory.
            engines.keys.removeAll { it.startsWith(id + "\u0000") && it != key }
            engines.getOrPut(key) {
                val isolatedPlaylists = object : PlaylistRepository by playlists {
                    override suspend fun getPlaylists(): List<Playlist> =
                        listOfNotNull(playlists.getPlaylist(id)?.takeIf { it.enabled && it.type == PlaylistType.LIVE })
                    override suspend fun getDefaultPlaylist(): Playlist? = getPlaylists().firstOrNull()
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val dir = platformEpgCacheDir()?.let { File(it, hash).apply { mkdirs() }.absolutePath }
                LiteEpgRepository(client, isolatedPlaylists, channels, config, cacheDir = dir)
            }
        }
    }

    override suspend fun refreshState() {
        val active = enabledPlaylists()
        val default = playlists.getDefaultPlaylist()?.takeIf { p -> active.any { it.id == p.id } }
            ?: active.firstOrNull()
        val delegate = default?.let { engine(it.id) }
        delegate?.refreshState()
        val busy = _state.value.isLoading
        _state.value = (delegate?.observeState()?.value ?: EpgState(enabled = config.isEnabled()))
            .copy(isLoading = busy)
    }

    // Do not queue repeat OK presses behind an in-progress XMLTV import.
    // A queued force refresh can starve low-power TV devices for minutes.
    override suspend fun refreshIfStale(force: Boolean): AppResult<Unit> {
        if (!AndroidEpgPlaybackGate.canImport) {
            deferProviderUntilForeground(force)
            _state.update { it.copy(notice = "EPG обновится при возвращении в приложение") }
            return AppResult.Success(Unit)
        }
        if (!refreshLock.tryLock()) return AppResult.Success(Unit)
        return try {
            val startedAt = System.currentTimeMillis()
            if (!force && startedAt < nextAutoRefreshAt) return AppResult.Success(Unit)
            // Prevent another entry point from immediately starting a second
            // import while this one is still active or has just completed.
            nextAutoRefreshAt = startedAt + AUTO_COOLDOWN_MS
            val result = withContext(Dispatchers.IO.limitedParallelism(1)) {
                importLock.withLock { refresh(activeIds = enabledPlaylists().map { it.id }, force = force) }
            }
            if (!AndroidEpgPlaybackGate.canImport && result is AppResult.Failure) {
                // Retry a foreground-interrupted import without waiting for
                // the 30-minute automatic cooldown. Playing video is permitted.
                nextAutoRefreshAt = 0L
                deferProviderUntilForeground(force = true)
                _state.update { it.copy(isLoading = false,
                    lastError = null, notice = "EPG продолжится при возвращении в приложение") }
                return AppResult.Success(Unit)
            }
            if (result is AppResult.Failure) {
                consecutiveFailures++
                nextAutoRefreshAt = System.currentTimeMillis() + FAILURE_RETRY_MS
                // Each download itself already gets one bounded retry below.
                // Schedule at most one delayed auto retry per failed cycle;
                // do not hammer a failing provider every 60/120/180 seconds.
                if (consecutiveFailures == 1 && retryJob?.isActive != true) {
                    retryJob = retryScope.launch {
                        delay(FAILURE_RETRY_MS)
                        refreshIfStale(force = false)
                    }
                }
            } else {
                consecutiveFailures = 0
                nextAutoRefreshAt = System.currentTimeMillis() + AUTO_COOLDOWN_MS
                // A pending retry is harmless: it is freshness-guarded and
                // cannot start another download within the cooldown.
            }
            result
        } finally { refreshLock.unlock() }
    }

    override suspend fun refreshPlaylist(playlistId: String, force: Boolean): AppResult<Unit> {
        if (!AndroidEpgPlaybackGate.canImport) {
            deferProviderUntilForeground(force)
            _state.update { it.copy(notice = "EPG обновится при возвращении в приложение") }
            return AppResult.Success(Unit)
        }
        if (!refreshLock.tryLock()) return AppResult.Success(Unit)
        return try {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                importLock.withLock { refresh(activeIds = listOf(playlistId), force = force) }
            }
        } finally { refreshLock.unlock() }
    }

    private suspend fun refresh(activeIds: List<String>, force: Boolean): AppResult<Unit> {
        if (!config.isEnabled()) {
            _state.update { it.copy(enabled = false, isLoading = false) }
            return AppResult.Success(Unit)
        }
        // Prune deleted/disabled source engines; their programmes must not stay retained.
        val enabledIds = enabledPlaylists().map { it.id }.toSet()
        lock.withLock { engines.keys.removeAll { it.substringBefore('\u0000') !in enabledIds } }
        _state.update { it.copy(isLoading = true, lastError = null) }
        var failure: AppResult.Failure? = null
        try {
            for (id in activeIds) {
                if (config.userEpgUrl().isNullOrBlank() && playlists.getPlaylist(id)?.epgUrl == null) {
                    playlists.refreshPlaylist(id)
                }
                val delegate = engine(id) ?: continue
                val before = delegate.observeVersion().first()
                // Lite refreshPlaylist always downloads; freshness lives in refreshIfStale.
                // One IO worker feeds the import; report staging progress at a
                // low frequency so Compose never recomposes for every XMLTV row.
                val result = coroutineScope {
                    val reporter = launch {
                        while (true) {
                            delay(750)
                            val snapshot = delegate.observeState().value
                            _state.update { previous -> previous.copy(
                                channelCount = snapshot.channelCount,
                                programmeCount = snapshot.programmeCount,
                            ) }
                        }
                    }
                    try {
                        var attempt = delegate.refreshIfStale(force = force)
                        val heap = Runtime.getRuntime()
                        val used = heap.totalMemory() - heap.freeMemory()
                        if (attempt is AppResult.Failure && AndroidEpgPlaybackGate.canImport &&
                            used * 100L < heap.maxMemory() * 65L) {
                            // One bounded retry for a dropped XMLTV transfer.
                            // Never retry indefinitely or switch the playing stream.
                            delay(3_000)
                            attempt = delegate.refreshIfStale(force = true)
                        }
                        attempt
                    } finally { reporter.cancelAndJoin() }
                }
                if (result is AppResult.Failure) failure = result
                if (delegate.observeVersion().first() != before) _version.update { it + 1 }
            }
            refreshState()
            if (failure != null) _state.update { it.copy(lastError = "Не удалось обновить один из источников EPG") }
            return failure ?: AppResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(lastError = "Не удалось обновить EPG") }
            return AppResult.Failure(AppError.Network(error))
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    override suspend fun getNowNext(channel: Channel, at: Long): NowNext {
        if (!config.isEnabled()) return NowNext()
        val primary = engine(channel.playlistId)?.getNowNext(channel, at) ?: NowNext()
        if (primary.current != null && primary.next != null) return primary
        requestFallbackIfMissing(primary.current == null || primary.next == null)
        val external = fromFallback { it.getNowNext(channel, at) }
        return NowNext(primary.current ?: external.firstNotNullOfOrNull { it.current },
            primary.next ?: external.firstNotNullOfOrNull { it.next })
    }

    override suspend fun getUpcomingProgrammes(channel: Channel, from: Long, limit: Int): List<EpgProgramme> {
        if (limit <= 0 || !config.isEnabled()) return emptyList()
        val primary = engine(channel.playlistId)?.getUpcomingProgrammes(channel, from, limit).orEmpty()
        if (primary.isNotEmpty()) return primary
        requestFallbackIfMissing(true)
        return fromFallback { it.getUpcomingProgrammes(channel, from, limit) }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private suspend fun <T> grouped(
        requested: List<Channel>,
        query: suspend (EpgRepository, List<Channel>) -> Map<String, T>,
    ): Map<String, T> {
        if (!config.isEnabled()) return emptyMap()
        val result = mutableMapOf<String, T>()
        for ((id, group) in requested.groupBy { it.playlistId }) {
            engine(id)?.let { result.putAll(query(it, group)) }
        }
        return result
    }

    override suspend fun getNowNextForChannels(requested: List<Channel>, at: Long): Map<String, NowNext> {
        val primary = grouped(requested) { repo, group -> repo.getNowNextForChannels(group, at) }
        val missing = requested.filter { primary[it.id]?.current == null || primary[it.id]?.next == null }
        if (missing.isEmpty()) return primary
        requestFallbackIfMissing(true)
        val result = primary.toMutableMap()
        for (external in fromFallback { it.getNowNextForChannels(missing, at) }) {
            for ((id, guide) in external) {
                val original = result[id]
                result[id] = NowNext(original?.current ?: guide.current,
                    original?.next ?: guide.next)
            }
        }
        return result
    }

    override suspend fun getNowPlaying(requested: List<Channel>, at: Long): Map<String, EpgProgramme> {
        val primary = grouped(requested) { repo, group -> repo.getNowPlaying(group, at) }
        val missing = requested.filter { it.id !in primary }
        if (missing.isEmpty()) return primary
        requestFallbackIfMissing(true)
        val result = primary.toMutableMap()
        for (external in fromFallback { it.getNowPlaying(missing, at) }) {
            for ((id, programme) in external) result.putIfAbsent(id, programme)
        }
        return result
    }

    override suspend fun getProgrammesInRange(
        requested: List<Channel>, from: Long, to: Long,
    ): Map<String, List<EpgProgramme>> {
        val primary = grouped(requested) { repo, group -> repo.getProgrammesInRange(group, from, to) }
        // A provider may have only today's events while a backup has older
        // days. Merge holes in the requested interval, never replace overlapping
        // provider broadcasts. This also fixes archived days visible in the TV
        // guide but missing in the channel list.
        // Compute potentially thousands of EPG gaps off Main. zipWithNext()
        // allocated an entire pair-list per channel on every guide request.
        val incomplete = withContext(Dispatchers.Default) {
            requested.filter { channel ->
                val entries = primary[channel.id].orEmpty()
                needsFallbackEntries(entries, from, to)
            }
        }
        if (incomplete.isEmpty()) return primary
        requestFallbackIfMissing(true)
        // Fallback engines are asked about missing channels only (not all 1700).
        val result = primary.toMutableMap()
        for (external in fromFallback { it.getProgrammesInRange(incomplete, from, to) }) {
            for (channel in incomplete) {
                val incoming = external[channel.id].orEmpty()
                if (incoming.isNotEmpty()) result[channel.id] = mergePrimaryAndFallback(
                    result[channel.id].orEmpty(), incoming)
            }
        }
        return result
    }

    /** Fast, allocation-free gap check. Runs on a background dispatcher. */
    private fun needsFallbackEntries(entries: List<EpgProgramme>, from: Long, to: Long): Boolean {
        if (entries.isEmpty()) return true
        if (entries.first().start > from + 60_000L || entries.last().stop < to - 60_000L) return true
        for (index in 1 until entries.size) {
            if (entries[index].start > entries[index - 1].stop + 30L * 60_000L) return true
        }
        return false
    }

    /**
     * Fill only uncovered provider slots. Both inputs are chronological XMLTV
     * lists; scan the provider once instead of checking every new entry against
     * every old one (which made large fallback guides expensive during playback).
     * Earlier backup feeds win over later ones, while provider always wins.
     */
    private fun mergePrimaryAndFallback(
        primary: List<EpgProgramme>, fallback: List<EpgProgramme>,
    ): List<EpgProgramme> {
        if (fallback.isEmpty()) return primary
        val baseline = primary.sortedBy { it.start }
        val candidates = fallback.sortedBy { it.start }
        val accepted = ArrayList<EpgProgramme>()
        var providerIndex = 0
        for (programme in candidates) {
            if (programme.stop <= programme.start) continue
            while (providerIndex < baseline.size && baseline[providerIndex].stop <= programme.start) {
                providerIndex++
            }
            // XMLTV provider entries are normally nonoverlapping. Still check
            // every potentially intersecting item, not only the first one.
            var i = providerIndex
            var overlapsProvider = false
            while (i < baseline.size && baseline[i].start < programme.stop) {
                if (baseline[i].stop > programme.start) {
                    overlapsProvider = true
                    break
                }
                i++
            }
            val overlapsEarlierBackup = accepted.lastOrNull()?.stop?.let { it > programme.start } ?: false
            if (!overlapsProvider && !overlapsEarlierBackup) accepted.add(programme)
        }
        if (accepted.isEmpty()) return primary
        return (primary + accepted).sortedBy { it.start }
    }

    override suspend fun getAvailableProgrammeRange(requested: List<Channel>): LongRange? {
        if (!config.isEnabled()) return null
        val ranges = requested.groupBy { it.playlistId }.mapNotNull { (id, group) ->
            engine(id)?.getAvailableProgrammeRange(group)
        }
        val external = fromFallback { it.getAvailableProgrammeRange(requested) }
        val all = ranges + external.filterNotNull()
        return if (all.isEmpty()) null else all.minOf { it.first }..all.maxOf { it.last }
    }

    override suspend fun getMatchingDiagnostics(): EpgDiagnostics? = null

    override suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit> {
        if (limit <= 0 || query.isBlank() || !config.isEnabled()) return emptyList()
        val result = mutableListOf<ProgrammeSearchHit>()
        for (playlist in enabledPlaylists()) {
            result += engine(playlist.id)?.searchProgrammes(query, limit).orEmpty()
        }
        return result.sortedBy { it.programme.start }.take(limit)
    }
}
