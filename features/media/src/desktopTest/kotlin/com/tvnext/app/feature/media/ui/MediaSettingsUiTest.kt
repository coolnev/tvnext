package com.tvnext.app.feature.media.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asSkiaBitmap
import com.tvnext.app.core.common.UiLanguage
import com.tvnext.app.core.ui.i18n.UiLocale
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.player.*
import com.tvnext.app.feature.media.domain.MediaContent
import com.tvnext.app.feature.settings.domain.*
import com.tvnext.app.feature.settings.ui.MediaLibrarySettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MediaSettingsUiTest {
    private class Component:MediaComponent {
        val items=listOf(MediaItem("a","vod","Альфа",1,"Кино","file:///a"),MediaItem("b","vod","Бета",2,"Кино","file:///b"))
        override val state=MutableStateFlow(MediaState(isLoading=false,content=MediaContent(sources=listOf(Playlist("vod","VOD","https://fixture",type=PlaylistType.VOD)),allItems=items),
            preferences=MediaPreferences(rememberPosition=true),browser=MediaBrowserPosition("vod",null,"b",false,true)))
        override val seekStepMs=MutableStateFlow(60_000L)
        val player=MutableStateFlow(PlayerState())
        val events=mutableListOf<MediaIntent>()
        override val playbackController=Proxy.newProxyInstance(PlaybackController::class.java.classLoader,arrayOf(PlaybackController::class.java)){_,m,_->if(m.name=="getState")player else Unit} as PlaybackController
        override fun dispatch(intent:MediaIntent) {
            events+=intent
            if(intent is MediaIntent.BrowserMoved)state.value=state.value.copy(browser=intent.position)
            if(intent is MediaIntent.ResumeChosen || intent==MediaIntent.ResumeCancelled)state.value=state.value.copy(pendingResume=null)
        }
    }
    @Test fun browserRestoresTheSelectedFilmAndPosterResizeKeepsRemoteFocus()=runDesktopComposeUiTest(width=1100,height=700) {
        val c=Component()
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {AndroidMediaScreen(c,Modifier.size(1100.dp,700.dp),ClockPosition.OFF,{},null,{},false,3)}}
        waitForIdle()
        onNodeWithTag("media-film-b").assertIsFocused()
        val small=onNodeWithTag("media-film-b").fetchSemanticsNode().boundsInRoot.height
        runOnIdle {c.state.value=c.state.value.copy(preferences=c.state.value.preferences.copy(posterSize=MediaPosterSize.LARGE))}
        waitForIdle()
        onNodeWithTag("media-film-b").assertIsFocused()
        assertTrue(onNodeWithTag("media-film-b").fetchSemanticsNode().boundsInRoot.height>small)
        onRoot().performKeyInput {pressKey(Key.DirectionLeft)}
        waitForIdle()
        onNodeWithTag("media-film-a").assertIsFocused()
        assertEquals("a",c.state.value.browser.itemId)
        runOnIdle {c.state.value=c.state.value.copy(preferences=c.state.value.preferences.copy(filmSort=MediaFilmSort.NAME_DESC))}
        waitForIdle()
        onNodeWithTag("media-film-a").assertIsFocused()
        assertEquals("a",c.state.value.browser.itemId)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let{root->val f=java.io.File(root,"media-library-posters-and-focus.png");f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use{f.writeBytes(it.bytes)}}
    }
    @Test fun allFiveSettingsTranslateAndTheChineseSortOptionChangesThePreference()=runDesktopComposeUiTest(width=1000,height=600) {
        try {
            UiLocale.language=UiLanguage.CHINESE
            var p by mutableStateOf(MediaPreferences())
            setContent {MaterialTheme(colorScheme=darkColorScheme()) {Surface {MediaLibrarySettings(p,{p=it})}}}
            waitForIdle()
            onNodeWithText("影片排序").performClick()
            onNodeWithText("名称升序").performClick()
            assertEquals(MediaFilmSort.NAME_ASC,p.filmSort)
            runOnIdle {UiLocale.language=UiLanguage.ENGLISH}
            waitForIdle()
            for(label in listOf("Poster size","Film sorting","Category order","Remember catalogue position","Playback start")) onNodeWithText(label).assertIsDisplayed()
            System.getenv("TVNEXT_VALIDATION_DIR")?.let{root->val f=java.io.File(root,"media-settings-english.png");f.parentFile.mkdirs()
                org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use{f.writeBytes(it.bytes)}}
        } finally {UiLocale.language=UiLanguage.RUSSIAN}
    }
    @Test fun theResumeQuestionOffersResumeAndStartOverWithoutStartingBehindTheDialog()=runDesktopComposeUiTest(width=1000,height=600) {
        val c=Component()
        try {
            UiLocale.language=UiLanguage.ENGLISH
            c.state.value=c.state.value.copy(pendingResume=MediaResumeRequest("a","Альфа",90_000))
            setContent {MaterialTheme {MediaResumeDialog(c,c.state.collectAsState().value.pendingResume)}}
            waitForIdle()
            onNodeWithText("Resume playback?").assertIsDisplayed()
            onNodeWithText("Start over").performClick()
            waitForIdle()
            assertTrue(c.events.contains(MediaIntent.ResumeChosen(false)))
            assertNull(c.player.value.item)
            onNodeWithText("Resume playback?").assertDoesNotExist()
        }finally{UiLocale.language=UiLanguage.RUSSIAN}
    }
}
