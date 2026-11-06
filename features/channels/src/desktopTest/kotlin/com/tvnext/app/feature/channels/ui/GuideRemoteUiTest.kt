package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import com.tvnext.app.core.common.util.formatDate
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.tmdb.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import androidx.compose.ui.platform.testTag
import com.tvnext.app.core.ui.component.AndroidPlaybackControls
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.startOfDay
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Drive real Compose focus hosts with complete remote DOWN/UP key cycles. */
class GuideRemoteUiTest {
    @get:Rule val ui = createComposeRule()
    private val hour = 3_600_000L
    private val today = startOfDay(1_799_971_200_000L)
    private val now = today + 12 * hour + hour / 2
    private val channel = Channel(id = "channel", name = "Тестовый канал", number = 1,
        category = "Тест", streamUrl = "https://example.com/live", catchup = "default")
    private val programmes = (-48..23).map { offset ->
        EpgProgramme(channel.id, today + offset * hour, today + (offset + 1) * hour, "Передача $offset")
    }

    @Before fun setup() { startKoin {} }
    @After fun cleanup() { stopKoin() }

    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            ui.onRoot().performKeyInput { pressKey(key) }
            ui.waitForIdle()
        }
    }

    @Test fun programmeRowsDoNotRepeatTheSeparateDateColumn() {
        ui.setContent {
            MaterialTheme {
                AndroidWideGuide(channels = listOf(channel), playingChannelId = channel.id, focusedChannelId = channel.id,
                    timeline = ChannelTimelineState(channel.id, false, programmes), nowPlaying = emptyMap(), now = now,
                    showNumbers = false, showCategories = false, descriptionOnRight = false,
                    categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                    onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {}, onPlayArchive = { _, _ -> },
                    onClose = {}, modifier = Modifier.size(1100.dp, 700.dp))
            }
        }
        ui.waitForIdle()
        val current = programmes.first { it.start <= now && it.stop > now }
        ui.onNodeWithText("${formatTimeOfDay(current.start)}  ${current.title}").assertIsDisplayed()
        ui.onNodeWithText("${guideWeekday(today)}\n${formatDate(today)}").assertIsDisplayed()
    }

    @Test fun kinopoiskSynopsisReplacesTheEpgMissingDescriptionSentence() {
        val synopsis = "История героев второй части комедии, найденная по названию фильма Холоп 2."
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/search"))
                """{"docs":[{"id":2,"name":"Холоп 2","description":"$synopsis"}]}""" else "{}"
            respond(body, headers = headersOf("Content-Type", "application/json"))
        }) { install(HttpTimeout) }
        val key = object : KinopoiskKeyStore {
            override suspend fun getKey() = "test-key"
            override suspend fun setKey(value: String) = Unit
        }
        val settings = object : TmdbSettingsStore {
            override suspend fun isEnabled() = false
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun getApiKey() = ""
            override suspend fun setApiKey(apiKey: String) = Unit
        }
        val tmdb = java.lang.reflect.Proxy.newProxyInstance(TmdbRepository::class.java.classLoader,
            arrayOf(TmdbRepository::class.java)) { _, _, _ -> error("TMDB is disabled") } as TmdbRepository
        val repository = ProgrammeMetadataRepository(tmdb, KinopoiskProgrammeLookup(client, key), settings)
        loadKoinModules(module { single { repository } })
        try {
            ui.setContent {
                MaterialTheme { Box(Modifier.size(700.dp, 500.dp)) {
                    ProgrammeRichDetails(EpgProgramme(channel.id, now, now + hour, "Холоп 2", "Описание передачи отсутствует"))
                } }
            }
            ui.waitUntil(5_000) { ui.onAllNodesWithText(synopsis).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(synopsis).assertIsDisplayed()
            ui.onNodeWithText("Описание передачи отсутствует").assertDoesNotExist()
        } finally { client.close() }
    }

    @Test fun remoteWalksFromCurrentDayIntoTwoPreviousDaysWithoutDateSelection() {
        var played: EpgProgramme? = null
        ui.setContent {
            MaterialTheme {
                AndroidWideGuide(
                    channels = listOf(channel), playingChannelId = channel.id, focusedChannelId = channel.id,
                    timeline = ChannelTimelineState(channel.id, false, programmes), nowPlaying = emptyMap(), now = now,
                    showNumbers = false, showCategories = false, descriptionOnRight = false,
                    categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                    onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {},
                    onPlayArchive = { _, programme -> played = programme }, onClose = {},
                    modifier = Modifier.size(1100.dp, 700.dp),
                )
            }
        }
        ui.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionUp, 37)
        press(Key.Enter)
        if (played == null) println(ui.onRoot(useUnmergedTree = true).printToString(maxDepth = 6))
        assertEquals(today - 25 * hour, played?.start)
    }

    @Test fun openingArchiveFocusesItsProgrammeEvenWhenEpgArrivesAfterOpening() {
        val timeline = mutableStateOf(ChannelTimelineState(channel.id, true, emptyList()))
        var played: EpgProgramme? = null
        ui.setContent {
            MaterialTheme {
                AndroidWideGuide(
                    channels = listOf(channel), playingChannelId = channel.id, focusedChannelId = channel.id,
                    timeline = timeline.value, nowPlaying = emptyMap(), now = now,
                    initialProgrammeTimeMs = today - 30 * hour + hour / 2,
                    showNumbers = false, showCategories = false, descriptionOnRight = false,
                    categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                    onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {},
                    onPlayArchive = { _, programme -> played = programme }, onClose = {},
                    modifier = Modifier.size(1100.dp, 700.dp),
                )
            }
        }
        ui.waitForIdle()
        ui.runOnIdle { timeline.value = ChannelTimelineState(channel.id, false, programmes.takeLast(1)) }
        ui.waitForIdle()
        ui.runOnIdle { timeline.value = ChannelTimelineState(channel.id, false, programmes) }
        press(Key.Enter)
        if (played == null) println(ui.onRoot(useUnmergedTree = true).printToString(maxDepth = 6))
        assertEquals(today - 30 * hour, played?.start)
    }

    @Test fun windowedProgrammeCursorSurvivesAsyncDataAndMovesAcrossMidnight() {
        val epg = mutableStateOf(emptyList<EpgProgramme>())
        var highlighted: EpgProgramme? = null
        var played: EpgProgramme? = null
        ui.setContent {
            MaterialTheme {
                AndroidWindowedProgrammeList(channel, epg.value, false, now,
                    initialProgrammeTimeMs = today + hour / 2, focusRequester = remember { FocusRequester() },
                    requestInitialFocus = true, onProgrammeHighlighted = { highlighted = it },
                    onPlayProgramme = { played = it }, modifier = Modifier.size(400.dp, 600.dp))
            }
        }
        ui.waitForIdle()
        ui.runOnIdle { epg.value = programmes }
        ui.waitForIdle()
        assertEquals(today, highlighted?.start)
        press(Key.DirectionUp)
        assertEquals(today - hour, highlighted?.start)
        press(Key.Enter)
        assertEquals(today - hour, played?.start)
    }

    @Test fun windowedRightSelectsProgrammesThenVideoAndOkOpensFullscreenOnRelease() {
        val epgOpen = mutableStateOf(false)
        val videoFocused = mutableStateOf(false)
        var fullscreen = false
        ui.setContent {
            val input = remember { WindowedRemoteInput() }
            val channelFocus = remember { FocusRequester() }
            val videoFocus = remember { FocusRequester() }
            MaterialTheme {
                Row(Modifier.size(900.dp, 600.dp).onPreviewKeyEvent { event ->
                    input.handle(event,
                        pane = when {
                            videoFocused.value -> WindowedPane.VIDEO
                            epgOpen.value -> WindowedPane.PROGRAMMES
                            else -> WindowedPane.CHANNELS
                        },
                        onChannels = {}, onProgrammes = { epgOpen.value = true },
                        onVideo = { videoFocus.requestFocus() },
                        onFullscreen = { epgOpen.value = false; fullscreen = true })
                }) {
                    if (epgOpen.value) AndroidWindowedProgrammeList(
                        channel, programmes, false, now, null, null, true, {}, {},
                        Modifier.size(400.dp, 600.dp))
                    else Box(Modifier.size(400.dp, 600.dp).focusRequester(channelFocus).focusable())
                    Box(Modifier.size(400.dp, 300.dp).focusRequester(videoFocus)
                        .onFocusChanged { videoFocused.value = it.isFocused }.focusable())
                }
                LaunchedEffect(Unit) { channelFocus.requestFocus() }
            }
        }
        ui.waitForIdle()
        ui.onRoot().performKeyInput { keyDown(Key.DirectionRight); advanceEventTime(1_000) }
        ui.waitForIdle()
        ui.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
        assertTrue(epgOpen.value)
        assertFalse(videoFocused.value)
        press(Key.DirectionRight)
        assertTrue(videoFocused.value)
        ui.onRoot().performKeyInput { keyDown(Key.Enter) }
        assertFalse(fullscreen)
        ui.onRoot().performKeyInput { keyUp(Key.Enter) }
        ui.waitForIdle()
        assertTrue(fullscreen)
        assertFalse(epgOpen.value)
    }

    @Test fun narrowWindowedControlsStayVisibleAndDoNotStealVideoFocus() {
        val archived = mutableStateOf(false)
        ui.setContent {
            val videoFocus = remember { FocusRequester() }
            MaterialTheme {
                Column(Modifier.size(420.dp, 700.dp)) {
                    Box(Modifier.size(420.dp, 240.dp).testTag("video").focusRequester(videoFocus).focusable())
                    AndroidPlaybackControls(
                        paused = false, positionMs = 0, durationMs = hour,
                        onTogglePlayback = {}, onSeekBy = {}, requestInitialFocus = false,
                        canPause = archived.value, isLiveChannel = !archived.value,
                        onLive = if (archived.value) ({}) else null, onDetails = {},
                    )
                }
                LaunchedEffect(Unit) { videoFocus.requestFocus() }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("video").assertIsFocused()
        ui.onNodeWithText("Описание").assertIsDisplayed()
        ui.onNodeWithText("Записать эфир", substring = true).assertIsDisplayed()
        ui.runOnIdle { archived.value = true }
        ui.waitForIdle()
        ui.onNodeWithTag("video").assertIsFocused()
        ui.onNodeWithText("LIVE").assertIsDisplayed()
        ui.onNodeWithText("Описание").assertIsDisplayed()
    }
}
