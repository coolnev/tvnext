package com.tvnext.app.core.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.*

class ProgrammeDiscoveryTest {
    private val keys=object:KinopoiskKeyStore {override suspend fun getKey()="fixture";override suspend fun setKey(value:String)=Unit}
    private class Settings(val active:Boolean=false):TmdbSettingsStore {
        override suspend fun isEnabled()=active;override suspend fun setEnabled(enabled:Boolean)=Unit
        override suspend fun getApiKey()="fixture";override suspend fun setApiKey(apiKey:String)=Unit
    }
    private fun api(block:(String)->Any?)=Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,arrayOf(TmdbRepository::class.java)) {_,method,_->block(method.name)} as TmdbRepository
    private fun MockRequestHandleScope.response(body:String)=respond(body,headers=headersOf("Content-Type","application/json"))
    private val search="""{"films":[{"filmId":7,"nameRu":"Новый маршрут","type":"FILM","year":"2024","posterUrl":"https://fixture/unverified.jpg"}]}"""
    private val full="""{"kinopoiskId":7,"nameRu":"Новый маршрут","type":"FILM","year":2024,"filmLength":108,"genres":[{"genre":"комедия"}],"description":"Друзья отправляются путешествовать через горы, спасают деревню и находят пропавшего путешественника.","posterUrl":"https://fixture/verified.jpg","coverUrl":"https://fixture/frame.jpg"}"""

    @Test fun bareFilmTitleLoadsFullSynopsisAndNeverPublishesUnverifiedSearchArtwork()=runBlocking<Unit> {
        var requests=0;val updates=mutableListOf<ProgrammeMetadata>()
        val client=HttpClient(MockEngine {r->requests++;response(if(r.url.encodedPath.endsWith("search-by-keyword"))search.replace("\"type\":\"FILM\",", "") else full)}) {install(HttpTimeout)}
        try {
            val repo=ProgrammeMetadataRepository(api{error("TMDB disabled")},KinopoiskProgrammeLookup(client,keys),Settings())
            val request=ProgrammeMetadataRequest("Новый маршрут",durationMinutes=135)
            assertEquals(ProgrammeKind.DISCOVERY,request.kind)
            assertNotNull(repo.find(request,updates::add)?.overview)
            assertTrue(updates.isNotEmpty());assertTrue(updates.none{it.posterUrl?.contains("unverified")==true})
            assertEquals(2,requests)
        } finally {client.close()}
    }
    @Test fun filmCardCannotBeReusedForSameTitleShortNewsSlot()=runBlocking<Unit> {
        val client=HttpClient(MockEngine {r->response(if(r.url.encodedPath.endsWith("search-by-keyword"))search else full)}) {install(HttpTimeout)}
        try {
            val repo=ProgrammeMetadataRepository(api{error("TMDB disabled")},KinopoiskProgrammeLookup(client,keys),Settings())
            assertNotNull(repo.find(ProgrammeMetadataRequest("Новый маршрут",durationMinutes=135)))
            val short=ProgrammeMetadataRequest("Новый маршрут",durationMinutes=30)
            val updates=mutableListOf<ProgrammeMetadata>()
            assertNull(repo.cached(short));assertNull(repo.find(short,updates::add));assertTrue(updates.isEmpty())
        } finally {client.close()}
    }
    @Test fun identicallyNamedTelevisionShowMakesUnclassifiedFilmMatchAmbiguous()=runBlocking<Unit> {
        var detailsRequested=false
        val mixed=search.replace("]}",",{\"filmId\":8,\"nameRu\":\"Новый маршрут\",\"type\":\"TV_SHOW\"}]}")
        val client=HttpClient(MockEngine {r->if(!r.url.encodedPath.endsWith("search-by-keyword"))detailsRequested=true;response(mixed)}) {install(HttpTimeout)}
        try {
            val repo=ProgrammeMetadataRepository(api{error("TMDB disabled")},KinopoiskProgrammeLookup(client,keys),Settings())
            assertNull(repo.find(ProgrammeMetadataRequest("Новый маршрут",durationMinutes=135)))
            assertFalse(detailsRequested)
        } finally {client.close()}
    }
    @Test fun catalogueRuntimeAndGenresAreRequiredBeforeTmdbPublishesBareTitle()=runBlocking<Unit> {
        val client=HttpClient(MockEngine{error("Keyless Kinopoisk")}){install(HttpTimeout)}
        val noKey=object:KinopoiskKeyStore {override suspend fun getKey()="";override suspend fun setKey(value:String)=Unit}
        var isNews=false
        val tmdb=api {when(it) {
            "searchMovie"->if(isNews)emptyList() else listOf(TmdbSearchResult(7,title="Новый маршрут",overview="Не проверено",poster_path="/wrong.jpg"))
            "searchTv"->if(isNews)listOf(TmdbSearchResult(8,name="Новый маршрут")) else emptyList()
            "getMovieDetails"->TmdbDetails(7,title="Новый маршрут",runtime=108,release_date="2024-01-01",overview="Проверенный сюжет фильма",poster_path="/verified.jpg")
            "getTvDetails"->TmdbDetails(8,name="Новый маршрут",type="News",episode_run_time=listOf(60),genres=listOf(TmdbGenre(10763,"News")),overview="Новости")
            else->error(it)
        }}
        try {
            val repo=ProgrammeMetadataRepository(tmdb,KinopoiskProgrammeLookup(client,noKey),Settings(true))
            val updates=mutableListOf<ProgrammeMetadata>()
            assertEquals("Проверенный сюжет фильма",repo.find(ProgrammeMetadataRequest("Новый маршрут",durationMinutes=135),updates::add)?.overview)
            assertTrue(updates.none{it.posterUrl?.contains("wrong")==true})
            isNews=true
            assertNull(repo.find(ProgrammeMetadataRequest("Новый маршрут",durationMinutes=60)))
        } finally{client.close()}
    }
    @Test fun epgEpisodeAndStructuredYearWorkWithoutFilmPrefixAndBroadcastContextStillWins() {
        assertEquals(ProgrammeKind.SERIES,ProgrammeMetadataRequest("Название",episodeNum="0.2.").kind)
        assertEquals(ProgrammeKind.SERIES,ProgrammeMetadataRequest("Название. 2-я серия").kind)
        val movie=ProgrammeMetadataRequest("Название",description="Год выпуска: 2024")
        assertEquals(2024,movie.releaseYear)
        assertTrue(movie.canEnrich)
        assertFalse(ProgrammeMetadataRequest("Название",description="Гости студии обсуждают события",durationMinutes=120).canEnrich)
    }
}
