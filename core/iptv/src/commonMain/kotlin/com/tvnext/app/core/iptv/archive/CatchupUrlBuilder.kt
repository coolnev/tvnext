package com.tvnext.app.core.iptv.archive

import com.tvnext.app.core.iptv.domain.Channel

/**
 * Builds archive (catch-up) URLs for live channels.
 *
 * Providers describe catch-up very differently, so this is a **format
 * registry**: each [CatchupFormat] knows how to recognize and build one
 * provider convention. Adding a new convention = one new class; nothing else
 * in the app changes.
 *
 * Currently supported conventions:
 * - [UtcTemplateCatchupFormat] — the de-facto standard `catchup-source`
 *   template with `{utc}` / `{start}` / `{end}` / `{start-utc}` / `{end-utc}`
 *   (Unix seconds, UTC) and `{year}`..`{second}` date-part placeholders.
 * - [TimeshiftParamCatchupFormat] — `catchup-type="timeshift"` (or a truthy
 *   `timeshift` attribute) with a plain `catchup-source` URL: appends
 *   `?start=..&end=..` time parameters (a convention used by providers that
 *   do not ship templates).
 * - [FlussonicCatchupFormat] — Flussonic Media Server DVR
 *   (`catchup-type="flussonic"`): replaces `index.m3u8` with the DVR file URL
 *   `index-<start_epoch_sec>-<duration_sec>.m3u8` (finished broadcast) per
 *   the official Flussonic docs.
 * - [ShiftParamCatchupFormat] — `catchup="shift"` / a `timeshift` attribute
 *   WITHOUT a template: the widely-used `?start=..&end=..` convention applied
 *   to the LIVE stream URL. If the provider does not actually serve the
 *   archive (declares it but returns an empty playlist), the probe/play fails
 *   honestly — the app never fakes a working archive.
 */
class CatchupUrlBuilder(
    private val formats: List<CatchupFormat> = listOf(
        UtcTemplateCatchupFormat,
        TimeshiftParamCatchupFormat,
        FlussonicCatchupFormat,
        ShiftParamCatchupFormat,
    ),
) {
    /** True when at least one format can build an archive URL for this channel. */
    fun isArchivePlayable(channel: Channel): Boolean =
        formats.any { it.accepts(channel) }

    /** Builds the archive URL for [startMs]..[endMs], or null when unsupported. */
    fun buildArchiveUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        if (endMs <= startMs) return null
        if (!channel.hasCatchupMechanism) return null
        return formats.firstNotNullOfOrNull { format ->
            if (format.accepts(channel)) format.buildUrl(channel, startMs, endMs) else null
        }
    }
}

/** One provider-specific catch-up URL convention. */
sealed interface CatchupFormat {
    /** Whether this format claims the channel's catch-up attributes. */
    fun accepts(channel: Channel): Boolean

    /** Builds the archive URL; null when the channel is not fully supported. */
    fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String?
}

/** `catchup-source="http://.../x.m3u8?utc={utc}"` template substitution. */
object UtcTemplateCatchupFormat : CatchupFormat {

    override fun accepts(channel: Channel): Boolean {
        val source = channel.catchupSource ?: return false
        // A plain URL without any placeholder is NOT a template — it must not
        // claim the format (it would otherwise shadow the timeshift convention).
        return TEMPLATE_TOKENS.any { source.contains(it) }
    }

    override fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        val template = channel.catchupSource ?: return null
        val startSeconds = startMs / 1000
        val endSeconds = endMs / 1000
        val parts = utcPartsFromEpochMillis(startMs)
        val year = "%04d".format(parts.year)
        val month = "%02d".format(parts.month)
        val day = "%02d".format(parts.day)
        val hour = "%02d".format(parts.hour)
        val minute = "%02d".format(parts.minute)
        val second = "%02d".format(parts.second)
        return template
            .replace("{utc}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("{end}", endSeconds.toString())
            .replace("{start-utc}", startSeconds.toString())
            .replace("{end-utc}", endSeconds.toString())
            .replace("{duration}", ((endMs - startMs) / 1000).toString())
            .replace("{year}", year)
            .replace("{month}", month)
            .replace("{day}", day)
            .replace("{hour}", hour)
            .replace("{minute}", minute)
            .replace("{second}", second)
            .takeIf { it.isNotBlank() }
    }

    private val TEMPLATE_TOKENS = listOf(
        "{utc}", "{start}", "{end}", "{start-utc}", "{end-utc}", "{duration}",
        "{year}", "{month}", "{day}", "{hour}", "{minute}", "{second}",
    )
}

/** Timeshift convention: plain URL + `?start=..&end=..` time parameters. */
object TimeshiftParamCatchupFormat : CatchupFormat {

    override fun accepts(channel: Channel): Boolean {
        val timeshiftDeclared = channel.catchupType?.equals("timeshift", ignoreCase = true) == true ||
            !channel.timeshift.isNullOrBlank()
        return timeshiftDeclared && !channel.catchupSource.isNullOrBlank()
    }

    override fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        val base = channel.catchupSource ?: return null
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}start=${startMs / 1000}&end=${endMs / 1000}"
    }
}

/**
 * Flussonic Media Server DVR (`catchup-type="flussonic"`).
 *
 * Per the official Flussonic docs, the DVR playback URL is the stream URL with
 * the trailing `index.m3u8` replaced by:
 *   `index-<start_epoch_sec>-<duration_sec>.m3u8` — a finished recording
 *   (the playlist carries `#EXT-X-ENDLIST`, so the player gets a timeline);
 *   `index-<start_epoch_sec>-now.m3u8` — rewind to live for a broadcast that
 *   is still running.
 *
 * The DVR filename lives in the URL PATH, so it survives load-balancer
 * redirects that strip query parameters (verified on vivamax.pro).
 */
object FlussonicCatchupFormat : CatchupFormat {

    override fun accepts(channel: Channel): Boolean =
        channel.catchupType?.equals("flussonic", ignoreCase = true) == true &&
            !channel.streamUrl.isNullOrBlank()

    override fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        val durationSec = (endMs - startMs) / 1000
        if (durationSec <= 0L) return null
        val streamUrl = channel.streamUrl
        val path = streamUrl.substringBefore('?')
        val query = streamUrl.substringAfter('?', "")
        val separator = if (query.isEmpty()) "" else "?"
        val base = if (path.endsWith(".m3u8")) {
            path.substringBeforeLast('.')
        } else {
            path.trimEnd('/') + "/index"
        }
        return "$base-${startMs / 1000}-$durationSec.m3u8$separator$query"
    }
}

/**
 * `catchup="shift"` / `timeshift` providers WITHOUT a `catchup-source`:
 * the `?start=..&end=..` time convention applied to the live stream URL.
 * Widely used by Russian/EDEM-style IPTV providers; a provider that declares
 * the archive but serves an empty playlist fails honestly at play time.
 */
object ShiftParamCatchupFormat : CatchupFormat {

    override fun accepts(channel: Channel): Boolean {
        val shiftDeclared = channel.catchup?.equals("shift", ignoreCase = true) == true ||
            channel.catchupType?.equals("shift", ignoreCase = true) == true ||
            !channel.timeshift.isNullOrBlank()
        return shiftDeclared && !channel.streamUrl.isNullOrBlank()
    }

    override fun buildUrl(channel: Channel, startMs: Long, endMs: Long): String? {
        val base = channel.streamUrl
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}start=${startMs / 1000}&end=${endMs / 1000}"
    }
}

/** UTC date parts (pure Kotlin, deterministic — no platform time APIs). */
internal data class UtcParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * Epoch millis → UTC date/time parts using the `civil_from_days` algorithm
 * (Howard Hinnant) — no java.util / java.time, so it is common and testable.
 */
internal fun utcPartsFromEpochMillis(epochMillis: Long): UtcParts {
    val seconds = epochMillis / 1000
    val days = Math.floorDiv(seconds, 86_400L)
    val secOfDay = Math.floorMod(seconds, 86_400L)
    val z = days + 719_468
    val era = if (z >= 0) z else z - 146_096
    val doe = Math.floorMod(era, 146_097L)
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era / 146_097 * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return UtcParts(
        year = year.toInt(),
        month = m.toInt(),
        day = d.toInt(),
        hour = (secOfDay / 3_600).toInt(),
        minute = ((secOfDay % 3_600) / 60).toInt(),
        second = (secOfDay % 60).toInt(),
    )
}
