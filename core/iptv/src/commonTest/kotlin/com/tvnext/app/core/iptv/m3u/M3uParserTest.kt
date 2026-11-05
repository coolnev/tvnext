package com.tvnext.app.core.iptv.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M3uParserTest {

    private val parser = M3uParser()

    private fun parseSuccess(content: String): M3uPlaylist {
        val result = parser.parse(content)
        val success = assertIs<M3uParseResult.Success>(result)
        return success.playlist
    }

    @Test
    fun simpleM3u() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,Channel One
            http://example.com/one.m3u8
            """.trimIndent(),
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Channel One", playlist.channels[0].name)
        assertEquals("http://example.com/one.m3u8", playlist.channels[0].streamUrl)
    }

    @Test
    fun multipleChannels() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,First
            http://example.com/1.ts
            #EXTINF:0,Second
            http://example.com/2.ts
            #EXTINF:60,Third
            http://example.com/3.ts
            """.trimIndent(),
        )
        assertEquals(3, playlist.channels.size)
        assertEquals(listOf("First", "Second", "Third"), playlist.channels.map { it.name })
        assertEquals(listOf("http://example.com/1.ts", "http://example.com/2.ts", "http://example.com/3.ts"),
            playlist.channels.map { it.streamUrl })
    }

    @Test
    fun groupTitleParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="1" group-title="News",News Channel
            http://example.com/news.m3u8
            #EXTINF:-1 group-title="Sport",Sport Channel
            http://example.com/sport.m3u8
            """.trimIndent(),
        )
        assertEquals("News", playlist.channels[0].groupTitle)
        assertEquals("Sport", playlist.channels[1].groupTitle)
    }

    @Test
    fun tvgIdParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="channel.one" tvg-name="Channel One",Channel One
            http://example.com/one.m3u8
            """.trimIndent(),
        )
        assertEquals("channel.one", playlist.channels[0].tvgId)
        assertEquals("Channel One", playlist.channels[0].tvgName)
    }

    @Test
    fun tvgLogoParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://example.com/logo.png",Logo Channel
            http://example.com/logo.m3u8
            """.trimIndent(),
        )
        assertEquals("http://example.com/logo.png", playlist.channels[0].tvgLogo)
    }

    @Test
    fun crlfLineEndings() {
        val playlist = parseSuccess(
            "#EXTM3U\r\n" +
                "#EXTINF:-1,First\r\n" +
                "http://example.com/1.ts\r\n" +
                "\r\n" +
                "#EXTINF:-1,Second\r\n" +
                "http://example.com/2.ts\r\n",
        )
        assertEquals(2, playlist.channels.size)
        assertEquals(listOf("First", "Second"), playlist.channels.map { it.name })
    }

    @Test
    fun utf8BomStripped() {
        val content = "\uFEFF#EXTM3U\n#EXTINF:-1,After BOM\nhttp://example.com/stream.m3u8\n"
        val playlist = parseSuccess(content)
        assertEquals(1, playlist.channels.size)
        assertEquals("After BOM", playlist.channels[0].name)
    }

    @Test
    fun emptyPlaylistIsValid() {
        val playlist = parseSuccess("#EXTM3U\n")
        assertTrue(playlist.channels.isEmpty())
    }

    @Test
    fun notM3uHeaderIsFailure() {
        val result = parser.parse("this is not a playlist")
        assertIs<M3uParseResult.Failure>(result)
    }

    @Test
    fun headerOnlyWithComments() {
        val result = parser.parse("#EXTINF:-1,No header here\nhttp://example.com/x.m3u8\n")
        assertIs<M3uParseResult.Failure>(result)
    }

    @Test
    fun malformedExtinfIsSkipped() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF malformed-no-duration-no-comma
            #EXTINF:-1,Good Channel
            http://example.com/good.m3u8
            """.trimIndent(),
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Good Channel", playlist.channels[0].name)
    }

    @Test
    fun streamUrlWithoutExtinfIsSkipped() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            http://example.com/orphan.ts
            #EXTINF:-1,Proper Channel
            http://example.com/proper.m3u8
            """.trimIndent(),
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Proper Channel", playlist.channels[0].name)
    }

    @Test
    fun urlWithQueryParameters() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,Query Channel
            http://example.com/playlist.m3u8?token=abc123&quality=1080&foo=bar%20baz
            """.trimIndent(),
        )
        assertEquals("http://example.com/playlist.m3u8?token=abc123&quality=1080&foo=bar%20baz",
            playlist.channels[0].streamUrl)
    }

    @Test
    fun unicodeCyrillicNames() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,Первый канал
            http://example.com/1.m3u8
            #EXTINF:-1 group-title="Новости",Россия 24
            http://example.com/24.m3u8
            """.trimIndent(),
        )
        assertEquals("Первый канал", playlist.channels[0].name)
        assertEquals("Россия 24", playlist.channels[1].name)
        assertEquals("Новости", playlist.channels[1].groupTitle)
    }

    @Test
    fun duplicateChannelsAreKept() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Duplicated
            http://example.com/a.m3u8
            #EXTINF:-1 tvg-id="dup",Duplicated
            http://example.com/a.m3u8
            """.trimIndent(),
        )
        assertEquals(2, playlist.channels.size)
        assertEquals(listOf("Duplicated", "Duplicated"), playlist.channels.map { it.name })
    }

    @Test
    fun quotedAttributeWithComma() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, Sports",Comma Channel
            http://example.com/comma.m3u8
            """.trimIndent(),
        )
        assertEquals("News, Sports", playlist.channels[0].groupTitle)
        assertEquals("Comma Channel", playlist.channels[0].name)
    }

    @Test
    fun channelNameWithComma() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,Channel, The
            http://example.com/c.m3u8
            """.trimIndent(),
        )
        assertEquals("Channel, The", playlist.channels[0].name)
    }

    @Test
    fun commentsAndOtherDirectivesIgnored() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            # some comment
            #EXTVLCOPT:http-referrer=http://example.com
            #EXTINF:-1,Real Channel
            http://example.com/real.m3u8
            """.trimIndent(),
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Real Channel", playlist.channels[0].name)
    }

    @Test
    fun headerWithExtraAttributes() {
        val playlist = parseSuccess(
            """
            #EXTM3U x-tvg-url="http://example.com/epg.xml"
            #EXTINF:-1,Attributed
            http://example.com/attributed.m3u8
            """.trimIndent(),
        )
        assertEquals(1, playlist.channels.size)
        assertEquals("Attributed", playlist.channels[0].name)
    }

    @Test
    fun durationIsParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:123,Timed Channel
            http://example.com/timed.m3u8
            """.trimIndent(),
        )
        assertEquals(123L, playlist.channels[0].durationSeconds)
    }

    @Test
    fun urlTvgFromHeaderIsParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U url-tvg="https://example.com/epg.xml.gz"
            #EXTINF:-1,Channel One
            http://example.com/one.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.com/epg.xml.gz", playlist.urlTvg)
    }

    @Test
    fun xTvgUrlFromHeaderIsParsedAsFallback() {
        val playlist = parseSuccess(
            """
            #EXTM3U x-tvg-url="https://example.com/epg2.xml"
            #EXTINF:-1,Channel One
            http://example.com/one.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.com/epg2.xml", playlist.urlTvg)
    }

    @Test
    fun urlTvgTakesPriorityOverXTvgUrl() {
        val playlist = parseSuccess(
            """
            #EXTM3U url-tvg="https://example.com/main.xml" x-tvg-url="https://example.com/backup.xml"
            #EXTINF:-1,Channel One
            http://example.com/one.m3u8
            """.trimIndent(),
        )
        assertEquals("https://example.com/main.xml", playlist.urlTvg)
    }

    @Test
    fun noEpgUrlWhenHeaderHasNoAttributes() {
        val playlist = parseSuccess("#EXTM3U\n#EXTINF:-1,C\nhttp://example.com/c.m3u8\n")
        assertTrue(playlist.urlTvg == null)
    }

    @Test
    fun catchupAttributesParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-days="7" catchup-source="http://example.com/archive.m3u8?utc={utc}" catchup-type="default",Archive Channel
            http://example.com/live.m3u8
            """.trimIndent(),
        )
        val channel = playlist.channels.single()
        assertEquals("default", channel.catchup)
        assertEquals(7L, channel.catchupDays)
        assertEquals("http://example.com/archive.m3u8?utc={utc}", channel.catchupSource)
        assertEquals("default", channel.catchupType)
        assertTrue(channel.timeshift == null)
    }

    @Test
    fun timeshiftAttributeParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 timeshift="1" catchup-type="timeshift" catchup-source="http://example.com/ts.m3u8",Timeshift Channel
            http://example.com/live.m3u8
            """.trimIndent(),
        )
        val channel = playlist.channels.single()
        assertEquals("1", channel.timeshift)
        assertEquals("timeshift", channel.catchupType)
        assertEquals("http://example.com/ts.m3u8", channel.catchupSource)
    }

    @Test
    fun catchupDaysWithoutCatchupFlagStillParsed() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1 catchup-days="14",Days Only Channel
            http://example.com/live.m3u8
            """.trimIndent(),
        )
        assertEquals(14L, playlist.channels.single().catchupDays)
        assertTrue(playlist.channels.single().catchup == null)
    }

    @Test
    fun noCatchupAttributesByDefault() {
        val playlist = parseSuccess(
            """
            #EXTM3U
            #EXTINF:-1,Plain Channel
            http://example.com/live.m3u8
            """.trimIndent(),
        )
        val channel = playlist.channels.single()
        assertTrue(channel.catchup == null)
        assertTrue(channel.catchupDays == null)
        assertTrue(channel.catchupSource == null)
        assertTrue(channel.catchupType == null)
        assertTrue(channel.timeshift == null)
    }
}
