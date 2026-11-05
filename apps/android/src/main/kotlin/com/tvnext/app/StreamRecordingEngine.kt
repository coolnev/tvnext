package com.tvnext.app

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android-only capture of transport streams. Never transcodes/remuxes media.
 * Supports MPEG-TS direct and ordinary HLS with AES-128/CBC, EXT-X-MAP and ranges.
 * DRM, external audio renditions, DASH and low-latency partial segments are unsupported.
 */
internal class StreamRecordingEngine(
    private val maxBytes: Long,
    private val onProgress: (Long) -> Unit = {},
) {
    private val bytes = AtomicLong(0)
    private val keys = mutableMapOf<String, ByteArray>()
    private val seen = hashSetOf<String>()

    suspend fun capture(streamUrl: String, folder: File, durationMs: Long): String {
        require(streamUrl.startsWith("https://", true) || streamUrl.startsWith("http://", true)) { "Unsupported URL protocol" }
        require(durationMs in 1L..(6L * 60 * 60 * 1000)) { "Invalid recording duration" }
        folder.mkdirs()
        val first = if (streamUrl.substringBefore('?').endsWith(".m3u8", true)) true else runCatching {
            val connection = connect(streamUrl)
            try { connection.contentType?.contains("mpegurl", true) == true } finally { connection.disconnect() }
        }.getOrDefault(false)
        return if (first) captureHls(streamUrl, folder, durationMs) else captureTs(streamUrl, folder, durationMs)
    }

    private suspend fun captureTs(url: String, folder: File, durationMs: Long): String {
        val started = SystemClock.elapsedRealtime()
        val out = File(folder, "capture.ts")
        val connection = connect(url)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (SystemClock.elapsedRealtime() - started < durationMs) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        reserve(n.toLong())
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
        require(out.length() > 0) { "Empty transport stream" }
        return out.absolutePath
    }

    private suspend fun captureHls(originalUrl: String, folder: File, durationMs: Long): String {
        val started = SystemClock.elapsedRealtime()
        var playlistUrl = originalUrl
        val outputIndex = File(folder, "recording.m3u8")
        val records = mutableListOf<Pair<Double, String>>()
        val initFiles = mutableMapOf<String, String>()
        val initForSegment = mutableMapOf<String, String>()
        var targetDuration = 6
        var emptyPolls = 0
        while (SystemClock.elapsedRealtime() - started < durationMs) {
            currentCoroutineContext().ensureActive()
            val listing = readText(playlistUrl)
            val index = RecordingFormats.parseHls(playlistUrl, listing)
            if (index.variants.isNotEmpty()) {
                playlistUrl = index.variants.first()
                continue
            }
            targetDuration = index.targetDuration
            var newSegments = 0
            for (segment in index.segments) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - started >= durationMs) break
                val identity = "${segment.sequence}:${segment.url}:${segment.range}"
                if (!seen.add(identity)) continue
                val format = if (segment.initMapUrl != null || segment.url.substringBefore('?').endsWith(".m4s", true)) "m4s" else "ts"
                val localName = "segment_%07d.%s".format(records.size, format)
                val output = File(folder, localName)
                try {
                    if (segment.initMapUrl != null && !initFiles.containsKey(segment.initMapUrl)) {
                        val localInit = "init_%03d.mp4".format(initFiles.size)
                        download(segment.initMapUrl, File(folder, localInit), null, null, 0)
                        initFiles[segment.initMapUrl] = localInit
                    }
                    segment.initMapUrl?.let { initForSegment[localName] = initFiles.getValue(it) }
                    download(segment.url, output, segment.range, segment.key, segment.sequence)
                    records.add(segment.seconds to localName)
                    newSegments++
                    writeIndex(outputIndex, records, initForSegment, targetDuration)
                } catch (e: Exception) {
                    output.delete()
                    seen.remove(identity)
                    throw e
                }
            }
            if (index.endList && newSegments == 0) break
            if (newSegments == 0) emptyPolls++ else emptyPolls = 0
            if (emptyPolls >= 20) break
            delay((targetDuration * 500L).coerceIn(1000, 6000))
        }
        require(records.isNotEmpty()) { "No media segments were received" }
        // ENDLIST makes the captured file finite so local playback can seek to the end.
        writeIndex(outputIndex, records, initForSegment, targetDuration, finished = true)
        return outputIndex.absolutePath
    }

    private fun writeIndex(
        output: File, entries: List<Pair<Double, String>>,
        maps: Map<String, String>, target: Int, finished: Boolean = false,
    ) {
        val text = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:${target.coerceAtLeast(1)}\n#EXT-X-MEDIA-SEQUENCE:0\n")
            var lastMap: String? = null
            for ((seconds, filename) in entries) {
                val map = maps[filename]
                if (map != null && map != lastMap) append("#EXT-X-MAP:URI=\"$map\"\n")
                lastMap = map
                append("#EXTINF:$seconds,\n$filename\n")
            }
            if (finished) append("#EXT-X-ENDLIST\n")
        }
        val pending = File(output.parentFile, "recording.m3u8.part")
        pending.writeText(text)
        if (!pending.renameTo(output)) { output.writeText(text); pending.delete() }
    }

    private fun download(url: String, output: File, range: String?, key: HlsKey?, sequence: Long) {
        require(key == null || key.method == "AES-128") { "Unsupported HLS encryption: ${key?.method}" }
        val conn = connect(url, range)
        try {
            val raw = conn.inputStream
            val input: InputStream = if (key?.method == "AES-128") {
                val keyUrl = requireNotNull(key.uri) { "Missing AES key URI" }
                val keyBytes = keys.getOrPut(keyUrl) { getBytes(keyUrl, 1024) }
                require(keyBytes.size == 16) { "AES-128 key must be 16 bytes" }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes,"AES"),
                    IvParameterSpec(RecordingFormats.ivBytes(key.iv, sequence)))
                CipherInputStream(raw, cipher)
            } else raw
            input.use { stream -> FileOutputStream(output).use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    reserve(count.toLong())
                    target.write(buffer,0,count)
                }
                target.fd.sync()
            } }
        } finally { conn.disconnect() }
    }

    private fun readText(url: String): String = getBytes(url, 2 * 1024 * 1024).toString(Charsets.UTF_8)
    private fun getBytes(url: String, max: Int): ByteArray {
        val c = connect(url)
        try {
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= max) { "Playlist / key is too large" }
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        } finally { c.disconnect() }
    }
    private fun connect(url: String, byteRange: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TVNext/0.5.21-recovery")
        if (byteRange != null) {
            val length = byteRange.substringBefore('@').toLongOrNull()
            val offset = byteRange.substringAfter('@', "").toLongOrNull()
            require(length != null && length > 0 && offset != null && offset >= 0) { "Unsupported implicit HLS byte range" }
            connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        }
        connection.connect()
        require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        if (byteRange != null) require(connection.responseCode == 206) { "Server ignored byte range" }
        return connection
    }
    private fun reserve(amount: Long) {
        require(bytes.addAndGet(amount) <= maxBytes) { "Recording storage limit exceeded" }
        onProgress(bytes.get())
    }
}
