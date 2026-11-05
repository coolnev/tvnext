package com.tvnext.app.core.iptv.xmltv

/**
 * A single XMLTV `<channel>`: its xmltv id, display names and logo icon.
 * `displayNames` keeps the original order — the first one is the canonical name.
 */
data class EpgChannel(
    val id: String,
    val displayNames: List<String> = emptyList(),
    val iconUrl: String? = null,
)

/** A single XMLTV `<programme>` with parsed (epoch millis) timestamps. */
data class EpgProgramme(
    val channelId: String,
    /** Epoch millis (UTC). */
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val episodeNum: String? = null,
)

data class XmltvDocument(
    val channels: List<EpgChannel> = emptyList(),
    val programmes: List<EpgProgramme> = emptyList(),
)

sealed interface XmltvParseResult {
    data class Success(val document: XmltvDocument) : XmltvParseResult
    data class Failure(val reason: String) : XmltvParseResult
}
