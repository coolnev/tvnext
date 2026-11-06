package com.tvnext.app.core.tmdb

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.iptv.domain.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*

class MetadataNetworkTest {
    private val synopsis = "Полное русское описание фильма. ".repeat(8)
    private val search = """{"results":[{"id":42,"title":"Хитровка","overview":"Из поиска","poster_path":"/p.jpg","backdrop_path":"/f.jpg"}]}"""
    private val details get() = """{"id":42,"title":"Хитровка","overview":"$synopsis","poster_path":"/p.jpg","backdrop_path":"/f.jpg"}"""
    private val item = MediaItem("film", "p", "Хитровка", 1, "Фильмы", "https://example.org/movie")
    private class Settings : TmdbSettingsStore {
        override suspend fun isEnabled() = true
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun getApiKey() = "test-key"
        override suspend fun setApiKey(apiKey: String) = Unit
    }
    private class Key(private val value: String) : KinopoiskKeyStore {
        override suspend fun getKey() = value
        override suspend fun setKey(value: String) = Unit
    }
    private inner class Fixture(handler: suspend (HttpRequestData) -> Pair<Int, String>) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        init { TvNextDatabase.Schema.create(driver) }
        val client = HttpClient(MockEngine { request ->
            val (code, text) = handler(request)
            respond(text, HttpStatusCode.fromValue(code), headersOf("Content-Type", "application/json"))
        }) { install(HttpTimeout); install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val database = TvNextDatabase(driver)
        val tmdb = SqlDelightTmdbRepository(client, database, Settings())
        fun programme(kinoKey: String = "") = ProgrammeMetadataRepository(tmdb, KinopoiskProgrammeLookup(client, Key(kinoKey)), Settings())
        fun close() { client.close(); driver.close() }
    }
    private fun userFullCard() = checkNotNull(javaClass.getResource("/kinopoisk-home-alone-2.json")).readText()

    @Test fun actualUserSampleContainsAReadableFullAndShortSynopsis() {
        val card = parseKinopoiskDetails(userFullCard())
        assertTrue(card.overview.orEmpty().startsWith("Самый маленький герой Америки"))
        assertTrue(hasProgrammeSynopsis(card.overview))
        val json = Json.parseToJsonElement(userFullCard()) as kotlinx.serialization.json.JsonObject
        val shortOnly = kotlinx.serialization.json.JsonObject(json - "description").toString()
        assertTrue(parseKinopoiskDetails(shortOnly).overview.orEmpty().startsWith("Неугомонный малыш Кевин"))
    }

    @Test fun reopeningAnImageOnlyCardResumesItsDescriptionImmediately() = runBlocking<Unit> {
        val enteredDetails = CompletableDeferred<Unit>()
        var requests = 0
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword")) 200 to
                """{"docs":[{"id":5928,"name":"Один дома 2: Затерянный в Нью-Йорке","poster":{"url":"https://example.org/p.jpg"}}]}"""
            else {
                requests++
                if (requests == 1) { enteredDetails.complete(Unit); awaitCancellation() }
                200 to userFullCard()
            }
        }
        try {
            val repository = f.programme("test-key")
            val first = async { repository.find("Один дома 2: Затерянный в Нью-Йорке") }
            withTimeout(2_000) { enteredDetails.await() }
            first.cancelAndJoin()
            val resumed = withTimeout(2_000) { repository.find("Один дома 2: Затерянный в Нью-Йорке") }
            assertTrue(resumed?.overview.orEmpty().startsWith("Самый маленький герой Америки"))
            assertNotNull(resumed?.posterUrl)
            assertEquals(2, requests)
        } finally { f.close() }
    }

    @Test fun aNumberedSequelWithASubtitleCanBeFoundByItsBroadcastShortName() = runBlocking<Unit> {
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword")) 200 to """{"docs":[${userFullCard()}]}"""
            else 200 to userFullCard()
        }
        try {
            val card = f.programme("test-key").find("Один дома 2")
            assertTrue(card?.overview.orEmpty().startsWith("Самый маленький герой Америки"))
        } finally { f.close() }
    }

    @Test fun aVisiblePosterOnlyCardRetriesTheSynopsisWithoutAnotherSearch() = runBlocking<Unit> {
        var searches = 0; var detailsCalls = 0
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword")) {
                searches++
                200 to """{"docs":[{"id":5928,"name":"Один дома 2: Затерянный в Нью-Йорке","poster":{"url":"https://example.org/p.jpg"}}]}"""
            } else {
                detailsCalls++
                if (detailsCalls <= 2) 503 to "{}" else 200 to userFullCard()
            }
        }
        try {
            val result = withTimeout(8_000) { f.programme("test-key").observe("Один дома 2").first { hasProgrammeSynopsis(it?.overview) } }
            assertTrue(result?.overview.orEmpty().startsWith("Самый маленький герой Америки"))
            assertEquals(1, searches)
            assertEquals(3, detailsCalls)
        } finally { f.close() }
    }

    @Test fun buildDefaultKeyWorksBeforeSettingsAndManualKeysSurviveStoreRecreation() = runBlocking<Unit> {
        var expected = "build-default"
        var calls = 0
        val f = Fixture { request ->
            assertEquals("kinopoiskapiunofficial.tech", request.url.host)
            assertTrue(request.headers["X-API-KEY"] == expected, "The request must use the effective configured key")
            calls++
            200 to """{"docs":[{"id":2,"name":"Холоп 2","description":"$synopsis","poster":{"url":"https://example.org/p.jpg"},"backdrop":{"url":"https://example.org/f.jpg"}}]}"""
        }
        try {
            val meta = com.tvnext.app.core.database.SqlDelightAppMetaRepository(f.database)
            suspend fun lookup() = KinopoiskProgrammeLookup(f.client, SqlDelightKinopoiskKeyStore(meta, "build-default")).lookup("Холоп 2")
            assertEquals(synopsis, lookup()?.overview)
            SqlDelightKinopoiskKeyStore(meta, "build-default").setKey("  manual-key  ")
            expected = "manual-key"
            assertEquals(synopsis, lookup()?.overview)
            SqlDelightKinopoiskKeyStore(meta, "build-default").setKey(" ")
            expected = "build-default"
            assertEquals(synopsis, lookup()?.overview)
            assertEquals(3, calls)
            assertEquals("", SqlDelightKinopoiskKeyStore(meta).getKey())
        } finally { f.close() }
    }

    @Test fun kinopoiskRetriesTheFullCardAfterAnImageOnlySearchResult() = runBlocking<Unit> {
        var detailsCalls = 0
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword")) 200 to
                """{"docs":[{"id":2,"name":"Холоп 2","poster":{"url":"https://example.org/p.jpg"},"backdrop":{"url":"https://example.org/f.jpg"}}]}"""
            else {
                detailsCalls++
                if (detailsCalls == 1) 503 to "{}" else 200 to """{"description":"$synopsis"}"""
            }
        }
        try {
            val result = f.programme("test-key").find("Холоп 2")
            assertEquals(synopsis, result?.overview)
            assertEquals("https://example.org/p.jpg", result?.posterUrl)
            assertEquals(2, detailsCalls)
        } finally { f.close() }
    }

    @Test fun transientSearchAndDetailsFailuresAreRetried() = runBlocking<Unit> {
        var searches = 0; var cards = 0
        val f = Fixture {
            if (it.url.encodedPath.contains("search")) {
                searches++; if (searches == 1) 503 to "{}" else 200 to search
            } else { cards++; if (cards == 1) 500 to "{}" else 200 to details }
        }
        try {
            assertEquals(42, f.tmdb.searchMovie("Хитровка").single().id)
            assertEquals(synopsis, f.tmdb.getMovieDetails(42).overview)
            assertEquals(2, searches); assertEquals(2, cards)
        } finally { f.close() }
    }
    @Test fun badKeyIsNotRetried() = runBlocking<Unit> {
        var calls = 0
        val f = Fixture { calls++; 401 to "{}" }
        try {
            assertNotNull(runCatching { f.tmdb.searchMovie("Хитровка") }.exceptionOrNull())
            assertEquals(1, calls)
        } finally { f.close() }
    }
    @Test fun searchArtworkSurvivesAFullCardOutage() = runBlocking<Unit> {
        val f = Fixture { if (it.url.encodedPath.contains("search")) 200 to search else 404 to "{}" }
        try {
            val result = f.programme().find("Хитровка")
            assertEquals("Из поиска", result?.overview)
            assertTrue(result?.posterUrl?.endsWith("/p.jpg") == true)
            assertTrue(result?.frameUrl?.endsWith("/f.jpg") == true)
        } finally { f.close() }
    }
    @Test fun kinopoiskFillsMissingFrameEvenWhenTmdbHasSynopsisAndPoster() = runBlocking<Unit> {
        var kinoDetails = 0
        val f = Fixture { request ->
            if (request.url.host == "kinopoiskapiunofficial.tech") {
                assertEquals("test-key", request.headers["X-API-KEY"])
                if (request.url.encodedPath.endsWith("/search-by-keyword")) 200 to
                    """{"docs":[{"id":9,"name":"Хитровка","description":"$synopsis","poster":{"url":"https://example.org/kp-p.jpg"}}]}"""
                else { kinoDetails++; 200 to """{"description":"$synopsis","backdrop":{"url":"https://example.org/kp-frame.jpg"}}""" }
            } else if (request.url.encodedPath.contains("search")) 200 to search.replace("\"backdrop_path\":\"/f.jpg\"", "\"backdrop_path\":null")
            else 200 to details.replace("\"backdrop_path\":\"/f.jpg\"", "\"backdrop_path\":null")
        }
        try {
            val result = f.programme("test-key").find("Хитровка")
            assertEquals(synopsis, result?.overview)
            assertEquals("https://example.org/kp-frame.jpg", result?.frameUrl)
            assertTrue(result?.posterUrl?.startsWith("https://image.tmdb.org/") == true)
            assertEquals(1, kinoDetails)
        } finally { f.close() }
    }
    @Test fun simultaneousProgrammeCardsShareOneLookupAndCacheItsResult() = runBlocking<Unit> {
        var searches = 0
        val f = Fixture {
            if (it.url.encodedPath.contains("search")) { searches++; delay(50); 200 to search }
            else 200 to details
        }
        try {
            val repository = f.programme()
            val cards = (1..12).map { async(Dispatchers.Default) { repository.find("Хитровка") } }.awaitAll()
            assertTrue(cards.all { it?.overview == synopsis })
            assertEquals(1, searches)
            assertEquals(synopsis, repository.find("Хитровка")?.overview)
            assertEquals(1, searches)
        } finally { f.close() }
    }
    @Test fun kinopoiskDownloadsStillFromImageEndpointWhenBackdropIsAbsent() = runBlocking<Unit> {
        var images = 0
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") {
                if (request.url.encodedPath.contains("search")) 200 to """{"results":[]}""" else 404 to "{}"
            } else if (request.url.encodedPath.endsWith("/images")) {
                images++
                assertEquals("/api/v2.2/films/9/images", request.url.encodedPath)
                assertEquals("STILL", request.url.parameters["type"])
                200 to """{"docs":[{"type":"still","url":"https://example.org/full.jpg","previewUrl":"https://example.org/still.jpg"}]}"""
            } else if (request.url.encodedPath.endsWith("/search-by-keyword")) 200 to
                """{"docs":[{"id":9,"name":"Хитровка","description":"$synopsis","poster":{"url":"https://example.org/poster.jpg"}}]}"""
            else 200 to """{"description":"$synopsis","poster":{"url":"https://example.org/poster.jpg"}}"""
        }
        try {
            val card = f.programme("test-key").find("Хитровка")
            assertEquals("https://example.org/still.jpg", card?.frameUrl)
            assertEquals("https://example.org/poster.jpg", card?.posterUrl)
            assertEquals(1, images)
        } finally { f.close() }
    }
    @Test fun aServerFailureDoesNotEraseTheLastGoodVodCard() = runBlocking<Unit> {
        var offline = false
        val f = Fixture {
            if (offline) 401 to "{}"
            else if (it.url.encodedPath.contains("search")) 200 to search else 200 to details
        }
        try {
            assertEquals(TmdbCacheStatus.FOUND, f.tmdb.enrich(item).status)
            offline = true
            val cached = f.tmdb.enrich(item, true)
            assertEquals(TmdbCacheStatus.FOUND, cached.status)
            assertEquals(synopsis, cached.metadata?.overview)
        } finally { f.close() }
    }
    @Test fun aTemporaryErrorExpiresAfterSecondsInsteadOfBeingCachedForAWeek() = runBlocking<Unit> {
        var offline = true
        val f = Fixture {
            if (offline) 401 to "{}" else if (it.url.encodedPath.contains("search")) 200 to search else 200 to details
        }
        try {
            assertEquals(TmdbCacheStatus.ERROR, f.tmdb.enrich(item).status)
            f.driver.execute(null, "UPDATE tmdb_cache SET updated_at = ?", 1) { bindLong(0, System.currentTimeMillis() - 30_000) }
            offline = false
            assertEquals(TmdbCacheStatus.FOUND, f.tmdb.enrich(item).status)
        } finally { f.close() }
    }
    @Test fun kholopSequelDescriptionArrivesWhileTmdbIsStillWaiting() = runBlocking<Unit> {
        var queries = 0
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") {
                delay(20_000)
                200 to """{"results":[]}"""
            } else {
                queries++
                assertEquals("Холоп 2", request.url.parameters["keyword"])
                200 to """{"docs":[{"id":1,"name":"Холоп","description":"Не тот фильм"},
                  {"id":2,"name":"Холоп 2","description":"$synopsis","poster":{"url":"https://example.org/p.jpg"},"backdrop":{"url":"https://example.org/f.jpg"}}]}"""
            }
        }
        try {
            val repository = f.programme("test-key")
            val first = withTimeout(2_000) { repository.observe("Х/ф «Холоп2» (2024) 16+").first { hasProgrammeSynopsis(it?.overview) } }
            assertEquals(synopsis, first?.overview)
            assertEquals(synopsis, repository.find("Х/ф «Холоп2» (2024) 16+")?.overview)
            assertEquals(1, queries)
        } finally { f.close() }
    }

    @Test fun synopsisIsPublishedBeforeSlowKinopoiskDetailsAndImages() = runBlocking<Unit> {
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword"))
                200 to """{"docs":[{"id":2,"name":"Холоп 2","description":"$synopsis"}]}"""
            else { delay(20_000); 200 to "{}" }
        }
        try {
            val first = withTimeout(2_000) { f.programme("test-key").observe("Холоп 2").first { hasProgrammeSynopsis(it?.overview) } }
            assertEquals(synopsis, first?.overview)
        } finally { f.close() }
    }

    @Test fun placeholderInFullCardCannotEraseASearchSynopsis() = runBlocking<Unit> {
        val f = Fixture { request ->
            if (request.url.host != "kinopoiskapiunofficial.tech") 401 to "{}"
            else if (request.url.encodedPath.endsWith("/search-by-keyword"))
                200 to """{"docs":[{"id":2,"name":"Холоп 2","description":"$synopsis"}]}"""
            else 200 to """{"description":"Описание передачи отсутствует","poster":{"url":"https://example.org/p.jpg"}}"""
        }
        try {
            assertEquals(synopsis, f.programme("test-key").find("Холоп 2")?.overview)
        } finally { f.close() }
    }

}
