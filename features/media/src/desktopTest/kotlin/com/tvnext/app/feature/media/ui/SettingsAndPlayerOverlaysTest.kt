package com.tvnext.app.feature.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
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
        assertTrue(label.bottom<panel.top)
        onNodeWithTag("video-loading-status").assertDoesNotExist()
        screenshot("archive-label-loading-controls.png")
        runOnIdle{enabled=false}
        onNodeWithTag("archive-video-label").assertDoesNotExist()
        runOnIdle { controls=false }
        onNodeWithTag("video-loading-status").assertIsDisplayed()
        val busy=onNodeWithTag("video-loading-status").fetchSemanticsNode().boundsInRoot
        assertEquals(480f,busy.center.x,1f)
        assertTrue(busy.bottom>500f)
        runOnIdle{enabled=true;state=PlaybackState.PAUSED;controls=false}
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithTag("video-loading-status").assertDoesNotExist()
    }

    @Test fun epgStatusIsBottomCentredHiddenByEveryOpenPanelAndRestoredAfterDispose() = runDesktopComposeUiTest(width=960,height=540) {
        val visibility=PlaybackOverlayVisibility()
        var controls by mutableStateOf(false)
        var secondPanel by mutableStateOf(false)
        var screenMounted by mutableStateOf(true)
        var videoState by mutableStateOf(PlaybackState.PLAYING)
        setContent { MaterialTheme(darkColorScheme()) {
            CompositionLocalProvider(LocalPlaybackOverlayVisibility provides visibility) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    if(screenMounted) {
                        ReportPlaybackOverlays(bottomPanel=controls)
                        VideoPlaybackStatus(videoState,null)
                    }
                    if(secondPanel) ReportPlaybackOverlays(bottomPanel=true)
                    if(controls && screenMounted) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(180.dp).background(Color.Black).testTag("bottom-controls"))
                    EpgLoadingIndicator("Скачивание EPG: 12 МБ",Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        } }
        waitForIdle()
        val bounds=onNodeWithTag("epg-loading-status").fetchSemanticsNode().boundsInRoot
        assertEquals(480f,bounds.center.x,1f)
        assertTrue(bounds.bottom>500f)
        screenshot("epg-bottom-centre.png")
        runOnIdle{controls=true}
        onNodeWithTag("epg-loading-status").assertDoesNotExist()
        screenshot("epg-hidden-by-bottom-panel.png")
        runOnIdle{secondPanel=true;controls=false}
        onNodeWithTag("epg-loading-status").assertDoesNotExist()
        runOnIdle{secondPanel=false}
        onNodeWithText("Скачивание EPG: 12 МБ").assertIsDisplayed()
        runOnIdle{videoState=PlaybackState.BUFFERING}
        onNodeWithTag("epg-loading-status").assertDoesNotExist()
        val loading=onNodeWithTag("video-loading-status").fetchSemanticsNode().boundsInRoot
        assertEquals(480f,loading.center.x,1f);assertTrue(loading.bottom>500f)
        runOnIdle{controls=true}
        onNodeWithTag("video-loading-status").assertDoesNotExist()
        onNodeWithTag("epg-loading-status").assertDoesNotExist()
        runOnIdle{screenMounted=false}
        onNodeWithText("Скачивание EPG: 12 МБ").assertIsDisplayed()
        runOnIdle{assertFalse(visibility.bottomPanelVisible);assertFalse(visibility.videoLoading)}
    }

    @Test fun afrSettingsAreTranslatedAndDisplayModeListWorksWithRemote() = runDesktopComposeUiTest(width=750,height=700) {
        var preferences by mutableStateOf(com.tvnext.app.core.player.AutoFrameRatePreferences())
        val modes=(0..30).map {com.tvnext.app.core.player.VideoDisplayMode(it+1,1920,1080,24.0+it)}
        val status=com.tvnext.app.core.player.AutoFrameRateStatus(modes,modes.first())
        try {
            setContent { MaterialTheme(darkColorScheme()) { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
                com.tvnext.app.feature.settings.ui.AutoFrameRateSettings(preferences,status,{preferences=it})
            } } } }
            for(language in UiLanguage.entries) {
                runOnIdle{UiLocale.language=language}
                val label=com.tvnext.app.core.ui.i18n.UiTranslations.text(language,"Включить автофреймрейт")
                onNodeWithText(label).assertIsDisplayed()
            }
            runOnIdle{UiLocale.language=UiLanguage.RUSSIAN}
            onNodeWithText("Включить автофреймрейт").performClick()
            runOnIdle{assertTrue(preferences.enabled)}
            onNodeWithText("Поддерживаемые режимы экрана").performClick()
            onNodeWithTag("afr-mode-list").assertIsFocused()
            repeat(20) {onNodeWithTag("afr-mode-list").performKeyInput {pressKey(Key.DirectionDown)};waitForIdle()}
            onNodeWithText("1920 × 1080 · 44 Гц").assertIsDisplayed()
            screenshot("afr-supported-display-modes.png")
        }finally{UiLocale.language=UiLanguage.RUSSIAN}
    }

    @Test fun codecBadgesIdentifyTheActualDecoderAndAudioWithoutGuessingUnknownFacts() {
        val badges=com.tvnext.app.core.ui.component.playbackFormatBadges("1920x1080","5.1 · EC-3","23.976","SOFTWARE")
        assertEquals(listOf("П","1080p","23,976 кадр/с","5.1","Dolby D+"),badges)
        assertEquals(listOf("Stereo"),com.tvnext.app.core.ui.component.playbackFormatBadges(null,"Стерео · AAC",null,"UNKNOWN"))
        assertTrue(com.tvnext.app.core.ui.component.playbackFormatBadges("n/a",null,"NaN","UNKNOWN").isEmpty())
    }

    private fun ComposeUiTest.screenshot(name:String) {
        System.getenv("TVNEXT_VALIDATION_DIR")?.let { root ->
            val f=java.io.File(root,name); f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onAllNodes(isRoot()).let {it[it.fetchSemanticsNodes().lastIndex]}.captureToImage().asSkiaBitmap()).encodeToData()?.use{ f.writeBytes(it.bytes) }
        }
    }
}
