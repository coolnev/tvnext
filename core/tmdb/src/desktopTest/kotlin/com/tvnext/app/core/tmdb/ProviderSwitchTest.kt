package com.tvnext.app.core.tmdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProviderSwitchTest {
    @Test fun allFourProviderCombinationsWorkWithoutReusingADisabledProvidersCachedCard()=runBlocking<Unit> {
        var useTmdb=true;var useKino=true;var requests=0
        val settings=object:TmdbSettingsStore {
            override suspend fun isEnabled()=useTmdb;override suspend fun setEnabled(enabled:Boolean){useTmdb=enabled}
            override suspend fun getApiKey()="fixture";override suspend fun setApiKey(apiKey:String)=Unit
        }
        val keys=object:KinopoiskKeyStore {
            override suspend fun isEnabled()=useKino;override suspend fun setEnabled(enabled:Boolean){useKino=enabled}
            override suspend fun getKey()="fixture";override suspend fun setKey(value:String)=Unit
        }
        val client=HttpClient(MockEngine {r->requests++;respond(if(r.url.encodedPath.endsWith("search-by-keyword"))
            """{"films":[{"filmId":1,"nameRu":"Проверка","type":"FILM"}]}""" else
            """{"type":"FILM","description":"Кинопоиск: проверенный сюжет","posterUrl":"https://fixture/poster.jpg","coverUrl":"https://fixture/frame.jpg"}""",
            headers=headersOf("Content-Type","application/json"))}){install(HttpTimeout)}
        val tmdb=Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,arrayOf(TmdbRepository::class.java)){_,m,_->
            check(useTmdb);when(m.name) {
                "searchMovie"->listOf(TmdbSearchResult(1,title="Проверка",overview="TMDB: сюжет"))
                "getMovieDetails"->TmdbDetails(1,title="Проверка",overview="TMDB: сюжет")
                else->error(m.name)
            }
        } as TmdbRepository
        try {
            val repo=ProgrammeMetadataRepository(tmdb,KinopoiskProgrammeLookup(client,keys),settings)
            val request=ProgrammeMetadataRequest("Проверка","Фильм")
            assertEquals("Кинопоиск: проверенный сюжет",repo.find(request)?.overview)
            useKino=false
            val before=requests
            assertEquals("TMDB: сюжет",repo.find(request)?.overview);assertEquals(before,requests)
            useTmdb=false;useKino=true
            assertEquals("Кинопоиск: проверенный сюжет",repo.find(request)?.overview)
            useKino=false
            assertNull(repo.find(request));assertNull(repo.cached(request))
        } finally{client.close()}
    }
}
