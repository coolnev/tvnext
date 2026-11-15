package com.tvnext.app.core.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.*

class SeriesProgrammeLookupTest {
    private val keys=object:KinopoiskKeyStore {
        override suspend fun getKey()="fixture"
        override suspend fun setKey(value:String)=Unit
    }
    private val settings=object:TmdbSettingsStore {
        override suspend fun isEnabled()=false
        override suspend fun setEnabled(enabled:Boolean)=Unit
        override suspend fun getApiKey()=""
        override suspend fun setApiKey(apiKey:String)=Unit
    }
    private fun repository(client:HttpClient)=ProgrammeMetadataRepository(
        Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,arrayOf(TmdbRepository::class.java)) {_,_,_->error("TMDB disabled")} as TmdbRepository,
        KinopoiskProgrammeLookup(client,keys),settings)
    private val plot="Опытный сыщик расследует преступления вместе с молодым напарником, который помогает ему сохранить важные воспоминания и раскрыть тайну прошлого."
    private val details get()="""{"kinopoiskId":5599922,"nameRu":"Оперативная память","type":"TV_SERIES","year":2025,"description":"$plot","posterUrl":"https://fixture/poster.jpg","coverUrl":"https://fixture/frame.jpg"}"""

    @Test fun actualArchiveTitleWithBrokenBarAndEpisodeFetchesTheFullSeriesDescription()=runBlocking<Unit> {
        val queries=mutableListOf<String?>()
        val client=HttpClient(MockEngine {r->respond(when {
            r.url.encodedPath.endsWith("search-by-keyword")->{queries+=r.url.parameters["keyword"]
                """{"films":[{"filmId":5599922,"nameRu":"Оперативная память","type":null,"year":"2025"}]}"""}
            r.url.encodedPath.endsWith("/5599922")->details
            else->error("Unexpected request ${r.url}")
        },headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {
            val request=ProgrammeMetadataRequest("Т/с Оперативная память ¦ 1.",durationMinutes=50)
            assertEquals(plot,repository(client).find(request)?.overview)
            assertEquals(listOf<String?>("Оперативная память"),queries)
        } finally {client.close()}
    }

    @Test fun aKnownSeriesWithoutPrefixUsesItsCategoryAndSharesTheVerifiedCardBetweenEpisodes()=runBlocking<Unit> {
        var requests=0
        val client=HttpClient(MockEngine {r->requests++;respond(if(r.url.encodedPath.endsWith("search-by-keyword")) {
            assertEquals("Оперативная память",r.url.parameters["keyword"])
            """{"films":[{"filmId":5599922,"nameRu":"Оперативная память","type":"TV_SERIES"}]}"""
        } else details,headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {
            val repo=repository(client)
            assertEquals(plot,repo.find(ProgrammeMetadataRequest("Оперативная память ┆ 1.",category="Сериал",episodeNum="0.0."))?.overview)
            val initial=requests
            assertEquals(plot,repo.find(ProgrammeMetadataRequest("Оперативная память ¦ 2.",category="Сериал",episodeNum="0.1."))?.overview)
            assertEquals(initial,requests,"Episode annotations must not restart the same verified series lookup")
        } finally {client.close()}
    }

    @Test fun ambiguousLegacySearchFallsBackToTheTypedSeriesEndpointAndVerifiesDetails()=runBlocking<Unit> {
        val paths=mutableListOf<String>()
        val client=HttpClient(MockEngine {r->paths+=r.url.encodedPath;respond(when {
            r.url.encodedPath.endsWith("search-by-keyword")->"""{"films":[{"filmId":1,"nameRu":"Оперативная память"},{"filmId":5599922,"nameRu":"Оперативная память"}]}"""
            r.url.encodedPath=="/api/v2.2/films"->{assertEquals("TV_SERIES",r.url.parameters["type"]);assertEquals("Оперативная память",r.url.parameters["keyword"])
                """{"items":[{"kinopoiskId":5599922,"nameRu":"Оперативная память","type":"TV_SERIES"}]}"""}
            r.url.encodedPath.endsWith("/5599922")->details
            else->error("A movie must not be fetched: ${r.url}")
        },headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {
            assertEquals(plot,repository(client).find(ProgrammeMetadataRequest("Т/с Оперативная память ¦ 1."))?.overview)
            assertEquals(listOf("/api/v2.1/films/search-by-keyword","/api/v2.2/films","/api/v2.2/films/5599922"),paths)
        } finally {client.close()}
    }

    @Test fun aTemporaryDetailFailureRetriesWhileTheSameSeriesCardRemainsVisible()=runBlocking<Unit> {
        var detailRequests=0
        val client=HttpClient(MockEngine {r->when {
            r.url.encodedPath.endsWith("search-by-keyword")->respond("""{"films":[{"filmId":5599922,"nameRu":"Оперативная память","type":"TV_SERIES"}]}""",headers=headersOf("Content-Type","application/json"))
            else->{detailRequests++;if(detailRequests<=2) respond("{}",HttpStatusCode.ServiceUnavailable)
                else respond(details,headers=headersOf("Content-Type","application/json"))}
        }}) {install(HttpTimeout)}
        try {
            val cards=repository(client).observe(ProgrammeMetadataRequest("Т/с Оперативная память")).toList()
            assertTrue(cards.any{it?.overview==plot},"A transient first failure must not leave the visible description permanently empty")
            assertEquals(3,detailRequests)
        } finally {client.close()}
    }

    @Test fun typedFallbackStillRejectsMoviesAndAConfirmedEmptyResultDoesNotPoll()=runBlocking<Unit> {
        var requests=0
        val client=HttpClient(MockEngine {r->requests++;respond(when(r.url.encodedPath) {
            "/api/v2.1/films/search-by-keyword"->"""{"films":[]}"""
            "/api/v2.2/films"->"""{"items":[{"kinopoiskId":1,"nameRu":"Оперативная память","type":"FILM"}]}"""
            else->error("Unverified artwork or description must not be fetched")
        },headers=headersOf("Content-Type","application/json"))}) {install(HttpTimeout)}
        try {
            val repo=repository(client)
            assertTrue(repo.observe(ProgrammeMetadataRequest("Т/с Оперативная память ¦ 1.")).toList().all{it==null})
            assertEquals(2,requests)
        } finally {client.close()}
    }

    @Test fun tmdbAlsoReceivesTheSeriesTitleWithoutTheArchiveEpisodeSuffix()=runBlocking<Unit> {
        val queries=mutableListOf<String>()
        val api=Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,arrayOf(TmdbRepository::class.java)) {_,method,args->
            when(method.name) {
                "searchTv"->{queries+=args[0] as String;listOf(TmdbSearchResult(9,name="Оперативная память"))}
                "getTvDetails"->TmdbDetails(9,name="Оперативная память",overview=plot)
                else->error("A series must not use the movie API: ${method.name}")
            }
        } as TmdbRepository
        val noKey=object:KinopoiskKeyStore{override suspend fun getKey()="";override suspend fun setKey(value:String)=Unit}
        val active=object:TmdbSettingsStore by settings {override suspend fun isEnabled()=true}
        val client=HttpClient(MockEngine{error("No Kinopoisk key")}){install(HttpTimeout)}
        try {
            val repo=ProgrammeMetadataRepository(api,KinopoiskProgrammeLookup(client,noKey),active)
            assertEquals(plot,repo.find(ProgrammeMetadataRequest("Оперативная память ¦ 1.",episodeNum="0.0."))?.overview)
            assertEquals(listOf("Оперативная память"),queries)
        } finally {client.close()}
    }
}
