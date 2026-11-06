package com.tvnext.app

import android.app.Application
import android.content.Context
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.common.result.AppResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import com.tvnext.app.feature.tvg.ui.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class AndroidGuideAccessRegressionTest {
    @Test fun unlockingNotifiesAllViewsWithoutPersistingAccess() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("parental_access_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ParentalChannelGate.lock()
        val protection = ParentalProtection(context)
        try {
            assertTrue(ParentalChannelGate.session.value.restrictionsActive)
            assertFalse(protection.verify("0000"))
            assertTrue(protection.verify("1234"))
            protection.unlock()
            assertFalse(ParentalChannelGate.session.value.restrictionsActive)
            assertTrue(ParentalProtection(context).unlocked) // Activity recreation keeps this session.
            assertFalse(prefs.contains("unlocked"))
            protection.lock() // Clean application exit; a killed process also loses the StateFlow.
            assertTrue(ParentalChannelGate.session.value.restrictionsActive)
            protection.enabled = false
            assertFalse(ParentalChannelGate.session.value.restrictionsActive)
            protection.enabled = true
            assertTrue(ParentalChannelGate.session.value.restrictionsActive)
            assertTrue(protection.changePin("1234", "567890"))
            assertFalse(protection.verify("1234"))
            assertTrue(ParentalProtection(context).verify("567890"))
            assertFalse(prefs.all.values.contains("567890"))
        } finally { protection.lock(); prefs.edit().clear().commit(); ParentalChannelGate.setEnabled(true) }
    }

    @Test fun guideReentryReadsChannelsAfterInitialEmptyStartupWithoutAnotherEpgRefresh() = guide(emptyList()) { fixture ->
        assertTrue(fixture.component.state.value.filteredChannels.isEmpty())
        fixture.channels = listOf(fixture.channel)
        fixture.component.dispatch(TvgIntent.Activated)
        assertEquals(listOf(fixture.channel), fixture.component.state.value.filteredChannels)
        assertEquals("Кино", fixture.component.state.value.selectedCategory)
        assertNotNull(fixture.component.state.value.programmes[fixture.channel.id]?.firstOrNull())
        assertNull(fixture.component.state.value.error)
    }

    @Test fun removedCategoryCannotLeaveGuideEmpty() = guide(null) { fixture ->
        fixture.component.dispatch(TvgIntent.CategorySelected("Удалённая категория"))
        assertFalse(fixture.component.state.value.filteredChannels.isEmpty())
        assertNull(fixture.component.state.value.selectedCategory)
        fixture.channels = listOf(fixture.channel.copy(category = "Новая категория"))
        fixture.component.dispatch(TvgIntent.Activated)
        assertEquals("Новая категория", fixture.component.state.value.selectedCategory)
        assertEquals(fixture.channels, fixture.component.state.value.filteredChannels)
    }

    @Test fun missingEpgCannotHideFreshChannels() = guide(null) { fixture ->
        fixture.channels = listOf(fixture.channel.copy(id = "replacement", name = "Новый канал"))
        fixture.rangeFails = true
        fixture.component.dispatch(TvgIntent.Activated)
        assertEquals(fixture.channels, fixture.component.state.value.filteredChannels)
        assertNotNull(fixture.component.state.value.error)
    }

    @Test fun emptyArchiveDayAndReentryCannotHideTodaysProgrammes() = guide(null) { f ->
        f.emptyFrom = startOfLocalDayOffset(f.now, -2)
        f.component.dispatch(TvgIntent.DaySet(-2))
        assertTrue(f.component.state.value.programmes.values.all { it.isEmpty() })
        f.component.dispatch(TvgIntent.DaySet(0))
        assertFalse(f.component.state.value.programmes[f.channel.id].isNullOrEmpty())
        f.component.dispatch(TvgIntent.DaySet(-2))
        f.component.dispatch(TvgIntent.Activated)
        assertEquals(0, f.component.state.value.selectedDayOffset)
        assertEquals(startOfLocalDayOffset(f.now, 0), f.component.state.value.from)
        assertFalse(f.component.state.value.programmes[f.channel.id].isNullOrEmpty())
    }

    @Test fun latePreviousDayResultCannotOverwriteReturnToCurrentDay() = guide(null) { f ->
        val yesterday = startOfLocalDayOffset(f.now, -1)
        f.deferFrom = yesterday
        f.component.dispatch(TvgIntent.TimelineCursorMoved(yesterday + 23 * 3_600_000L))
        assertTrue(f.pending.isNotEmpty())
        // Returning to a day whose grid is still displayed must cancel the older request too.
        f.component.dispatch(TvgIntent.TimelineCursorMoved(f.now))
        val pending = f.pending.toList();f.pending.clear();f.deferFrom = null
        pending.forEach { (continuation, rows) -> continuation.resume(rows) }
        assertEquals(0, f.component.state.value.selectedDayOffset)
        assertEquals(startOfLocalDayOffset(f.now, 0), f.component.state.value.from)
        assertFalse(f.component.state.value.programmes[f.channel.id].isNullOrEmpty())
        assertNull(f.component.state.value.error)
    }

    @Test fun repeatedAbsoluteCursorDoesNotSkipSeveralDates() = guide(null) { f ->
        val yesterday = startOfLocalDayOffset(f.now, -1)
        f.deferFrom = yesterday
        repeat(12) { f.component.dispatch(TvgIntent.TimelineCursorMoved(yesterday + 23 * 3_600_000L)) }
        assertEquals(-1, f.component.state.value.selectedDayOffset)
        assertEquals(1, f.pending.size)
        val pending = f.pending.toList();f.pending.clear();f.deferFrom = null
        pending.forEach { (continuation, rows) -> continuation.resume(rows) }
        assertEquals(yesterday, f.component.state.value.from)
        f.component.dispatch(TvgIntent.TimelineCursorMoved(f.now))
        assertFalse(f.component.state.value.programmes[f.channel.id].isNullOrEmpty())
    }

    @Test fun reopeningKeepsLoadedRowsBeyondWarmupEvenIfReadIsTemporarilyEmpty() = guide(null) { f ->
        f.channels = listOf(f.channel) + (1..30).map { f.channel.copy(id = "channel-$it") }
        f.component.dispatch(TvgIntent.Activated)
        val id = f.channels.last().id
        f.component.dispatch(TvgIntent.VisibleChannelsChanged(setOf(id)))
        assertFalse(f.component.state.value.programmes[id].isNullOrEmpty())
        f.emptyChannels = setOf(id)
        f.component.dispatch(TvgIntent.Activated)
        assertFalse(f.component.state.value.programmes[id].isNullOrEmpty())
    }

    @Test fun reopeningDuringArchiveUsesActualPlaybackDay() = guide(null) { f ->
        f.player.value = f.player.value.copy(item = f.player.value.item!!.copy(
            kind = PlaybackContentKind.ARCHIVE, archiveEpgAnchorMs = startOfLocalDayOffset(f.now, -2) + 12 * 3_600_000L), positionMs = 300_000)
        f.component.dispatch(TvgIntent.Activated)
        assertEquals(-2, f.component.state.value.selectedDayOffset)
        assertFalse(f.component.state.value.programmes[f.channel.id].isNullOrEmpty())
    }

    private class Fixture {
        var rangeFails = false
        val now = System.currentTimeMillis()
        var deferFrom: Long? = null
        var emptyFrom: Long? = null
        var emptyChannels: Set<String> = emptySet()
        val pending = mutableListOf<Pair<Continuation<Any?>, Map<String, List<EpgProgramme>>>>()
        val channel = Channel("live", "Кино", 1, "Кино", "https://unused.invalid/live", playlistId = "playlist")
        val player = MutableStateFlow(PlayerState(item = PlaybackItem(channel.id, channel.name, channel.streamUrl)))
        var channels: List<Channel> = listOf(channel)
        lateinit var component: DefaultTvgComponent
    }

    private inline fun <reified T> fake(crossinline response: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            response(method.name, args.orEmpty())
        } as T

    private fun guide(initial: List<Channel>?, block: (Fixture) -> Unit) {
        val fixture = Fixture().also { if (initial != null) it.channels = initial }
        val failures = mutableListOf<Throwable>()
        val lifecycle = LifecycleRegistry().apply { create(); resume() }
        val now = fixture.now
        var refreshes = 0
        val playlist = Playlist("playlist", "Тест", "https://unused.invalid/list")
        val channels = fake<ChannelsRepository> { name, _ -> when (name) {
            "getChannels" -> fixture.channels
            else -> error("Unexpected channels.$name")
        } }
        val playlists = fake<PlaylistRepository> { name, _ -> when (name) {
            "getPlaylists" -> listOf(playlist)
            else -> error("Unexpected playlists.$name")
        } }
        val epg = fake<EpgRepository> { name, args -> when (name) {
            "observeVersion" -> MutableStateFlow(1L)
            "observeState" -> MutableStateFlow(EpgState())
            "getAvailableProgrammeRange" -> { if (fixture.rangeFails) error("Missing test EPG index"); (now - 3 * 86_400_000L)..(now + 2 * 86_400_000L) }
            "getProgrammesInRange" -> {
                val from = args[1] as Long
                val to = args[2] as Long
                val rows = (args[0] as List<Channel>).associate { c -> c.id to
                    if (fixture.emptyFrom == from || c.id in fixture.emptyChannels) emptyList()
                    else (-72..72).map { hour ->
                        val start = startOfLocalDayOffset(now, 0) + hour * 3_600_000L
                        EpgProgramme(c.id, start, start + 3_600_000L, "Передача $hour")
                    }.filter { it.start < to && it.stop > from }
                }
                if (fixture.deferFrom == from) {
                    @Suppress("UNCHECKED_CAST")
                    fixture.pending += (args.last() as Continuation<Any?>) to rows
                    COROUTINE_SUSPENDED // A deliberately cancellation-insensitive repository response.
                } else rows
            }
            "getNowPlaying" -> (args[0] as List<Channel>).associate { c -> c.id to EpgProgramme(c.id, now - 3_600_000, now + 3_600_000, "Текущий фильм") }
            "refreshIfStale" -> { refreshes++; AppResult.Success(Unit) }
            else -> error("Unexpected epg.$name")
        } }
        val archive = fake<ArchiveRepository> { name, _ -> when (name) {
            "getCapabilitiesCached" -> emptyMap<String, Any>()
            else -> error("Unexpected archive.$name")
        } }
        val scheduled = fake<ScheduledRepository> { name, _ -> when (name) {
            "observeAll" -> emptyFlow<List<ScheduledProgramme>>()
            else -> error("Unexpected schedule.$name")
        } }
        val playback = fake<PlaybackController> { name, _ -> when (name) {
            "getState" -> fixture.player
            else -> error("Unexpected playback.$name")
        } }
        try {
            fixture.component = DefaultTvgComponent(DefaultComponentContext(lifecycle), channels, playlists, epg, archive, scheduled,
                playback, {}, mainContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error })
            assertTrue("Initialization failures: $failures", failures.isEmpty())
            // TV Guide remembers dates in-process; start each fixture via the real Today action.
            fixture.component.dispatch(TvgIntent.DaySet(0))
            val before = refreshes
            block(fixture)
            assertEquals("Reentry must only read the cache", before, refreshes)
            assertTrue("Coroutine failures: $failures", failures.isEmpty())
        } finally { lifecycle.destroy() }
    }
}
