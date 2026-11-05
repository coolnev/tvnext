package com.tvnext.app

import java.net.URI

/** Restored recording primitives, kept independent of Android for JVM testing. */
internal data class HlsKey(val method: String, val uri: String?, val iv: String?)
internal data class HlsSegment(
    val sequence: Long,
    val url: String,
    val seconds: Double,
    val key: HlsKey?,
    val range: String? = null,
    val initMapUrl: String? = null,
)
internal data class HlsIndex(
    val variants: List<String>,
    val segments: List<HlsSegment>,
    val targetDuration: Int,
    val endList: Boolean,
)

internal object RecordingFormats {
    fun url(base: String, relative: String): String = URI(base).resolve(relative.trim()).toString()

    fun parseHls(base: String, source: String): HlsIndex {
        require(source.lineSequence().any { it.trim() == "#EXTM3U" }) { "Not an HLS playlist" }
        val variants = mutableListOf<String>()
        val segments = mutableListOf<HlsSegment>()
        var nextVariant = false
        var sequence = 0L
        var targetDuration = 6
        var duration = 0.0
        var key: HlsKey? = null
        var range: String? = null
        var previousRangeUrl: String? = null
        var previousRangeEnd: Long? = null
        var initMap: String? = null
        var end = false
        for (raw in source.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> nextVariant = true
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: sequence
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetDuration = (line.substringAfter(':').toIntOrNull() ?: 6).coerceAtLeast(1)
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    key = if (a["METHOD"] == "NONE") null else HlsKey(a["METHOD"] ?: "UNKNOWN", a["URI"]?.let { url(base, it) }, a["IV"])
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
                line.startsWith("#EXT-X-MAP:") -> initMap = attributes(line.substringAfter(':'))["URI"]?.let { url(base, it) }
                line == "#EXT-X-ENDLIST" -> end = true
                line.isNotBlank() && !line.startsWith('#') -> {
                    if (nextVariant) { variants.add(url(base, line)); nextVariant = false }
                    else {
                        val segmentUrl = url(base, line)
                        val normalizedRange = range?.let { rawRange ->
                            val length = rawRange.substringBefore('@').toLongOrNull()
                            require(length != null && length > 0L) { "Invalid HLS byte range" }
                            val explicitOffset = rawRange.substringAfter('@', "").toLongOrNull()
                            val offset = explicitOffset ?: previousRangeEnd?.takeIf { previousRangeUrl == segmentUrl }
                                ?: throw IllegalArgumentException("HLS range offset missing for first segment")
                            require(offset >= 0L && offset <= Long.MAX_VALUE - length) { "Invalid HLS range offset" }
                            previousRangeUrl = segmentUrl
                            previousRangeEnd = offset + length
                            "$length@$offset"
                        }
                        if (normalizedRange == null) {
                            previousRangeUrl = null
                            previousRangeEnd = null
                        }
                        segments.add(HlsSegment(sequence++, segmentUrl, duration, key, normalizedRange, initMap))
                    }
                    range = null
                    duration = 0.0
                }
            }
        }
        return HlsIndex(variants, segments, targetDuration, end)
    }

    /** Quoted attributes can contain commas, e.g. CODECS="avc1.4d401f,mp4a.40.2". */
    fun attributes(s: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
            val start = i
            while (i < s.length && s[i] != '=' && s[i] != ',') i++
            if (i >= s.length || s[i] != '=') { i++; continue }
            val name = s.substring(start, i++).trim()
            val value = if (i < s.length && s[i] == '"') {
                i++
                val st = i
                while (i < s.length && s[i] != '"') i++
                s.substring(st, i).also { if (i < s.length) i++ }
            } else {
                val st = i
                while (i < s.length && s[i] != ',') i++
                s.substring(st, i).trim()
            }
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }

    fun ivBytes(iv: String?, sequence: Long): ByteArray {
        if (iv == null) {
            return ByteArray(16).also { bytes ->
                var n = sequence
                for (index in 15 downTo 8) { bytes[index] = n.toByte(); n = n ushr 8 }
            }
        }
        val stripped = iv.removePrefix("0x").removePrefix("0X")
        require(stripped.length in 1..32 && stripped.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Invalid AES IV" }
        return stripped.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
