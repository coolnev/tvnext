package com.tvnext.app.feature.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.UiLanguage
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.ui.component.*
import com.tvnext.app.core.ui.i18n.UiLocale
import com.tvnext.app.feature.settings.domain.MediaPreferences
import com.tvnext.app.feature.settings.ui.MediaLibrarySettings
import com.tvnext.app.feature.settings.ui.MovieSourceSettings
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsAndPlayerOverlaysTest {
    @Test fun compactLibraryRowsDoNotClipInAnyLanguageOrWithLargeFonts() = runDesktopComposeUiTest(width=650,height=620) {
        var scale by mutableStateOf(1f)
        try {
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                MaterialTheme(darkColorScheme()) { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
                    MediaLibrarySettings(MediaPreferences(),{},compact=true,showTitle=false)
                } } }
            } }
            for(language in UiLanguage.entries) for(fontScale in listOf(1f,1.4f)) {
                runOnIdle { UiLocale.language=language; scale=fontScale }
                waitForIdle()
                val texts=onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true)
                assertTrue(texts.fetchSemanticsNodes().size >= 10)
                for(i in texts.fetchSemanticsNodes().indices) {
                    val layouts=mutableListOf<TextLayoutResult>()
                    texts[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { assertFalse(it.didOverflowHeight,"$language scale=$scale: ${it.layoutInput.text}") }
                    assertTrue(texts[i].fetchSemanticsNode().boundsInRoot.height >= layouts.first().size.height-1,
                        "Clipped painted text: ${layouts.first().layoutInput.text}")
                }
                if(language==UiLanguage.RUSSIAN && fontScale==1f) screenshot("settings-library-compact.png")
            }
        } finally { UiLocale.language=UiLanguage.RUSSIAN }
    }

    @Test fun bothMovieSourcesHaveTheSameRowsAndIndependentActions() = runDesktopComposeUiTest(width=650,height=620) {
        val actions=mutableListOf<String>()
        setContent { MaterialTheme(darkColorScheme()) { Surface { Column(Modifier.padding(16.dp)) {
            for(name in listOf("TMDB","Кинопоиск")) MovieSourceSettings(name,true,true,false,true,
                {actions+="$name toggle"},{actions+="$name key"},{actions+="$name test"},{actions+="$name clear"})
        } } } }
        for(label in listOf("Использовать источник","API-ключ","Ключ задан","Проверить соединение","Очистить кэш"))
            onAllNodesWithText(label).assertCountEquals(2)
        for((label,action) in listOf("Использовать источник" to "toggle","API-ключ" to "key","Проверить соединение" to "test","Очистить кэш" to "clear")) {
            onAllNodesWithText(label)[0].performClick()
            onAllNodesWithText(label)[1].performClick()
            assertTrue(actions.contains("TMDB $action")); assertTrue(actions.contains("Кинопоиск $action"))
        }
        screenshot("settings-movie-sources.png")
    }

    @Test fun archiveLabelPersistsMovesAboveControlsAndCanBeDisabledWithoutHidingLoading() = runDesktopComposeUiTest(width=960,height=540) {
        var enabled by mutableStateOf(true)
        var controls by mutableStateOf(false)
        var state by mutableStateOf(PlaybackState.PLAYING)
        val title="Первый канал • Архив • 14.11 20:00"
        setContent { MaterialTheme(darkColorScheme()) {
            CompositionLocalProvider(LocalArchiveLabelEnabled provides enabled) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Text("Первый канал",Modifier.align(Alignment.TopStart).testTag("channel-name"))
                    VideoPlaybackStatus(state,title,if(controls)180.dp else 0.dp)
                    if(controls) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp)
                        .background(Color.Black).testTag("bottom-controls"))
                }
            }
        } }
        waitForIdle()
        val initial=onNodeWithTag("archive-video-label").fetchSemanticsNode().boundsInRoot
        assertTrue(initial.bottom>500)
        runOnIdle{controls=true;state=PlaybackState.BUFFERING}
        waitForIdle()
        val label=onNodeWithTag("archive-video-label").fetchSemanticsNode().boundsInRoot
        val panel=onNodeWithTag("bottom-controls").fetchSemanticsNode().boundsInRoot
        val busy=onNodeWithTag("video-loading-status").fetchSemanticsNode().boundsInRoot
        val name=onNodeWithTag("channel-name").fetchSemanticsNode().boundsInRoot
        assertTrue(label.bottom<panel.top)
        assertTrue(busy.top>name.bottom)
        assertTrue(busy.bottom<label.top)
        screenshot("archive-label-loading-controls.png")
        runOnIdle{enabled=false}
        onNodeWithTag("archive-video-label").assertDoesNotExist()
        onNodeWithTag("video-loading-status").assertIsDisplayed()
        runOnIdle{enabled=true;state=PlaybackState.PAUSED;controls=false}
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithTag("video-loading-status").assertDoesNotExist()
    }

    private fun ComposeUiTest.screenshot(name:String) {
        System.getenv("TVNEXT_VALIDATION_DIR")?.let { root ->
            val f=java.io.File(root,name); f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use{ f.writeBytes(it.bytes) }
        }
    }
}
