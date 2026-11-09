package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import kotlin.test.*

class LiteXmltvMatchingRegressionTest {
    private fun channel(id: String, name: String, tvg: String? = null, playlist: String = "p") =
        Channel(id, name, 1, "", "https://unused.invalid/live", tvgId = tvg, playlistId = playlist)
    private fun programme(id: String, title: String = id) = "<programme channel='$id' start='20261109100000 +0000' stop='20261109110000 +0000'><title>$title</title></programme>"
    private fun parse(roster: List<Channel>, headers: String, rows: String): Map<String, List<com.tvnext.app.core.iptv.xmltv.EpgProgramme>> {
        val parser = LiteXmltvParser(); val output = EpgBuffer()
        assertTrue(StringEpgTextSource("<tv>$headers$rows</tv>").use { parser.parse(it, parser.buildChannelLookup(roster), "", output) })
        return output.drain()
    }
    @Test fun idMatchDoesNotHideOtherPlaylistVariantsOrOtherDisplayAliases() {
        val roster = listOf(channel("sd", "Первый", "provider.id"), channel("hd", "Первый (HD)", "other.id"),
            channel("alias", "Первый канал FHD"))
        val result = parse(roster, "<channel id='provider.id'><display-name>Первый</display-name><display-name>Первый канал</display-name></channel>", programme("provider.id"))
        assertEquals(setOf("p|sd", "p|hd", "p|alias"), result.keys)
    }
    @Test fun exactIdsBeatOverlappingNamesAndSeparateHdSchedules() {
        val roster = listOf(channel("sd", "Канал", "sd.id"), channel("hd", "Канал HD (H.265)", "hd.id"))
        val headers = "<channel id='sd.id'><display-name>Канал</display-name></channel><channel id='hd.id'><display-name>Канал HD</display-name></channel>"
        val result = parse(roster, headers, programme("sd.id", "SD") + programme("hd.id", "HD"))
        assertEquals(listOf("SD"), result["p|sd"]?.map { it.title })
        assertEquals(listOf("HD"), result["p|hd"]?.map { it.title })
    }
    @Test fun exactQualityNameWinsBeforeQualityFallbackAndCodecTagsDoNotBlockIt() {
        val result = parse(listOf(channel("hd", "Канал HD (HEVC)")),
            "<channel id='sd'><display-name>Канал</display-name></channel><channel id='hd'><display-name>Канал HD</display-name></channel>",
            programme("sd", "SD") + programme("hd", "HD"))
        assertEquals(listOf("HD"), result["p|hd"]?.map { it.title })
    }
    @Test fun decimalAndHexEntitiesUnicodeSpacesAndYoAreMatchedAndSupplementaryTitlesSurvive() {
        val result = parse(listOf(channel("one", "Все\u00a0ТВ [HD]")),
            "<channel id='feed'><display-name>&#1042;&#1089;&#1105; ТВ</display-name></channel>", programme("feed", "&#x1F3AC; &#1056;&#1077;&#1082;&#1072; &amp;#65;"))
        assertEquals("🎬 Река &#65;", result["p|one"]?.single()?.title)
    }
    @Test fun duplicatedChannelIdsInDifferentPlaylistsBothReceiveTheirGuide() {
        val result = parse(listOf(channel("same", "Первый", "news", "a"), channel("same", "Первый (HD)", null, "b")),
            "<channel id='news'><display-name>Первый</display-name></channel>", programme("news"))
        assertEquals(setOf("a|same", "b|same"), result.keys)
    }
    @Test fun ambiguousNamesAreNotRandomlyAssignedAndTimeShiftVariantsStaySeparate() {
        val roster = listOf(channel("ambiguous", "Канал"), channel("east", "Канал (+2)"), channel("west", "Канал (-2)"))
        val headers = "<channel id='a'><display-name>Канал</display-name></channel><channel id='b'><display-name>Канал</display-name></channel>" +
            "<channel id='east'><display-name>Канал +2</display-name></channel><channel id='west'><display-name>Канал -2</display-name></channel>"
        val result = parse(roster, headers, programme("a") + programme("b") + programme("east") + programme("west"))
        assertNull(result["p|ambiguous"])
        assertEquals("east", result["p|east"]?.single()?.title)
        assertEquals("west", result["p|west"]?.single()?.title)
    }
    @Test fun punctuationInExplicitIdsDoesNotMergeDifferentServices() {
        val result = parse(listOf(channel("a", "Один", "a.b"), channel("b", "Другой", "ab")), "", programme("a.b") + programme("ab"))
        assertEquals("a.b", result["p|a"]?.single()?.title)
        assertEquals("ab", result["p|b"]?.single()?.title)
    }
}
