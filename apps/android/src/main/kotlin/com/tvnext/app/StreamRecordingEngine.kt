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
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    private val bytes = AtomicLong(0)
    private lateinit var recordingFolder: File
    private var nextSpaceCheck = 0L
    private val keys = mutableMapOf<String, ByteArray>()
    private val seen = hashSetOf<String>()

    suspend fun capture(streamUrl: String, folder: File, durationMs: Long): String {
        require(streamUrl.startsWith("https://", true) || streamUrl.startsWith("http://", true)) { "Unsupported URL protocol" }
        require(durationMs in 1L..(6L * 60 * 60 * 1000)) { "Invalid recording duration" }
        recordingFolder = folder
        disk { if (!folder.isDirectory && !folder.mkdirs()) throw RecordingFailure(RecordingFailureReason.STORAGE_UNAVAILABLE) }
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
            network { connection.inputStream.use { input ->
                disk { FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (SystemClock.elapsedRealtime() - started < durationMs) {
                        currentCoroutineContext().ensureActive()
                        val n = network { input.read(buffer) }
                        if (n < 0) break
                        reserve(n.toLong())
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                } }
            } }
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
        var primaryFailure: Throwable? = null
        try {
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
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (records.isNotEmpty()) try {
                writeIndex(outputIndex, records, initForSegment, targetDuration, finished = true)
            } catch (finalization: Exception) {
                if (primaryFailure == null) throw finalization
                primaryFailure.addSuppressed(finalization)
            }
        }
        require(records.isNotEmpty()) { "No media segments were received" }
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
        disk {
            pending.writeText(text)
            if (!pending.renameTo(output)) { output.writeText(text); pending.delete() }
        }
    }

    private fun download(url: String, output: File, range: String?, key: HlsKey?, sequence: Long) {
        require(key == null || key.method == "AES-128") { "Unsupported HLS encryption: ${key?.method}" }
        val conn = connect(url, range)
        try {
            val raw = network { conn.inputStream }
            val input: InputStream = if (key?.method == "AES-128") {
                val keyUrl = requireNotNull(key.uri) { "Missing AES key URI" }
                val keyBytes = keys.getOrPut(keyUrl) { getBytes(keyUrl, 1024) }
                require(keyBytes.size == 16) { "AES-128 key must be 16 bytes" }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes,"AES"),
                    IvParameterSpec(RecordingFormats.ivBytes(key.iv, sequence)))
                CipherInputStream(raw, cipher)
            } else raw
            network { input.use { stream -> disk { FileOutputStream(output).use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = network { stream.read(buffer) }
                    if (count < 0) break
                    reserve(count.toLong())
                    target.write(buffer,0,count)
                }
                target.fd.sync()
            } } } }
        } finally { conn.disconnect() }
    }

    private fun readText(url: String): String = getBytes(url, 2 * 1024 * 1024).toString(Charsets.UTF_8)
    private fun getBytes(url: String, max: Int): ByteArray {
        val c = connect(url)
        try {
            network { c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= max) { "Playlist / key is too large" }
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } }
        } finally { c.disconnect() }
    }
    private fun connect(url: String, byteRange: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", com.tvnext.app.core.common.AppVersion.USER_AGENT)
        if (byteRange != null) {
            val length = byteRange.substringBefore('@').toLongOrNull()
            val offset = byteRange.substringAfter('@', "").toLongOrNull()
            require(length != null && length > 0 && offset != null && offset >= 0) { "Unsupported implicit HLS byte range" }
            connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        }
        network {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                throw RecordingFailure(RecordingFailureReason.SOURCE)
            }
        }
        if (byteRange != null) require(connection.responseCode == 206) { "Server ignored byte range" }
        return connection
    }
    private fun reserve(amount: Long) {
        val total = bytes.addAndGet(amount)
        if (total > maxBytes) throw RecordingFailure(RecordingFailureReason.LIMIT)
        if (total >= nextSpaceCheck) {
            if (!recordingFolder.isDirectory) throw RecordingFailure(RecordingFailureReason.STORAGE_UNAVAILABLE)
            // Leave room for the final HLS index and Android's metadata. Check once per MiB.
            if (disk { availableBytes(recordingFolder) } < 16L * 1024 * 1024 + amount)
                throw RecordingFailure(RecordingFailureReason.STORAGE_FULL)
            nextSpaceCheck = total + 1024 * 1024
        }
        onProgress(total)
    }

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (known: RecordingFailure) {
        throw known
    } catch (failure: java.io.IOException) {
        throw RecordingFailure(RecordingFailureReason.NETWORK, failure)
    }

    private inline fun <T> disk(block: () -> T): T = try {
        block()
    } catch (known: RecordingFailure) {
        throw known
    } catch (failure: java.io.IOException) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        val full = causes.any { cause ->
            cause is android.system.ErrnoException &&
                cause.errno in setOf(android.system.OsConstants.ENOSPC, android.system.OsConstants.EDQUOT) ||
                cause.message.orEmpty().let { "ENOSPC" in it || "EDQUOT" in it || "No space left" in it }
        }
        throw RecordingFailure(if (full) RecordingFailureReason.STORAGE_FULL else RecordingFailureReason.STORAGE_WRITE, failure)
    } catch (failure: SecurityException) {
        throw RecordingFailure(RecordingFailureReason.STORAGE_UNAVAILABLE, failure)
    }
}
