package com.tvnext.app.core.iptv.xmltv

import com.tvnext.app.core.iptv.data.EpgTextSource
import com.tvnext.app.core.iptv.data.StringEpgTextSource

/**
 * Streaming XMLTV parser for the subset of XMLTV used in practice.
 *
 * It scans the document WITHOUT building a DOM or a full in-memory String, so
 * it can handle very large files (multi-hundred-MB XMLTV, e.g. vivamax ~487 MB)
 * with bounded memory. The scanner reads the document from an [EpgTextSource]
 * in bounded chunks; [parseStreaming] delivers every `<channel>` / `<programme>`
 * to a callback as it is parsed, so the caller can write batches to the
 * database and never hold the whole document. [parse] materializes a document
 * (tests / small files only).
 *
 * Handles: prolog, comments, CDATA, DOCTYPE/PI, entities, self-closing tags,
 * nested unknown elements, UTF-8 content (already decoded by the caller).
 *
 * Error handling is local: a malformed or truncated document still yields
 * whatever was successfully parsed; only a missing `<tv>` root is a hard error.
 */

/** Outcome of a streaming parse. */
sealed interface XmltvStreamParseResult {
    data object Success : XmltvStreamParseResult
    data class Failure(val reason: String) : XmltvStreamParseResult
}

class XmltvParser {

    /**
     * Parses the document into a materialized [XmltvDocument]. Thin wrapper
     * over the core scan — kept for tests and small documents; large EPG
     * imports must use [parseStreaming] to avoid holding millions of
     * programmes in memory.
     */
    fun parse(text: String): XmltvParseResult {
        val source = StringEpgTextSource(text)
        val channels = ArrayList<EpgChannel>()
        val programmes = ArrayList<EpgProgramme>()
        return when (val result = parseCore(
            source = source,
            onChannel = { channels.add(it) },
            onProgramme = { programmes.add(it) },
        )) {
            is XmltvStreamParseResult.Failure -> XmltvParseResult.Failure(result.reason)
            XmltvStreamParseResult.Success ->
                XmltvParseResult.Success(
                    XmltvDocument(channels = channels, programmes = programmes),
                )
        }
    }

    /**
     * Streaming parse: every parsed `<channel>` / `<programme>` is delivered to
     * the corresponding SUSPEND callback IMMEDIATELY, so the caller can write
     * it in batches to the database without materializing the whole document.
     * Memory stays O(batch), not O(document).
     *
     * Failure is only possible before the first element (empty text / missing
     * `<tv>` root / wrong root); a structurally broken tail simply stops the
     * stream at the last good element (same leniency as before).
     */
    suspend fun parseStreaming(
        source: EpgTextSource,
        onChannel: suspend (EpgChannel) -> Unit,
        onProgramme: suspend (EpgProgramme) -> Unit,
    ): XmltvStreamParseResult {
        val scanner = Scanner(source)
        if (!scanner.skipToRootTv()) {
            return XmltvStreamParseResult.Failure("Не найден корневой элемент <tv>")
        }
        val root = scanner.readOpenTag() ?: return XmltvStreamParseResult.Failure("Некорректный тег <tv>")
        if (root.name != "tv") return XmltvStreamParseResult.Failure("Корневой элемент не <tv>")

        var elements = 0
        while (elements < MAX_ELEMENTS && !scanner.atEnd()) {
            if (!scanner.skipToNextElement()) break
            if (scanner.startsWith("</")) {
                if (scanner.readCloseTag() == "tv") break
                continue
            }
            val open = scanner.readOpenTag() ?: continue
            elements++
            when (open.name) {
                "channel" -> parseChannel(scanner, open)?.let { onChannel(it) }
                "programme" -> parseProgramme(scanner, open)?.let { onProgramme(it) }
                else -> scanner.skipElementContent(open)
            }
        }

        return XmltvStreamParseResult.Success
    }

    /** Non-suspend core scan (used by [parse]; synchronous callbacks). */
    private fun parseCore(
        source: EpgTextSource,
        onChannel: (EpgChannel) -> Unit,
        onProgramme: (EpgProgramme) -> Unit,
    ): XmltvStreamParseResult {
        val scanner = Scanner(source)
        if (!scanner.skipToRootTv()) {
            return XmltvStreamParseResult.Failure("Не найден корневой элемент <tv>")
        }
        val root = scanner.readOpenTag() ?: return XmltvStreamParseResult.Failure("Некорректный тег <tv>")
        if (root.name != "tv") return XmltvStreamParseResult.Failure("Корневой элемент не <tv>")

        var elements = 0
        while (elements < MAX_ELEMENTS && !scanner.atEnd()) {
            if (!scanner.skipToNextElement()) break
            if (scanner.startsWith("</")) {
                if (scanner.readCloseTag() == "tv") break
                continue
            }
            val open = scanner.readOpenTag() ?: continue
            elements++
            when (open.name) {
                "channel" -> parseChannel(scanner, open)?.let { onChannel(it) }
                "programme" -> parseProgramme(scanner, open)?.let { onProgramme(it) }
                else -> scanner.skipElementContent(open)
            }
        }

        return XmltvStreamParseResult.Success
    }

    /**
     * Cheap structural validation (non-empty text + `<tv>` root) WITHOUT parsing
     * the content. Used to decide whether a destructive replace (delete old
     * cache → stream new rows) is safe: a malformed document must not wipe the
     * existing cache. Reads the stream once.
     */
    fun validateStructure(source: EpgTextSource): XmltvParseResult {
        val scanner = Scanner(source)
        if (!scanner.skipToRootTv()) {
            return XmltvParseResult.Failure("Не найден корневой элемент <tv>")
        }
        val root = scanner.readOpenTag() ?: return XmltvParseResult.Failure("Некорректный тег <tv>")
        if (root.name != "tv") return XmltvParseResult.Failure("Корневой элемент не <tv>")
        return XmltvParseResult.Success(XmltvDocument())
    }

    /** [String]-based validation (tests / small documents). */
    fun validateStructure(text: String): XmltvParseResult {
        if (text.isBlank()) return XmltvParseResult.Failure("Пустой XMLTV документ")
        return validateStructure(StringEpgTextSource(text))
    }

    private fun parseChannel(scanner: Scanner, open: Scanner.OpenTag): EpgChannel? {
        val id = open.attributes["id"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        val displayNames = ArrayList<String>()
        var iconUrl: String? = null
        if (open.selfClosing) return EpgChannel(id = id, iconUrl = iconUrl)

        while (!scanner.atEnd()) {
            if (!scanner.skipToNextElement()) break
            if (scanner.startsWith("</")) {
                scanner.readCloseTag()
                break
            }
            val child = scanner.readOpenTag() ?: break
            when (child.name) {
                "display-name" -> {
                    if (!child.selfClosing) {
                        scanner.readTextUntilClose("</display-name>")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { displayNames.add(it) }
                    }
                }
                "icon" -> {
                    if (iconUrl == null) iconUrl = child.attributes["src"]?.trim()
                }
                else -> scanner.skipElementContent(child)
            }
        }
        return EpgChannel(id = id, displayNames = displayNames, iconUrl = iconUrl)
    }

    private fun parseProgramme(scanner: Scanner, open: Scanner.OpenTag): EpgProgramme? {
        val channelId = open.attributes["channel"]?.trim().orEmpty()
        if (channelId.isEmpty()) return null
        val start = XmltvTimestamp.parse(open.attributes["start"] ?: "") ?: return null
        val stop = XmltvTimestamp.parse(open.attributes["stop"] ?: "") ?: return null

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var episodeNum: String? = null

        if (!open.selfClosing) {
            while (!scanner.atEnd()) {
                if (!scanner.skipToNextElement()) break
                if (scanner.startsWith("</")) {
                    scanner.readCloseTag()
                    break
                }
                val child = scanner.readOpenTag() ?: break
                when (child.name) {
                    "title" -> {
                        if (title == null && !child.selfClosing) {
                            title = scanner.readTextUntilClose("</title>").trim().takeIf { it.isNotEmpty() }
                        } else if (!child.selfClosing) {
                            scanner.readTextUntilClose("</title>")
                        }
                    }
                    "desc" -> {
                        if (description == null && !child.selfClosing) {
                            description = scanner.readTextUntilClose("</desc>").trim().takeIf { it.isNotEmpty() }
                        } else if (!child.selfClosing) {
                            scanner.readTextUntilClose("</desc>")
                        }
                    }
                    "category" -> {
                        if (category == null && !child.selfClosing) {
                            category = scanner.readTextUntilClose("</category>").trim().takeIf { it.isNotEmpty() }
                        } else if (!child.selfClosing) {
                            scanner.readTextUntilClose("</category>")
                        }
                    }
                    "episode-num" -> {
                        if (episodeNum == null && !child.selfClosing) {
                            episodeNum = scanner.readTextUntilClose("</episode-num>").trim().takeIf { it.isNotEmpty() }
                        } else if (!child.selfClosing) {
                            scanner.readTextUntilClose("</episode-num>")
                        }
                    }
                    else -> scanner.skipElementContent(child)
                }
            }
        }

        return EpgProgramme(
            channelId = channelId,
            start = start,
            stop = stop,
            title = title ?: "",
            description = description,
            category = category,
            episodeNum = episodeNum,
        )
    }

    /**
     * Forward-only character scanner over an [EpgTextSource] with a bounded
     * sliding buffer. The public surface matches the old String-based scanner,
     * so element parsing is unchanged; memory stays O(buffer) regardless of the
     * document size.
     */
    private class Scanner(private val source: EpgTextSource) {

        private companion object {
            const val BUF_SIZE = 16 * 1024 * 1024
        }

        private var buffer = CharArray(BUF_SIZE)
        /** Absolute position of buffer[0]. */
        private var bufferStart = 0L
        /** Absolute position just past the last available character. */
        private var bufferEnd = 0L
        private var eof = false

        /**
         * When the document is already fully in memory (a [StringEpgTextSource]
         * — tests, or a desktop import of a file that fits in the heap), the
         * scanner switches to fast paths built on the JVM's native
         * `String.indexOf` / `String.startsWith`, which are 5-10x faster than
         * the char-by-char sliding-buffer loops for the dominant scans
         * (finding `<`, finding `</tag>`, extracting text).
         */
        private val fastText: String? = (source as? StringEpgTextSource)?.text

        /** Absolute read position (monotonic). */
        var pos = 0L

        fun atEnd(): Boolean {
            fastText?.let { t -> return pos >= t.length }
            while (pos >= bufferEnd) {
                if (!loadMore()) return true
            }
            return false
        }

        /** Shifts the unconsumed tail to the front and refills the buffer. */
        private fun loadMore(): Boolean {
            if (eof) return false
            val tailStart = (pos - bufferStart).toInt().coerceIn(0, buffer.size)
            val tailCount = (bufferEnd - pos).toInt().coerceIn(0, buffer.size - tailStart)
            // When the buffer is completely full of unconsumed content
            // (pos == bufferStart and bufferEnd == bufferStart + size), there is
            // nothing to shift and no room to read: a forward indexOf over more
            // than a buffer's worth of un-scanned text/comments/DOCTYPE would
            // otherwise return -1 and silently truncate the parse. Grow instead
            // so the scan can continue.
            if (tailCount >= buffer.size) {
                buffer = buffer.copyOf(buffer.size * 2)
            }
            for (i in 0 until tailCount) buffer[i] = buffer[tailStart + i]
            bufferStart = pos
            val n = source.read(buffer, tailCount, buffer.size - tailCount)
            if (n <= 0) {
                eof = true
                bufferEnd = bufferStart + tailCount
                return false
            }
            bufferEnd = bufferStart + tailCount + n
            return true
        }

        /** Character at an ABSOLUTE position (never rewinds). */
        private fun charAt(abs: Long): Char? {
            fastText?.let { t -> return t.getOrNull(abs.toInt()) }
            while (abs >= bufferEnd) {
                if (!loadMore()) return null
            }
            // Positions before the sliding buffer were evicted. Callers must
            // only pass the current read position (`pos`) or a forward offset;
            // this guard turns a would-be ArrayIndexOutOfBoundsException into a
            // clean null (defensive — the parser no longer looks back).
            if (abs < bufferStart) return null
            return buffer[(abs - bufferStart).toInt()]
        }

        fun startsWith(prefix: String): Boolean {
            fastText?.let { t -> return t.startsWith(prefix, pos.toInt()) }
            for (i in prefix.indices) {
                val c = charAt(pos + i) ?: return false
                if (c != prefix[i]) return false
            }
            return true
        }

        /** Skips whitespace/comments/PIs/DOCTYPE to the next real element start (`<`). */
        fun skipToNextElement(): Boolean {
            val t = fastText
            if (t != null) {
                val len = t.length
                var i = pos.toInt()
                while (true) {
                    if (i >= len) {
                        pos = len.toLong()
                        return false
                    }
                    val c = t[i]
                    if (c.isWhitespace()) {
                        i++
                        continue
                    }
                    if (c == '<') {
                        if (t.startsWith("<!--", i)) {
                            val end = t.indexOf("-->", i)
                            if (end < 0) {
                                pos = len.toLong()
                                return false
                            }
                            i = end + 3
                            continue
                        }
                        if (t.startsWith("<?", i) ||
                            t.startsWith("<!DOCTYPE", i) ||
                            t.startsWith("<!doctype", i)
                        ) {
                            val end = t.indexOf('>', i)
                            if (end < 0) {
                                pos = len.toLong()
                                return false
                            }
                            i = end + 1
                            continue
                        }
                        pos = i.toLong()
                        return true
                    }
                    val next = t.indexOf('<', i)
                    if (next < 0) {
                        pos = len.toLong()
                        return false
                    }
                    i = next
                }
            }
            while (true) {
                val c = charAt(pos)
                if (c == null) return false
                if (c.isWhitespace()) {
                    pos++
                    continue
                }
                if (c == '<') {
                    if (startsWith("<!--")) {
                        val end = indexOf("-->", pos)
                        if (end < 0) {
                            pos = bufferEnd
                            return false
                        }
                        pos = end + 3
                        continue
                    }
                    if (startsWith("<?") ||
                        startsWith("<!DOCTYPE") ||
                        startsWith("<!doctype")
                    ) {
                        val end = indexOf('>', pos)
                        if (end < 0) {
                            pos = bufferEnd
                            return false
                        }
                        pos = end + 1
                        continue
                    }
                    return true
                }
                val next = indexOf('<', pos)
                if (next < 0) {
                    pos = bufferEnd
                    return false
                }
                pos = next
            }
        }

        /** Skips leading content until the `<tv` root tag. Leaves pos AT `<tv`. */
        fun skipToRootTv(): Boolean {
            if (!skipToNextElement()) return false
            if (!startsWith("<tv")) return false
            val afterName = pos + 3
            val c = charAt(afterName)
            return c == null || c == '>' || c == '/' || c.isWhitespace()
        }

        data class OpenTag(
            val name: String,
            val attributes: Map<String, String>,
            val selfClosing: Boolean,
        )

        /**
         * Reads a tag name up to whitespace/`>`/`/`. Direct buffer access —
         * the lambda-parameterised readToken allocated a Function1 per call and
         * the token loop is one of the two hottest paths in the import.
         */
        private fun readTagName(): String {
            val sb = StringBuilder(8)
            while (true) {
                val c = charAt(pos) ?: return sb.toString()
                if (c.isWhitespace() || c == '>' || c == '/') return sb.toString()
                sb.append(c)
                pos++
            }
        }

        /** Reads an attribute key up to whitespace/`=`/`>`/`/`. */
        private fun readAttrKey(): String {
            val sb = StringBuilder(8)
            while (true) {
                val c = charAt(pos) ?: return sb.toString()
                if (c.isWhitespace() || c == '=' || c == '>' || c == '/') return sb.toString()
                sb.append(c)
                pos++
            }
        }

        /** Reads a quoted attribute value up to [quote]. */
        private fun readAttrValue(quote: Char): String {
            val sb = StringBuilder(12)
            while (true) {
                val c = charAt(pos) ?: return sb.toString()
                if (c == quote) return sb.toString()
                sb.append(c)
                pos++
            }
        }

        /** Reads a closing-tag name up to `>`. */
        private fun readCloseName(): String {
            val sb = StringBuilder(8)
            while (true) {
                val c = charAt(pos) ?: return sb.toString()
                if (c == '>') return sb.toString()
                sb.append(c)
                pos++
            }
        }

        /** Reads an unquoted attribute value up to whitespace/`>`. */
        private fun readUnquotedValue(): String {
            val sb = StringBuilder(12)
            while (true) {
                val c = charAt(pos) ?: return sb.toString()
                if (c.isWhitespace() || c == '>') return sb.toString()
                sb.append(c)
                pos++
            }
        }

        /** Reads an opening tag starting at `<`. Consumes through `>` or `/>`. */
        fun readOpenTag(): OpenTag? {
            if (charAt(pos) != '<') return null
            pos++
            val name = readTagName()
            if (name.isEmpty()) return null
            val attributes = readAttributes()
            var selfClosing = false
            if (charAt(pos) == '/') {
                selfClosing = true
                pos++
            }
            if (charAt(pos) == '>') pos++
            return OpenTag(name = name, attributes = attributes, selfClosing = selfClosing)
        }

        private fun readAttributes(): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            while (true) {
                while (true) {
                    val c = charAt(pos) ?: return result
                    if (!c.isWhitespace()) break
                    pos++
                }
                val c = charAt(pos) ?: return result
                if (c == '>' || c == '/') break
                val key = readAttrKey()
                while (true) {
                    val cc = charAt(pos) ?: break
                    if (!cc.isWhitespace()) break
                    pos++
                }
                var value = ""
                if (charAt(pos) == '=') {
                    pos++
                    while (true) {
                        val cc = charAt(pos) ?: break
                        if (!cc.isWhitespace()) break
                        pos++
                    }
                    val q = charAt(pos)
                    if (q == '"' || q == '\'') {
                        pos++
                        value = readAttrValue(q)
                        // Consume the closing quote.
                        if (charAt(pos) == q) pos++
                    } else {
                        value = readUnquotedValue()
                    }
                }
                if (key.isNotEmpty()) result[key] = decodeEntities(value)
            }
            return result
        }

        /** Reads the element content up to `</name>` (close tag text must match). */
        fun readTextUntilClose(closeTag: String): String {
            val t = fastText
            if (t != null) {
                // Fast path: one native indexOf over the whole document, then a
                // single substring — no buffer sliding, no chunk copying.
                val start = pos.toInt()
                val end = t.indexOf(closeTag, start)
                pos = if (end >= 0) {
                    (end + closeTag.length).toLong()
                } else {
                    t.length.toLong()
                }
                return decodeContent(if (end >= 0) t.substring(start, end) else t.substring(start))
            }
            // Bulk path: the close tag is located with an in-buffer indexOf and
            // the text is appended in large chunks, instead of char-by-char
            // accumulation. Reading a multi-hundred-MB document with long
            // descriptions is one of the two dominant import costs; the
            // character-by-character loop made it ~10 MB/s.
            val sb = StringBuilder(64)
            while (true) {
                if (pos >= bufferEnd && !loadMore()) break
                if (pos >= bufferEnd) break
                val end = indexOf(closeTag, pos)
                if (end >= 0) {
                    appendRange(sb, pos, end)
                    pos = end + closeTag.length
                    return decodeContent(sb.toString())
                }
                appendRange(sb, pos, bufferEnd)
                pos = bufferEnd
            }
            return decodeContent(sb.toString())
        }

        /** Appends the currently buffered absolute range [from, to) to [sb]. */
        private fun appendRange(sb: StringBuilder, from: Long, to: Long) {
            val t = fastText
            if (t != null) {
                sb.append(t, from.toInt(), to.toInt())
                return
            }
            val start = (from - bufferStart).toInt().coerceIn(0, buffer.size)
            val count = (to - from).toInt().coerceIn(0, buffer.size - start)
            if (count > 0) sb.append(buffer, start, count)
        }

        /** Reads a closing tag starting at `</`. Returns the tag name. */
        fun readCloseTag(): String? {
            if (!startsWith("</")) return null
            pos += 2
            val name = readCloseName().trim()
            if (charAt(pos) == '>') pos++
            return name
        }

        /** Skips a whole element (including nested children) given its already-read open tag. */
        fun skipElementContent(open: OpenTag) {
            if (open.selfClosing) return
            var depth = 1
            while (depth > 0 && !atEnd()) {
                if (!skipToNextElement()) break
                if (startsWith("</")) {
                    readCloseTag()
                    depth--
                } else {
                    val child = readOpenTag()
                    if (child == null) break
                    if (!child.selfClosing) depth++
                }
            }
        }

        private fun indexOf(ch: Char, from: Long): Long {
            fastText?.let { t -> return t.indexOf(ch, from.toInt()).toLong() }
            var i = from
            while (true) {
                while (i < bufferEnd) {
                    if (buffer[(i - bufferStart).toInt()] == ch) return i
                    i++
                }
                if (eof) return -1
                if (!loadMore()) return -1
            }
        }

        private fun indexOf(str: String, from: Long): Long {
            fastText?.let { t -> return t.indexOf(str, from.toInt()).toLong() }
            val n = str.length
            if (n == 0) return from
            var i = from
            while (true) {
                // Ensure [i, i+n) is fully buffered (may cross chunk boundaries).
                while (i + n > bufferEnd) {
                    if (eof) return -1
                    if (!loadMore()) return -1
                }
                if (buffer[(i - bufferStart).toInt()] == str[0]) {
                    var match = true
                    for (k in 1 until n) {
                        if (buffer[(i + k - bufferStart).toInt()] != str[k]) {
                            match = false
                            break
                        }
                    }
                    if (match) return i
                }
                i++
            }
        }

        private fun decodeContent(raw: String): String {
            if (!raw.contains("<![CDATA[")) return decodeEntities(raw)
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val cdata = raw.indexOf("<![CDATA[", i)
                if (cdata < 0) {
                    sb.append(raw.substring(i))
                    break
                }
                sb.append(raw, i, cdata)
                val end = raw.indexOf("]]>", cdata)
                if (end < 0) {
                    sb.append(raw.substring(cdata + 9))
                    i = raw.length
                } else {
                    sb.append(raw, cdata + 9, end)
                    i = end + 3
                }
            }
            return decodeEntities(sb.toString())
        }

        private fun decodeEntities(s: String): String {
            if (!s.contains('&')) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i)
                    if (semi > i && semi - i <= 10) {
                        val entity = s.substring(i + 1, semi)
                        val decoded = when {
                            entity == "amp" -> "&"
                            entity == "lt" -> "<"
                            entity == "gt" -> ">"
                            entity == "quot" -> "\""
                            entity == "apos" -> "'"
                            entity.length >= 2 && entity[0] == '#' && entity[1] == 'x' ->
                                entity.substring(2).toIntOrNull(16)?.let(::charFromCodePoint)
                            entity.length >= 2 && entity[0] == '#' && entity[1] == 'X' ->
                                entity.substring(2).toIntOrNull(16)?.let(::charFromCodePoint)
                            entity.startsWith("#") ->
                                entity.substring(1).toIntOrNull()?.let(::charFromCodePoint)
                            else -> null
                        }
                        if (decoded != null) {
                            sb.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun charFromCodePoint(cp: Int): String = when {
            cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF -> {
                if (cp > 0xFFFF) {
                    val v = cp - 0x10000
                    val high = (0xD800 + (v shr 10)).toChar()
                    val low = (0xDC00 + (v and 0x3FF)).toChar()
                    String(charArrayOf(high, low))
                } else {
                    cp.toChar().toString()
                }
            }
            else -> "\uFFFD"
        }
    }

    private companion object {
        /**
         * Bounds for pathological documents. Real providers ship large EPGs:
         * vivamax.pro's XMLTV carries ~1.46M programmes (2k+ channels × weeks
         * of programmes) in a ~487 MB decompressed document. The cap tracks
         * MAX_DECOMPRESSED_BYTES in EpgIo.{android,desktop}.kt (2 GB) so a
         * multi-GB EPG is not silently truncated; the scanner is O(n) and
         * memory stays O(buffer) regardless of the cap.
         */
        const val MAX_ELEMENTS = 12_000_000
    }
}
