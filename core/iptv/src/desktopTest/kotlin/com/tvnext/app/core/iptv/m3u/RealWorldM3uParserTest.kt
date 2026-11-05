package com.tvnext.app.core.iptv.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealWorldM3uParserTest {

    private val parser = M3uParser()

    @Test
    fun parsesRealWorldPlaylist() {
        val content = javaClass.classLoader.getResourceAsStream("real-world-ru.m3u")
            ?.readBytes()
            ?.decodeToString()
            ?: error("Fixture not found")
        val result = parser.parse(content)
        val success = assertIs<M3uParseResult.Success>(result)
        assertTrue(success.playlist.channels.isNotEmpty())
        assertTrue(success.playlist.channels.size > 50)

        val first = success.playlist.channels.first()
        assertEquals("2x2 (576i)", first.name)
        assertTrue(first.streamUrl.startsWith("https://"))
        assertEquals("Entertainment", first.groupTitle)
        assertEquals("2x2.ru@SD", first.tvgId)
        assertTrue(first.tvgLogo.orEmpty().startsWith("http"))

        val withTvgId = success.playlist.channels.count { it.tvgId != null }
        assertTrue(withTvgId > 50, "expected many channels with tvg-id, got $withTvgId")
    }
}
