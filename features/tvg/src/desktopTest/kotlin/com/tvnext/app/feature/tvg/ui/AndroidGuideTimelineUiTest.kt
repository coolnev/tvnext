package com.tvnext.app.feature.tvg.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlayerState
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Android grid key handler and horizontal viewport, with delayed day reads. */
class AndroidGuideTimelineUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun rapidLeftAcrossMidnightThenRightRestoresVisibleProgrammes() {
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

    @Test fun reverseBeforeDayQueryFinishesKeepsCurrentGridAndCursor() {
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
        fun values(day: Int) = (0..23).map { hour ->
            val from = startOfLocalDayOffset(now, day) + hour * 3_600_000L
            EpgProgramme(channel.id, from, from + 3_600_000L, "День $day Передача $hour")
        }
        override val state = MutableStateFlow(TvgState(channels = listOf(channel), filteredChannels = listOf(channel),
            programmes = mapOf(channel.id to values(0)), from = startOfLocalDayOffset(now, 0),
            to = startOfLocalDayOffset(now, 1), minArchiveDayOffset = -3))
        override val focusState = MutableStateFlow(TvgFocusState())
        override val playbackController = Proxy.newProxyInstance(PlaybackController::class.java.classLoader,
            arrayOf(PlaybackController::class.java)) { _, method, _ ->
                if (method.name == "getState") MutableStateFlow(PlayerState()) else error(method.name)
            } as PlaybackController
        override fun dispatch(intent: TvgIntent) {
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
