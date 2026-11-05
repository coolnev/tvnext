package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatchupUrlBuilderTest {

    private val builder = CatchupUrlBuilder()

    private fun channel(
        catchup: String? = null,
        catchupDays: Long? = null,
        catchupSource: String? = null,
        catchupType: String? = null,
        timeshift: String? = null,
    ) = Channel(
        id = "ch1",
        name = "Канал",
        number = 1,
        category = "Тест",
        streamUrl = "http://example.com/live.m3u8",
        playlistId = "pl",
        catchup = catchup,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
        catchupType = catchupType,
        timeshift = timeshift,
    )

    @Test
    fun noMechanismCannotBuild() {
        val ch = channel()
        assertFalse(ch.hasCatchupMechanism)
        assertFalse(builder.isArchivePlayable(ch))
        assertNull(builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L))
    }

    @Test
    fun utcTemplateReplacedWithStartSeconds() {
        // 2023-11-14T22:13:20Z = 1700000000
        val ch = channel(catchupSource = "http://example.com/arch.m3u8?utc={utc}&end={end}")
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("http://example.com/arch.m3u8?utc=1700000000&end=1700003600", url)
    }

    @Test
    fun datePartPlaceholdersReplacedInUtc() {
        // 2023-11-14T22:13:20Z
        val ch = channel(catchupSource = "{year}-{month}-{day}T{hour}:{minute}:{second}Z")
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("2023-11-14T22:13:20Z", url)
    }

    @Test
    fun startEndAliasesReplaced() {
        val ch = channel(catchupSource = "{start}|{start-utc}|{end-utc}|{duration}")
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertEquals("1700000000|1700000000|1700003600|3600", url)
    }

    @Test
    fun timeshiftTypeWithPlainUrlAppendsTimeParams() {
        val ch = channel(
            catchupType = "timeshift",
            timeshift = "1",
            catchupSource = "http://example.com/ts.m3u8",
        )
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("http://example.com/ts.m3u8?start=1700000000&end=1700003600", url)
    }

    @Test
    fun timeshiftPlainUrlAlreadyHasQueryKeepsParams() {
        val ch = channel(
            catchupType = "timeshift",
            catchupSource = "http://example.com/ts.m3u8?token=abc",
        )
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("http://example.com/ts.m3u8?token=abc&start=1700000000&end=1700003600", url)
    }

    @Test
    fun declaredDaysAloneDoesNotBuildUrl() {
        // catchup-days without a source/type cannot produce a URL (honest: no
        // fabricated template).
        val ch = channel(catchupDays = 7, catchupSource = null)
        assertTrue(ch.hasCatchupMechanism)
        assertFalse(builder.isArchivePlayable(ch))
        assertNull(builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L))
    }

    @Test
    fun shiftFlagWithoutTemplateUsesLiveUrlWithTimeParams() {
        // cdntv.online-style: catchup="shift" + timeshift, no catchup-source.
        val ch = channel(catchup = "shift", catchupDays = 3, timeshift = "3")
        assertTrue(builder.isArchivePlayable(ch))
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("http://example.com/live.m3u8?start=1700000000&end=1700003600", url)
    }

    @Test
    fun flussonicTypeBuildsDvrFileUrl() {
        // vivamax.pro-style: catchup-type="flussonic", stream URL with query.
        val ch = Channel(
            id = "ch1",
            name = "Первый канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://stream.vivamax.pro/PerviKanal/index.m3u8?token=abc123",
            playlistId = "pl",
            catchupType = "flussonic",
            catchupDays = 14,
        )
        assertTrue(ch.hasCatchupMechanism)
        assertTrue(builder.isArchivePlayable(ch))
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals(
            "http://stream.vivamax.pro/PerviKanal/index-1700000000-3600.m3u8?token=abc123",
            url,
        )
    }

    @Test
    fun flussonicWithoutQueryKeepsNoQuery() {
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://host/stream/index.m3u8",
            playlistId = "pl",
            catchupType = "flussonic",
        )
        val url = builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_003_600_000L)
        assertNotNull(url)
        assertEquals("http://host/stream/index-1700000000-3600.m3u8", url)
    }

    @Test
    fun flussonicEmptyDurationRejected() {
        val ch = Channel(
            id = "ch1",
            name = "Канал",
            number = 1,
            category = "Тест",
            streamUrl = "http://host/stream/index.m3u8",
            playlistId = "pl",
            catchupType = "flussonic",
        )
        assertNull(builder.buildArchiveUrl(ch, 1_700_000_000_000L, 1_700_000_000_000L))
    }

    @Test
    fun emptyRangeRejected() {
        val ch = channel(catchupSource = "http://example.com/arch.m3u8?utc={utc}")
        assertNull(builder.buildArchiveUrl(ch, 1_700_003_600_000L, 1_700_000_000_000L))
    }
}
