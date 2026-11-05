package com.tvnext.app.core.iptv.data

/**
 * Deterministic stable identifiers for playlists, channels and media items.
 *
 * Channel IDs never use list position. The identity strategy is:
 *  - `tvg-id` present  → `playlist + tvg-id` (the provider's stable id);
 *  - no `tvg-id`       → `playlist + normalized name` (case/whitespace folded);
 *  - neither present   → the resolved stream URL as a LAST-RESORT fallback.
 *
 * A provider changing the CDN/token/URL of the SAME channel therefore keeps
 * the channel's id (and with it favorites / history / archive / schedule),
 * because the URL is only consulted when no other stable identity exists.
 * `playlistId` is always part of the identity, so identical channels in
 * different playlists never collide.
 */
object IdGenerator {

    fun playlistId(url: String): String = "pl-${stableHash("playlist|$url")}"

    /**
     * Stable channel id. The [streamUrl] is only used when neither `tvgId`
     * nor a non-blank [name] provides an identity.
     */
    fun channelId(
        playlistId: String,
        tvgId: String?,
        name: String,
        streamUrl: String,
    ): String {
        val tvg = tvgId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = name.trim()
        return when {
            tvg != null -> "ch-${stableHash("channel|$playlistId|tvg|$tvg")}"
            cleanName.isNotEmpty() -> "ch-${stableHash("channel|$playlistId|name|${normalizeName(cleanName)}")}"
            else -> "ch-${stableHash("channel|$playlistId|url|${streamUrl.trim()}")}"
        }
    }

    /**
     * Stable VOD-item id: playlist + identity fields (tvg-id / name / URL /
     * season-episode). Never the list position, so a refresh cannot shuffle
     * favorites/history around. When the provider gives each film its own
     * `tvg-id` it is the whole identity; series with a shared `tvg-id` add
     * season/episode; without `tvg-id` the normalized name (plus season/
     * episode) is used and the URL is the last-resort fallback.
     */
    fun mediaItemId(
        playlistId: String,
        tvgId: String?,
        name: String,
        streamUrl: String,
        season: String? = null,
        episode: String? = null,
    ): String {
        val tvg = tvgId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanSeason = season?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEpisode = episode?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = name.trim()
        val seriesPart = listOfNotNull(cleanSeason, cleanEpisode).joinToString("|")
        return when {
            tvg != null && seriesPart.isNotEmpty() ->
                "mi-${stableHash("media|$playlistId|tvg|$tvg|series|$seriesPart")}"
            tvg != null ->
                "mi-${stableHash("media|$playlistId|tvg|$tvg")}"
            cleanName.isNotEmpty() ->
                "mi-${stableHash("media|$playlistId|name|${normalizeName(cleanName)}|series|$seriesPart")}"
            else ->
                "mi-${stableHash("media|$playlistId|url|${streamUrl.trim()}|series|$seriesPart")}"
        }
    }

    /** Case/whitespace folding for the name-based identity fallback. */
    internal fun normalizeName(value: String): String =
        value.lowercase().filterNot { it.isWhitespace() }

    fun stableHash(input: String): String {
        var hash: ULong = FNV_OFFSET_BASIS
        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        return hash.toString(16)
    }

    private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325UL
    private const val FNV_PRIME: ULong = 0x100000001b3UL
}
