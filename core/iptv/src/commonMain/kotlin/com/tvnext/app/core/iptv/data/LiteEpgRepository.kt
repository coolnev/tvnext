package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.tvnext.app.core.common.log.Logger
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.common.result.AppResult.Failure
import com.tvnext.app.core.common.result.AppResult.Success
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lightweight EPG engine — a Kotlin port of the LiteTV approach.
 *
 * The old pipeline imported a huge XMLTV into SQLite through a native C++
 * parser; on low-end Android TV boxes that memory spike silently killed the
 * app ("it just closes"). This implementation follows LiteTV instead:
 *
 *  - the whole schedule lives in memory as `Map<playlistId|channelId,
 *    List<EpgProgramme>>`, keyed by the playlist channel, never in SQLite;
 *  - the XMLTV document is streamed ([LiteXmltvParser]) and only the
 *    programmes of OUR channels are kept (title + start/stop — no giant
 *    descriptions), so memory stays proportional to the visible schedule, not
 *    to the (multi-hundred-MB) document;
 *  - a compact line-based disk cache is written after an import and read back
 *    almost instantly on the next launch ("EPG is there right after start");
 *  - one refresh at a time (mutex), fresh cache is skipped, failures are
 *    throttled by a short retry delay and surfaced to the UI.
 *
 * The [EpgRepository] contract is preserved so every screen (now-playing,
 * TV guide, search) keeps working unchanged.
 */
class LiteEpgRepository(
    private val client: HttpClient,
    private val playlistRepository: PlaylistRepository,
    private val channelsRepository: ChannelsRepository,
    private val configProvider: EpgConfigProvider,
    private val cacheDir: String? = platformEpgCacheDir(),
    private val clock: () -> Long = ::currentTimeMillis,
) : EpgRepository {

    private val parser = LiteXmltvParser()
    private val androidImportDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(EpgState())
    private val _version = MutableStateFlow(0L)

    /** All matched programmes: `playlistId|channelId` -> sorted-by-start list. */
    private val programs = HashMap<String, List<EpgProgramme>>()

    /** Guards [programs], [cacheLoaded], [loadedSourceKey], [cacheTimestamp]. */
    private val dataLock = Mutex()

    /** Guards the whole refresh (download+parse+save) — one import at a time. */
    private val refreshLock = Mutex()

    @Volatile private var cacheLoaded = false
    private var androidHydrationAttempted = false
    // Compact now/next cache: loaded independently of the potentially enormous
    // multi-week XMLTV history, so the channel list appears immediately.
    private val quickLock = Mutex()
    @Volatile private var quickCacheAttempted = false
    @Volatile private var quickCacheLoaded = false
    @Volatile private var quickPrograms: Map<String, List<EpgProgramme>> = emptyMap()
    private var loadedSourceKey = ""
    private var loadedSourceHash: Int? = null
    @Volatile private var cacheTimestamp = 0L
    private var legacyDescriptionCache = false
    private var lastImportFailedAt = 0L
    @Volatile private var cacheSaveFailed = false

    override fun observeState(): StateFlow<EpgState> = _state.asStateFlow()

    override fun observeVersion(): Flow<Long> = _version

    override suspend fun refreshState() {
        // Hydrate the small current-programme snapshot before any network work.
        // A full archive scan is deferred until a guide/history query needs it.
        if (isAndroidPlatform()) ensureQuickCacheLoaded()
        // Keep the current lastError/notice: refreshState only refreshes the
        // metadata — an earlier failure must stay visible on the Settings
        // screen until the next successful import clears it.
        val current = _state.value
        val enabled = runCatching { configProvider.isEnabled() }.getOrDefault(true)
        val overrideUrl = runCatching { configProvider.userEpgUrl() }.getOrDefault(null)
        val playlist = focusedPlaylist()
        val effectiveUrl = playlist?.let { resolveEffectiveUrl(overrideUrl, it.epgUrl) }
        dataLock.withLock {
            _state.value = EpgState(
                enabled = enabled,
                playlistId = playlist?.id,
                playlistName = playlist?.name,
                detectedUrl = playlist?.epgUrl,
                overrideUrl = overrideUrl.orEmpty(),
                effectiveUrl = effectiveUrl,
                lastUpdated = cacheTimestamp.takeIf { it > 0L },
                channelCount = if (cacheLoaded) programs.size else quickPrograms.size,
                programmeCount = if (cacheLoaded) programs.values.sumOf { it.size }
                    else quickPrograms.values.sumOf { it.size },
                lastError = current.lastError,
                notice = current.notice,
            )
        }
    }

    /**
     * At most TWO DIFFERENT XMLTV endpoints download together. Parsing and
     * publishing always happen one source at a time, so two big XML documents
     * never compete for CPU with Media3 or overwrite each other's channel rows.
     * A common URL is downloaded just once for a pair of playlists.
     */
    override suspend fun refreshIfStale(force: Boolean): AppResult<Unit> = refreshLock.withLock {
        val enabled = runCatching { configProvider.isEnabled() }.getOrDefault(true)
        if (!enabled) {
            _state.update { it.copy(enabled = false, isLoading = false, lastError = null, notice = null) }
            return Success(Unit)
        }
        cacheSaveFailed = false
        val overrideUrl = runCatching { configProvider.userEpgUrl() }.getOrDefault(null)
        val playlists = playlistRepository.getPlaylists()
            .filter { it.enabled && it.type == PlaylistType.LIVE }
        val sources = ArrayList<EpgSource>()
        for (playlist in playlists) {
            // With a global override (including a backup XMLTV), never refresh
            // the underlying M3U just to discover its url-tvg: it is irrelevant
            // and can stall the TV UI on large / slow playlists.
            val epgUrl = if (isAndroidPlatform() && !overrideUrl.isNullOrBlank()) playlist.epgUrl
                else playlist.epgUrl ?: resolveDetectedUrlOnce(playlist)
            val effectiveUrl = resolveEffectiveUrl(overrideUrl, epgUrl) ?: continue
            val channels = channelsRepository.getChannelsByPlaylist(playlist.id)
            if (channels.isNotEmpty()) sources.add(EpgSource(playlist, channels, effectiveUrl))
        }
        if (sources.isEmpty()) {
            _state.update { it.copy(isLoading = false, lastError = null,
                notice = "EPG не настроен: добавьте url-tvg в M3U или укажите URL в Настройках → EPG") }
            refreshState()
            return Failure(AppError.InvalidData("EPG не настроен"))
        }

        // The disk cache may contain rows for several playlists. Invalidate
        // ONLY when the complete source configuration changes; the previous
        // per-playlist comparison discarded earlier playlists on every import.
        val signature = sources.map { sourceKeyFor(it.playlist.id, it.url) }
            .sorted().joinToString("\u0001")
        val (needsDownload, dataChanged) = withContext(
            if (isAndroidPlatform()) epgParseDispatcher() else Dispatchers.Default
        ) {
            dataLock.withLock {
                var changed = false
                val onDiskSourceHash = if (isAndroidPlatform())
                    readCacheFileHeader()?.second else null
                if ((loadedSourceKey.isNotEmpty() && loadedSourceKey != signature) ||
                    (loadedSourceHash ?: onDiskSourceHash)?.let { it != signature.hashCode() } == true
                ) {
                    programs.clear()
                    quickPrograms = emptyMap()
                    quickCacheLoaded = false
                    quickCacheAttempted = false
                    loadedSourceKey = ""
                    loadedSourceHash = null
                    cacheLoaded = false
                    cacheTimestamp = 0L
                    deleteCacheFile()
                    changed = true
                }
                if (!cacheLoaded && !(isAndroidPlatform() && quickCacheLoaded && isCacheFresh())) {
                    cacheLoaded = readCacheIntoMemoryLocked()
                    changed = cacheLoaded
                }
                // A fresh durable snapshot is sufficient: low-memory devices
                // need not re-download the feed just because full hydration is
                // too costly. The timeline can read the full cache on demand.
                (force || !isCacheFresh() || (!cacheLoaded && !quickCacheLoaded)) to changed
            }
        }
        if (!needsDownload) {
            dataLock.withLock {
                loadedSourceKey = signature
                loadedSourceHash = signature.hashCode()
            }
            if (dataChanged) _version.update { it + 1 }
            _state.update { it.copy(isLoading = false, lastError = null, notice = null) }
            refreshState()
            return Success(Unit)
        }
        if (!force && clock() < lastImportFailedAt) {
            _state.update { it.copy(isLoading = false,
                notice = "Повторная попытка EPG будет через несколько минут") }
            refreshState()
            return Failure(AppError.Network(cause = null))
        }

        _state.update { it.copy(isLoading = true, lastError = null, notice = "Загрузка EPG…") }
        var firstError: AppError? = null
        var anyImported = false
        // Two network requests at most; for one source stay on the original
        // streaming path, which starts presenting EPG without staging a file.
        val uniqueSources = sources.groupBy { it.url }.toList()
        // Android TV: no two huge XMLTV network/temp-file jobs compete with
        // the playback I/O. Desktop can retain the two-source prefetch.
        for (pair in uniqueSources.chunked(if (isAndroidPlatform()) 1 else 2)) {
            val prefetched: Map<String, AppResult<String>> = if (
                uniqueSources.size > 1 || (isAndroidPlatform() && sources.size > 1)
            ) coroutineScope {
                pair.map { (url, _) ->
                    async(Dispatchers.IO) { url to downloadEpgToTempFile(client, url) }
                }.awaitAll().associate { it }
            } else emptyMap()
            try {
                for ((url, sourceGroup) in pair) {
                    // Always delete staged files after all playlists using a
                    // shared URL have been parsed. Failed download falls back
                    // to the existing one-source streaming retry behavior.
                    val path = (prefetched[url] as? Success)?.data
                    for (source in sourceGroup) {
                        val ok = withContext(if (isAndroidPlatform()) androidImportDispatcher else Dispatchers.Default) {
                            importPlaylistLocked(source.playlist, source.channels, url, path,
                                persistCache = sources.size == 1)
                        }
                        if (ok) anyImported = true
                        else if (firstError == null) firstError = AppError.Network(cause = null)
                    }
                }
            } finally {
                for ((_, result) in prefetched) {
                    val path = (result as? Success)?.data
                    if (path != null) deleteEpgTempFile(path)
                }
            }
        }
        if (anyImported) {
            // Multiple playlist imports are committed to disk ONCE after all
            // parsing is complete. Rewriting a huge EPG file after every
            // playlist makes video stutter on low-end eMMC storage.
            if (sources.size > 1) withContext(if (isAndroidPlatform()) androidImportDispatcher else Dispatchers.IO) {
                val snapshot = dataLock.withLock { programs.toMap() to cacheTimestamp }
                writeCacheSnapshot(snapshot.first, snapshot.second, signature.hashCode())
            }
            if (firstError == null) dataLock.withLock {
                loadedSourceKey = signature
                loadedSourceHash = signature.hashCode()
            }
            _version.update { it + 1 }
            _state.update {
                it.copy(isLoading = false, lastError = if (firstError != null)
                    "Не все источники EPG обновились" else null,
                    notice = when {
                        cacheSaveFailed -> "EPG загружена, но не удалось сохранить на диск. Проверьте свободное место."
                        firstError != null -> "Старая программа неуспешного источника сохранена"
                        else -> null
                    })
            }
            refreshState()
            return Success(Unit)
        }
        _state.update {
            it.copy(isLoading = false,
                lastError = "Не удалось загрузить EPG. Проверьте URL и интернет-соединение.", notice = null)
        }
        refreshState()
        return Failure(firstError ?: AppError.Network(cause = null))
    }

    private data class EpgSource(val playlist: Playlist, val channels: List<Channel>, val url: String)

    override suspend fun refreshPlaylist(playlistId: String, force: Boolean): AppResult<Unit> =
        refreshLock.withLock {
            val enabled = runCatching { configProvider.isEnabled() }.getOrDefault(true)
            if (!enabled) return Success(Unit)
            val playlist = playlistRepository.getPlaylist(playlistId)
                ?: return Failure(AppError.InvalidData("Плейлист не найден"))
            val overrideUrl = runCatching { configProvider.userEpgUrl() }.getOrDefault(null)
            val effectiveUrl = resolveEffectiveUrl(overrideUrl, playlist.epgUrl)
                ?: return Success(Unit)
            val channels = channelsRepository.getChannelsByPlaylist(playlistId)
            val ok = withContext(Dispatchers.Default) {
                importPlaylistLocked(playlist, channels, effectiveUrl)
            }
            if (ok) {
                // A single-playlist manual refresh must not replace the global
                // multi-source signature: doing so would cause the next routine
                // refresh to invalidate the cache and download everything again.
                dataLock.withLock { loadedSourceKey = ""; loadedSourceHash = null }
                _state.update { it.copy(isLoading = false, lastError = null, notice = null) }
                _version.update { it + 1 }
                refreshState()
                return Success(Unit)
            }
            refreshState()
            return Failure(AppError.Network(cause = null))
        }

    // ----------------------------------------------------------------- queries

    override suspend fun getUpcomingProgrammes(channel: Channel, from: Long, limit: Int): List<EpgProgramme> {
        ensureCacheLoaded()
        val list = dataLock.withLock { programs[keyOf(channel)] } ?: return emptyList()
        val result = ArrayList<EpgProgramme>(minOf(limit, 32))
        for (programme in list) {
            if (programme.start >= from) {
                result.add(programme)
                if (result.size >= limit) break
            }
        }
        return result
    }

    override suspend fun getNowNext(channel: Channel, at: Long): NowNext {
        if (isAndroidPlatform()) ensureQuickCacheLoaded() else ensureCacheLoaded()
        val list = liveSnapshotFor(channel) ?: return NowNext()
        var current: EpgProgramme? = null
        var next: EpgProgramme? = null
        for (programme in list) {
            if (programme.start <= at && at < programme.stop) {
                current = programme
            } else if (programme.start > at) {
                next = programme
                break
            }
        }
        return NowNext(current = current, next = next)
    }

    override suspend fun getNowNextForChannels(channels: List<Channel>, at: Long): Map<String, NowNext> {
        if (isAndroidPlatform()) ensureQuickCacheLoaded() else ensureCacheLoaded()
        val snapshot = liveSnapshotFor(channels)
        return withContext(Dispatchers.Default) {
            val result = HashMap<String, NowNext>(channels.size)
            for (channel in channels) {
                val list = snapshot[channel.id] ?: continue
                var current: EpgProgramme? = null
                var next: EpgProgramme? = null
                for (programme in list) {
                    if (programme.start <= at && at < programme.stop) {
                        current = programme
                    } else if (programme.start > at) {
                        next = programme
                        break
                    }
                }
                if (current != null || next != null) {
                    result[channel.id] = NowNext(current = current, next = next)
                }
            }
            result
        }
    }

    override suspend fun getNowPlaying(channels: List<Channel>, at: Long): Map<String, EpgProgramme> {
        if (isAndroidPlatform()) ensureQuickCacheLoaded() else ensureCacheLoaded()
        val snapshot = liveSnapshotFor(channels)
        return withContext(Dispatchers.Default) {
            val result = HashMap<String, EpgProgramme>(channels.size)
            for (channel in channels) {
                val list = snapshot[channel.id] ?: continue
                if (list.isEmpty()) continue
                var index = lowerBoundStart(list, at + 1L) - 1
                if (index < 0) index = 0
                // At most a couple of candidates around `at` are needed.
                // Never scan an entire channel's EPG to find the current item.
                while (index < list.size) {
                    val programme = list[index]
                    if (programme.start > at) break
                    if (programme.stop > at) {
                        result[channel.id] = programme
                        break
                    }
                    index++
                }
            }
            result
        }
    }

    override suspend fun getProgrammesInRange(
        channels: List<Channel>,
        from: Long,
        to: Long,
    ): Map<String, List<EpgProgramme>> {
        ensureCacheLoaded()
        // Take one immutable snapshot of the channel lists. The old code acquired
        // the mutex once per channel; with a 16-row viewport that created needless
        // lock traffic on every EPG refresh.
        val snapshot = dataLock.withLock {
            channels.associate { channel -> channel.id to programs[keyOf(channel)] }
        }
        return withContext(Dispatchers.Default) {
            val result = HashMap<String, List<EpgProgramme>>(channels.size)
            for (channel in channels) {
                val list = snapshot[channel.id] ?: run {
                    result[channel.id] = emptyList()
                    continue
                }
                if (list.isEmpty()) {
                    result[channel.id] = emptyList()
                    continue
                }
                // Lists are sorted by start. Start close to `from` instead of
                // scanning potentially hundreds of old programmes. Keep the
                // immediately preceding item because it may overlap `from`.
                var index = lowerBoundStart(list, from) - 1
                if (index < 0) index = 0
                val overlapping = ArrayList<EpgProgramme>(8)
                while (index < list.size) {
                    val programme = list[index]
                    if (programme.start >= to) break
                    if (programme.stop > from) overlapping.add(programme)
                    index++
                }
                result[channel.id] = overlapping
            }
            result
        }
    }

    override suspend fun getAvailableProgrammeRange(channels: List<Channel>): LongRange? {
        ensureCacheLoaded()
        val snapshot = dataLock.withLock {
            channels.mapNotNull { programs[keyOf(it)]?.takeIf { list -> list.isNotEmpty() } }
        }
        return withContext(Dispatchers.Default) {
            var minStart = Long.MAX_VALUE
            var maxStop = Long.MIN_VALUE
            snapshot.forEach { list ->
                minStart = minOf(minStart, list.first().start)
                maxStop = maxOf(maxStop, list.last().stop)
            }
            if (minStart == Long.MAX_VALUE || maxStop <= minStart) null else minStart..maxStop
        }
    }

    private fun lowerBoundStart(list: List<EpgProgramme>, value: Long): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid].start < value) low = mid + 1 else high = mid
        }
        return low
    }

    override suspend fun getMatchingDiagnostics(): EpgDiagnostics? = null

    override suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit> {
        ensureCacheLoaded()
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val snapshot = dataLock.withLock { HashMap(programs) }
            val playlists = playlistRepository.getPlaylists()
                .filter { it.enabled && it.type == PlaylistType.LIVE }
            val hits = ArrayList<ProgrammeSearchHit>(limit)
            for (playlist in playlists) {
                val channels = channelsRepository.getChannelsByPlaylist(playlist.id)
                    .associateBy { it.id }
                for ((key, list) in snapshot) {
                    val sep = key.indexOf('|')
                    if (sep < 0 || key.substring(0, sep) != playlist.id) continue
                    val channel = channels[key.substring(sep + 1)] ?: continue
                    for (programme in list) {
                        if (!programme.title.contains(normalized, ignoreCase = true)) continue
                        hits += ProgrammeSearchHit(
                            playlistId = playlist.id,
                            channelId = channel.id,
                            channelName = channel.name,
                            programme = programme,
                        )
                        if (hits.size >= limit) return@withContext hits
                    }
                }
            }
            hits.sortedBy { it.programme.start }
        }
    }

    // ------------------------------------------------------------ internals

    private fun keyOf(channel: Channel): String = "${channel.playlistId}|${channel.id}"

    private fun sourceKeyFor(playlistId: String, url: String): String = "$playlistId\u0000$url"

    private suspend fun focusedPlaylist(): Playlist? {
        playlistRepository.getDefaultPlaylist()?.let { default ->
            if (default.enabled && default.type == PlaylistType.LIVE) return default
        }
        return playlistRepository.getPlaylists().firstOrNull {
            it.enabled && it.type == PlaylistType.LIVE
        }
    }

    private suspend fun resolveEffectiveUrl(overrideUrl: String?, playlistUrl: String?): String? {
        val override = overrideUrl?.trim().takeIf { !it.isNullOrEmpty() }
        val detected = playlistUrl?.trim().takeIf { !it.isNullOrEmpty() }
        return override ?: detected
    }

    private suspend fun resolveDetectedUrlOnce(playlist: Playlist): String? {
        if (playlist.epgUrl != null) return playlist.epgUrl
        runCatching { playlistRepository.refreshPlaylist(playlist.id) }
        return runCatching { playlistRepository.getPlaylist(playlist.id) }.getOrNull()?.epgUrl
    }

    /** Quick/current guide lookups never queue behind a multi-week cache scan. */
    private suspend fun liveSnapshotFor(channel: Channel): List<EpgProgramme>? {
        if (isAndroidPlatform() && !cacheLoaded && quickCacheLoaded)
            return quickPrograms[keyOf(channel)]
        return dataLock.withLock { programs[keyOf(channel)] }
    }

    private suspend fun liveSnapshotFor(channels: List<Channel>): Map<String, List<EpgProgramme>?> {
        if (isAndroidPlatform() && !cacheLoaded && quickCacheLoaded) {
            val snapshot = quickPrograms
            return channels.associate { it.id to snapshot[keyOf(it)] }
        }
        return dataLock.withLock { channels.associate { it.id to programs[keyOf(it)] } }
    }

    /** Small disk snapshot is independent of the main EPG/import mutex. */
    private suspend fun ensureQuickCacheLoaded() {
        if (!isAndroidPlatform() || cacheLoaded || quickCacheLoaded) return
        quickLock.withLock {
            if (!quickCacheAttempted) {
                quickCacheAttempted = true
                val loaded = withContext(epgParseDispatcher()) { readQuickCache() }
                val completeHeader = readCacheFileHeader()
                if (loaded != null && completeHeader?.first == loaded.second &&
                    (completeHeader.second == null || loaded.third == completeHeader.second)
                ) {
                    quickPrograms = loaded.first
                    quickCacheLoaded = true
                    if (cacheTimestamp == 0L) cacheTimestamp = loaded.second
                    loadedSourceHash = loaded.third
                    _version.update { it + 1L }
                }
            }
        }
        // First launch after upgrading from an older version has no fast
        // snapshot yet. Read the existing complete cache once, then create it.
        if (!quickCacheLoaded && !cacheLoaded) ensureCacheLoaded()
    }

    private fun readQuickCache(): Triple<Map<String, List<EpgProgramme>>, Long, Int?>? {
        val dir = cacheDir ?: return null
        val restored = HashMap<String, MutableList<EpgProgramme>>()
        var first = true
        var stamp = 0L
        var sourceHash: Int? = null
        var count = 0
        val fullyRead = readEpgCacheLines("$dir/$FAST_CACHE_FILE") { line ->
            if (first) {
                first = false
                if (!line.startsWith("v1-fast:")) return@readEpgCacheLines false
                val fields = line.removePrefix("v1-fast:").split(':', limit = 2)
                stamp = fields[0].toLongOrNull() ?: 0L
                sourceHash = fields.getOrNull(1)?.toIntOrNull()
                return@readEpgCacheLines stamp > 0L
            }
            if (line.isBlank()) return@readEpgCacheLines true
            val parts = line.split(FIELD_SEP, limit = 7)
            if (parts.size < 5) return@readEpgCacheLines false
            val start = parts[2].toLongOrNull() ?: return@readEpgCacheLines false
            val stop = parts[3].toLongOrNull() ?: return@readEpgCacheLines false
            if (stop <= start) return@readEpgCacheLines false
            restored.getOrPut("${parts[0]}|${parts[1]}") { ArrayList() }.add(EpgProgramme(
                channelId = parts[1], start = start, stop = stop,
                title = unescape(parts[4]),
                description = if (parts.size > 5) unescape(parts[5]) else null,
                category = if (parts.size > 6) unescape(parts[6]) else null,
            ))
            ++count <= MAX_FAST_CACHE_ROWS &&
                (count % 1024 != 0 || !epgCacheMemoryExceeded())
        }
        if (!fullyRead || stamp <= 0L || count == 0) return null
        return Triple(restored.mapValues { (_, list) -> list.sortedBy { it.start } }, stamp, sourceHash)
    }

    private suspend fun ensureCacheLoaded() {
        // The full archive can be large. Its hydration belongs on a background
        // worker and the quick now/next snapshot remains usable meanwhile.
        val upgradeSnapshot = dataLock.withLock {
            if (cacheLoaded || (isAndroidPlatform() && androidHydrationAttempted)) return
            cacheLoaded = withContext(if (isAndroidPlatform()) epgParseDispatcher() else Dispatchers.Default) {
                readCacheIntoMemoryLocked()
            }
            if (isAndroidPlatform()) androidHydrationAttempted = true
            if (cacheLoaded) {
                val needsFastIndex = isAndroidPlatform() && !quickCacheLoaded
                // Once the complete immutable guide is resident, do not keep
                // a second set of current programmes in a small TV heap.
                if (isAndroidPlatform()) {
                    quickPrograms = emptyMap()
                    quickCacheLoaded = false
                    quickCacheAttempted = false
                }
                _version.update { it + 1L }
                if (needsFastIndex && cacheTimestamp > 0L)
                    programs.toMap() to cacheTimestamp
                else null
            } else null
        }
        // An upgrade from a previous version creates the small index once,
        // outside the EPG data mutex so live channel queries can proceed.
        if (upgradeSnapshot != null) withContext(Dispatchers.IO) {
            writeQuickCacheSnapshot(upgradeSnapshot.first, upgradeSnapshot.second)
        }
    }

    /** Runs under [dataLock]. Returns true when programmes landed in memory. */
    private fun readCacheIntoMemoryLocked(): Boolean {
        val dir = cacheDir ?: return false
        val path = "$dir/$CACHE_FILE"
        var stamp = 0L
        var headerSeen = false
        var count = 0
        var stoppedForMemory = false
        val ok = readEpgCacheLines(path) { line ->
            if (!headerSeen) {
                headerSeen = true
                legacyDescriptionCache = isAndroidPlatform() &&
                    !line.startsWith("v2-full:") && !line.startsWith("v3-full:")
                val fields = line.trim().substringAfter(':').split(':', limit = 2)
                stamp = fields[0].toLongOrNull() ?: 0L
                loadedSourceHash = fields.getOrNull(1)?.toIntOrNull()
                return@readEpgCacheLines stamp > 0L
            }
            if (line.isBlank()) return@readEpgCacheLines true
            val parts = line.split(FIELD_SEP, limit = 7)
            if (parts.size < 5) return@readEpgCacheLines true
            val start = parts[2].toLongOrNull() ?: return@readEpgCacheLines true
            val stop = parts[3].toLongOrNull() ?: return@readEpgCacheLines true
            val programme = EpgProgramme(
                channelId = parts[1],
                start = start,
                stop = stop,
                title = unescape(parts[4]),
                description = if (parts.size > 5) unescape(parts[5]) else null,
                category = if (parts.size > 6) unescape(parts[6]) else null,
            )
            val key = "${parts[0]}|${parts[1]}"
            val list = programs.getOrPut(key) { ArrayList() }
            if (list is ArrayList<*>) {
                @Suppress("UNCHECKED_CAST")
                (list as ArrayList<EpgProgramme>).add(programme)
            }
            count++
            // Cached schedules are untrusted in size too: fail cleanly rather
            // than allocating beyond the Android TV Java heap.
            if (isAndroidPlatform() && count % 1024 == 0) {
                if (epgCacheMemoryExceeded()) {
                    stoppedForMemory = true
                    false
                } else {
                    paceEpgCacheHydration()
                    true
                }
            } else true
        }
        if (!ok || stoppedForMemory || stamp <= 0L) {
            // Abort: a partial read must not leave half-loaded rows visible.
            programs.clear()
            return false
        }
        cacheTimestamp = stamp
        // Rebuild as sorted immutable lists.
        val rebuilt = HashMap<String, List<EpgProgramme>>(programs.size)
        for ((key, list) in programs) {
            if (isAndroidPlatform() && epgCacheMemoryExceeded()) {
                programs.clear()
                return false
            }
            rebuilt[key] = list.sortedBy { it.start }
        }
        programs.clear()
        programs.putAll(rebuilt)
        Logger.d(TAG, "EPG cache loaded: $count programmes across ${programs.size} channels")
        return count > 0
    }

    /** Runs under [dataLock]. Returns true when the on-disk cache is young. */
    private fun isCacheFresh(): Boolean {
        if (isAndroidPlatform() && legacyDescriptionCache) return false
        if (cacheTimestamp <= 0L) {
            val dir = cacheDir ?: return false
            cacheTimestamp = readCacheFileHeader()?.first ?: 0L
        }
        if (cacheTimestamp <= 0L) return false
        if (isAndroidPlatform() && quickCacheLoaded && !cacheLoaded) {
            // The fast index is only valid alongside its full EPG snapshot.
            // An interrupted write cannot make a missing full import look fresh.
            val dir = cacheDir ?: return false
            if (readCacheFileHeader()?.first != cacheTimestamp) return false
        }
        val now = clock()
        // Crossing midnight is NOT a reason to discard/re-download EPG.  A fresh
        // cache stays usable for the regular TTL, including archived programmes.
        val age = now - cacheTimestamp
        return age in 0L until EpgRepository.TTL_MS &&
            (isAndroidPlatform() || localDayOf(cacheTimestamp) == localDayOf(now))
    }

    private fun localDayOf(epochMs: Long): Long =
        (epochMs + localTimezoneOffsetMs(epochMs)) / DAY_MS

    private suspend fun importPlaylistLocked(
        playlist: Playlist,
        channels: List<Channel>,
        url: String,
        downloadedPath: String? = null,
        persistCache: Boolean = true,
    ): Boolean {
        if (channels.isEmpty()) return false
        val lookup = parser.buildChannelLookup(channels)
        val buffer = EpgBuffer()
        val now = clock()
        // Android low-memory mode: recent EPG descriptions only. The entire
        // archive timeline (titles/times) is retained. Full descriptions for
        // every entry in a multi-week 1700-channel guide can exhaust TV RAM.
        val windowStart = now - if (isAndroidPlatform()) ANDROID_DESC_PAST_MS else DESC_WINDOW_PAST_MS
        val windowEnd = now + if (isAndroidPlatform()) ANDROID_DESC_FUTURE_MS else DESC_WINDOW_FUTURE_MS

        // The stream is consumed INSIDE withEpgStream while the HTTP response
        // is open — the parser reads bytes as they arrive, and the incremental
        // publisher below flushes parsed batches every ~800 ms, so the first
        // channel rows appear long before the whole feed has downloaded.
        var importedCount = 0
        // Android publishes only a completed import: a broken stream cannot
        // overwrite the last good cache or accumulate obsolete programmes.
        val staged = if (isAndroidPlatform()) HashMap<String, MutableList<EpgProgramme>>() else null
        suspend fun drainBatch(): Int {
            if (staged == null) return publishBufferBatch(buffer)
            val batch = buffer.drain()
            batch.forEach { (key, list) -> staged.getOrPut(key) { ArrayList() }.addAll(list) }
            return batch.values.sumOf { it.size }
        }
        suspend fun consume(source: EpgTextSource) {
            coroutineScope {
                val parseJob = launch(if (isAndroidPlatform()) epgParseDispatcher() else Dispatchers.Default) {
                    parser.parse(source, lookup, playlist.id, buffer, windowStart, windowEnd,
                        fullDescriptions = false)
                }
                while (parseJob.isActive) {
                    delay(PUBLISH_INTERVAL_MS)
                    importedCount += drainBatch()
                    if (staged != null) {
                        _state.update { it.copy(isLoading = true,
                            channelCount = staged.size, programmeCount = importedCount) }
                    }
                }
                parseJob.join()
                importedCount += drainBatch()
            }
        }
        var deferredForBackground = false
        val streamOk = try {
            if (downloadedPath != null) {
                val source = openEpgTextSource(downloadedPath)
                try {
                    consume(source)
                    true
                } finally {
                    source.close()
                }
            } else withEpgStream(client, url) { source -> consume(source) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: EpgImportDeferredException) {
            deferredForBackground = true
            false
        } catch (_: EpgMemoryPressureException) {
            _state.update { it.copy(notice = "EPG слишком большой для памяти устройства") }
            false
        } catch (error: Exception) {
            Logger.e(TAG, "EPG source parse failed", error)
            false
        }
        val ok = streamOk && importedCount > 0

        if (!ok) {
            if (!deferredForBackground) lastImportFailedAt = clock() + IMPORT_RETRY_DELAY_MS
            _state.update {
                it.copy(
                    isLoading = false,
                    lastError = "Не удалось загрузить EPG. Проверьте URL и интернет-соединение.",
                    notice = null,
                )
            }
            return false
        }

        suspend fun commitImport() {
            // Keep previously downloaded archive programmes even when the
            // provider's rolling XMLTV feed now starts at the new day's 00:00.
            // The EPG cache is NOT the playable catch-up archive. The user's
            // display limit must never delete already downloaded programme
            // history: increasing it later should reveal cached broadcasts.
            // Retain ONLY real provider entries, bounded to protect TV RAM.
            val cutoffs = channels.associate { channel ->
                val providerDays = channel.catchupDays?.takeIf { it > 0L } ?: 0L
                val retentionDays = maxOf(UNKNOWN_ARCHIVE_HISTORY_DAYS, providerDays)
                    .coerceAtMost(MAX_EPG_HISTORY_DAYS)
                keyOf(channel) to (clock() - retentionDays * DAY_MS)
            }
            // Build immutable sorted lists OUTSIDE the shared EPG data lock.
            // Large feeds previously blocked all guide queries while they were
            // normalized AND written to disk, causing playback UI stalls.
            val normalized = if (staged != null) {
                val result = HashMap<String, List<EpgProgramme>>(staged.size)
                var processed = 0
                for ((key, list) in staged) {
                    if (isAndroidPlatform()) checkEpgImportPriority()
                    result[key] = parser.normalizeProgramList(list)
                    // TV boxes often share a few small cores with Media3. Yield
                    // frequently during the end-of-import sorting burst.
                    if (isAndroidPlatform() && ++processed % 12 == 0) delay(4)
                }
                result
            } else null
            if (normalized != null) {
                // Take references to immutable lists under a short mutex, then
                // do ALL archive merging and sorting outside the mutex. A big
                // EPG must not freeze timeline reads while Media3 is playing.
                val previous = dataLock.withLock {
                    cutoffs.keys.associateWith { programs[it].orEmpty() }
                }
                val importTime = clock()
                var mergedChannels = 0
                for ((key, cutoff) in cutoffs) {
                    if (isAndroidPlatform()) checkEpgImportPriority()
                    val old = previous[key].orEmpty()
                    if (old.isEmpty()) continue
                    val incoming = normalized[key].orEmpty()
                    val merged = mergeEpgArchiveHistory(old, incoming, importTime, cutoff)
                    if (merged !== incoming) {
                        normalized[key] = parser.normalizeProgramList(merged)
                    }
                    if (isAndroidPlatform() && ++mergedChannels % 12 == 0) delay(4)
                }
            }
            if (isAndroidPlatform()) checkEpgImportPriority()
            val snapshot: Map<String, List<EpgProgramme>>
            val stamp: Long
            dataLock.withLock {
                if (normalized != null) {
                    programs.keys.removeAll { it.startsWith(playlist.id + "|") }
                    programs.putAll(normalized)
                }
                cacheTimestamp = clock()
                legacyDescriptionCache = false
                cacheLoaded = true
                quickCacheLoaded = false
                quickCacheAttempted = false
                quickPrograms = emptyMap()
                // Copy only the channel-index map; immutable programme lists
                // are shared. Readers can continue while the cache is written.
                snapshot = programs.toMap()
                stamp = cacheTimestamp
            }
            if (persistCache) writeCacheSnapshot(snapshot, stamp,
                sourceKeyFor(playlist.id, url).hashCode())
        }
        val wasCommitted = try {
            if (isAndroidPlatform()) withContext(epgParseDispatcher()) { commitImport() } else commitImport()
            true
        } catch (_: EpgImportDeferredException) {
            // Keep old programme rows intact; foreground refresh can retry.
            false
        }
        if (!wasCommitted) {
            _state.update { it.copy(isLoading = false, notice = "EPG продолжится при возвращении в приложение") }
            return false
        }
        val total = dataLock.withLock { programs.size to programs.values.sumOf { it.size } }
        _state.update {
            it.copy(
                isLoading = false,
                lastError = null,
                notice = if (cacheSaveFailed)
                    "EPG загружена, но не удалось сохранить на диск. Проверьте свободное место."
                    else null,
                channelCount = total.first,
                programmeCount = total.second,
            )
        }
        if (isAndroidPlatform()) {
            com.tvnext.app.core.common.log.AndroidPerformanceCounters.set("epg.programmes", total.second.toLong())
            com.tvnext.app.core.common.log.AndroidPerformanceCounters.increment("epg.imports")
        }
        Logger.d(TAG, "EPG imported: ${total.first}/${channels.size} channels, ${total.second} programmes")
        return true
    }

    /** Drains one parsed batch into the in-memory map. Returns programmes added. */
    private suspend fun publishBufferBatch(buffer: EpgBuffer): Int {
        val batch = buffer.drain()
        if (batch.isEmpty()) return 0
        var count = 0
        dataLock.withLock {
            for ((key, list) in batch) {
                val existing = programs[key].orEmpty()
                programs[key] = parser.normalizeProgramList(existing + list)
                count += list.size
            }
        }
        return count
    }

    /** Writes from an immutable snapshot without holding the guide's data lock. */
    private fun writeCacheSnapshot(
        snapshot: Map<String, List<EpgProgramme>>, stamp: Long, sourceHash: Int,
    ): Boolean {
        val dir = cacheDir ?: run { cacheSaveFailed = true; return false }
        val lines = sequence {
            for ((key, list) in snapshot) {
                val sep = key.indexOf('|')
                if (sep < 0) continue
                val playlistId = key.substring(0, sep)
                val channelId = key.substring(sep + 1)
                for (programme in list) {
                    yield(
                        playlistId + FIELD_SEP +
                            channelId + FIELD_SEP +
                            programme.start + FIELD_SEP +
                            programme.stop + FIELD_SEP +
                            escape(programme.title) + FIELD_SEP +
                            escape(programme.description.orEmpty()) + FIELD_SEP +
                            escape(programme.category.orEmpty()),
                    )
                }
            }
        }
        val fullSaved = writeEpgCacheStreaming("$dir/$CACHE_FILE",
            (if (isAndroidPlatform()) "v3-full:$stamp:$sourceHash" else "v1:$stamp"), lines)
        val quickSaved = !isAndroidPlatform() ||
            (fullSaved && writeQuickCacheSnapshot(snapshot, stamp, sourceHash))
        val saved = fullSaved && quickSaved
        if (!saved) {
            cacheSaveFailed = true
            Logger.d(TAG, "EPG disk snapshot failed (full=$fullSaved, current=$quickSaved)")
        }
        return saved
    }

    /** A few hours of current/future broadcasts, not an extra archive copy. */
    private fun writeQuickCacheSnapshot(
        snapshot: Map<String, List<EpgProgramme>>, stamp: Long, sourceHash: Int? = loadedSourceHash,
    ): Boolean {
        val dir = cacheDir ?: return false
        val from = stamp - FAST_PAST_MS
        val to = stamp + FAST_FUTURE_MS
        val lines = sequence {
            for ((key, list) in snapshot) {
                val separator = key.indexOf('|')
                if (separator < 0) continue
                val playlistId = key.substring(0, separator)
                val channelId = key.substring(separator + 1)
                for (programme in list) {
                    if (programme.stop <= from || programme.start >= to) continue
                    yield(playlistId + FIELD_SEP + channelId + FIELD_SEP +
                        programme.start + FIELD_SEP + programme.stop + FIELD_SEP +
                        escape(programme.title) + FIELD_SEP +
                        escape(programme.description.orEmpty()) + FIELD_SEP +
                        escape(programme.category.orEmpty()))
                }
            }
        }
        return writeEpgCacheStreaming("$dir/$FAST_CACHE_FILE",
            "v1-fast:$stamp${sourceHash?.let { ":$it" }.orEmpty()}", lines)
    }

    private fun deleteCacheFile() {
        val dir = cacheDir ?: return
        writeEpgCacheFile("$dir/$CACHE_FILE", "")
        writeEpgCacheFile("$dir/$FAST_CACHE_FILE", "")
    }

    private fun readCacheFileHeader(): Pair<Long, Int?>? {
        // Never read a potentially 300 MB file into a String for its header.
        val dir = cacheDir ?: return null
        var header: String? = null
        readEpgCacheLines("$dir/$CACHE_FILE") { line ->
            header = line
            false
        }
        val fields = header?.substringAfter(':')?.split(':', limit = 2) ?: return null
        val stamp = fields[0].toLongOrNull() ?: return null
        return stamp to fields.getOrNull(1)?.toIntOrNull()
    }

    // ------------------------------------------------------------ cache utils

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(FIELD_SEP, "\\p")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'p' -> { sb.append(FIELD_SEP); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "LiteEpg"
        const val CACHE_FILE = "epg_programs.dat"
        const val FAST_CACHE_FILE = "epg_current.dat"
        const val MAX_FAST_CACHE_ROWS = 250_000
        const val FAST_PAST_MS = 2L * 60 * 60 * 1000
        const val FAST_FUTURE_MS = 16L * 60 * 60 * 1000
        const val FIELD_SEP = "|"
        const val DAY_MS = 24L * 60 * 60 * 1000
        // Only used if neither the user nor the playlist declares a depth.
        // The EPG cannot establish the provider's playable archive depth.
        const val UNKNOWN_ARCHIVE_HISTORY_DAYS = 30L
        const val MAX_EPG_HISTORY_DAYS = 60L
        const val IMPORT_RETRY_DELAY_MS = 60_000L

        /** How often parsed batches are flushed into the in-memory map. */
        const val PUBLISH_INTERVAL_MS = 800L

        /**
         * Time window around "now" for which programme DESCRIPTIONS are kept
         * (titles are always kept). Bounding this keeps memory proportional to
         * what the user can actually read on the guide / info panel.
         */
        const val ANDROID_DESC_PAST_MS = 24L * 60 * 60 * 1000
        const val ANDROID_DESC_FUTURE_MS = 2L * 24 * 60 * 60 * 1000
        const val DESC_WINDOW_PAST_MS = 2L * 24 * 60 * 60 * 1000
        const val DESC_WINDOW_FUTURE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
