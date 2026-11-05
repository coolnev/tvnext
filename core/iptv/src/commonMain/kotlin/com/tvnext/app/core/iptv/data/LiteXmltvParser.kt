package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.log.Logger
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme

/**
 * Streaming XMLTV parser (LiteTV port). Reads the document from an
 * [EpgTextSource] with a sliding-buffer scanner, keeps only the programmes of
 * OUR channels. Android retains full descriptions; the legacy desktop path
 * keeps its existing description window and limit. The full XML is never materialized.
 */
internal class LiteXmltvParser {

    /**
     * Streams the feed and fills [out] (`playlistId|channelId` -> programmes).
     * Channel matching is by tvg-id, then by normalised display name (with
     * quality suffixes like "4K"/"HD" stripped), exactly like LiteTV.
     *
     * Descriptions are kept only for programmes whose start falls inside
     * [windowStart]..[windowEnd] (a bounded time window around "now") — that
     * keeps memory proportional to what the user can actually read, not to the
     * whole multi-hundred-MB archive. Titles are always kept.
     */
    fun parse(
        source: EpgTextSource,
        lookup: Map<String, MutableList<String>>,
        playlistId: String,
        out: EpgProgrammeSink,
        windowStart: Long = Long.MIN_VALUE,
        windowEnd: Long = Long.MAX_VALUE,
        fullDescriptions: Boolean = com.tvnext.app.core.common.platform.isAndroidPlatform(),
    ): Boolean {
        val scanner = XmlScanner(source)
        val xmltvIdToKeys = HashMap<String, List<String>>()
        var parsedChannels = 0
        var parsedProgrammes = 0

        while (true) {
            val tag = scanner.nextElementTag() ?: break
            if (tag == "channel") {
                val element = scanner.extractElement("channel") ?: return false
                parsedChannels++
                parseChannelElement(element, lookup, xmltvIdToKeys)
            } else {
                val element = scanner.extractElement("programme") ?: return false
                parsedProgrammes++
                parseProgrammeElement(element, playlistId, xmltvIdToKeys, lookup, out, windowStart, windowEnd, fullDescriptions)
                // Every 256 entries, give Android playback threads a small CPU
                // window. Desktop stays unchanged through its platform actual.
                if (parsedProgrammes % 256 == 0) paceEpgParser()
            }
        }
        Logger.d(TAG, "Parsed $parsedChannels channels / $parsedProgrammes programmes")
        return scanner.closedDocument
    }

    /** Builds a normalised name/id -> channel keys index (ported from LiteTV). */
    fun buildChannelLookup(channels: List<Channel>): Map<String, MutableList<String>> {
        val lookup = HashMap<String, MutableList<String>>()
        for (channel in channels) {
            val key = "${channel.playlistId}|${channel.id}"
            addLookupToken(lookup, channel.tvgId, key)
            addLookupToken(lookup, channel.tvgName, key)
            addLookupToken(lookup, channel.name, key)
        }
        return lookup
    }

    private fun addLookupToken(lookup: MutableMap<String, MutableList<String>>, token: String?, key: String) {
        val normalized = normalize(token)
        if (normalized.isEmpty()) return
        lookup.getOrPut(normalized) { ArrayList(1) }.let { if (key !in it) it.add(key) }
        val stripped = stripQualitySuffix(normalized)
        if (stripped != normalized) {
            lookup.getOrPut(stripped) { ArrayList(1) }.let { if (key !in it) it.add(key) }
        }
    }

    private fun matchChannelKeys(lookup: Map<String, MutableList<String>>, token: String?): List<String>? {
        val normalized = normalize(token)
        if (normalized.isEmpty()) return null
        lookup[normalized]?.let { return it }
        return lookup[stripQualitySuffix(normalized)]
    }

    /** Lowercase, separators feeds/playlists disagree about removed (ported from LiteTV). */
    private fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val sb = StringBuilder(value.length)
        for (c in value) {
            if (c == ' ' || c == '\t' || c == '-' || c == '_' || c == '.' || c == '\u00b7') continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun stripQualitySuffix(normalized: String): String {
        for (suffix in QUALITY_SUFFIXES) {
            if (normalized.length > suffix.length && normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length - suffix.length)
            }
        }
        return normalized
    }

    private fun parseChannelElement(
        element: String,
        lookup: Map<String, MutableList<String>>,
        xmltvIdToKeys: MutableMap<String, List<String>>,
    ) {
        val gt = element.indexOf('>')
        if (gt < 0) return
        val open = element.substring(0, gt + 1)
        val id = decodeXml(attr(open, "id"))
        var keys = matchChannelKeys(lookup, id)

        // XMLTV channels can have several <display-name> aliases. Do not
        // discard a valid second/third alias when the first one differs from M3U.
        if (keys == null) {
            var cursor = gt + 1
            while (true) {
                val dn = element.indexOf("<display-name", cursor)
                if (dn < 0) break
                val dgt = element.indexOf('>', dn)
                if (dgt < 0) break
                val end = element.indexOf("</display-name", dgt + 1)
                if (end < 0) break
                val name = decodeXml(element.substring(dgt + 1, end))?.trim()
                keys = matchChannelKeys(lookup, name)
                if (keys != null) break
                cursor = end + 14
            }
        }
        if (keys != null && !id.isNullOrEmpty() && !xmltvIdToKeys.containsKey(id)) {
            xmltvIdToKeys[id] = keys
        }
    }

    private fun parseProgrammeElement(
        element: String,
        playlistId: String,
        xmltvIdToKeys: Map<String, List<String>>,
        lookup: Map<String, MutableList<String>>,
        out: EpgProgrammeSink,
        windowStart: Long,
        windowEnd: Long,
        fullDescriptions: Boolean,
    ) {
        val gt = element.indexOf('>')
        if (gt < 0) return
        val open = element.substring(0, gt + 1)
        val xmltvId = decodeXml(attr(open, "channel")) ?: return
        // Some valid feeds omit <channel> declarations and use M3U tvg-id
        // directly on programmes. These used to silently import zero rows.
        val keys = xmltvIdToKeys[xmltvId] ?: matchChannelKeys(lookup, xmltvId) ?: return
        val title = childText(element, gt + 1, "title") ?: return
        if (title.isEmpty()) return
        val start = parseXmltvTime(decodeXml(attr(open, "start"))) ?: return
        if (start <= 0L) return
        val stop = parseXmltvTime(decodeXml(attr(open, "stop"))) ?: 0L

        // Description is kept (bounded) only inside the visible time window.
        val description = if (fullDescriptions) {
            childText(element, gt + 1, "desc")
        } else if (start in windowStart..windowEnd) {
            childText(element, gt + 1, "desc")?.take(
                if (com.tvnext.app.core.common.platform.isAndroidPlatform()) 600 else MAX_DESC_LENGTH
            )
        } else {
            null
        }
        val category = childText(element, gt + 1, "category")?.take(MAX_CATEGORY_LENGTH)

        for (key in keys) {
            val sep = key.indexOf('|')
            if (sep < 0) continue
            val channelId = key.substring(sep + 1)
            out.add(
                key,
                EpgProgramme(
                    channelId = channelId,
                    start = start,
                    stop = stop,
                    title = title,
                    description = description,
                    category = category,
                ),
            )
        }
    }

    private fun attr(openTag: String, name: String): String? {
        var p = openTag.indexOf(name)
        while (p >= 0) {
            val before = if (p == 0) ' ' else openTag[p - 1]
            if (before.isWhitespace()) {
                var q = p + name.length
                while (q < openTag.length && openTag[q].isWhitespace()) q++
                if (q >= openTag.length || openTag[q] != '=') {
                    p = openTag.indexOf(name, p + name.length)
                    continue
                }
                q++
                while (q < openTag.length && openTag[q].isWhitespace()) q++
                if (q < openTag.length && (openTag[q] == '"' || openTag[q] == '\'')) {
                    val quote = openTag[q]
                    val end = openTag.indexOf(quote, q + 1)
                    if (end > q) return openTag.substring(q + 1, end)
                }
            }
            p = openTag.indexOf(name, p + 1)
        }
        return null
    }

    private fun displayName(element: String, from: Int): String? {
        val dn = element.indexOf("<display-name", from)
        if (dn < 0) return null
        val dgt = element.indexOf('>', dn)
        if (dgt < 0) return null
        val dc = element.indexOf("</display-name", dgt + 1)
        if (dc < 0) return null
        return decodeXml(element.substring(dgt + 1, dc))?.trim()
    }

    private fun childText(element: String, from: Int, name: String): String? {
        val open = "<$name"
        var p = element.indexOf(open, from)
        while (p >= 0) {
            val gt = element.indexOf('>', p + open.length)
            if (gt < 0) return null
            val close = "</$name"
            val c = element.indexOf(close, gt + 1)
            if (c < 0) return null
            val text = element.substring(gt + 1, c)
            val cleaned = if (text.startsWith("<![CDATA[") && text.endsWith("]]>")) {
                text.substring(9, text.length - 3)
            } else {
                text
            }
            return decodeXml(cleaned)?.trim()
        }
        return null
    }

    private fun decodeXml(value: String?): String? {
        if (value == null) return null
        var s = value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        // Numeric entities (&#123; / &#x1f;).
        var p = s.indexOf("&#")
        while (p >= 0) {
            val e = s.indexOf(';', p + 2)
            if (e < 0) break
            val num = s.substring(p + 2, e)
            val cp = num.removePrefix("x").removePrefix("X").toIntOrNull(16) ?: num.toIntOrNull(10)
            if (cp != null) {
                s = s.substring(0, p) + cp.toChar() + s.substring(e + 1)
                p = s.indexOf("&#", p + 1)
            } else {
                p = s.indexOf("&#", e + 1)
            }
        }
        return s
    }

    /** Sorts by start, drops repeats, and fills missing stop times (LiteTV port). */
    fun normalizeProgramList(list: List<EpgProgramme>): List<EpgProgramme> {
        if (list.size < 2) {
            val single = list.firstOrNull() ?: return emptyList()
            val fixed = if (single.stop > single.start) single else single.copy(stop = single.start + DEFAULT_PROGRAM_DURATION_MS)
            return listOf(fixed)
        }
        val sorted = list.sortedBy { it.start }
        val kept = ArrayList<EpgProgramme>(sorted.size)
        for (programme in sorted) {
            val last = kept.lastOrNull()
            if (last != null && last.start == programme.start && last.title == programme.title) {
                if (last.stop <= last.start && programme.stop > programme.start) {
                    kept[kept.size - 1] = programme
                }
                continue
            }
            kept.add(programme)
        }
        for (i in kept.indices) {
            val programme = kept[i]
            if (programme.stop > programme.start) continue
            kept[i] = programme.copy(
                stop = if (i + 1 < kept.size) kept[i + 1].start else programme.start + DEFAULT_PROGRAM_DURATION_MS,
            )
        }
        return kept
    }

    // -------------------------------------------------------------- XMLTV time

    /**
     * Parses `20260726004600 +0800` (offset honoured) or `20260726004600`
     * (offset-less = device local time). Ported from LiteTV.
     */
    private fun parseXmltvTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        var digits = 0
        while (digits < text.length && text[digits].isDigit()) digits++
        if (digits < 8) return null
        val year = number(text, 0, 4)
        val month = number(text, 4, 6)
        val day = number(text, 6, 8)
        if (month < 1 || month > 12 || day < 1 || day > 31) return null
        val secondsOfDay = (if (digits >= 10) number(text, 8, 10) else 0) * 3600L +
            (if (digits >= 12) number(text, 10, 12) else 0) * 60L +
            (if (digits >= 14) number(text, 12, 14) else 0)
        val utcSeconds = daysFromEpoch(year, month, day) * 86400L + secondsOfDay

        val offset = parseUtcOffset(text.substring(digits).trim())
        return if (offset != null) {
            (utcSeconds - offset) * 1000L
        } else {
            val asIfUtc = utcSeconds * 1000L
            asIfUtc - localTimezoneOffsetMs(asIfUtc)
        }
    }

    /** Returns null when no offset is present, so callers fall back to local time. */
    private fun parseUtcOffset(text: String): Long? {
        if (text.isEmpty()) return null
        if (text.equals("Z", true) || text.equals("UTC", true) || text.equals("GMT", true)) return 0L
        val sign = text[0]
        if (sign != '+' && sign != '-') return null
        val digits = text.substring(1).replace(":", "")
        if (digits.length < 2) return null
        val hours = digits.substring(0, 2).toLongOrNull() ?: return null
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toLongOrNull() ?: 0L else 0L
        val offset = hours * 3600L + minutes * 60L
        return if (sign == '-') -offset else offset
    }

    private fun number(text: String, from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) {
            value = value * 10 + (text[i] - '0')
        }
        return value
    }

    /** Days between 1970-01-01 and the given civil date (proleptic Gregorian). */
    private fun daysFromEpoch(year: Int, month: Int, day: Int): Long {
        val y = year - (if (month <= 2) 1 else 0).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthIdx = month + (if (month > 2) -3 else 9)
        val dayOfYear = (153 * monthIdx + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }

    private companion object {
        const val TAG = "LiteEpgParser"
        const val DEFAULT_PROGRAM_DURATION_MS = 30L * 60 * 1000
        const val MAX_DESC_LENGTH = 500
        const val MAX_CATEGORY_LENGTH = 80
        val QUALITY_SUFFIXES = arrayOf("4k", "8k", "uhd", "fhd", "hd", "超清", "高清", "标清", "蓝光")
    }
}

/**
 * Thread-safe accumulator for parsed programmes. The streaming parser (single
 * thread) appends while the repository (another coroutine) periodically drains
 * published batches, so the first channel rows appear on screen before the
 * whole document has been read.
 */
internal fun interface EpgProgrammeSink {
    fun add(key: String, programme: EpgProgramme)
}

internal class EpgBuffer : EpgProgrammeSink {
    private val lock = Any()
    private val map = HashMap<String, ArrayList<EpgProgramme>>()

    override fun add(key: String, programme: EpgProgramme) {
        synchronized(lock) {
            map.getOrPut(key) { ArrayList() }.add(programme)
        }
    }

    /** Copies and clears the accumulated programmes (never throws). */
    fun drain(): Map<String, List<EpgProgramme>> = synchronized(lock) {
        if (map.isEmpty()) return emptyMap()
        val out = HashMap<String, List<EpgProgramme>>(map.size)
        map.forEach { (key, list) -> out[key] = ArrayList(list) }
        map.clear()
        out
    }
}

/**
 * Sliding-buffer scanner over an [EpgTextSource]: reads in bounded chunks and
 * can extract a whole XML element (`<programme>…</programme>`), keeping at most
 * ~2 MiB of the document in memory.
 */
private class XmlScanner(private val source: EpgTextSource) {
    private val buf = StringBuilder(1024 * 1024)
    private var cursor = 0
    private var eof = false
    private val chunk = CharArray(64 * 1024)
    var closedDocument = false
        private set

    // Inspect each next opening tag once. Searching the entire unread tail
    // for a missing <channel> on every <programme> repeatedly scanned the same text.
    fun nextElementTag(): String? {
        while (true) {
            val p = buf.indexOf("<", cursor)
            if (p < 0) {
                if (!fillMore()) return null
                continue
            }
            cursor = p
            if (!eof && buf.length - p < 12 && readMore()) continue
            if (matchesTag(p, "channel")) return "channel"
            if (matchesTag(p, "programme")) return "programme"
            cursor = p + 1
        }
    }

    private fun matchesTag(start: Int, name: String): Boolean {
        val end = start + 1 + name.length
        if (end >= buf.length) return false
        for (i in name.indices) if (buf[start + 1 + i] != name[i]) return false
        return buf[end].isWhitespace() || buf[end] == '>' || buf[end] == '/'
    }

    /** Reads the next chunk; returns false at end of input. */
    fun fillMore(): Boolean {
        // Missing/unknown tags must not grow a buffer to the size of the feed.
        if (buf.length - cursor > 128) {
            buf.delete(0, buf.length - 128)
            cursor = 0
        }
        return readMore()
    }

    private fun readMore(): Boolean {
        if (eof) return false
        val n = source.read(chunk, 0, chunk.size)
        if (n < 0) {
            eof = true
            return false
        }
        buf.append(chunk, 0, n)
        val end = buf.indexOf("</tv", (buf.length - n - 16).coerceAtLeast(0))
        if (end >= 0 && buf.indexOf(">", end) >= 0) closedDocument = true
        return true
    }

    /** Returns the full `<$tag …>…</$tag>` element, or null at end of input. */
    fun extractElement(tag: String): String? {
        val open = "<$tag"
        val close = "</$tag>"
        for (iteration in 0..MAX_ITERATIONS) {
            var p = buf.indexOf(open, cursor)
            if (p < 0) {
                // Retain a tail so a tag split across chunks is not lost.
                if (buf.length - cursor > 128) cursor = buf.length - 128
                if (!readMore()) return null
                continue
            }
            var gt = buf.indexOf('>', p + open.length)
            if (gt < 0) {
                if (!readMore()) return null
                continue
            }
            val selfClosing = gt > p && buf[gt - 1] == '/'
            val e = if (selfClosing) gt + 1 else buf.indexOf(close, gt + 1)
            if (e < 0) {
                if (buf.length - p > MAX_ELEMENT_LENGTH) return null
                if (!readMore()) return null
                continue
            }
            val end = if (selfClosing) e else e + close.length
            val element = buf.substring(p, end)
            cursor = end
            if (cursor > 2 * 1024 * 1024) {
                buf.delete(0, cursor)
                cursor = 0
            }
            return element
        }
        return null
    }

    private companion object {
        const val MAX_ELEMENT_LENGTH = 2 * 1024 * 1024
        const val MAX_ITERATIONS = 1_000_000
    }
}
