package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgChannel

/**
 * Matches M3U channels to XMLTV channels.
 *
 * Primary rule: `Channel.tvgId` ↔ `EpgChannel.id` (exact, then case- and
 * whitespace-insensitive).
 * Fallback (when tvgId is absent or unmatched): normalized `tvgName` (or
 * channel `name`) against normalized XMLTV `display-name`s, with a final
 * "loose" pass that ignores case, whitespace AND punctuation (so «Россия 1»
 * can link to «Россия-1» or «РОССИЯ 1»).
 *
 * Normalization guarantees strict equality after normalizing — never fuzzy, so
 * «Россия 1» can never be linked to «Россия 24» just because the names look
 * similar.
 */
class EpgMatcher(private val allowQualityFallback: Boolean = false) {

    /** Case- and whitespace-insensitive, punctuation kept. */
    fun normalize(value: String): String =
        value.lowercase().filterNot { it.isWhitespace() }.trim()

    /** Case-, whitespace- AND punctuation-insensitive (loose name fallback). */
    fun looseNormalize(value: String): String =
        CODEC_SUFFIX.replace(nameText(value).trim(), "").filter { it.isLetterOrDigit() || it == '+' || it == '−' }

    private fun nameText(value: String): String = java.text.Normalizer
        .normalize(value, java.text.Normalizer.Form.NFKC).lowercase().replace('ё', 'е')
        .replace(NEGATIVE_OFFSET, "−")
        .filterNot { it == '\u200b' || it == '\ufeff' }

    private fun strictName(value: String): String = normalize(nameText(value))

    internal fun qualityName(value: String): String {
        var name = nameText(value).trim()
        while (true) {
            val stripped = QUALITY_SUFFIX.replace(name, "").trim()
            if (stripped == name) break
            name = stripped
        }
        return looseNormalize(name)
    }

    /**
     * Builds the `Channel.id -> EpgChannel.id` mapping and matching diagnostics.
     */
    fun match(
        channels: List<Channel>,
        epgChannels: List<EpgChannel>,
    ): MatchResult {
        val byEpgId = HashMap<String, EpgChannel>(epgChannels.size)
        val byNormalizedId = HashMap<String, EpgChannel>()
        // Name aliases can be duplicated by a provider (e.g., time-shift and
        // regional versions of one station). Never arbitrarily bind one label
        // to the first entry: a wrong EPG is worse than no EPG.
        val byNormalizedName = HashMap<String, String?>()
        val byLooseName = HashMap<String, String?>()
        val byQualityName = HashMap<String, String?>()
        fun add(index: MutableMap<String, String?>, token: String, id: String) {
            if (token.isEmpty()) return
            if (token !in index) index[token] = id
            else if (index[token] != id) index[token] = null
        }
        epgChannels.forEach { epg ->
            byEpgId[epg.id] = epg
            val normId = normalize(epg.id)
            if (normId.isNotEmpty()) byNormalizedId.putIfAbsent(normId, epg)
            epg.displayNames.forEach { name ->
                val strict = strictName(name)
                if (strict.isNotEmpty()) {
                    if (strict !in byNormalizedName) byNormalizedName[strict] = epg.id
                    else if (byNormalizedName[strict] != epg.id) byNormalizedName[strict] = null
                }
                val loose = looseNormalize(name)
                if (loose.isNotEmpty()) {
                    if (loose !in byLooseName) byLooseName[loose] = epg.id
                    else if (byLooseName[loose] != epg.id) byLooseName[loose] = null
                }
                if (allowQualityFallback) add(byQualityName, qualityName(name), epg.id)
            }
        }

        val mapping = HashMap<String, String>()
        var matchedByTvgId = 0
        var matchedByName = 0

        channels.forEach { channel ->
            val byId = channel.tvgId?.let { tvgId ->
                when {
                    byEpgId.containsKey(tvgId) -> tvgId
                    else -> byNormalizedId[normalize(tvgId)]?.id
                }
            }
            val byName = if (byId == null) {
                // IPTV providers often put a service name in tvg-name that is
                // absent from the external XMLTV. Try the displayed channel
                // name too, but require a unique exact normalized match.
                val aliases = listOfNotNull(channel.tvgName, channel.name).distinct()
                aliases.firstNotNullOfOrNull { name ->
                    byNormalizedName[strictName(name)]
                } ?: aliases.firstNotNullOfOrNull { name ->
                    byLooseName[looseNormalize(name)]
                } ?: if (allowQualityFallback) aliases.firstNotNullOfOrNull { byQualityName[qualityName(it)] } else null
            } else null
            val epgId = byId ?: byName
            if (epgId != null) {
                mapping[channel.id] = epgId
                if (byId != null) matchedByTvgId++ else matchedByName++
            }
        }

        return MatchResult(
            channelToEpgId = mapping,
            diagnostics = EpgDiagnostics(
                m3uChannelCount = channels.size,
                epgChannelCount = epgChannels.size,
                matchedByTvgId = matchedByTvgId,
                matchedByName = matchedByName,
                unmatchedM3u = channels.size - mapping.size,
            ),
        )
    }

    data class MatchResult(
        val channelToEpgId: Map<String, String>,
        val diagnostics: EpgDiagnostics,
    )

    private companion object {
        val NEGATIVE_OFFSET = Regex("(?<=[\\s(])-(?=\\s*\\d)")
        val CODEC_SUFFIX = Regex("[\\s._\\-\\[(]*(?:hevc|h\\.?26[45])[\\s\\])]*$")
        val QUALITY_SUFFIX = Regex("[\\s._\\-\\[(]*(?:full\\s*hd|ultra\\s*hd|uhd|fhd|hd|sd|4k|8k|1080[pi]|720p|2160p|hevc|h\\.?26[45]|超清|高清|标清|蓝光)[\\s\\])]*$")
    }
}

/** Diagnostics of one EPG ↔ M3U matching pass. */
data class EpgDiagnostics(
    val m3uChannelCount: Int = 0,
    val epgChannelCount: Int = 0,
    val matchedByTvgId: Int = 0,
    val matchedByName: Int = 0,
    val unmatchedM3u: Int = 0,
) {
    val matched: Int get() = matchedByTvgId + matchedByName
}
