package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.common.result.*
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.*
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

/** The same disk index, parser and public repository API used by Android. */
class IndexedEpgRepositoryTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private fun stamp(t: Long) = formatter.format(Instant.ofEpochMilli(t))
    private fun programme(id: String = "news", start: Long = now - hour, title: String = "Новости", description: String = "Описание") =
        "<programme channel = '$id' start = '${stamp(start)}' stop = '${stamp(start + hour * 2)}'><title>$title</title><desc>$description</desc></programme>"
    private fun channel(id: String = "c", playlist: String = "p") = Channel(id, "Новости HD", 1, "Тест", "https://unused.invalid/live",
        tvgId = "news", playlistId = playlist)

    private inner class Fixture(val path: java.nio.file.Path = Files.createTempDirectory("tvnext-index").resolve("index.db")) {
        var time = now
        var roster = listOf(channel())
        var playlistList = listOf(Playlist("p", "Тест", "https://unused.invalid/list.m3u", epgUrl = "https://unused.invalid/epg.xml"))
        private val exists = Files.exists(path)
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        init {
            if (!exists) TvNextDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA journal_mode=WAL", 0)
            driver.execute(null, "PRAGMA busy_timeout=3000", 0)
        }
        val store = IndexedEpgStore(TvNextDatabase(driver), driver)
        private val repos = arrayListOf<IndexedEpgRepository>()
        private val playlistRepo = object : PlaylistRepository {
            override fun observePlaylists(): Flow<List<Playlist>> = emptyFlow()
            override suspend fun getPlaylists() = playlistList
            override suspend fun getPlaylist(id: String) = playlistList.firstOrNull { it.id == id }
            override suspend fun getDefaultPlaylist() = playlistList.firstOrNull()
            override suspend fun addPlaylist(url: String, type: PlaylistType, name: String?): AppResult<Playlist> = error("No M3U HTTP allowed")
            override suspend fun removePlaylist(id: String): AppResult<Unit> = error("unused")
            override suspend fun renamePlaylist(id: String, name: String): AppResult<Unit> = error("unused")
            override suspend fun refreshPlaylist(playlistId: String): AppResult<Playlist> = error("No M3U HTTP allowed")
            override suspend fun refreshAll(): AppResult<Unit> = error("No M3U HTTP allowed")
            override suspend fun setDefaultPlaylist(id: String?): AppResult<Unit> = error("unused")
            override suspend fun refreshIfParserUpgraded(): AppResult<Unit> = AppResult.Success(Unit)
        }
        private val channelRepo = object : ChannelsRepository {
            override suspend fun getChannels() = roster
            override suspend fun getChannelsByPlaylist(playlistId: String) = roster.filter { it.playlistId == playlistId }
            override suspend fun isConfigured() = true
            override suspend fun refresh(): AppResult<Unit> = error("No M3U HTTP allowed")
            override suspend fun getFavoriteIds(): Set<String> = emptySet()
            override suspend fun setFavorite(channelId: String, favorite: Boolean) = Unit
        }
        fun repo(automatic: Boolean = false, fallback: List<String> = emptyList(), legacy: List<String> = emptyList(), timeoutMs: Long = 20 * 60_000L,
            stream: suspend (String, suspend (EpgTextSource) -> Unit) -> Boolean): IndexedEpgRepository =
            IndexedEpgRepository({ store }, playlistRepo, channelRepo, object : EpgConfigProvider {
                override suspend fun isEnabled() = true
                override suspend fun userEpgUrl(): String? = null
            }, { fallback }, stream, { _, _ -> legacy }, worker = Dispatchers.IO.limitedParallelism(1),
                clock = { time }, automaticRefresh = automatic, importTimeoutMs = timeoutMs).also { repos.add(it) }
        suspend fun close() { repos.forEach { it.close() }; driver.close() }
    }

    @Test fun timedOutRefreshStopsLoadingAndKeepsThePreviousGuide() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val initial = f.repo { _, consume -> StringEpgTextSource("<tv>${programme(title = "Сохранённая")}</tv>").use { consume(it) }; true }
            initial.refreshIfStale()
            val stalled = f.repo(timeoutMs = 100) { _, _ -> awaitCancellation() }
            assertIs<AppResult.Failure>(withTimeout(2_000) { stalled.refreshIfStale(true) })
            assertFalse(stalled.observeState().value.isLoading)
            assertNull(stalled.observeState().value.notice)
            assertTrue(stalled.observeState().value.lastError.orEmpty().contains("вовремя"))
            assertEquals("Сохранённая", stalled.getNowPlaying(f.roster, now)["c"]?.title)
        } finally { f.close() }
    }

    @Test fun expiredCoverageIsRefreshedEvenWhenDownloadedLessThanADayAgo() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var requests = 0
            val r = f.repo { _, consume -> requests++; StringEpgTextSource("<tv>${programme(start = f.time - hour)}</tv>").use { consume(it) }; true }
            r.refreshIfStale()
            f.time += 2 * hour
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(2, requests)
            assertNotNull(r.getNowPlaying(f.roster, f.time)["c"])
        } finally { f.close() }
    }

    @Test fun refreshDoesNotPublishPartialVersionsOverACompleteGuide() = runBlocking<Unit> {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val first = f.repo { _, consume -> StringEpgTextSource("<tv>${programme(title = "Старая")}</tv>").use { consume(it) }; true }
            first.refreshIfStale()
            val r = f.repo { _, consume ->
                StringEpgTextSource("<tv>${programme(title = "Новая")}</tv>").use { consume(it) }
                entered.complete(Unit); release.await(); true
            }
            r.getNowPlaying(f.roster, now)
            val job = async(Dispatchers.IO) { r.refreshIfStale(true) }
            withTimeout(5_000) { entered.await() }
            assertEquals(0L, r.observeVersion().first())
            assertEquals("Старая", r.getNowPlaying(f.roster, now)["c"]?.title)
            release.complete(Unit)
            assertIs<AppResult.Success<Unit>>(job.await())
            assertEquals(1L, r.observeVersion().first())
            assertEquals("Новая", r.getNowPlaying(f.roster, now)["c"]?.title)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun cleanupRetainsANewerImportAndTheActiveArchive() = runBlocking<Unit> {
        val f = Fixture()
        try {
            fun row(title: String) = listOf("p|c" to com.tvnext.app.core.iptv.xmltv.EpgProgramme("p|c", now-hour, now+hour, title))
            val old = f.store.begin("s"); f.store.write("s", old, row("Старая"), true)
            f.store.complete("s", old, "", now, now)
            val active = f.store.begin("s"); f.store.write("s", active, row("Активная"), true)
            f.store.complete("s", active, "", now, now)
            val next = f.store.begin("s"); f.store.write("s", next, row("Будущая"), true)
            f.store.purgeInactive("s", active)
            assertEquals("Активная", f.store.now("s", listOf("p|c"), now)["p|c"]?.title)
            assertEquals("Будущая", f.store.now("s", listOf("p|c"), now, next)["p|c"]?.title)
            assertTrue(f.store.now("s", listOf("p|c"), now, old).isEmpty())
        } finally { f.close() }
    }

    @Test fun missingChannelDeclarationsAndWhitespaceAttributesStillProduceEpg() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val r = f.repo { _, consume -> StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }; true }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals("Новости", r.getNowPlaying(f.roster, now)["c"]?.title)
            assertEquals("Описание", r.getNowNext(f.roster.single(), now).current?.description)
            assertEquals("p", r.observeState().value.playlistId)
            assertEquals("https://unused.invalid/epg.xml", r.observeState().value.effectiveUrl)
        } finally { f.close() }
    }

    @Test fun restartingFromDiskAndCrossingMidnightDoesNotDownloadFreshGuide() = runBlocking<Unit> {
        val f = Fixture()
        val lateEvening = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 23 * hour
        f.time = lateEvening
        val path = f.path
        try {
            val r = f.repo { _, consume -> StringEpgTextSource("<tv>${programme(start = lateEvening - hour)}${programme(start = lateEvening + hour, title = "Утро")}</tv>").use { consume(it) }; true }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
        } finally { f.close() }
        val restarted = Fixture(path)
        restarted.time = lateEvening + 2 * hour
        try {
            var requests = 0
            val r = restarted.repo { _, _ -> requests++; error("Fresh cache must not use HTTP") }
            assertEquals("Утро", r.getNowPlaying(restarted.roster, restarted.time)["c"]?.title)
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(0, requests)
        } finally { restarted.close() }
    }

    @Test fun overnightRestartKeepsACompleteUsableGuideWithoutHttp() = runBlocking<Unit> {
        val f = Fixture(); val path = f.path
        try {
            val body = "<tv>" + (0..72).joinToString("") { programme(start = now + (it - 1) * hour) } + "</tv>"
            val r = f.repo { _, consume -> StringEpgTextSource(body).use { consume(it) }; true }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
        } finally { f.close() }
        val reopened = Fixture(path); reopened.time = now + 37 * hour
        try {
            var requests = 0
            val r = reopened.repo { _, _ -> requests++; error("Usable disk guide must not restart its download") }
            assertNotNull(r.getNowPlaying(reopened.roster, reopened.time)["c"])
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(0, requests)
        } finally { reopened.close() }
    }

    @Test fun aVisibleChannelAbsentFromAFreshFeedDoesNotRestartTheSameDownload() = runBlocking<Unit> {
        val f = Fixture()
        f.roster = f.roster + channel("missing").copy(name = "Канал без EPG", tvgId = "missing")
        try {
            var requests = 0
            val r = f.repo { _, consume -> requests++; StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }; true }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            r.getNowPlaying(listOf(f.roster.last()), now)
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(1, requests)
        } finally { f.close() }
    }

    @Test fun cosmeticChannelRenameDoesNotInvalidateTheDownloadedSource() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var requests = 0
            val r = f.repo { _, consume -> requests++; StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }; true }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            f.roster = f.roster.map { it.copy(name = "Новое название", number = 99, category = "Другая категория") }
            r.invalidateConfiguration()
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(1, requests)
        } finally { f.close() }
    }

    @Test fun primaryAndBackupCoverageCountIsTheUnionNotTheLargestSource() = runBlocking<Unit> {
        val f = Fixture()
        f.roster = (0 until 1800).map { i -> channel("c$i").copy(tvgId = "news$i") }
        try {
            val r = f.repo(fallback = listOf("https://unused.invalid/backup.xml")) { url, consume ->
                val ids = if (url.endsWith("/epg.xml")) 0 until 1533 else 1533 until 1800
                val body = "<tv>" + ids.joinToString("") { programme(id = "news$it") } + "</tv>"
                StringEpgTextSource(body).use { consume(it) }; true
            }
            r.getNowPlaying(f.roster, now)
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale(true))
            assertEquals(1800, r.getNowPlaying(f.roster, now).size)
            assertEquals(1800, r.observeState().value.channelCount)
            assertFalse(r.observeState().value.isLoading)
        } finally { f.close() }
    }

    @Test fun brokenRefreshKeepsCompleteCacheAndItsOriginalFreshness() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var body = "<tv>${programme(title = "Сохранённая")}</tv>"
            val r = f.repo { _, consume -> StringEpgTextSource(body).use { consume(it) }; true }
            r.refreshIfStale()
            f.time += 60_000
            body = "<tv>${programme(title = "Оборванная")}" // Missing document footer.
            assertIs<AppResult.Failure>(r.refreshIfStale(true))
            assertEquals("Сохранённая", r.getNowPlaying(f.roster, now)["c"]?.title)
            assertEquals(now, r.observeState().value.lastUpdated)
        } finally { f.close() }
    }
    @Test fun anInterruptedFirstImportKeepsRealPartialRowsWithoutClaimingFreshness() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var requests = 0
            val r = f.repo { _, consume -> requests++; StringEpgTextSource("<tv>${programme()}").use { consume(it) }; true }
            assertIs<AppResult.Failure>(r.refreshIfStale())
            assertEquals("Новости", r.getNowPlaying(f.roster, now)["c"]?.title)
            assertEquals(1, r.observeState().value.programmeCount)
            assertNull(r.observeState().value.lastUpdated)
            r.refreshIfStale()
            assertEquals(1, requests)
        } finally { f.close() }
    }

    @Test fun visibleChannelIsPublishedWhileTheRestOfTheStreamIsStillBlocked() = runBlocking<Unit> {
        val f = Fixture()
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val xml = "<tv>${programme()}" + (1..600).joinToString("") { programme(start = now + it * hour, description = "x".repeat(1024)) } + "</tv>"
            val r = f.repo { _, consume ->
                val input = StringEpgTextSource(xml)
                var reads = 0
                consume(object : EpgTextSource {
                    override fun read(into: CharArray, offset: Int, length: Int): Int {
                        if (++reads == 2) { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                        return input.read(into, offset, length)
                    }
                    override fun close() = input.close()
                })
                true
            }
            r.getNowPlaying(f.roster, now) // Sets the real viewport priority.
            val job = async(Dispatchers.IO) { r.refreshIfStale() }
            assertTrue(withContext(Dispatchers.IO) { blocked.await(5, TimeUnit.SECONDS) })
            val cached = withTimeout(1_000) { r.getNowPlaying(f.roster, now) }
            assertEquals("Новости", cached["c"]?.title)
            assertNull(r.observeState().value.lastUpdated)
            release.countDown()
            assertIs<AppResult.Success<Unit>>(job.await())
        } finally { release.countDown(); f.close() }
    }

    @Test fun oneUrlIsFetchedOnceForMultiplePlaylistsAndArchiveSurvivesRollingFeed() = runBlocking<Unit> {
        val f = Fixture()
        f.roster = listOf(channel(), channel("c2", "p2"))
        f.playlistList = f.playlistList + f.playlistList.single().copy(id = "p2")
        try {
            var requests = 0
            var body = "<tv>${programme(start = now - 24 * hour, title = "Вчера")}${programme()}</tv>"
            val r = f.repo { _, consume -> requests++; StringEpgTextSource(body).use { consume(it) }; true }
            r.refreshIfStale()
            assertEquals(1, requests)
            assertEquals(setOf("c", "c2"), r.getNowPlaying(f.roster, now).keys)
            body = "<tv>${programme(title = "Исправленное название")}</tv>"
            r.refreshIfStale(true)
            val entries = r.getProgrammesInRange(f.roster, now - 48 * hour, now + hour)
            assertEquals(listOf("Вчера", "Исправленное название"), entries["c"]?.map { it.title })
            assertEquals(2, entries["c2"]?.size)
        } finally { f.close() }
    }

    @Test fun forcedPrimaryRefreshDoesNotDownloadRedundantBackupGuides() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val requests = arrayListOf<String>()
            val r = f.repo(fallback = listOf("https://unused.invalid/backup1.xml", "https://unused.invalid/backup2.xml")) { url, consume ->
                requests += url
                StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }
                true
            }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale(true))
            assertEquals(listOf("https://unused.invalid/epg.xml"), requests)
        } finally { f.close() }
    }

    @Test fun stopsDownloadingBackupSourcesOnceVisibleChannelsAreCovered() = runBlocking<Unit> {
        val f = Fixture()
        f.playlistList = f.playlistList.map { it.copy(epgUrl = null) }
        try {
            val requests = arrayListOf<String>()
            val r = f.repo(fallback = listOf("https://unused.invalid/backup1.xml", "https://unused.invalid/backup2.xml")) { url, consume ->
                requests += url
                StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }
                true
            }
            r.getNowPlaying(f.roster, now)
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale(true))
            assertEquals(listOf("https://unused.invalid/backup1.xml"), requests)
        } finally { f.close() }
    }

    @Test fun legacyCurrentAndArchiveFilesMigrateWithoutHttp() = runBlocking<Unit> {
        val f = Fixture()
        val dir = Files.createTempDirectory("tvnext-old-epg")
        val current = "p|c|${now - hour}|${now + hour}|Из кэша|Описание\n"
        Files.writeString(dir.resolve("epg_current.dat"), "v1-fast:$now\n$current")
        Files.writeString(dir.resolve("epg_programs.dat"), "v3-full:$now\n" +
            "p|c|${now - 25 * hour}|${now - 24 * hour}|Архив|\n$current")
        try {
            val r = f.repo(legacy = listOf(dir.toString())) { _, _ -> error("Migration must not download") }
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals("Из кэша", r.getNowPlaying(f.roster, now)["c"]?.title)
            assertEquals(now - 25 * hour, r.getAvailableProgrammeRange(f.roster)?.first)
        } finally { f.close() }
    }

    @Test fun noCacheAutomaticallyPreloadsVisibleSourceFirst() = runBlocking<Unit> {
        val f = Fixture()
        f.roster = listOf(channel(), channel("visible", "p2"))
        f.playlistList = f.playlistList + f.playlistList.single().copy(id = "p2", epgUrl = "https://unused.invalid/visible.xml")
        try {
            val order = java.util.Collections.synchronizedList(arrayListOf<String>())
            val r = f.repo(automatic = true) { url, consume ->
                order.add(url); StringEpgTextSource("<tv>${programme()}</tv>").use { consume(it) }; true
            }
            r.getNowPlaying(listOf(f.roster.last()), now)
            withTimeout(5_000) { r.observeVersion().first { it > 0L } }
            withTimeout(5_000) {
                while (r.getNowPlaying(listOf(f.roster.last()), now)["visible"] == null) delay(10)
            }
            assertEquals("https://unused.invalid/visible.xml", order.first())
        } finally { f.close() }
    }

    @Test fun persistedBackupIsReadableImmediatelyAfterRestartWithoutWarmingOrHttp() = runBlocking<Unit> {
        val f = Fixture()
        f.playlistList = f.playlistList.map { it.copy(epgUrl = null) }
        val path = f.path
        val fallback = listOf("https://unused.invalid/backup.xml")
        try {
            val r = f.repo(fallback = fallback) { _, consume ->
                StringEpgTextSource("<tv>${programme(title = "Резервный EPG")}</tv>").use { consume(it) }; true
            }
            r.getNowPlaying(f.roster, now)
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
        } finally { f.close() }
        val restarted = Fixture(path)
        restarted.playlistList = restarted.playlistList.map { it.copy(epgUrl = null) }
        try {
            var requests = 0
            val r = restarted.repo(fallback = fallback) { _, _ -> requests++; false }
            assertEquals("Резервный EPG", r.getNowPlaying(restarted.roster, now)["c"]?.title)
            assertEquals(0, requests)
        } finally { restarted.close() }
    }

    @Test fun largeGeneratedFeedIsIndexedInBoundedBatchesAndQueriesOnlyRequestedTimeRange() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val r = f.repo { _, consume ->
                val parts = sequence {
                    yield("<tv>")
                    for (i in 0 until 10_000) yield(programme(start = now + (i - 5_000) * hour,
                        title = "Передача $i", description = "Д".repeat(512)))
                    yield("</tv>")
                }.iterator()
                var part = ""; var position = 0
                consume(object : EpgTextSource {
                    override fun read(into: CharArray, offset: Int, length: Int): Int {
                        var used = 0
                        while (used < length) {
                            if (position == part.length) {
                                if (!parts.hasNext()) break
                                part = parts.next(); position = 0
                            }
                            val count = minOf(length - used, part.length - position)
                            part.toCharArray(into, offset + used, position, position + count)
                            position += count; used += count
                        }
                        return if (used == 0) -1 else used
                    }
                    override fun close() = Unit
                })
                true
            }
            val started = System.nanoTime()
            assertIs<AppResult.Success<Unit>>(r.refreshIfStale())
            assertEquals(10_000, r.observeState().value.programmeCount)
            assertEquals("Передача 5000", r.getNowPlaying(f.roster, now)["c"]?.title)
            val day = r.getProgrammesInRange(f.roster, now, now + 24 * hour)["c"].orEmpty()
            assertTrue(day.size in 24..26)
            println("Indexed 10000 streamed programmes in ${(System.nanoTime() - started) / 1_000_000} ms; day query returned ${day.size} rows")
        } finally { f.close() }
    }
}
