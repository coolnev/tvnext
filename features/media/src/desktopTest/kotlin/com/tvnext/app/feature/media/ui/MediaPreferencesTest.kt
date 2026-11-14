package com.tvnext.app.feature.media.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import com.tvnext.app.core.database.*
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.player.*
import com.tvnext.app.core.tmdb.*
import com.tvnext.app.feature.media.data.VodProgressStore
import com.tvnext.app.feature.settings.data.*
import com.tvnext.app.feature.settings.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlin.test.*

class MediaPreferencesTest {
    private class Meta:AppMetaRepository {
        val map=linkedMapOf<String,String>();val rows=MutableStateFlow<List<App_meta>>(emptyList())
        override suspend fun getValue(key:String)=map[key]
        override suspend fun setValue(key:String,value:String){map[key]=value;rows.value=map.map{App_meta(it.key,it.value)}}
        override fun observeAll():Flow<List<App_meta>> = rows
    }
    private inline fun <reified T> fake(noinline fn:(String,Array<out Any?>)->Any?):T=Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,m,a->fn(m.name,a.orEmpty())} as T
    private inner class Fixture(preferences:MediaPreferences=MediaPreferences(),saved:MediaBrowserPosition?=null,kino:KinopoiskProgrammeLookup?=null,tmdbCard:TmdbMetadata?=null) {
        val meta=Meta();val settings=SqlDelightSettingsRepository(meta);val progress=VodProgressStore(meta)
        val items=listOf(MediaItem("b","vod","Бета",1,"З Категория","file:///b"),MediaItem("a","vod","Альфа",2,"А Категория","file:///a"))
        val playerState=MutableStateFlow(PlayerState());var played=0;var seek=0L
        val player=fake<PlaybackController>{m,a->when(m){
            "getState"->playerState
            "play"->{played++;playerState.value=PlayerState(item=a[0] as PlaybackItem,state=PlaybackState.PLAYING);Unit}
            "seekTo"->{seek=a[0] as Long;Unit}
            else->Unit
        }}
        val library=fake<MediaLibraryRepository>{m,_->when(m){"getSources"->listOf(com.tvnext.app.core.iptv.domain.Playlist("vod","VOD","https://fixture/list",type=PlaylistType.VOD));"getItems"->items;"getFavoriteIds"->emptySet<String>();else->Unit}}
        val history=fake<HistoryRepository>{m,_->if(m=="getRecentByType")emptyList<HistoryEntry>() else Unit}
        val tmdb=fake<TmdbRepository>{m,_->if(m=="enrich")TmdbLookupResult(tmdbCard,if(tmdbCard==null)TmdbCacheStatus.NOT_FOUND else TmdbCacheStatus.FOUND) else Unit}
        val lifecycle=LifecycleRegistry().apply{create();resume()}
        val component:DefaultMediaComponent
        init {
            runBlocking{settings.save(AppSettings(media=preferences));if(saved!=null)settings.setMediaBrowserPosition(saved);progress.set("a",90_000)}
            component=DefaultMediaComponent(DefaultComponentContext(lifecycle),library,history,progress,tmdb,settings,player,Dispatchers.Unconfined,kino)
            runBlocking{withTimeout(3000){component.state.first{!it.isLoading}}}
        }
        fun close(){lifecycle.destroy()}
    }
    @Test fun libraryInformationCanComeFromKinopoiskWhileTmdbReturnsNoCard()=runBlocking<Unit> {
        val keys=object:KinopoiskKeyStore {override suspend fun getKey()="fixture";override suspend fun setKey(value:String)=Unit}
        val client=HttpClient(MockEngine {r->respond(if(r.url.encodedPath.endsWith("search-by-keyword"))
            """{"films":[{"filmId":1,"nameRu":"Альфа"}]}""" else
            """{"type":"FILM","description":"Сюжет из Кинопоиска","posterUrl":"https://fixture/poster.jpg","coverUrl":"https://fixture/frame.jpg"}""",
            headers=headersOf("Content-Type","application/json"))}){install(HttpTimeout)}
        val f=Fixture(kino=KinopoiskProgrammeLookup(client,keys))
        try {
            f.component.dispatch(MediaIntent.ShowInfo("a"))
            val result=withTimeout(3000){f.component.state.first{it.infoTmdbMetadata?.overview=="Сюжет из Кинопоиска"}}
            assertEquals("https://fixture/poster.jpg",result.infoTmdbMetadata?.posterUrl)
            assertEquals(0,f.played)
        }finally{f.close();client.close()}
    }
    @Test fun aFastTmdbSynopsisAppearsBeforeASlowKinopoiskRequestFinishes()=runBlocking<Unit> {
        val release=CompletableDeferred<Unit>()
        val client=HttpClient(MockEngine {release.await();respond("{}")}){install(HttpTimeout)}
        val keys=object:KinopoiskKeyStore {override suspend fun getKey()="fixture";override suspend fun setKey(value:String)=Unit}
        val f=Fixture(kino=KinopoiskProgrammeLookup(client,keys),tmdbCard=TmdbMetadata(1,"movie",overview="Быстрый сюжет TMDB"))
        try {
            f.component.dispatch(MediaIntent.ShowInfo("a"))
            val result=withTimeout(1500){f.component.state.first{it.infoTmdbMetadata?.overview=="Быстрый сюжет TMDB"}}
            assertFalse(result.infoTmdbLoading)
            assertFalse(release.isCompleted)
        }finally{release.complete(Unit);f.close();client.close()}
    }

    @Test fun sortingChangesRealCatalogueAndProviderCategoryOrderCanBeRestored()=runBlocking<Unit> {
        val f=Fixture()
        try {
            assertEquals(listOf("А Категория","З Категория"),f.component.state.value.categories)
            assertEquals(listOf("b","a"),f.component.state.value.filteredItems.map{it.id})
            f.settings.save(AppSettings(media=MediaPreferences(filmSort=MediaFilmSort.NAME_ASC,categorySort=MediaCategorySort.PROVIDER)))
            assertEquals(listOf("a","b"),f.component.state.value.filteredItems.map{it.id})
            assertEquals(listOf("З Категория","А Категория"),f.component.state.value.categories)
        } finally{f.close()}
    }
    @Test fun askModeWaitsForUserAndContinueSeeksToTheSavedPosition() {
        val f=Fixture(MediaPreferences(resumeMode=MediaResumeMode.ASK))
        try {
            f.component.dispatch(MediaIntent.SelectItem("a"))
            assertNotNull(f.component.state.value.pendingResume);assertEquals(0,f.played)
            f.component.dispatch(MediaIntent.ResumeChosen(true))
            assertEquals(1,f.played);assertEquals(90_000L,f.seek)
        } finally{f.close()}
    }
    @Test fun startingOverDoesNotSeekAndClearsTheOldResumePoint()=runBlocking<Unit> {
        for(mode in listOf(MediaResumeMode.ASK,MediaResumeMode.START_OVER)) {
            val f=Fixture(MediaPreferences(resumeMode=mode))
            try {
                f.component.dispatch(MediaIntent.SelectItem("a"))
                if(mode==MediaResumeMode.ASK)f.component.dispatch(MediaIntent.ResumeChosen(false))
                assertEquals(1,f.played);assertEquals(0L,f.seek);assertNull(f.progress.get("a"))
            } finally{f.close()}
        }
    }
    @Test fun cancellingTheResumeQuestionDoesNotStartTheFilm() {
        val f=Fixture(MediaPreferences(resumeMode=MediaResumeMode.ASK))
        try {f.component.dispatch(MediaIntent.SelectItem("a"));f.component.dispatch(MediaIntent.ResumeCancelled)
            assertNull(f.component.state.value.pendingResume);assertEquals(0,f.played)
        } finally{f.close()}
    }
    @Test fun savedBrowserPositionRestoresSourceCategoryAndSelectedFilmAndTracksNewFocus()=runBlocking<Unit> {
        val saved=MediaBrowserPosition("vod","А Категория","a",false,true)
        val f=Fixture(MediaPreferences(rememberPosition=true),saved)
        try {
            assertEquals("vod",f.component.state.value.selectedSourceId)
            assertEquals("А Категория",f.component.state.value.selectedCategory)
            assertEquals(saved,f.component.state.value.browser)
            val changed=saved.copy(category=null,itemId="b")
            f.component.dispatch(MediaIntent.BrowserMoved(changed))
            withTimeout(3000){while(f.settings.getMediaBrowserPosition()!=changed)delay(10)}
            assertEquals(changed,f.settings.getMediaBrowserPosition())
        } finally{f.close()}
    }
    @Test fun missingProviderCategoryAndRemovedFilmDoNotPreventCatalogueOpening() {
        val f=Fixture(MediaPreferences(rememberPosition=true),MediaBrowserPosition("removed","Missing","deleted",false,true))
        try {assertNull(f.component.state.value.selectedSourceId);assertNull(f.component.state.value.selectedCategory);assertEquals(2,f.component.state.value.filteredItems.size)}finally{f.close()}
    }
}
