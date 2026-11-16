package com.tvnext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.app.*
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.SharedVideoStage
import com.tvnext.app.feature.media.ui.*
import com.tvnext.app.feature.media.domain.MediaContent
import com.tvnext.app.feature.settings.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MediaPresentationTest {
    private class Player:PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("channel","Канал","fixture")))
        override fun play(item:PlaybackItem){state.value=state.value.copy(item=item)}
        override fun pause()=Unit
        override fun resume()=Unit
        override fun seekTo(positionMs:Long)=Unit
        override fun seekBy(deltaMs:Long)=Unit
        override fun setVolume(volume:Float)=Unit
        override fun setMuted(muted:Boolean)=Unit
        override fun stop()=Unit
        override fun release()=Unit
    }
    private val sources=listOf(Playlist("a","A","fixture-a",type=PlaylistType.VOD),Playlist("b","B","fixture-b",type=PlaylistType.VOD))
    private val movies=listOf(MediaItem("a1","a","Кино A",1,"Фильмы","fixture",isFavorite=true),
        MediaItem("a2","a","Сериал",2,"Сериалы","fixture"),MediaItem("b1","b","Кино B",1,"Фильмы","fixture",isFavorite=true))
    @Test fun favoritesFilterKeepsSourceSearchAndSortButCrossesProviderGenres() {
        val state=MediaState(content=MediaContent(sources=sources,allItems=movies),selectedCategory="Сериалы",
            preferences=MediaPreferences(filmSort=MediaFilmSort.NAME_DESC))
        assertEquals(listOf("b1","a1"),desktopMediaItems(state,true).map {it.id})
        assertEquals(listOf("a2"),desktopMediaItems(state,false).map {it.id})
        assertEquals(listOf("a1"),desktopMediaItems(state.copy(selectedSourceId="a"),true).map {it.id})
        assertEquals(listOf("b1"),desktopMediaItems(state.copy(query="Кино B"),true).map {it.id})
    }
    @Test fun androidPosterHasOnlyAFilledYellowStarForFavoritesAndNoStarInItsTitle()=runDesktopComposeUiTest(width=1100,height=720) {
        val player=Player()
        val model=MutableStateFlow(MediaState(content=MediaContent(sources=sources,allItems=movies),browser=MediaBrowserPosition(inFilms=true),preferences=MediaPreferences(rememberPosition=true)))
        val component=object:MediaComponent {
            override val state=model
            override val seekStepMs=MutableStateFlow(10_000L)
            override val playbackController=player
            override fun dispatch(intent:MediaIntent)=Unit
        }
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {MediaScreen(component,Modifier.fillMaxSize())}}
        waitUntil(timeoutMillis=10_000){onAllNodesWithTag("media-film-a1").fetchSemanticsNodes().isNotEmpty()}
        onNodeWithTag("media-favorite-a1").assertIsDisplayed()
        onNodeWithTag("media-favorite-a2").assertDoesNotExist()
        onNodeWithText("★ Кино A").assertDoesNotExist();onNodeWithText("Кино A").assertExists()
        val poster=onNodeWithTag("media-poster-a1").fetchSemanticsNode().boundsInRoot
        val star=onNodeWithTag("media-favorite-a1").fetchSemanticsNode().boundsInRoot
        assertTrue(star.left>=poster.left && star.top>=poster.top && star.left-poster.left<20f && star.top-poster.top<20f)
        val image=onNodeWithTag("media-favorite-a1").captureToImage();val color=image.toPixelMap()[image.width/2,image.height/2]
        assertEquals(1f,color.red,.02f);assertEquals(213/255f,color.green,.03f)
        runOnIdle {model.value=model.value.copy(content=model.value.content.copy(allItems=movies.map {it.copy(isFavorite=false)}))}
        onNodeWithTag("media-favorite-a1").assertDoesNotExist()
    }
    @Test fun animatedHomePreviewHidesForCatalogueAndCannotWaitForeverForAMissingDestination()=runDesktopComposeUiTest(width=500,height=300) {
        val player=Player();val scene=VideoScene();val token=Any()
        val viewport=VideoViewport(token,Rect(20f,20f,220f,180f),Rect(20f,20f,220f,180f),player)
        var tab by mutableStateOf(AppTab.HOME);var destination by mutableStateOf(true)
        setContent {Box(Modifier.fillMaxSize().background(Color.Black).testTag("stage-fixture")) {
            SharedVideoStage(scene,player,true,transitionKey=tab,hasVideoDestination=destination,
                surface={m,_->Box(m.background(Color.Red))})
        }}
        fun red():Float=onNodeWithTag("stage-fixture").captureToImage().toPixelMap()[100,100].red
        runOnIdle {scene.update(viewport)};waitForIdle();assertTrue(red()>.9f)
        runOnIdle {tab=AppTab.MEDIA_LIBRARY;destination=appHasVideoDestination(tab,false,null,player.state.value.item);scene.remove(token)}
        mainClock.advanceTimeBy(1000);waitForIdle();assertTrue(red()<.1f,"Home live preview must disappear from the media catalogue")
        runOnIdle {tab=AppTab.HOME;destination=true;scene.update(viewport)};waitForIdle();assertTrue(red()>.9f)
        runOnIdle {tab=AppTab.TV_GUIDE;scene.remove(token)}
        mainClock.advanceTimeBy(1500)
        waitUntil(timeoutMillis=3000){red()<.1f}
    }
}
