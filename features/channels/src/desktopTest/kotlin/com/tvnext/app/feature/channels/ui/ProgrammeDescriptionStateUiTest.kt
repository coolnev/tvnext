package com.tvnext.app.feature.channels.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tvnext.app.core.tmdb.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.*
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgrammeDescriptionStateUiTest {
    @get:Rule val ui = createComposeRule()
    @Before fun start() { startKoin {} }
    @After fun stop() { stopKoin() }

    @Test fun switchingFilmsNeverRendersThePreviousSynopsisAndCachedReturnHasNoBlankFrame() {
        val plotA = "Первый герой возвращается домой и встречает старого друга."
        val plotB = "Второй герой спасает семью и раскрывает опасный заговор."
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            val text = when {
                request.url.encodedPath.endsWith("/search-by-keyword") -> {
                    val first = request.url.parameters["keyword"] == "Первый фильм"
                    val id = if (first) 1 else 2
                    val title = if (first) "Первый фильм" else "Хитрый койот"
                    """{"films":[{"filmId":$id,"nameRu":"$title","posterUrlPreview":"https://example.org/$id.jpg"}]}"""
                }
                request.url.encodedPath.endsWith("/1") -> """{"type":"FILM","description":"$plotA"}"""
                request.url.encodedPath.endsWith("/2") -> { entered.complete(Unit);release.await();"""{"type":"FILM","description":"$plotB"}""" }
                else -> "{}"
            }
            respond(text, headers = headersOf("Content-Type", "application/json"))
        }) { install(HttpTimeout) }
        val keys = object : KinopoiskKeyStore {
            override suspend fun getKey() = "test-key"
            override suspend fun setKey(value: String) = Unit
        }
        val settings = object : TmdbSettingsStore {
            override suspend fun isEnabled() = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun getApiKey() = ""
            override suspend fun setApiKey(apiKey: String) = Unit
        }
        val tmdb = java.lang.reflect.Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,
            arrayOf(TmdbRepository::class.java)) { _, _, _ -> error("TMDB disabled") } as TmdbRepository
        val repository = ProgrammeMetadataRepository(tmdb, KinopoiskProgrammeLookup(client, keys), settings)
        loadKoinModules(module { single { repository } })
        val title = mutableStateOf("Первый фильм")
        val seen = mutableListOf<Pair<String, String?>>()
        try {
            runBlocking { assertEquals(plotA, repository.find(ProgrammeMetadataRequest("Первый фильм",category="Фильм"))?.overview) }
            assertNull(repository.cached("Хитрый койот"))
            assertNull(repository.cached("Первый фильм (2024)"))
            ui.setContent {
                val card = rememberProgrammeMetadata(title.value, debounceMs = 0)
                SideEffect { seen += title.value to card?.overview }
                Text(card?.overview ?: "Ожидание")
            }
            ui.onNodeWithText(plotA).assertIsDisplayed()
            ui.runOnIdle { title.value = "Хитрый койот" }
            ui.waitUntil(5_000) { entered.isCompleted }
            ui.waitForIdle()
            assertTrue(seen.filter { it.first == "Хитрый койот" }.isNotEmpty())
            assertTrue(seen.filter { it.first == "Хитрый койот" }.none { it.second == plotA })
            release.complete(Unit)
            ui.waitUntil(5_000) { ui.onAllNodesWithText(plotB).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(plotB).assertIsDisplayed()
            val boundary = seen.size
            ui.runOnIdle { title.value = "Первый фильм" }
            ui.waitForIdle()
            assertTrue(seen.drop(boundary).filter { it.first == "Первый фильм" }.isNotEmpty())
            assertTrue(seen.drop(boundary).filter { it.first == "Первый фильм" }.none { it.second == plotB }, "Reopening must not reuse another programme's story")
        } finally { release.complete(Unit);client.close() }
    }
    @Test fun sameTitleNewsKeepsItsEpgAndNeverDisplaysThePreviouslyViewedMoviesArtwork() {
        val filmPlot="Сюжет одноименного фантастического фильма."
        val newsPlot="Информационная программа. Главные события дня."
        var requests=0
        val client=HttpClient(MockEngine {r->requests++;respond(if(r.url.encodedPath.endsWith("search-by-keyword"))
            """{"films":[{"filmId":1,"nameRu":"Время","type":"FILM","year":"2011"}]}"""
            else """{"type":"FILM","year":2011,"description":"$filmPlot","posterUrl":"https://fixture/movie.jpg","coverUrl":"https://fixture/frame.jpg"}""",
            headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        val keys=object:KinopoiskKeyStore {override suspend fun getKey()="fixture";override suspend fun setKey(value:String)=Unit}
        val settings=object:TmdbSettingsStore {override suspend fun isEnabled()=false;override suspend fun setEnabled(enabled:Boolean)=Unit
            override suspend fun getApiKey()="";override suspend fun setApiKey(apiKey:String)=Unit}
        val tmdb=java.lang.reflect.Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,arrayOf(TmdbRepository::class.java)){_,_,_->error("TMDB disabled")} as TmdbRepository
        val repo=ProgrammeMetadataRepository(tmdb,KinopoiskProgrammeLookup(client,keys),settings)
        loadKoinModules(module {single {repo}})
        val now=System.currentTimeMillis()
        val programme=mutableStateOf(com.tvnext.app.core.iptv.xmltv.EpgProgramme("c",now,now+3_600_000,"Время (2011)",category="Фильм"))
        try {
            ui.setContent {androidx.compose.material3.MaterialTheme {ProgrammeRichDetails(programme.value)}}
            ui.waitUntil(5_000) {ui.onAllNodesWithText(filmPlot).fetchSemanticsNodes().isNotEmpty()}
            val before=requests
            ui.runOnIdle {programme.value=programme.value.copy(title="Время",category="Новости",description=newsPlot)}
            ui.waitForIdle()
            ui.onNodeWithText(newsPlot).assertIsDisplayed()
            ui.onNodeWithText(filmPlot).assertDoesNotExist()
            ui.onNodeWithTag("programme-description-artwork").assertDoesNotExist()
            assertEquals(before,requests)
        } finally {client.close()}
    }

}
