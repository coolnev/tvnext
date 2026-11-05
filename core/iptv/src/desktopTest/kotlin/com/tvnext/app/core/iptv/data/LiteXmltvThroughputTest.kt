package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteXmltvThroughputTest {
    @Test fun largeChannelGroupedFeedIsReadCompletelyWithFullDescriptions() {
        val path = Files.createTempFile("xmltv-throughput", ".xml").toFile()
        val entries = 50_000
        try {
            path.bufferedWriter().use { out ->
                out.write("<tv><channel id='news'><display-name>Новости</display-name></channel>")
                val desc = "Полное описание передачи. ".repeat(24)
                repeat(entries) { i -> out.write("<programme channel='news' start='20261105180000 +0000' stop='20261105190000 +0000'><title>Передача $i</title><desc>$desc</desc></programme>") }
                out.write("</tv>")
            }
            val parser = LiteXmltvParser()
            val lookup = parser.buildChannelLookup(listOf(Channel("c", "Новости", 1, "", "https://unused.invalid/live", tvgId = "news", playlistId = "p")))
            var count = 0
            val started = System.nanoTime()
            val complete = openEpgTextSource(path.path).use { source -> parser.parse(source, lookup, "p", object : EpgProgrammeSink {
                override fun add(key: String, programme: EpgProgramme) {
                    count++
                    assertTrue(programme.description.orEmpty().length > 500)
                }
            }, fullDescriptions = true) }
            println("XMLTV_BENCHMARK entries=$count bytes=${path.length()} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
            assertTrue(complete)
            assertEquals(entries, count)
        } finally { path.delete() }
    }
}
