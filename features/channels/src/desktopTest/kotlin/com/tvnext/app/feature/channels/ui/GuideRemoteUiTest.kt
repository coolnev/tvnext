package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
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

    private fun showDensityGuide(right: Boolean): List<Channel> {
        val roster = (0 until 30).map { channel.copy(id = "density-$it", name = "Канал $it") }
        val selected = roster[12]
        val entries = programmes.map { it.copy(channelId = selected.id) }
        ui.setContent {
            MaterialTheme {
                AndroidWideGuide(channels = roster, playingChannelId = selected.id, focusedChannelId = selected.id,
                    timeline = ChannelTimelineState(selected.id, false, entries), nowPlaying = emptyMap(), now = now,
                    showNumbers = false, showCategories = false, descriptionOnRight = right,
                    categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                    onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {}, onPlayArchive = { _, _ -> },
                    onClose = {}, modifier = Modifier.size(1100.dp, if (right) 700.dp else 540.dp))
            }
        }
        ui.waitForIdle()
        return roster
    }

    @Test fun bottomDescriptionShowsTenProgrammesBesideFiveChannels() {
        showDensityGuide(false)
        ui.onAllNodesWithTag("guide-channel-row").assertCountEquals(5)
        ui.onAllNodesWithTag("guide-programme-row").assertCountEquals(10)
        assertProgrammeIsBesideSelectedChannel(2)
        saveValidationScreenshot("bottom-5-channels-10-programmes.png")
    }

    @Test fun rightDescriptionShowsTwiceAsManyProgrammesAsChannels() {
        showDensityGuide(true)
        ui.onAllNodesWithTag("guide-channel-row").assertCountEquals(9)
        ui.onAllNodesWithTag("guide-programme-row").assertCountEquals(18)
        assertProgrammeIsBesideSelectedChannel(4)
        saveValidationScreenshot("right-9-channels-18-programmes.png")
    }

    private fun saveValidationScreenshot(name: String) {
        val directory = System.getenv("TVNEXT_VALIDATION_DIR")?.takeIf { it.isNotBlank() } ?: return
        val target = java.io.File(directory, name)
        target.parentFile.mkdirs()
        val image = org.jetbrains.skia.Image.makeFromBitmap(ui.onRoot().captureToImage().asSkiaBitmap())
        image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { target.writeBytes(it.bytes) }
        image.close()
    }

    private fun assertProgrammeIsBesideSelectedChannel(channelSlot: Int) {
        val channelBounds = ui.onAllNodesWithTag("guide-channel-row")[channelSlot].fetchSemanticsNode().boundsInRoot
        val current = programmes.first { it.start <= now && now < it.stop }
        val programmeBounds = ui.onNodeWithText("${formatTimeOfDay(current.start)}  ${current.title}").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(channelBounds.center.y-programmeBounds.center.y) <= channelBounds.height/3,
            "The live programme must remain beside its selected channel")
    }

    @Test fun leavingUnplayedArchiveReturnsToCurrentProgrammeOnTheSameChannel() {
        var played: EpgProgramme? = null
        ui.setContent { MaterialTheme {
            AndroidWideGuide(channels = listOf(channel), playingChannelId = channel.id, focusedChannelId = channel.id,
                timeline = ChannelTimelineState(channel.id, false, programmes), nowPlaying = emptyMap(), now = now,
                showNumbers = false, showCategories = false, descriptionOnRight = false,
                categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {}, onPlayArchive = { _, p -> played = p },
                onClose = {}, modifier = Modifier.size(1100.dp, 540.dp))
        } }
        ui.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionUp, 37)
        assertEquals(null, played)
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        press(Key.Enter)
        assertEquals(today + 12 * hour, played?.start)
    }

    @Test fun anUnplayedArchiveTimeNeverMovesTheNextChannelAwayFromItsLiveProgramme() {
        var played: EpgProgramme? = null
        val other = channel.copy(id = "other", name = "Другой канал")
        val focused = mutableStateOf(channel.id)
        ui.setContent { MaterialTheme {
            val selected = focused.value
            AndroidWideGuide(channels = listOf(channel, other), playingChannelId = channel.id, focusedChannelId = selected,
                timeline = ChannelTimelineState(selected, false, programmes.map { it.copy(channelId = selected) }),
                nowPlaying = emptyMap(), now = now,
                showNumbers = false, showCategories = false, descriptionOnRight = true,
                categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                onChannelFocus = { focused.value = it }, onVisibleChannelsChanged = {}, onPlayChannel = {},
                onPlayArchive = { _, p -> played = p }, onClose = {}, modifier = Modifier.size(1100.dp, 700.dp))
        } }
        ui.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionUp, 37)
        assertEquals(null, played)
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        assertEquals(other.id, focused.value)
        press(Key.DirectionRight)
        press(Key.Enter)
        assertEquals(other.id, played?.channelId)
        assertEquals(today + 12 * hour, played?.start)
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

    @Test fun inlineWindowedSynopsisArrivesAfterThePoster() = descriptionAfterPoster(3)
    @Test fun descriptionArrivesAfterThePosterInWindowedDetails() = descriptionAfterPoster(0)
    @Test fun descriptionArrivesAfterThePosterBelowTheFullGuide() = descriptionAfterPoster(1)
    @Test fun descriptionArrivesAfterThePosterBesideTheFullGuide() = descriptionAfterPoster(2)

    private fun descriptionAfterPoster(mode: Int) {
        val synopsis = "Найденный сюжет о возвращении героя домой."
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pictures = java.util.concurrent.atomic.AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/search") ->
                    """{"docs":[{"id":2,"name":"Холоп 2","poster":{"url":"https://example.org/poster.png"}}]}"""
                request.url.encodedPath.endsWith("/2") -> { release.await(); """{"description":"$synopsis"}""" }
                else -> "{}"
            }
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
        val image = androidx.compose.ui.graphics.ImageBitmap(80, 120)
        val loader = object : com.tvnext.app.core.ui.image.LogoImageLoader {
            override suspend fun load(url: String): androidx.compose.ui.graphics.ImageBitmap {
                pictures.incrementAndGet(); return image
            }
        }
        val programme = EpgProgramme(channel.id, now - hour / 2, now + hour / 2, "Холоп 2", "Описание\nпередачи\u00a0отсутствует")
        try {
            ui.setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.tvnext.app.core.ui.image.LocalLogoImageLoader provides loader) {
                    MaterialTheme {
                        if (mode == 0) Box(Modifier.size(700.dp, 500.dp)) { ProgrammeRichDetails(programme) }
                        else if (mode == 3) AndroidWindowedPlaybackInfo(
                            programme, false, 0, hour, {}, {}, 60_000, false, true,
                            modifier = Modifier.size(240.dp, 170.dp))
                        else AndroidWideGuide(channels = listOf(channel), playingChannelId = channel.id, focusedChannelId = channel.id,
                            timeline = ChannelTimelineState(channel.id, false, listOf(programme)), nowPlaying = emptyMap(), now = now,
                            showNumbers = false, showCategories = false, descriptionOnRight = mode == 2,
                            categories = emptyList(), selectedCategory = null, onToggleCategories = {}, onChooseCategory = {},
                            onChannelFocus = {}, onVisibleChannelsChanged = {}, onPlayChannel = {}, onPlayArchive = { _, _ -> },
                            onClose = {}, modifier = Modifier.size(1100.dp, 700.dp))
                    }
                }
            }
            ui.waitUntil(5_000) { pictures.get() > 0 }
            release.complete(Unit)
            ui.waitUntil(5_000) { ui.onAllNodesWithText(synopsis).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(synopsis).assertIsDisplayed()
            ui.onNodeWithText(programme.description!!).assertDoesNotExist()
        } finally { release.complete(Unit); client.close() }
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
        ui.onNodeWithTag("windowed-programmes").assertIsFocused()
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

    @Test fun windowedListHasFourteenUndatedProgrammesInsteadOfSeven() {
        ui.setContent { MaterialTheme {
            AndroidWindowedProgrammeList(channel, programmes, false, now, null, null, true,
                {}, {}, Modifier.size(400.dp, 600.dp))
        } }
        ui.waitForIdle()
        ui.onAllNodesWithTag("windowed-programme-row").assertCountEquals(14)
        val current = programmes.first { it.start <= now && now < it.stop }
        ui.onNodeWithText("${formatTimeOfDay(current.start)}  ${current.title}").assertIsDisplayed()
        ui.onNodeWithText("${formatDate(current.start)}  ${formatTimeOfDay(current.start)}  ${current.title}").assertDoesNotExist()
        saveValidationScreenshot("windowed-fourteen-programmes-no-date.png")
    }

    @Test fun narrowWindowedControlsStayVisibleAndDoNotStealVideoFocus() {
        val archived = mutableStateOf(false)
        val synopsis = "Сюжет фильма уже под видео."
        val programme = EpgProgramme(channel.id, now, now + hour, "Фильм эфира", synopsis)
        var sought = 0L
        ui.setContent {
            val videoFocus = remember { FocusRequester() }
            MaterialTheme {
                Column(Modifier.size(240.dp, 305.dp)) {
                    Box(Modifier.fillMaxWidth().height(135.dp).testTag("video").focusRequester(videoFocus).focusable())
                    AndroidWindowedPlaybackInfo(
                        programme = programme, paused = false, positionMs = 0, durationMs = hour,
                        onTogglePlayback = {}, onSeekBy = { sought += it }, seekStepMs = 60_000,
                        canPause = archived.value, isLiveChannel = !archived.value,
                        onLive = if (archived.value) ({}) else null,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                LaunchedEffect(Unit) { videoFocus.requestFocus() }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("video").assertIsFocused()
        ui.onNodeWithText("Описание").assertDoesNotExist()
        ui.onNodeWithText("Телепрограмма").assertDoesNotExist()
        ui.onNodeWithText("● Запись").assertIsDisplayed()
        ui.onNodeWithText(synopsis).assertIsDisplayed()
        val controls = ui.onNodeWithTag("windowed-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(controls.height <= 64, "Compact controls must leave room for the inline synopsis")
        val rewind = ui.onNodeWithTag("playback-rewind").fetchSemanticsNode().boundsInRoot
        val recording = ui.onNodeWithText("● Запись").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(rewind.center.y-recording.center.y) <= 2, "Recording must remain in the same button row")
        saveValidationScreenshot("windowed-compact-live-with-description.png")
        ui.runOnIdle { archived.value = true }
        ui.waitForIdle()
        ui.onNodeWithTag("video").assertIsFocused()
        ui.onNodeWithText("LIVE").assertIsDisplayed()
        ui.onNodeWithText("Описание").assertDoesNotExist()
        ui.onNodeWithText(synopsis).assertIsDisplayed()
        ui.onNodeWithTag("playback-rewind").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(Key.Enter)
        assertEquals(-60_000L, sought)
        saveValidationScreenshot("windowed-compact-archive-with-description.png")
    }
}
