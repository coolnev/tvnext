package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Disk-first Android EPG. Readers never join an import, parse XML or hydrate a feed. */
class IndexedEpgRepository(
    storeProvider: () -> IndexedEpgStore,
    private val playlists: PlaylistRepository,
    private val channels: ChannelsRepository,
    private val config: EpgConfigProvider,
    private val fallbackUrls: () -> List<String> = { emptyList() },
    private val stream: suspend (String, suspend (EpgTextSource) -> Unit) -> Boolean,
    private val legacyDirectories: (String, Set<String>) -> List<String> = { _, _ -> emptyList() },
    private val awaitImport: suspend () -> Unit = {},
    private val worker: CoroutineDispatcher = epgParseDispatcher(),
    private val clock: () -> Long = ::currentTimeMillis,
    private val automaticRefresh: Boolean = true,
    private val onSourceImported: (String) -> Unit = {},
    private val importTimeoutMs: Long = 20 * 60_000L,
) : EpgRepository {
    private val store by lazy(storeProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reads = Dispatchers.IO.limitedParallelism(2)
    private val sourceLock = Mutex()
    private val importLock = Mutex()
    private val priority = AtomicReference<Set<String>>(emptySet())
    private val importing = ConcurrentHashMap<String, Long>()
    @Volatile private var configuration: Configuration? = null
    @Volatile private var autoJob: Job? = null
    private val _state = MutableStateFlow(EpgState())
    private val _version = MutableStateFlow(0L)
    private var publishedAt = 0L
    private data class Source(val id: String, val url: String, val channels: List<Channel>,
        val signature: String, val primary: Boolean)
    private data class Configuration(val enabled: Boolean, val sources: List<Source>,
        val primaryByPlaylist: Map<String, List<String>>)

    internal fun downloadProgress(progress: EpgSourceDownload.Progress) {
        fun amount(bytes: Long): String = when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${(bytes / 102.4).toLong() / 10.0} КБ"
            else -> "${(bytes / (1024.0 * 1024) * 10).toLong() / 10.0} МБ"
        }
        val notice = if (progress.bytes == 0L && progress.total == null) {
            if (progress.attempt > 1) "Повтор подключения к EPG (${progress.attempt})…" else "Подключение к источнику EPG…"
        } else {
            val size = amount(progress.bytes) + (progress.total?.let { " / ${amount(it)}" } ?: "")
            "Скачивание EPG: $size" + if (progress.resumed) " · докачка" else ""
        }
        _state.update { it.copy(notice = notice) }
    }
    internal fun downloadReady() { _state.update { it.copy(notice = "Обработка EPG…") } }

    override fun observeState(): StateFlow<EpgState> = _state.asStateFlow()
    override fun observeVersion(): Flow<Long> = _version.asStateFlow()
    fun invalidateConfiguration() { configuration = null; _version.update { it + 1L } }
    suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }

    private suspend fun sources(): Configuration = configuration ?: sourceLock.withLock {
        configuration ?: run {
            val enabled = config.isEnabled()
            val live = playlists.getPlaylists().filter { it.enabled && it.type == PlaylistType.LIVE }
            val all = channels.getChannels().filter { c -> live.any { it.id == c.playlistId } }
            val override = config.userEpgUrl()?.trim().orEmpty()
            val byUrl = linkedMapOf<String, MutableList<Channel>>()
            val primary = linkedMapOf<String, List<String>>()
            for (playlist in live) {
                val urls = urls(override.ifBlank { playlist.epgUrl.orEmpty() })
                primary[playlist.id] = urls.map(::sourceId)
                for (url in urls) byUrl.getOrPut(url) { arrayListOf() }
                    .addAll(all.filter { it.playlistId == playlist.id })
            }
            val providerUrls = byUrl.keys.toSet()
            for (url in fallbackUrls().flatMap(::urls).distinct()) byUrl.getOrPut(url) { all.toMutableList() }
            val entries = byUrl.map { (url, roster) ->
                val unique = roster.distinctBy(::key)
                Source(sourceId(url), url, unique,
                    sourceId(unique.map { "${key(it)}:${it.tvgId}:${it.tvgName}:${it.name}" }.sorted().joinToString("\n")),
                    url in providerUrls)
            }
            Configuration(enabled, entries, primary).also { configuration = it }
        }
    }

    override suspend fun refreshState() = withContext(reads) {
        val cfg = sources()
        val sourceSnapshots = cfg.sources.mapNotNull { s -> store.snapshot(s.id)?.let { s.id to it } }
        val snapshots = sourceSnapshots.map { it.second }
        val coveredChannels = sourceSnapshots.flatMap { (source, snapshot) -> store.channelKeys(source, snapshot.generation) }.toSet()
        val active = playlists.getPlaylists().filter { it.enabled && it.type == PlaylistType.LIVE }
        val selected = playlists.getDefaultPlaylist()?.takeIf { p -> active.any { it.id == p.id } } ?: active.firstOrNull()
        val override = config.userEpgUrl().orEmpty()
        _state.update { it.copy(enabled = cfg.enabled,
            playlistId = selected?.id, playlistName = selected?.name, detectedUrl = selected?.epgUrl,
            overrideUrl = override,
            effectiveUrl = override.takeIf(String::isNotBlank) ?: selected?.epgUrl,
            lastUpdated = snapshots.maxOfOrNull { s -> s.fetchedAt }?.takeIf { t -> t > 0L },
            programmeCount = snapshots.sumOf { s -> s.rows }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            channelCount = coveredChannels.size) }
    }

    private fun requestPriority(requested: List<Channel>) {
        priority.set(requested.mapTo(hashSetOf(), ::key))
        if (!automaticRefresh) return
        synchronized(this) {
            if (autoJob?.isActive == true) return
            autoJob = scope.launch {
                delay(100)
                refreshIfStale(false)
            }
        }
    }

    override suspend fun refreshPlaylist(playlistId: String, force: Boolean): AppResult<Unit> = refreshIfStale(force)

    override suspend fun refreshIfStale(force: Boolean): AppResult<Unit> {
        if (!importLock.tryLock()) return AppResult.Success(Unit)
        return try {
            awaitImport()
            withContext(worker) {
                val cfg = sources()
                if (!cfg.enabled) return@withContext AppResult.Success(Unit)
                var failure = false
                var failureNotice: String? = null
                // Cached sources (including backups) are readable before any HTTP.
                for (source in cfg.sources) if (store.snapshot(source.id) == null) migrate(source)
                val ordered = cfg.sources.sortedWith(compareBy<Source> { !it.primary }
                    .thenBy { s -> s.channels.none { key(it) in priority.get() } })
                val hasCompleteGuide = cfg.sources.any { source ->
                    store.snapshot(source.id)?.let { it.fetchedAt > 0 && it.rows > 0 } == true
                }
                var primaryAttempted = false
                for (source in ordered) {
                    if (source.channels.isEmpty()) continue
                    val old = store.snapshot(source.id)
                    val fresh = old != null && cacheUsable(source, old)
                    if (!force && (fresh || (old?.retryAt ?: 0L) > clock())) continue
                    // Do not fetch unrelated backup feeds when the visible channels have EPG.
                    if (!source.primary && !force && hasCompleteGuide && !primaryAttempted && (old?.fetchedAt ?: 0L) <= 0L) continue
                    if (!source.primary && !needsBackup(cfg, source)) continue
                    if (source.primary) primaryAttempted = true
                    awaitImport()
                    _state.update { it.copy(isLoading = true, lastError = null) }
                    if (!import(source)) { failure = true; failureNotice = _state.value.lastError ?: failureNotice }
                }
                refreshState()
                _state.update { it.copy(isLoading = false, lastError = if (failure)
                    (failureNotice ?: "Один из источников EPG не обновился. Сохранённая программа доступна.") else null) }
                if (failure) AppResult.Failure(AppError.InvalidData("Не удалось обновить источник EPG"))
                else AppResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(lastError = "Не удалось обновить EPG. Сохранённая программа доступна.") }
            AppResult.Failure(AppError.Database(error))
        } finally { _state.update { it.copy(isLoading = false, notice = null) }; importLock.unlock() }
    }

    private fun needsBackup(cfg: Configuration, source: Source): Boolean {
        val viewport = priority.get()
        val wanted = source.channels.filter { viewport.isEmpty() || key(it) in viewport }.mapTo(hashSetOf(), ::key)
        if (wanted.isEmpty()) return false
        for (cached in cfg.sources) {
            wanted.removeAll(store.now(cached.id, wanted, clock()).keys)
            if (wanted.isEmpty()) return false
        }
        return true
    }

    private fun cacheUsable(source: Source, snapshot: IndexedEpgStore.Snapshot): Boolean {
        if (snapshot.fetchedAt <= 0 || snapshot.rows <= 0) return false
        val age = clock() - snapshot.fetchedAt
        // Restart, channel renaming, order and group edits are not changes to the XMLTV source.
        if (age !in 0 until 7 * 24 * 60 * 60_000L) return false
        val viewport = priority.get()
        val covered = store.channelKeys(source.id, snapshot.generation)
        val available = source.channels.map(::key).filter { it in covered }
        // Channels absent from this feed need a backup, not repeated downloads of the same feed.
        val probe = available.filter { it in viewport }.take(8).ifEmpty { available.take(8) }
        if (probe.isEmpty() || store.now(source.id, probe, clock()).isEmpty()) return false
        if (age < 24 * 60 * 60_000L) return true
        // A weekly guide remains usable overnight. Refresh when its future coverage runs short.
        return store.bounds(source.id, probe)?.last?.let { it > clock() + 12 * 60 * 60_000L } == true
    }

    private suspend fun import(source: Source): Boolean {
        val generation = store.begin(source.id)
        val previous = store.snapshot(source.id)
        importing[source.id] = generation
        val writer = BatchWriter(source, generation, previous?.fetchedAt?.let { it > 0L } == true)
        var complete = false
        var parsedAt = 0L
        return try {
            val finished = withTimeoutOrNull(importTimeoutMs) {
                val context = currentCoroutineContext()
                _state.update { it.copy(notice = "Подготовка сохранённого EPG…") }
                store.discardUncommitted(source.id, previous?.generation ?: 0L, generation) { context.ensureActive() }
                val success = stream(source.url) { input ->
                    val parser = LiteXmltvParser()
                    complete = parser.parse(input, parser.buildChannelLookup(source.channels), "", writer,
                        fullDescriptions = true, onProgress = { processed ->
                            context.ensureActive()
                            if (clock() - parsedAt >= 500L) {
                                parsedAt = clock()
                                _state.update { it.copy(notice = "Обработка EPG: проверено $processed передач") }
                            }
                        })
                    writer.flush()
                    if (!complete) throw InvalidEpgDocumentException()
                }
                writer.flush()
                check(success && complete) { "Incomplete XMLTV" }
                if (writer.count == 0L) throw NoMatchedEpgChannels()
                _state.update { it.copy(notice = "Сохранение программы передач…") }
                store.complete(source.id, generation, source.signature, clock(), clock()) { done, total ->
                    context.ensureActive()
                    if (done % 16 == 0 || done == total) _state.update { it.copy(
                        notice = if (done == total) "Сохранение программы передач…" else "Сохранение архива: $done из $total каналов") }
                }
                true
            }
            if (finished != true) {
                store.failed(source.id, generation, clock() + RETRY_MS)
                _state.update { it.copy(lastError = "Обработка EPG не завершилась вовремя. Сохранённая программа доступна.") }
                return false
            }
            onSourceImported(source.url)
            _version.update { it + 1L }
            // The active pointer has committed. Deleting old rows must not keep the loading spinner alive.
            scope.launch(worker) {
                val context = currentCoroutineContext()
                try { store.purgeInactive(source.id, generation) { context.ensureActive() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Inactive rows can be cleaned on the next successful import. */ }
            }
            true
        } catch (cancelled: CancellationException) {
            store.failed(source.id, generation, clock() + RETRY_MS)
            throw cancelled
        } catch (_: NoMatchedEpgChannels) {
            store.failed(source.id, generation, clock() + 12 * 60 * 60_000L)
            false
        } catch (_: Exception) {
            store.failed(source.id, generation, clock() + RETRY_MS)
            if (previous?.fetchedAt?.let { it > 0 } != true) _version.update { it + 1L }
            false
        } finally { importing.remove(source.id) }
    }

    private class NoMatchedEpgChannels : IllegalStateException()

    /** Synchronous bounded sink on the single background parser thread. */
    private inner class BatchWriter(private val source: Source, private val generation: Long,
        private val hasCompleteCache: Boolean = false) : EpgProgrammeSink {
        private val batch = ArrayList<Pair<String, EpgProgramme>>(512)
        private var chars = 0
        var count = 0L
        private val seen = hashSetOf<String>()
        private val newKeys = hashSetOf<String>()
        override fun add(key: String, programme: EpgProgramme) {
            batch.add(key to programme)
            chars += programme.title.length + (programme.description?.length ?: 0)
            if (seen.add(key)) newKeys.add(key)
            val urgent = key in priority.get() && programme.start <= clock() && programme.stop > clock()
            if (batch.size >= 512 || chars >= 256 * 1024 || urgent) flush(urgent)
        }
        fun flush(urgent: Boolean = false) {
            if (batch.isEmpty()) return
            store.write(source.id, generation, batch, publishCold = true, newChannelKeys = newKeys)
            count += batch.size
            batch.clear(); chars = 0; newKeys.clear()
            if (!hasCompleteCache && (urgent || clock() - publishedAt >= 1_000L)) {
                publishedAt = clock()
                _state.update { it.copy(channelCount = seen.size, programmeCount = count.toInt(), notice = "Обработка EPG: сохранено $count передач") }
                _version.update { it + 1L }
            }
        }
    }

    /** Upgrade old compact files in bounded batches, without downloading again. */
    private fun migrate(source: Source) {
        val directories = legacyDirectories(source.url, source.channels.mapTo(hashSetOf()) { it.playlistId })
        if (directories.isEmpty()) return
        val generation = store.begin(source.id)
        val writer = BatchWriter(source, generation)
        var stamp = 0L
        var full = false
        val allowed = source.channels.mapTo(hashSetOf(), ::key)
        // The small current/next file is published before scanning archive history.
        for (name in listOf("epg_current.dat", "epg_programs.dat")) for (dir in directories) {
            var header = true
            var fileStamp = 0L
            val ok = readEpgCacheLines("$dir/$name") { line ->
                if (header) {
                    header = false
                    fileStamp = line.substringAfter(':').substringBefore(':').toLongOrNull() ?: 0L
                    return@readEpgCacheLines fileStamp > 0
                }
                val f = line.split('|', limit = 7)
                if (f.size >= 5 && "${f[0]}|${f[1]}" in allowed) {
                    val start = f[2].toLongOrNull(); val stop = f[3].toLongOrNull()
                    if (start != null && stop != null && stop > start) writer.add("${f[0]}|${f[1]}",
                        EpgProgramme(f[1], start, stop, unescape(f[4]), f.getOrNull(5)?.let(::unescape), f.getOrNull(6)?.let(::unescape)))
                }
                true
            }
            writer.flush()
            if (ok && name == "epg_programs.dat") { full = true; stamp = maxOf(stamp, fileStamp) }
        }
        if (full && writer.count > 0) store.complete(source.id, generation, source.signature, stamp, clock())
        if (writer.count > 0) _version.update { it + 1L }
    }

    override suspend fun getNowPlaying(channels: List<Channel>, at: Long): Map<String, EpgProgramme> = withContext(reads) {
        val cfg = sources()
        if (!cfg.enabled || channels.isEmpty()) return@withContext emptyMap()
        val result = linkedMapOf<String, EpgProgramme>()
        for (source in forChannels(cfg, channels)) {
            val requested = channels.filter { it.id !in result && applies(cfg, source, it) }
            val keys = requested.map(::key)
            val rows = store.now(source.id, keys, at).toMutableMap()
            importing[source.id]?.let { batch ->
                store.now(source.id, keys.filterNot { it in rows }, at, batch).forEach { (k, p) -> rows.putIfAbsent(k, p) }
            }
            for (channel in requested) rows[key(channel)]?.let { result[channel.id] = it.copy(channelId = channel.id) }
        }
        requestPriority(channels)
        result
    }

    override suspend fun getNowNext(channel: Channel, at: Long): NowNext = getNowNextForChannels(listOf(channel), at)[channel.id] ?: NowNext()

    override suspend fun getNowNextForChannels(channels: List<Channel>, at: Long): Map<String, NowNext> {
        val current = getNowPlaying(channels, at)
        return channels.associate { c ->
            val next = getUpcomingProgrammes(c, at, 2).firstOrNull { it.start > at }
            c.id to NowNext(current[c.id], next)
        }
    }

    override suspend fun getUpcomingProgrammes(channel: Channel, from: Long, limit: Int): List<EpgProgramme> = withContext(reads) {
        if (limit <= 0) return@withContext emptyList()
        val cfg = sources()
        if (!cfg.enabled) return@withContext emptyList()
        var result = emptyList<EpgProgramme>()
        for (source in forChannels(cfg, listOf(channel)).filter { applies(cfg, it, channel) }) {
            var incoming = store.upcoming(source.id, key(channel), from, limit)
            importing[source.id]?.let { incoming = merge(incoming, store.upcoming(source.id, key(channel), from, limit, it)) }
            result = merge(result, incoming)
            if (result.size >= limit) break
        }
        result.take(limit).map { it.copy(channelId = channel.id) }
    }

    override suspend fun getProgrammesInRange(channels: List<Channel>, from: Long, to: Long): Map<String, List<EpgProgramme>> = withContext(reads) {
        val cfg = sources()
        if (!cfg.enabled || channels.isEmpty() || to <= from) return@withContext emptyMap()
        val result = linkedMapOf<String, List<EpgProgramme>>()
        for (source in forChannels(cfg, channels)) {
            val requested = channels.filter { applies(cfg, source, it) }
            val rows = store.range(source.id, requested.map(::key), from, to).toMutableMap()
            importing[source.id]?.let { generation ->
                store.range(source.id, requested.map(::key), from, to, generation).forEach { (k, p) -> rows[k] = merge(rows[k].orEmpty(), p) }
            }
            requested.forEach { c -> result[c.id] = merge(result[c.id].orEmpty(), rows[key(c)].orEmpty()).map { it.copy(channelId = c.id) } }
        }
        requestPriority(channels)
        result
    }

    override suspend fun getAvailableProgrammeRange(channels: List<Channel>): LongRange? = withContext(reads) {
        val cfg = sources()
        if (!cfg.enabled) return@withContext null
        val ranges = forChannels(cfg, channels).mapNotNull { s -> store.bounds(s.id, channels.filter { applies(cfg, s, it) }.map(::key)) }
        if (ranges.isEmpty()) null else ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    override suspend fun searchProgrammes(query: String, limit: Int): List<ProgrammeSearchHit> = withContext(reads) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val cfg = sources()
        if (!cfg.enabled) return@withContext emptyList()
        val roster = channels.getChannels().associateBy(::key)
        cfg.sources.flatMap { source -> store.search(source.id, query, limit).mapNotNull { p ->
            roster[p.channelId]?.let { c -> ProgrammeSearchHit(c.playlistId, c.id, c.name, p.copy(channelId = c.id)) }
        } }.distinctBy { Triple(it.channelId, it.programme.start, it.programme.stop) }.sortedBy { it.programme.start }.take(limit)
    }
    override suspend fun getMatchingDiagnostics(): EpgDiagnostics? = null

    private fun forChannels(cfg: Configuration, channels: List<Channel>): List<Source> {
        val ids = channels.flatMap { cfg.primaryByPlaylist[it.playlistId].orEmpty() }.toSet()
        return cfg.sources.filter { it.id in ids } + cfg.sources.filter { !it.primary }
    }
    private fun applies(cfg: Configuration, source: Source, c: Channel) = !source.primary || source.id in cfg.primaryByPlaylist[c.playlistId].orEmpty()
    private fun key(c: Channel) = "${c.playlistId}|${c.id}"
    private fun urls(text: String): List<String> = text.split(Regex("[\\s,]+"))
        .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }.distinct()

    companion object {
        private const val RETRY_MS = 15 * 60_000L
        internal fun sourceId(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        internal fun merge(primary: List<EpgProgramme>, backup: List<EpgProgramme>): List<EpgProgramme> {
            if (backup.isEmpty()) return primary
            if (primary.isEmpty()) return backup.sortedBy { it.start }
            val baseline = primary.sortedBy { it.start }
            val accepted = arrayListOf<EpgProgramme>()
            var cursor = 0
            for (p in backup.sortedBy { it.start }) {
                while (cursor < baseline.size && baseline[cursor].stop <= p.start) cursor++
                if ((cursor >= baseline.size || baseline[cursor].start >= p.stop) &&
                    accepted.lastOrNull()?.stop?.let { it > p.start } != true) accepted.add(p)
            }
            return (baseline + accepted).sortedBy { it.start }
        }
        private fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\r", "\r")
            .replace("\\p", "|").replace("\\\\", "\\")
    }
}
