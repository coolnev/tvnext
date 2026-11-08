package com.tvnext.app.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.SharedVideoStage
import com.tvnext.app.feature.home.domain.FeaturedChannel
import com.tvnext.app.feature.home.domain.HomeContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HomeVideoPreviewUiTest {
    private class Player : PlaybackController {
        override val state=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("channel","КИНО ТВ","unused")))
        override fun play(item: PlaybackItem)=error("Home must keep the existing stream")
        override fun pause()=Unit
        override fun resume()=Unit
        override fun stop()=Unit
        override fun seekTo(positionMs: Long)=Unit
        override fun seekBy(deltaMs: Long)=Unit
        override fun setVolume(volume: Float)=Unit
        override fun setMuted(muted: Boolean)=Unit
        override fun release()=Unit
    }
    private class Home : HomeComponent {
        override val state=MutableStateFlow(HomeState())
        override fun dispatch(intent: HomeIntent)=Unit
    }

    @Test fun currentChannelAndMovieHaveALivePreviewNextToTheCardWithoutRestartingPlayback() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();val home=Home();val scene=VideoScene();var opened=0;var mounts=0
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {CompositionLocalProvider(LocalVideoScene provides scene){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SharedVideoStage(scene,player,true,surface={m,_ ->
                DisposableEffect(Unit){mounts++;onDispose {}}
                Box(m.background(Color(0xFF178A87)))
            })
            HomeScreen(home,playbackController=player,onOpenPlayingVideo={opened++})
        }}}}
        waitForIdle();onNodeWithTag("home-current-video").assertIsDisplayed()
        onNodeWithText("Сейчас идёт").assertDoesNotExist()
        val before=scene.viewport!!.bounds
        runOnIdle {home.state.value=HomeState(content=HomeContent(continueWatching=FeaturedChannel("channel","КИНО ТВ",1,"Кино")))}
        waitForIdle();assertEquals(before,scene.viewport!!.bounds)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).assertCountEquals(1)
        onNodeWithTag("home-current-block").performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        onNodeWithTag("home-current-block").assertIsFocused()
        onNodeWithTag("home-current-block").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("home-current-block").assertIsFocused()
        onNodeWithTag("home-current-block").performKeyInput {pressKey(Key.Enter)}
        assertEquals(1,opened)
        onNodeWithTag("home-current-video").performTouchInput {click(center)}
        onNodeWithText("КИНО ТВ").performTouchInput {click(center)}
        assertEquals(3,opened)
        runOnIdle {player.state.value=player.state.value.copy(item=PlaybackItem("movie","Текущий фильм","unused",PlaybackContentKind.VOD))}
        waitForIdle();onNodeWithTag("home-current-video").assertIsDisplayed()
        assertEquals(1,onAllNodesWithText("Текущий фильм").fetchSemanticsNodes().size)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).assertCountEquals(1)
        assertEquals(1,mounts)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"home-current-movie-preview.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
    }

    @Test fun languageChangesUpdateControlsWithoutTranslatingProviderTitlesOrRemountingVideo() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();val home=Home();val scene=VideoScene();var mounts=0
        player.state.value=player.state.value.copy(item=PlaybackItem("channel","Настройки","unused"))
        try {
            setContent {MaterialTheme(colorScheme=darkColorScheme()) {CompositionLocalProvider(LocalVideoScene provides scene) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SharedVideoStage(scene,player,true,surface={m,_ ->
                        DisposableEffect(Unit){mounts++;onDispose {}}
                        Box(m.background(Color(0xFF178A87)))
                    })
                    HomeScreen(home,playbackController=player)
                }
            }}}
            for ((language,label) in listOf(
                com.tvnext.app.core.common.UiLanguage.RUSSIAN to "Сейчас смотрите",
                com.tvnext.app.core.common.UiLanguage.ENGLISH to "Now watching",
                com.tvnext.app.core.common.UiLanguage.CHINESE to "正在观看",
            )) {
                runOnIdle {com.tvnext.app.core.ui.i18n.UiLocale.language=language}
                waitForIdle();onNodeWithText(label).assertIsDisplayed()
                onNodeWithText("Настройки").assertIsDisplayed()
                onNodeWithTag("home-current-video").assertIsDisplayed()
                System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
                    val file=java.io.File(root,"home-language-${language.code}.png");file.parentFile.mkdirs()
                    org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
                }
            }
            assertEquals(1,mounts)
            assertEquals("Next: {1} — 12:00 · фильм", com.tvnext.app.core.ui.i18n.UiTranslations.text(
                com.tvnext.app.core.common.UiLanguage.ENGLISH,"Далее: {0} — {1} · {2}","{1}","12:00","фильм"))
        } finally {com.tvnext.app.core.ui.i18n.UiLocale.language=com.tvnext.app.core.common.UiLanguage.RUSSIAN}
    }

    @Test fun historyCardDoesNotPretendThatVideoIsPlayingAfterStop() = runDesktopComposeUiTest(width=960,height=540) {
        val player=Player();player.state.value=PlayerState();val home=Home()
        home.state.value=HomeState(content=HomeContent(continueWatching=FeaturedChannel("channel","КИНО ТВ",1,"Кино")))
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {HomeScreen(home,playbackController=player)}}
        waitForIdle();onNodeWithTag("home-current-video").assertDoesNotExist()
        onNodeWithText("КИНО ТВ").assertIsDisplayed()
    }
}
