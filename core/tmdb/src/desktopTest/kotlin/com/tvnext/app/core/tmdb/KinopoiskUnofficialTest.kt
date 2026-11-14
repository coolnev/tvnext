package com.tvnext.app.core.tmdb

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.database.SqlDelightAppMetaRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

class KinopoiskUnofficialTest {
    private class Keys(private var key: String = "unofficial-test-key") : KinopoiskKeyStore {
        override suspend fun getKey() = key
        override suspend fun setKey(value: String) { key = value }
    }
    private fun response(body: String, code: Int = 200) = Triple(body, HttpStatusCode.fromValue(code), headersOf("Content-Type", "application/json"))

    @Test fun onlyUnofficialSearchDetailsAndStillsAreRequestedWithTheConfiguredKey() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val updates = mutableListOf<ProgrammeMetadata>()
        val synopsis = "Герои продолжают приключения во второй части фильма."
        val client = HttpClient(MockEngine { request ->
            assertEquals("kinopoiskapiunofficial.tech", request.url.host)
            assertEquals("unofficial-test-key", request.headers["X-API-KEY"])
            paths += request.url.encodedPath
            val data = when (request.url.encodedPath) {
                "/api/v2.1/films/search-by-keyword" -> {
                    assertEquals("Холоп 2", request.url.parameters["keyword"])
                    response("""{"films":[{"filmId":1,"nameRu":"Холоп","year":"2019"},{"filmId":2,"nameRu":"Холоп 2","year":"2024","posterUrlPreview":"https://example.org/poster.jpg"}]}""")
                }
                "/api/v2.2/films/2" -> response("""{"kinopoiskId":2,"nameRu":"Холоп 2","description":"$synopsis"}""")
                "/api/v2.2/films/2/images" -> {
                    assertEquals("STILL", request.url.parameters["type"])
                    assertTrue(updates.any { it.overview == synopsis }, "Publish the story before downloading stills")
                    response("""{"total":1,"items":[{"imageUrl":"https://example.org/full.jpg","previewUrl":"https://example.org/frame.jpg"}]}""")
                }
                else -> error("Unexpected API path")
            }
            respond(data.first, data.second, data.third)
        }) { install(HttpTimeout) }
        try {
            val card = KinopoiskProgrammeLookup(client, Keys()).lookup("Х/ф Холоп2 (2024) 16+", updates::add)
            assertEquals(synopsis, card?.overview)
            assertEquals("https://example.org/poster.jpg", card?.posterUrl)
            assertEquals("https://example.org/frame.jpg", card?.frameUrl)
            assertEquals(listOf("/api/v2.1/films/search-by-keyword", "/api/v2.2/films/2", "/api/v2.2/films/2/images"), paths)
        } finally { client.close() }
    }

    @Test fun clearingCacheRefetchesTheCardAndDoesNotChangeTheKeyOrEnableSwitch() = runBlocking<Unit> {
        var calls=0
        val keys=Keys()
        val client=HttpClient(MockEngine { request ->
            calls++
            respond(if(request.url.encodedPath.endsWith("search-by-keyword"))
                """{"films":[{"filmId":2,"nameRu":"Холоп 2","type":"FILM"}]}""" else
                """{"type":"FILM","description":"${"Полное описание фильма. ".repeat(8)}","posterUrl":"https://fixture/p.jpg","coverUrl":"https://fixture/f.jpg"}""",
                headers=headersOf("Content-Type","application/json"))
        }) { install(HttpTimeout) }
        try {
            val lookup=KinopoiskProgrammeLookup(client,keys)
            assertNotNull(lookup.lookup("Холоп 2"))
            val initial=calls
            assertNotNull(lookup.lookup("Холоп 2"))
            assertEquals(initial,calls,"Second lookup must use the completed card")
            lookup.clearCache()
            assertEquals("unofficial-test-key",keys.getKey())
            assertTrue(keys.isEnabled())
            assertNotNull(lookup.lookup("Холоп 2"))
            assertEquals(initial*2,calls,"Clear must evict the search and full card")
        } finally {client.close()}
    }

    @Test fun responseThatFinishesAfterClearCannotRepopulateTheCache() = runBlocking<Unit> {
        var searches=0
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine { request ->
            val body=if(request.url.encodedPath.endsWith("search-by-keyword")) {
                searches++
                """{"films":[{"filmId":2,"nameRu":"Холоп 2","type":"FILM"}]}"""
            } else {
                if(!entered.isCompleted) {entered.complete(Unit);release.await()}
                """{"type":"FILM","description":"${"Полное описание фильма. ".repeat(8)}","posterUrl":"https://fixture/p.jpg","coverUrl":"https://fixture/f.jpg"}"""
            }
            respond(body,headers=headersOf("Content-Type","application/json"))
        }) {install(HttpTimeout)}
        try {
            val lookup=KinopoiskProgrammeLookup(client,Keys())
            val pending=async{lookup.lookup("Холоп 2")}
            entered.await();lookup.clearCache();release.complete(Unit)
            assertNotNull(pending.await())
            assertNotNull(lookup.lookup("Холоп 2"))
            assertEquals(2,searches,"An old in-flight card must not reappear in the cleared cache")
        } finally {client.close()}
    }

    @Test fun legacyDevKeyCannotOverrideTheUnofficialDefaultOnUpgrade() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvNextDatabase.Schema.create(driver)
            val meta = SqlDelightAppMetaRepository(TvNextDatabase(driver))
            meta.setValue("settings.kinopoisk_backup_key", "disabled-dev-key")
            val store = SqlDelightKinopoiskKeyStore(meta, "unofficial-default")
            assertEquals("unofficial-default", store.getKey())
            store.setKey("  manual-unofficial-key  ")
            assertEquals("manual-unofficial-key", SqlDelightKinopoiskKeyStore(meta, "unofficial-default").getKey())
            assertEquals("disabled-dev-key", meta.getValue("settings.kinopoisk_backup_key"))
            store.setKey("")
            assertEquals("unofficial-default", store.getKey())
        } finally { driver.close() }
    }

    @Test fun connectionCheckValidatesAuthorizedJsonFromTheActiveProvider() = runBlocking<Unit> {
        val client = HttpClient(MockEngine { request ->
            assertEquals("kinopoiskapiunofficial.tech", request.url.host)
            assertEquals("/api/v2.2/films/301", request.url.encodedPath)
            assertEquals("unofficial-test-key", request.headers["X-API-KEY"])
            respond("""{"kinopoiskId":301,"nameRu":"Матрица"}""", headers = headersOf("Content-Type", "application/json"))
        }) { install(HttpTimeout) }
        try {
            val result = KinopoiskProgrammeLookup(client, Keys()).checkConnection()
            assertEquals(KinopoiskConnectionResult.Kind.SUCCESS, result.kind)
            assertEquals(200, result.httpStatus)
            assertTrue(result.message.contains("установлено"))
        } finally { client.close() }
    }

    @Test fun keyQuotaAndServerFailuresHaveDistinctMessages() = runBlocking<Unit> {
        for ((status, kind) in listOf(401 to KinopoiskConnectionResult.Kind.INVALID_KEY, 403 to KinopoiskConnectionResult.Kind.INVALID_KEY,
            402 to KinopoiskConnectionResult.Kind.LIMIT_REACHED, 429 to KinopoiskConnectionResult.Kind.LIMIT_REACHED,
            500 to KinopoiskConnectionResult.Kind.SERVER_ERROR, 503 to KinopoiskConnectionResult.Kind.SERVER_ERROR)) {
            val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status)) }) { install(HttpTimeout) }
            try {
                val result = KinopoiskProgrammeLookup(client, Keys()).checkConnection()
                assertEquals(kind, result.kind)
                assertTrue(result.message.contains(status.toString()))
                assertFalse(result.message.contains("unofficial-test-key"))
            } finally { client.close() }
        }
    }

    @Test fun missingKeyDoesNotRequestTheNetworkAndHtmlIsNotReportedAsSuccess() = runBlocking<Unit> {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("<html>Gateway</html>") }) { install(HttpTimeout) }
        try {
            assertEquals(KinopoiskConnectionResult.Kind.KEY_MISSING, KinopoiskProgrammeLookup(client, Keys("")).checkConnection().kind)
            assertEquals(0, calls)
            assertEquals(KinopoiskConnectionResult.Kind.INVALID_RESPONSE, KinopoiskProgrammeLookup(client, Keys()).checkConnection().kind)
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun networkFailureIsShownWithoutConfusingItWithAnInvalidKey() = runBlocking<Unit> {
        val client = HttpClient(MockEngine { throw IOException("Network unavailable") }) { install(HttpTimeout) }
        try {
            assertEquals(KinopoiskConnectionResult.Kind.NO_CONNECTION, KinopoiskProgrammeLookup(client, Keys()).checkConnection().kind)
        } finally { client.close() }
    }

    @Test fun leavingSettingsCancelsTheConnectionCheck() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { entered.complete(Unit); awaitCancellation() }) { install(HttpTimeout) }
        try {
            val job = async { KinopoiskProgrammeLookup(client, Keys()).checkConnection() }
            withTimeout(2_000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { client.close() }
    }
}
