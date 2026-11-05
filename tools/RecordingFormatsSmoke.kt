package com.tvnext.app

fun main() {
    val body = """#EXTM3U
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:127
#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x01
#EXTINF:3.2,
segment1.ts
#EXTINF:3.9,
segment2.ts
#EXT-X-ENDLIST
"""
    val parsed = RecordingFormats.parseHls("https://example.invalid/hls/live.m3u8", body)
    check(parsed.segments.size == 2)
    check(parsed.segments[0].sequence == 127L)
    check(parsed.segments[0].key?.uri == "https://example.invalid/hls/key.bin")
    check(parsed.segments[1].url == "https://example.invalid/hls/segment2.ts")
    check(parsed.endList)
    check(RecordingFormats.ivBytes(null, 1)[15] == 1.toByte())
    check(RecordingFormats.attributes("URI=\"https://host/a,b\",METHOD=AES-128")["URI"] == "https://host/a,b")
    verifyRanges()
    println("HLS parser smoke checks: OK")
}

private fun verifyRanges() {
    val m3u8 = """#EXTM3U
#EXT-X-TARGETDURATION:5
#EXT-X-BYTERANGE:100@20
#EXTINF:4.0,
segments.ts
#EXT-X-BYTERANGE:50
#EXTINF:4.0,
segments.ts
#EXT-X-BYTERANGE:10@3
#EXTINF:1.0,
other.ts
"""
    val result = RecordingFormats.parseHls("https://example.invalid/list.m3u8", m3u8)
    check(result.segments.map { it.range } == listOf("100@20", "50@120", "10@3"))
    check(runCatching { RecordingFormats.parseHls("https://example.invalid/list.m3u8", "#EXTM3U\n#EXT-X-BYTERANGE:100\n#EXTINF:4.0,\nseg.ts") }.isFailure)
}
