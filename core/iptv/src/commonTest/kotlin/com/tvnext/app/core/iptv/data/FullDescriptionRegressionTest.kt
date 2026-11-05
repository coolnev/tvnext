package com.tvnext.app.core.iptv.data

import kotlin.test.Test
import kotlin.test.assertEquals

class FullDescriptionRegressionTest {
    private fun parse(description: String, full: Boolean, windowStart: Long = Long.MIN_VALUE): String? {
        val xml = """<tv><channel id="c"><display-name>C</display-name></channel>
            <programme channel="c" start="20260907120000 +0000" stop="20260907130000 +0000">
            <title>Передача</title><desc><![CDATA[$description]]></desc></programme></tv>"""
        // Deliberately split Cyrillic, tags and CDATA across scanner reads.
        val chars = xml.toCharArray()
        val source = object : EpgTextSource {
            var offset = 0
            override fun read(into: CharArray, offset: Int, length: Int): Int {
                if (this.offset >= xml.length) return -1
                val count = minOf(37, length, xml.length - this.offset)
                chars.copyInto(into, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }
            override fun close() {}
        }
        val out = EpgBuffer()
        LiteXmltvParser().parse(source, mapOf("c" to mutableListOf("p|c")), "p", out,
            windowStart = windowStart, windowEnd = Long.MAX_VALUE, fullDescriptions = full)
        return out.drain().getValue("p|c").single().description
    }

    @Test fun fullDescriptionSurvivesChunkBoundaries() {
        val text = ("Полное описание. Кириллица, emoji 🎬.\nНовая строка\tи табуляция.\n".repeat(1800)) + "ПОСЛЕДНИЙ АБЗАЦ"
        assertEquals(text, parse(text, true))
    }

    @Test fun archiveDescriptionOutsideOldWindowIsKept() {
        val text = "Архивное описание ".repeat(300) + "КОНЕЦ"
        assertEquals(text, parse(text, true, windowStart = Long.MAX_VALUE))
    }

    @Test fun legacyDesktopLimitIsUnchanged() {
        val text = "Текст ".repeat(300)
        assertEquals(text.take(500), parse(text, false))
    }
}
