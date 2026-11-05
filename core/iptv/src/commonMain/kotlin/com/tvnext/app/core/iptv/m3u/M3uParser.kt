package com.tvnext.app.core.iptv.m3u

data class M3uChannel(
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val groupTitle: String? = null,
    val durationSeconds: Long? = null,
    /** Season number if the source declares it (VOD series), as-is. */
    val season: String? = null,
    /** Episode number if the source declares it (VOD series), as-is. */
    val episode: String? = null,
    /** Catch-up attribute: `catchup="default"`, `catchup="append"`, ... */
    val catchup: String? = null,
    /** Provider-declared archive depth in days (`catchup-days="7"`). */
    val catchupDays: Long? = null,
    /** Archive URL template (`catchup-source="http://.../x.m3u8?utc={utc}"`). */
    val catchupSource: String? = null,
    /** Catch-up flavour (`default`, `timeshift`, `append`, `flush`). */
    val catchupType: String? = null,
    /** Legacy `timeshift` attribute (usually a truthy flag or hour count). */
    val timeshift: String? = null,
)

data class M3uPlaylist(
    val channels: List<M3uChannel>,
    /** EPG (XMLTV) URL declared in the `#EXTM3U` header (`url-tvg` / `x-tvg-url`). */
    val urlTvg: String? = null,
)

sealed interface M3uParseResult {
    data class Success(val playlist: M3uPlaylist) : M3uParseResult
    data class Failure(val reason: String) : M3uParseResult
}

/**
 * Deterministic M3U/M3U8 playlist parser.
 *
 * Supports:
 *  - `#EXTM3U` header
 *  - `#EXTINF` entries with attributes: tvg-id, tvg-name, tvg-logo, group-title
 *  - CRLF, LF, UTF-8 BOM, blank lines, comments
 *  - URLs with query parameters
 *  - malformed entries (skipped, never crashes)
 *
 * A file is not considered a valid M3U unless it starts with `#EXTM3U`.
 */
class M3uParser {

    fun parse(content: String): M3uParseResult {
        val withoutBom = content.trimStart(BOM_CHAR)
        val lines = withoutBom.split('\n')
        val firstMeaningful = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!firstMeaningful.startsWith(EXTM3U_HEADER)) {
            return M3uParseResult.Failure("Файл не начинается с #EXTM3U")
        }

        // EPG URL may be declared in the #EXTM3U header, e.g.
        // `#EXTM3U url-tvg="https://example.com/epg.xml.gz" x-tvg-url="..."`
        val headerAttributes = parseAttributes(firstMeaningful.substring(EXTM3U_HEADER.length))
        val urlTvg = headerAttributes["url-tvg"]
            ?: headerAttributes["x-tvg-url"]
            ?.takeIf { it.isNotBlank() }

        val channels = mutableListOf<M3uChannel>()
        var pending: ExtInf? = null

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith(EXTINF_PREFIX) -> {
                    pending = parseExtInf(line)
                }
                line.startsWith("#") -> {
                    // Other directives / comments — ignored
                }
                else -> {
                    val current = pending
                    if (current != null) {
                        channels.add(
                            M3uChannel(
                                name = current.name,
                                streamUrl = line,
                                tvgId = current.attributes["tvg-id"],
                                tvgName = current.attributes["tvg-name"],
                                tvgLogo = current.attributes["tvg-logo"],
                                groupTitle = current.attributes["group-title"],
                                durationSeconds = current.duration,
                                season = current.attributes["season"],
                                episode = current.attributes["episode"],
                                catchup = current.attributes["catchup"],
                                catchupDays = current.attributes["catchup-days"]?.toLongOrNull(),
                                catchupSource = current.attributes["catchup-source"],
                                catchupType = current.attributes["catchup-type"],
                                timeshift = current.attributes["timeshift"],
                            ),
                        )
                        pending = null
                    }
                }
            }
        }

        return M3uParseResult.Success(M3uPlaylist(channels = channels, urlTvg = urlTvg))
    }

    private data class ExtInf(
        val duration: Long?,
        val attributes: Map<String, String>,
        val name: String,
    )

    private fun parseExtInf(line: String): ExtInf? {
        val body = line.substringAfter(EXTINF_PREFIX, "")
        val commaIndex = indexOfUnquoted(body, ',')
        if (commaIndex < 0) return null

        val meta = body.substring(0, commaIndex).trim()
        val name = body.substring(commaIndex + 1).trim()

        var duration: Long? = null
        val numberMatch = DURATION_REGEX.find(meta)
        val attrsText = if (numberMatch != null) {
            duration = numberMatch.value.toLongOrNull()
            meta.substring(numberMatch.range.last + 1).trim()
        } else {
            meta
        }

        return ExtInf(duration = duration, attributes = parseAttributes(attrsText), name = name)
    }

    private fun parseAttributes(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (match in ATTRIBUTE_REGEX.findAll(text)) {
            val key = match.groupValues[1]
            var value = match.groupValues[2]
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            result[key] = value
        }
        return result
    }

    private fun indexOfUnquoted(text: String, target: Char): Int {
        var inQuotes = false
        for (i in text.indices) {
            val c = text[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == target && !inQuotes) return i
        }
        return -1
    }

    private companion object {
        const val BOM_CHAR = '\uFEFF'
        const val EXTM3U_HEADER = "#EXTM3U"
        const val EXTINF_PREFIX = "#EXTINF:"
        val DURATION_REGEX = Regex("""^-?\d+\.?\d*""")
        val ATTRIBUTE_REGEX = Regex("""([\w-]+)=("[^"]*"|\S+)""")
    }
}
