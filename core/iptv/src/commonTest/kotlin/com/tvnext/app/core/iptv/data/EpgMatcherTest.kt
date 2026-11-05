package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EpgMatcherTest {

    private val matcher = EpgMatcher()

    private fun channel(
        id: String,
        tvgId: String? = null,
        tvgName: String? = null,
        name: String = "Канал $id",
    ) = Channel(
        id = id,
        name = name,
        number = 0,
        category = "Тест",
        streamUrl = "http://example.com/$id",
        tvgId = tvgId,
        tvgName = tvgName,
    )

    private fun epg(id: String, vararg displayNames: String) =
        EpgChannel(id = id, displayNames = displayNames.toList())

    @Test
    fun matchesByTvgId() {
        val channels = listOf(channel(id = "ch1", tvgId = "channel.one"))
        val epgChannels = listOf(epg("channel.one", "Первый канал"))
        val result = matcher.match(channels, epgChannels)

        assertEquals("channel.one", result.channelToEpgId["ch1"])
        assertEquals(1, result.diagnostics.matchedByTvgId)
        assertEquals(0, result.diagnostics.unmatchedM3u)
    }

    @Test
    fun fallsBackToNormalizedName() {
        val channels = listOf(
            channel(id = "ch1", tvgName = "Россия 1"),
            channel(id = "ch2", name = "НТВ"),
        )
        val epgChannels = listOf(
            epg("rossiya", "Россия 1 HD"),
            epg("ntv", "НТВ"),
        )
        val result = matcher.match(channels, epgChannels)

        assertNull(result.channelToEpgId["ch1"], "«Россия 1» не должна совпасть с «Россия 1 HD»")
        assertEquals("ntv", result.channelToEpgId["ch2"])
        assertEquals(1, result.diagnostics.matchedByName)
        assertEquals(1, result.diagnostics.unmatchedM3u)
    }

    @Test
    fun normalizationHandlesCaseWhitespaceAndFormatting() {
        val channels = listOf(channel(id = "ch1", name = "Россия 1"))
        val epgChannels = listOf(epg("rossiya", "россия1"))
        val result = matcher.match(channels, epgChannels)

        assertEquals("rossiya", result.channelToEpgId["ch1"])
    }

    @Test
    fun neverMatchesSimilarButDifferentNames() {
        val channels = listOf(
            channel(id = "ch1", name = "Россия 1"),
            channel(id = "ch2", name = "Россия 24"),
        )
        val epgChannels = listOf(epg("r24", "Россия 24"))
        val result = matcher.match(channels, epgChannels)

        assertNull(result.channelToEpgId["ch1"])
        assertEquals("r24", result.channelToEpgId["ch2"])
    }

    @Test
    fun tvgIdTakesPriorityOverName() {
        val channels = listOf(channel(id = "ch1", tvgId = "id.one", tvgName = "Channel One"))
        val epgChannels = listOf(
            epg("id.one", "Другое имя"),
            epg("id.two", "Channel One"),
        )
        val result = matcher.match(channels, epgChannels)

        // Matched by tvg-id even though the name would match a different epg channel.
        assertEquals("id.one", result.channelToEpgId["ch1"])
        assertEquals(1, result.diagnostics.matchedByTvgId)
        assertEquals(0, result.diagnostics.matchedByName)
    }

    @Test
    fun matchesTvgIdCaseInsensitively() {
        val channels = listOf(channel(id = "ch1", tvgId = "Channel.One"))
        val epgChannels = listOf(epg("channel.one", "Первый"))
        val result = matcher.match(channels, epgChannels)

        assertEquals("channel.one", result.channelToEpgId["ch1"])
        assertEquals(1, result.diagnostics.matchedByTvgId)
    }

    @Test
    fun looseNameFallbackMatchesPunctuationAndCase() {
        // «Россия-1» (M3U) vs «Россия 1» (XMLTV): strict normalize differs
        // (hyphen vs space), the loose pass links them.
        val channels = listOf(channel(id = "ch1", name = "Россия-1"))
        val epgChannels = listOf(epg("rossiya1", "Россия 1"))
        val result = matcher.match(channels, epgChannels)

        assertEquals("rossiya1", result.channelToEpgId["ch1"])
        assertEquals(1, result.diagnostics.matchedByName)
    }

    @Test
    fun looseNameNeverLinksDifferentChannels() {
        // «Россия 1» must still never link to «Россия 24» / «Россия 1 HD».
        val channels = listOf(
            channel(id = "ch1", name = "Россия 1"),
            channel(id = "ch2", name = "Россия 1 HD"),
        )
        val epgChannels = listOf(
            epg("r1", "Россия 1"),
            epg("r1hd", "Россия 1 HD"),
            epg("r24", "Россия 24"),
        )
        val result = matcher.match(channels, epgChannels)

        assertEquals("r1", result.channelToEpgId["ch1"])
        assertEquals("r1hd", result.channelToEpgId["ch2"])
        assertNull(result.channelToEpgId["ch1"]?.takeIf { it == "r24" })
    }

    @Test
    fun displayedNameIsUsedWhenProviderTvgNameDoesNotMatch() {
        val channels = listOf(channel(id = "ch1", tvgName = "unknown.provider.alias", name = "Музыка ТВ"))
        val epgChannels = listOf(epg("music", "Музыка ТВ"))
        assertEquals("music", matcher.match(channels, epgChannels).channelToEpgId["ch1"])
    }

    @Test
    fun ambiguousExternalNamesAreNotMatchedAtRandom() {
        val channels = listOf(channel(id = "ch1", name = "Тест ТВ"))
        val epgChannels = listOf(epg("east", "Тест ТВ"), epg("west", "Тест ТВ"))
        assertNull(matcher.match(channels, epgChannels).channelToEpgId["ch1"])
    }

    @Test
    fun diagnosticsCountEverything() {
        val channels = listOf(
            channel(id = "c1", tvgId = "epg1"),
            channel(id = "c2", name = "Матч! ТВ"),
            channel(id = "c3", name = "Без EPG"),
        )
        val epgChannels = listOf(
            epg("epg1", "Первый"),
            epg("match", "Матч! ТВ"),
        )
        val diagnostics = matcher.match(channels, epgChannels).diagnostics

        assertEquals(3, diagnostics.m3uChannelCount)
        assertEquals(2, diagnostics.epgChannelCount)
        assertEquals(1, diagnostics.matchedByTvgId)
        assertEquals(1, diagnostics.matchedByName)
        assertEquals(1, diagnostics.unmatchedM3u)
    }

    @Test
    fun normalizeStripsWhitespaceAndLowercases() {
        assertEquals("россия1", matcher.normalize("  РОССИЯ   1  "))
        assertEquals("channeltwo", matcher.normalize("Channel \t Two"))
    }
}
