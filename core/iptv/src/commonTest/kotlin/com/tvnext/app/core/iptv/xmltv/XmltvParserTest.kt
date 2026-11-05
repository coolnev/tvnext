package com.tvnext.app.core.iptv.xmltv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {

    private val parser = XmltvParser()

    private fun parseSuccess(content: String): XmltvDocument {
        val result = parser.parse(content)
        val success = assertIs<XmltvParseResult.Success>(result)
        return success.document
    }

    @Test
    fun parsesChannelsAndProgrammes() {
        val document = parseSuccess(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="test">
              <channel id="channel.one">
                <display-name>Первый канал</display-name>
                <display-name>Channel One</display-name>
                <icon src="http://example.com/1.png"/>
              </channel>
              <channel id="channel.two"><display-name>Россия 1</display-name></channel>
              <programme start="20260701090000 +0300" stop="20260701100000 +0300" channel="channel.one">
                <title lang="ru">Новости</title>
                <desc lang="ru">Вечерние новости</desc>
                <category lang="ru">Информация</category>
              </programme>
              <programme start="20260701100000 +0300" stop="20260701110000 +0300" channel="channel.one">
                <title>Время</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals(2, document.channels.size)
        assertEquals("channel.one", document.channels[0].id)
        assertEquals(listOf("Первый канал", "Channel One"), document.channels[0].displayNames)
        assertEquals("http://example.com/1.png", document.channels[0].iconUrl)

        assertEquals(2, document.programmes.size)
        val first = document.programmes[0]
        assertEquals("channel.one", first.channelId)
        assertEquals("Новости", first.title)
        assertEquals("Вечерние новости", first.description)
        assertEquals("Информация", first.category)
        assertNull(first.episodeNum)
        assertEquals(XmltvTimestamp.parse("20260701090000 +0300"), first.start)
        assertEquals(XmltvTimestamp.parse("20260701100000 +0300"), first.stop)
    }

    @Test
    fun handlesMissingOptionalFields() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="bare"><display-name>Без логотипа</display-name></channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="bare">
                <title>Только название</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals(1, document.channels.size)
        assertNull(document.channels[0].iconUrl)
        assertEquals(1, document.programmes.size)
        assertNull(document.programmes[0].description)
        assertNull(document.programmes[0].category)
        assertNull(document.programmes[0].episodeNum)
    }

    @Test
    fun handlesUtcOffsetAndColonTimezones() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="c"><display-name>Канал</display-name></channel>
              <programme start="20260701120000 +00:00" stop="20260701130000 +0300" channel="c">
                <title>Таймзоны</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        val programme = document.programmes.single()
        assertEquals(XmltvTimestamp.parse("20260701120000 +00:00"), programme.start)
        assertEquals(XmltvTimestamp.parse("20260701130000 +0300"), programme.stop)
    }

    @Test
    fun handlesEntitiesAndCdata() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="ent"><display-name>A &amp; B &lt;HD&gt;</display-name></channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="ent">
                <title><![CDATA[Новости & «Спорт»]]></title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals("A & B <HD>", document.channels.single().displayNames.single())
        assertEquals("Новости & «Спорт»", document.programmes.single().title)
    }

    @Test
    fun skipsUnknownElements() {
        val document = parseSuccess(
            """
            <tv>
              <generator-info-name>Some Generator</generator-info-name>
              <channel id="c">
                <display-name>Канал</display-name>
                <unexpected-el><nested/></unexpected-el>
              </channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="c">
                <title>Программа</title>
                <custom-tag>text</custom-tag>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals(1, document.channels.size)
        assertEquals(1, document.programmes.size)
        assertEquals("Программа", document.programmes.single().title)
    }

    @Test
    fun handlesCommentsAndDoctype() {
        val document = parseSuccess(
            """
            <!-- leading comment -->
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <!-- channel comment -->
              <channel id="c"><display-name>Канал</display-name></channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="c">
                <title>Программа</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals(1, document.channels.size)
        assertEquals(1, document.programmes.size)
    }

    @Test
    fun truncatedDocumentYieldsParsedPortion() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="c"><display-name>Канал</display-name></channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="c">
                <title>Усечённая программа
            """.trimIndent(),
        )
        // The channel parsed fine; the truncated programme is dropped or partial —
        // but parsing must not throw and must not lose the channel.
        assertEquals(1, document.channels.size)
        assertTrue(document.programmes.size <= 1)
    }

    @Test
    fun malformedWithoutTvRootIsFailure() {
        val result = parser.parse("<html><body>not xmltv</body></html>")
        assertIs<XmltvParseResult.Failure>(result)
    }

    @Test
    fun emptyDocumentIsFailure() {
        assertIs<XmltvParseResult.Failure>(parser.parse("   "))
    }

    @Test
    fun selfClosingChannelWithoutChildren() {
        val document = parseSuccess("<tv><channel id=\"lonely\"/></tv>")
        assertEquals(1, document.channels.size)
        assertEquals("lonely", document.channels[0].id)
        assertTrue(document.channels[0].displayNames.isEmpty())
    }

    @Test
    fun programmeWithoutValidTimestampsIsSkipped() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="c"><display-name>Канал</display-name></channel>
              <programme start="invalid" stop="20260701100000 +0000" channel="c">
                <title>Плохие таймстампы</title>
              </programme>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="c">
                <title>Хорошая</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals(1, document.programmes.size)
        assertEquals("Хорошая", document.programmes[0].title)
    }

    @Test
    fun episodeNumIsParsed() {
        val document = parseSuccess(
            """
            <tv>
              <channel id="c"><display-name>Канал</display-name></channel>
              <programme start="20260701090000 +0000" stop="20260701100000 +0000" channel="c">
                <title>Сериал</title>
                <episode-num system="onscreen">S1 E2</episode-num>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertEquals("S1 E2", document.programmes.single().episodeNum)
    }

    @Test
    fun largeDocumentParsesFastEnough() {
        val sb = StringBuilder("<tv>")
        repeat(200) { i ->
            sb.append("<channel id=\"c$i\"><display-name>Канал $i</display-name></channel>")
        }
        repeat(20_000) { i ->
            sb.append(
                "<programme start=\"20260701090000 +0000\" stop=\"20260701100000 +0000\" channel=\"c${i % 200}\">" +
                    "<title>Программа $i</title><desc>Описание $i</desc></programme>",
            )
        }
        sb.append("</tv>")
        val document = parseSuccess(sb.toString())
        assertEquals(200, document.channels.size)
        assertEquals(20_000, document.programmes.size)
    }

    @Test
    fun tokensStraddlingThe4mbBufferBoundaryAreParsed() {
        // The streaming scanner slides over the document with a 4 MB buffer.
        // A tag name / attribute whose characters straddle a buffer refill used
        // to crash with ArrayIndexOutOfBoundsException: the parser captured a
        // start position and re-read it AFTER the buffer had already shifted
        // past it. Build a document LARGER than the buffer so many tokens cross
        // the boundary, and assert every programme still survives.
        val sb = StringBuilder("<tv>")
        repeat(300) { i ->
            sb.append("<channel id=\"c$i\"><display-name>Канал $i</display-name></channel>")
        }
        val total = 40_000
        repeat(total) { i ->
            sb.append(
                "<programme start=\"20260701090000 +0000\" stop=\"20260701100000 +0000\" channel=\"c${i % 300}\">" +
                    "<title>Программа $i</title><desc>Описание $i с достаточно длинным текстом для границы буфера</desc></programme>",
            )
        }
        sb.append("</tv>")
        val document = parseSuccess(sb.toString())
        assertEquals(300, document.channels.size)
        assertEquals(total, document.programmes.size)
        assertEquals("Программа ${total - 1}", document.programmes.last().title)
    }

    @Test
    fun hugeCommentBeyondTheBufferDoesNotTruncateParsing() {
        // A comment longer than the 4 MB buffer used to make indexOf("-->")
        // return -1 (the buffer could not refill while still full), silently
        // ending the parse. The buffer must grow instead so the comment is
        // skipped and the following element is still parsed.
        val sb = StringBuilder("<tv>")
        sb.append("<channel id=\"first\"><display-name>Первый</display-name></channel>")
        sb.append("<!--")
        repeat(4 * 1024 * 1024 + 1024) { sb.append('x') } // > 4 MB comment body
        sb.append("-->")
        sb.append("<channel id=\"second\"><display-name>Второй</display-name></channel>")
        sb.append("</tv>")
        val document = parseSuccess(sb.toString())
        assertEquals(listOf("first", "second"), document.channels.map { it.id })
    }
}
