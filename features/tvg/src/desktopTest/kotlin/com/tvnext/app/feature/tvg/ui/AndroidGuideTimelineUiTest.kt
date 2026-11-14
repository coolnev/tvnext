package com.tvnext.app.feature.tvg.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import com.tvnext.app.core.player.PlaybackItem
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlayerState
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Android grid key handler and horizontal viewport, with delayed day reads. */
@OptIn(ExperimentalTestApi::class)
class AndroidGuideTimelineUiTest {
    private lateinit var ui: ComposeUiTest
    private fun guideTest(width: Int = 1100, height: Int = 700, body: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width=width,height=height) {ui=this;body()}

    @Test fun systemBackFocusesCategoriesAndLateInitialDataCannotStealFocusBeforeSecondBack()=guideTest(960,540) {
        val component=Guide();val loaded=component.state.value
        component.state.value=TvgState(isLoading=true)
        var closed=0
        setContent {MaterialTheme {AndroidTvgScreen(component,Modifier,{closed++})}}
        waitForIdle()
        runOnIdle {if(!component.consumeBack())closed++}
        waitForIdle()
        onNodeWithTag("tvg-categories").assertIsFocused()
        assertEquals(0,closed)
        runOnIdle {component.state.value=loaded}
        waitForIdle()
        onNodeWithTag("tvg-categories").assertIsFocused()
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val f=java.io.File(root,"tv-guide-first-back-categories.png");f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {f.writeBytes(it.bytes)}
        }
        runOnIdle {if(!component.consumeBack())closed++}
        assertEquals(1,closed)
    }

    @Test fun backFromGridOrAnotherToolbarButtonVisitsCategoriesBeforeClosing()=guideTest(960,540) {
        val component=Guide();var closed=0
        setContent {MaterialTheme {AndroidTvgScreen(component,Modifier,{closed++})}}
        waitForIdle()
        onRoot().performKeyInput{pressKey(Key.Back)}
        waitForIdle()
        onNodeWithTag("tvg-categories").assertIsFocused()
        assertEquals(0,closed)
        onNodeWithTag("tvg-now").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus){it()}
        onRoot().performKeyInput{pressKey(Key.Back)}
        waitForIdle()
        onNodeWithTag("tvg-categories").assertIsFocused()
        assertEquals(0,closed)
        onRoot().performKeyInput{pressKey(Key.Back)}
        assertEquals(1,closed)
    }

    @Test fun coldEntryConsumesOpeningOkBeforeItCanActivateCategoriesAndKeepsGridFocus() = guideTest(960,540) {
        val component = Guide()
        val loaded = component.state.value
        component.state.value = TvgState(isLoading = true)
        com.tvnext.app.core.common.input.OkInputGate.suppressNextKeyUp = true
        try {
            setContent { MaterialTheme(colorScheme=darkColorScheme()) { AndroidTvgScreen(component, Modifier, {}) } }
            waitForIdle()
            onNodeWithTag("tvg-categories").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("tvg-categories").performKeyInput { keyDown(Key.Enter); advanceEventTime(700); keyUp(Key.Enter) }
            waitForIdle()
            assertTrue(!com.tvnext.app.core.common.input.OkInputGate.suppressNextKeyUp)
            onNodeWithText("Категории").assertDoesNotExist()
            mainClock.advanceTimeBy(250); waitForIdle()
            onNodeWithText("Пожалуйста, подождите…").assertIsDisplayed()
            val wait = onNodeWithTag("tvg-loading").fetchSemanticsNode().boundsInRoot
            assertEquals(480f, wait.center.x, 2f); assertEquals(270f, wait.center.y, 2f)
            System.getenv("TVNEXT_VALIDATION_DIR")?.let { root ->
                val f=java.io.File(root,"tv-guide-cold-loading.png");f.parentFile.mkdirs()
                org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use { f.writeBytes(it.bytes) }
            }
            runOnIdle { component.state.value = loaded }
            waitForIdle()
            onNodeWithTag("tvg-loading").assertDoesNotExist()
            onNodeWithTag("tvg-grid").assertIsFocused()
            onNodeWithText("Категории").assertDoesNotExist()
            // A new, deliberate press still opens the user's category selector.
            onNodeWithTag("tvg-grid").performKeyInput { pressKey(Key.DirectionUp) }
            onNodeWithTag("tvg-categories").performKeyInput { pressKey(Key.Enter) }
            onNodeWithText("Категории").assertIsDisplayed()
        } finally { com.tvnext.app.core.common.input.OkInputGate.suppressNextKeyUp = false }
    }

    @Test fun playingChannelOpensInCentreOfAllChannelsWithoutCategoryDialog() = guideTest(960,540) {
        val component=Guide()
        val channels=(1..80).map { component.channel.copy(id="channel$it",number=it,name="Канал $it",category=if(it%2==0) "Кино" else "Новости") }
        val playing=channels[48]
        component.playbackState.value=PlayerState()
        component.state.value=component.state.value.copy(channels=channels,filteredChannels=channels,
            programmes=channels.associate { c -> c.id to component.values(0).map {it.copy(channelId=c.id)} })
        setContent {MaterialTheme(colorScheme=darkColorScheme()) { AndroidTvgScreen(component,Modifier,{}) }}
        waitForIdle()
        // Startup may restore the playing channel after the first guide frame.
        runOnIdle {component.playbackState.value=PlayerState(item=PlaybackItem(playing.id,playing.name,playing.streamUrl))}
        waitForIdle()
        val grid=onNodeWithTag("tvg-grid").fetchSemanticsNode().boundsInRoot
        val row=onNode(hasTestTag("tvg-channel-row") and isSelected()).fetchSemanticsNode().boundsInRoot
        assertEquals(grid.center.y,row.center.y,2f)
        onNodeWithText("Все каналы").assertIsDisplayed()
        onNodeWithText("Категории").assertDoesNotExist()
        onNodeWithTag("tvg-grid").assertIsFocused()
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val f=java.io.File(root,"tv-guide-playing-centred.png");f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {f.writeBytes(it.bytes)}
        }
    }

    @Test fun delayedProgrammeReadShowsPassiveWaitAndEmptyOrSuccessfulResultClearsIt() = guideTest(960,540) {
        val component=Guide();val loaded=component.state.value
        component.state.value=loaded.copy(programmes=emptyMap(), loadingChannelIds=setOf(component.channel.id))
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {AndroidTvgScreen(component,Modifier,{})}}
        mainClock.advanceTimeBy(250);waitForIdle()
        onNodeWithTag("tvg-loading").assertIsDisplayed()
        onNodeWithTag("tvg-grid").assertIsFocused()
        runOnIdle {component.state.value=component.state.value.copy(loadingChannelIds=emptySet())}
        waitForIdle();onNodeWithTag("tvg-loading").assertDoesNotExist()
        onNodeWithText("Нет программы").assertIsDisplayed()
        runOnIdle {component.state.value=component.state.value.copy(isEpgLoading=true)}
        mainClock.advanceTimeBy(250);waitForIdle()
        onNodeWithTag("tvg-loading").assertIsDisplayed()
        runOnIdle {component.state.value=loaded.copy(isEpgLoading=true)}
        waitForIdle();onNodeWithTag("tvg-loading").assertDoesNotExist()
        onNodeWithTag("tvg-grid").assertIsFocused()
    }

    @Test fun categoryPickerMatchesChannelNumberOrderInsteadOfAlphabet() = guideTest(960,540) {
        val component=Guide()
        val channels=listOf(component.channel.copy(id="news20",number=20,category="Я Новости"),
            component.channel.copy(id="film",number=50,category="А Кино"),
            component.channel.copy(id="news1",number=1,category="Я Новости"),
            component.channel.copy(id="kids",number=3,category="М Детские"))
        component.state.value=component.state.value.copy(channels=channels,filteredChannels=channels)
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {AndroidTvgScreen(component,Modifier,{})}}
        waitForIdle()
        onNodeWithTag("tvg-grid").performKeyInput {pressKey(Key.DirectionUp)}
        onNodeWithTag("tvg-categories").performKeyInput {pressKey(Key.Enter)}
        waitForIdle()
        val positions=listOf("Я Новости","М Детские","А Кино").map {onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top}
        assertTrue(positions.zipWithNext().all {(a,b)->a<b})
        onNodeWithText("М Детские").performClick();waitForIdle()
        assertEquals("М Детские",(component.intents.last {it is TvgIntent.CategorySelected} as TvgIntent.CategorySelected).category)
        onNodeWithTag("tvg-grid").assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun compactGuideKeepsPlayingPreviewStableWhileBrowsingAndLoading() = guideTest(960,540) {
        val component = Guide()
        val channels = (1..16).map { component.channel.copy(id="channel$it",number=it,name="Первый канал HD $it") }
        component.state.value=component.state.value.copy(channels=channels,filteredChannels=channels,
            programmes=channels.associate { c -> c.id to component.values(0).map { it.copy(channelId=c.id) } })
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {AndroidTvgScreen(component,Modifier,{})}}
        waitForIdle()
        val preview=onNodeWithTag("tvg-video-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(16f/9,preview.width/preview.height,.01f)
        val header=onNodeWithTag("tvg-header").fetchSemanticsNode().boundsInRoot
        val grid=onNodeWithTag("tvg-grid").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.right>900 && preview.bottom<grid.top)
        assertTrue(header.bottom<=grid.top)
        val rows=onAllNodesWithTag("tvg-channel-row").fetchSemanticsNodes().map {it.boundsInRoot}
        assertTrue(rows.count {it.height>=45 && it.bottom<=grid.bottom}>=6)
        val name=onAllNodesWithTag("tvg-channel-name").fetchSemanticsNodes().first().boundsInRoot
        assertTrue(name.width>216 && name.height<64)
        onNodeWithTag("tvg-grid").performKeyInput {pressKey(Key.DirectionDown);pressKey(Key.DirectionRight)}
        waitForIdle()
        assertEquals(preview,onNodeWithTag("tvg-video-preview").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("tvg-playing-channel").assertTextEquals(component.channel.name)
        runOnIdle {component.state.value=component.state.value.copy(isLoading=true)}
        waitForIdle()
        assertEquals(preview,onNodeWithTag("tvg-video-preview").fetchSemanticsNode().boundsInRoot)
        assertEquals(grid,onNodeWithTag("tvg-grid").fetchSemanticsNode().boundsInRoot)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root ->
            val file=java.io.File(root,"compact-tv-guide-960.png");file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {file.writeBytes(it.bytes)}
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun outlinedToolbarNavigatesBothRowsAndReturnsToGridAndVideo() = guideTest(960,540) {
        val component=Guide();var videoOpened=0
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {AndroidTvgScreen(component,Modifier,{videoOpened++})}}
        waitForIdle()
        onNodeWithTag("tvg-grid").assertIsFocused()
        onNodeWithTag("tvg-grid").performKeyInput {pressKey(Key.DirectionUp)}
        waitForIdle()
        onNodeWithTag("tvg-categories").assertIsFocused()
        onNodeWithTag("tvg-categories").performKeyInput {pressKey(Key.DirectionDown)}
        onNodeWithTag("tvg-now").assertIsFocused()
        onNodeWithTag("tvg-now").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("tvg-day-next").performKeyInput {pressKey(Key.DirectionCenter)}
        waitForIdle();assertEquals(1,component.intents.count {it is TvgIntent.DayDelta})
        onNodeWithTag("tvg-day-next").performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("tvg-refresh").performKeyInput {pressKey(Key.Enter)}
        waitForIdle();assertEquals(1,component.intents.count {it==TvgIntent.Refresh})
        onNodeWithTag("tvg-refresh").performKeyInput {pressKey(Key.DirectionDown)}
        onNodeWithTag("tvg-grid").assertIsFocused()
        onNodeWithTag("tvg-grid").performKeyInput {pressKey(Key.DirectionUp)}
        onNodeWithTag("tvg-categories").performKeyInput {pressKey(Key.Enter)}
        waitForIdle();onNodeWithText("Категории").assertIsDisplayed()
        onNodeWithText("Тест").performClick();waitForIdle()
        assertEquals("Тест",(component.intents.last {it is TvgIntent.CategorySelected} as TvgIntent.CategorySelected).category)
        onNodeWithTag("tvg-categories").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) {it()}
        onNodeWithTag("tvg-categories").performKeyInput {pressKey(Key.DirectionRight)}
        for(tag in listOf("tvg-day-previous","tvg-now","tvg-day-next","tvg-refresh")) onNodeWithTag(tag).performKeyInput {pressKey(Key.DirectionRight)}
        onNodeWithTag("tvg-video-preview").assertIsFocused()
        onNodeWithTag("tvg-video-preview").performKeyInput {pressKey(Key.Enter)}
        assertEquals(1,videoOpened)
    }

    @Test fun rapidLeftAcrossMidnightThenRightRestoresVisibleProgrammes() = guideTest {
        val component = Guide()
        ui.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            AndroidTvgScreen(component, Modifier.size(1100.dp, 700.dp), {})
        } }
        ui.waitForIdle()
        ui.onNodeWithTag("tvg-grid").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithTag("tvg-grid").assertIsFocused()
        // Reach today's first programme using complete key cycles and normal frames.
        val hours = component.values(0).count { it.start < component.now }
        repeat((hours - 1).coerceAtLeast(0)) { ui.onNodeWithTag("tvg-grid").performKeyInput { pressKey(Key.DirectionLeft) }; ui.waitForIdle() }
        // Eight repeats arrive before the asynchronous day query returns.
        ui.onNodeWithTag("tvg-grid").performKeyInput { repeat(8) { pressKey(Key.DirectionLeft) } }
        ui.waitForIdle()
        assertEquals(-1, component.state.value.selectedDayOffset, "Cursor targets: ${component.targets}")
        assertEquals(-1, component.pendingDay)
        component.completeDay()
        ui.waitForIdle()
        assertTrue(ui.onAllNodes(hasTestTag("tvg-programme") and hasAnyDescendant(hasText("День -1", substring = true))).fetchSemanticsNodes().isNotEmpty())
        ui.onNodeWithTag("tvg-grid").performKeyInput { repeat(16) { pressKey(Key.DirectionRight) } }
        ui.waitForIdle()
        assertTrue(component.targets.any { it < 0 }, "The cursor must actually cross midnight")
        assertEquals(0, component.state.value.selectedDayOffset)
        component.completeDay()
        ui.waitForIdle()
        assertTrue(ui.onAllNodes(hasTestTag("tvg-programme") and hasAnyDescendant(hasText("День 0", substring = true))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun reverseBeforeDayQueryFinishesKeepsCurrentGridAndCursor() = guideTest {
        val component = Guide()
        ui.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            AndroidTvgScreen(component, Modifier.size(1100.dp, 700.dp), {})
        } }
        ui.waitForIdle()
        ui.onNodeWithTag("tvg-grid").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithTag("tvg-grid").assertIsFocused()
        val hours = component.values(0).count { it.start < component.now }
        repeat((hours - 1).coerceAtLeast(0)) { ui.onNodeWithTag("tvg-grid").performKeyInput { pressKey(Key.DirectionLeft) }; ui.waitForIdle() }
        ui.onNodeWithTag("tvg-grid").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        ui.waitForIdle()
        assertTrue(component.targets.any { it < 0 }, "The cursor must actually cross midnight")
        assertEquals(0, component.state.value.selectedDayOffset)
        component.completeDay()
        ui.waitForIdle()
        assertTrue(ui.onAllNodes(hasTestTag("tvg-programme") and hasAnyDescendant(hasText("День 0", substring = true))).fetchSemanticsNodes().isNotEmpty())
    }

    private class Guide : TvgComponent {
        private var backAction:()->Boolean={false}
        override fun setBackHandler(handler:()->Boolean){backAction=handler}
        override fun consumeBack()=backAction()
        val now = currentTimeMillis()
        val channel = Channel("channel", "Проверка", 1, "Тест", "https://unused.invalid/live")
        var pendingDay: Int? = null
        val targets = mutableListOf<Long>()
        val intents = mutableListOf<TvgIntent>()
        val playbackState = MutableStateFlow(PlayerState(item=PlaybackItem(channel.id,channel.name,channel.streamUrl)))
        fun values(day: Int) = (0..23).map { hour ->
            val from = startOfLocalDayOffset(now, day) + hour * 3_600_000L
            EpgProgramme(channel.id, from, from + 3_600_000L, "День $day Передача $hour")
        }
        override val state = MutableStateFlow(TvgState(channels = listOf(channel), filteredChannels = listOf(channel),
            programmes = mapOf(channel.id to values(0)), from = startOfLocalDayOffset(now, 0),
            to = startOfLocalDayOffset(now, 1), minArchiveDayOffset = -3))
        override val focusState = MutableStateFlow(TvgFocusState())
        override val playbackController = Proxy.newProxyInstance(PlaybackController::class.java.classLoader,
            arrayOf(PlaybackController::class.java)) { proxy, method, args ->
                when(method.name) {
                    "getState" -> playbackState
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Guide playback fixture"
                    else -> error("Browsing must not restart playback: ${method.name}")
                }
            } as PlaybackController
        override fun dispatch(intent: TvgIntent) {
            intents += intent
            val day = when (intent) {
                is TvgIntent.DayDelta -> (state.value.selectedDayOffset + intent.delta).coerceIn(-3, 7)
                is TvgIntent.DaySet -> intent.offset.coerceIn(-3, 7)
                is TvgIntent.TimelineCursorMoved -> { targets += (intent.timeMs - startOfLocalDayOffset(now, 0)) / 60_000; guideDayOffsetAt(now, intent.timeMs, -3, 7) }
                else -> return
            }
            state.value = state.value.copy(selectedDayOffset = day)
            pendingDay = day
        }
        fun completeDay() {
            val day = pendingDay ?: return
            pendingDay = null
            state.value = state.value.copy(from = startOfLocalDayOffset(now, day), to = startOfLocalDayOffset(now, day + 1),
                programmes = mapOf(channel.id to values(day)))
        }
    }
}
