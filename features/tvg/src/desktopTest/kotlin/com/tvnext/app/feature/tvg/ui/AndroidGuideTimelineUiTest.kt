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
