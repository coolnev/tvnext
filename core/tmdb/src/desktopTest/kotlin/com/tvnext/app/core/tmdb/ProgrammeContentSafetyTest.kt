package com.tvnext.app.core.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.lang.reflect.Proxy
import kotlin.test.*

class ProgrammeContentSafetyTest {
    @Test fun latinSeriesEpgQueriesKinopoiskByCleanTitleAndRejectsTheSameTitleMovie()=runBlocking<Unit> {
        val paths=mutableListOf<String>()
        val client=HttpClient(MockEngine {r->
            paths+=r.url.encodedPath
            val body=when {
                r.url.encodedPath.endsWith("search-by-keyword") -> {
                    assertEquals("Оперативная память",r.url.parameters["keyword"])
                    """{"films":[{"filmId":1,"nameRu":"Оперативная память","type":"FILM"},{"filmId":2,"nameRu":"Оперативная память","type":"TV_SERIES"}]}"""
                }
                r.url.encodedPath.endsWith("/2") -> """{"nameRu":"Оперативная память","type":"TV_SERIES","description":"Полное описание сериала, полученное из карточки Кинопоиска."}"""
                else -> error("Unexpected request ${r.url}")
            }
            respond(body,headers=headersOf("Content-Type","application/json"))
        }) {install(HttpTimeout)}
        try {
            val repo=ProgrammeMetadataRepository(tmdb{_,_->error("TMDB is disabled")},KinopoiskProgrammeLookup(client,keys),Settings(false))
            val request=ProgrammeMetadataRequest("T/S — Оперативная память")
            assertEquals("Полное описание сериала, полученное из карточки Кинопоиска.",repo.find(request)?.overview)
            assertEquals(repo.find(request),repo.cached(request))
            assertFalse(paths.any{it.endsWith("/1")})
        } finally {client.close()}
    }
    @Test fun latinSeriesEpgUsesTmdbTvSearchAndTvDetailsWithoutSearchingMovies()=runBlocking<Unit> {
        val calls=mutableListOf<String>()
        val client=HttpClient(MockEngine{error("No Kinopoisk key")}) {install(HttpTimeout)}
        val noKey=object:KinopoiskKeyStore{override suspend fun getKey()="";override suspend fun setKey(value:String)=Unit}
        val api=tmdb {name,args->calls+=name;when(name) {
            "searchTv"->{assertEquals("Оперативная память",args[0]);listOf(TmdbSearchResult(2,name="Оперативная память",overview="Описание сериала"))}
            "getTvDetails"->TmdbDetails(2,name="Оперативная память",overview="Полное описание сериала")
            else->error("Unexpected request $name")
        }}
        try {
            val repo=ProgrammeMetadataRepository(api,KinopoiskProgrammeLookup(client,noKey),Settings(true))
            assertEquals("Полное описание сериала",repo.find(ProgrammeMetadataRequest("T/С: «Оперативная память»"))?.overview)
            assertEquals(listOf("searchTv","getTvDetails"),calls)
        } finally {client.close()}
    }
    private class Settings(private val enabled: Boolean): TmdbSettingsStore {
        override suspend fun isEnabled()=enabled
        override suspend fun setEnabled(enabled: Boolean)=Unit
        override suspend fun getApiKey()="fixture"
        override suspend fun setApiKey(apiKey: String)=Unit
    }
    private val keys=object: KinopoiskKeyStore {
        override suspend fun getKey()="fixture"
        override suspend fun setKey(value: String)=Unit
    }
    private fun tmdb(block: (String,Array<out Any?>)->Any?) = Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,
        arrayOf(TmdbRepository::class.java)) {_,m,args->block(m.name,args.orEmpty())} as TmdbRepository

    @Test fun broadcastAndUnclassifiedEpgNeverQueryFilmCataloguesOrReuseTheirCards() = runBlocking<Unit> {
        var requests=0
        val client=HttpClient(MockEngine { requests++;error("No catalogue request for broadcast EPG") }) {install(HttpTimeout)}
        val repo=ProgrammeMetadataRepository(tmdb {_,_->requests++;error("No TMDB for a broadcast")},KinopoiskProgrammeLookup(client,keys),Settings(true))
        try {
            for (title in listOf("Поле чудес","Время","Большая игра","Случайное название передачи")) {
                for(category in listOf<String?>("Новости","Ток-шоу","Игра",null)) {
                    val request=ProgrammeMetadataRequest(title,category)
                    assertFalse(request.canEnrich)
                    assertNull(repo.find(request));assertNull(repo.cached(request));assertNull(repo.observe(request).first())
                }
            }
            assertEquals(0,requests)
        } finally {client.close()}
    }
    @Test fun epgTypeOutranksTitleAndMovieAndSeriesHaveSeparateCacheKeys() = runBlocking<Unit> {
        val calls=mutableListOf<String>()
        val client=HttpClient(MockEngine {error("Keyless Kino is disabled")}) {install(HttpTimeout)}
        val noKey=object: KinopoiskKeyStore {override suspend fun getKey()="";override suspend fun setKey(value:String)=Unit}
        val api=tmdb {name,_->calls+=name;when(name) {
            "searchMovie"->listOf(TmdbSearchResult(1,title="Время",release_date="2011-01-01",poster_path="/movie.jpg",overview="Сюжет фильма"))
            "getMovieDetails"->TmdbDetails(1,title="Время",overview="Сюжет фильма",poster_path="/movie.jpg")
            "searchTv"->listOf(TmdbSearchResult(2,name="Время",first_air_date="2022-01-01",poster_path="/series.jpg",overview="Сюжет сериала"))
            "getTvDetails"->TmdbDetails(2,name="Время",overview="Сюжет сериала",poster_path="/series.jpg")
            else->error(name)
        }}
        val repo=ProgrammeMetadataRepository(api,KinopoiskProgrammeLookup(client,noKey),Settings(true))
        try {
            val movie=ProgrammeMetadataRequest("Время","Художественный фильм")
            assertEquals("Сюжет фильма",repo.find(movie)?.overview)
            val count=calls.size
            assertNull(repo.find(ProgrammeMetadataRequest("Время","Новости")))
            assertNull(repo.cached(ProgrammeMetadataRequest("Время","Новости")))
            assertEquals(count,calls.size)
            assertEquals("Сюжет сериала",repo.find(ProgrammeMetadataRequest("Время","Сериал"))?.overview)
            assertEquals("Сюжет фильма",repo.cached(movie)?.overview)
            repo.forget(movie);assertNull(repo.cached(movie))
        } finally {client.close()}
    }
    @Test fun absentMovieNeverFallsThroughToSameTitleTelevisionResult()=runBlocking<Unit> {
        val client=HttpClient(MockEngine {error("No API key")}) {install(HttpTimeout)}
        val noKey=object: KinopoiskKeyStore {override suspend fun getKey()="";override suspend fun setKey(value:String)=Unit}
        val calls=mutableListOf<String>()
        val api=tmdb {name,_->calls+=name;when(name) {
            "searchMovie"->emptyList<TmdbSearchResult>()
            "searchTv"->listOf(TmdbSearchResult(3,name="Название",overview="Чужая телепередача"))
            else->error("Unexpected request $name")
        }}
        try {
            val repo=ProgrammeMetadataRepository(api,KinopoiskProgrammeLookup(client,noKey),Settings(true))
            assertNull(repo.find(ProgrammeMetadataRequest("Название","Фильм")))
            assertEquals(listOf("searchMovie"),calls)
        } finally {client.close()}
    }
    @Test fun kinopoiskTypeFilterChoosesSeriesAndUnverifiedFilmArtIsNeverPublished()= runBlocking<Unit> {
        val paths=mutableListOf<String>();val updates=mutableListOf<ProgrammeMetadata>()
        val client=HttpClient(MockEngine {r->paths+=r.url.encodedPath;val text=when {
            r.url.encodedPath.endsWith("search-by-keyword")->"""{"films":[{"filmId":1,"nameRu":"Время","type":"FILM","posterUrl":"https://fixture/movie.jpg"},{"filmId":2,"nameRu":"Время","type":"TV_SERIES","posterUrl":"https://fixture/series.jpg"}]}"""
            r.url.encodedPath.endsWith("/2")->"""{"type":"TV_SERIES","nameRu":"Время","description":"Описание сериала","posterUrl":"https://fixture/series.jpg","coverUrl":"https://fixture/series-frame.jpg"}"""
            else->error("Wrong identity: ${r.url.encodedPath}")
        };respond(text,headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {
            val result=KinopoiskProgrammeLookup(client,keys).lookup("Время",updates::add,ProgrammeKind.SERIES)
            assertEquals("Описание сериала",result?.overview)
            assertTrue(updates.isNotEmpty());assertTrue(updates.none {it.posterUrl?.contains("movie")==true})
            assertFalse(paths.any {it.endsWith("/1")})
        } finally {client.close()}
    }
    @Test fun wrongYearAndAmbiguousRemakesAreRejectedAndCorrectYearRemainsAvailable() {
        val body="""{"films":[{"filmId":1,"nameRu":"Название","type":"FILM","year":"1990"},{"filmId":2,"nameRu":"Название","type":"FILM","year":"2020"}]}"""
        assertNull(kinopoiskMatchedId(body,"Название",ProgrammeKind.MOVIE))
        assertNull(kinopoiskMatchedId(body,"Название (2024)",ProgrammeKind.MOVIE))
        assertEquals(2,kinopoiskMatchedId(body,"Название (2020)",ProgrammeKind.MOVIE))
    }
    @Test fun noImageOrDescriptionEscapesWhenFullCardContradictsItsSearchType()=runBlocking<Unit> {
        val seen=mutableListOf<ProgrammeMetadata>()
        val client=HttpClient(MockEngine {r->respond(if(r.url.encodedPath.endsWith("search-by-keyword"))
            """{"films":[{"filmId":1,"nameRu":"Название","type":"TV_SERIES","posterUrl":"https://fixture/wrong.jpg"}]}"""
            else """{"type":"FILM","description":"Чужой сюжет"}""",headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {assertNull(KinopoiskProgrammeLookup(client,keys).lookup("Название",seen::add,ProgrammeKind.SERIES));assertTrue(seen.isEmpty())}
        finally {client.close()}
    }
}
