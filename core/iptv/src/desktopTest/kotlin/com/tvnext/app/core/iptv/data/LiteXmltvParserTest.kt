package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the LiteTV-style streaming XMLTV parser: sliding-buffer scan, channel
 * matching by tvg-id / display-name (with quality-suffix stripping), timezone
 * offsets, bounded description capture, and "title + times" storage.
 */
class LiteXmltvParserTest {

    private fun channel(id: String, name: String, tvgId: String?) = Channel(
        id = id,
        name = name,
        number = 0,
        category = "",
        streamUrl = "http://example.com/$id",
        tvgId = tvgId,
        playlistId = "p1",
    )

    private fun parse(xml: String, channels: List<Channel>, windowStart: Long = Long.MIN_VALUE, windowEnd: Long = Long.MAX_VALUE): Map<String, List<EpgProgramme>> {
        val parser = LiteXmltvParser()
        val lookup = parser.buildChannelLookup(channels)
        val buffer = EpgBuffer()
        StringEpgTextSource(xml).use { source ->
            parser.parse(source, lookup, "p1", buffer, windowStart, windowEnd)
        }
        return buffer.drain()
    }

    @Test
    fun parsesProgrammesAndMatchesByTvgId() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="c1"><display-name>Первый</display-name></channel>
              <channel id="c2"><display-name>Россия 1</display-name></channel>
              <programme start="20260726000000 +0800" stop="20260726010000 +0800" channel="c1">
                <title lang="ru">Новости</title>
                <desc lang="ru">Огромное описание, которое не должно попасть в память</desc>
              </programme>
              <programme start="20260726010000 +0800" stop="20260726020000 +0800" channel="c1">
                <title lang="ru">Фильм</title>
              </programme>
              <programme start="20260726003000 +0800" stop="20260726010000 +0800" channel="c2">
                <title lang="ru">Спорт</title>
              </programme>
            </tv>
        """.trimIndent()

        val channels = listOf(
            channel("101", "Первый", "c1"),
            channel("202", "Россия 1", "c2"),
        )
        val out = parse(xml, channels)

        val first = out["p1|101"]
        assertNotNull(first, "channel 101 must have programmes")
        assertEquals(2, first.size, "channel 101 must have 2 programmes")
        assertEquals("Новости", first[0].title)
        assertEquals("Фильм", first[1].title)
        assertTrue(first[0].start > 0L, "start must be parsed")
        assertEquals(3_600_000L, first[0].stop - first[0].start, "1-hour programme")

        val second = out["p1|202"]
        assertNotNull(second, "channel 202 must have programmes")
        assertEquals(1, second.size)
        assertEquals("Спорт", second[0].title)
    }

    @Test
    fun descriptionsKeptOnlyInsideWindow() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="c1"><display-name>Первый</display-name></channel>
              <programme start="20260726000000 +0800" stop="20260726010000 +0800" channel="c1">
                <title>Новости</title>
                <desc>Сегодня в программе важные события.</desc>
              </programme>
              <programme start="20260101000000 +0800" stop="20260101010000 +0800" channel="c1">
                <title>Старое</title>
                <desc>Это описание вне окна и не должно храниться.</desc>
              </programme>
            </tv>
        """.trimIndent()
        val channels = listOf(channel("101", "Первый", "c1"))

        // Window covering only the July programme.
        // 2026-07-26 00:00 +0800 == 2026-07-25 16:00 UTC.
        val julyStart = 1_784_995_200_000L
        val out = parse(xml, channels, julyStart - 3_600_000L, julyStart + 3_600_000L)

        val programmes = out["p1|101"] ?: error("must parse")
        assertEquals(2, programmes.size)
        assertEquals("Сегодня в программе важные события.", programmes[0].description)
        assertEquals(null, programmes[1].description, "description outside window must be null")
    }

    @Test
    fun matchesByDisplayNameAndStripsQualitySuffix() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="x1"><display-name>Первый</display-name></channel>
              <programme start="20260726000000 +0800" stop="20260726010000 +0800" channel="x1">
                <title>Новости</title>
              </programme>
            </tv>
        """.trimIndent()

        // No tvg-id on the M3U side: must match by display-name, and "HD"
        // suffix on the playlist name must still match the feed name.
        val channels = listOf(channel("55", "Первый HD", null))
        val out = parse(xml, channels)

        val programmes = out["p1|55"]
        assertNotNull(programmes, "HD-suffixed name must match")
        assertEquals(1, programmes.size)
        assertEquals("Новости", programmes[0].title)
    }

    @Test
    fun normalizesMissingStopTimes() {
        val parser = LiteXmltvParser()
        val programmes = listOf(
            EpgProgramme(channelId = "1", start = 1_000L, stop = 0L, title = "A"),
            EpgProgramme(channelId = "1", start = 3_000L, stop = 4_000L, title = "B"),
        )
        val normalized = parser.normalizeProgramList(programmes)
        assertEquals(2, normalized.size)
        assertEquals(3_000L, normalized[0].stop, "missing stop filled from next start")
        assertEquals(4_000L, normalized[1].stop)
    }
}
