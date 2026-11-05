package com.tvnext.app.core.player

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Diagnostics probe for [HlsPrefetchServer]: starts the prefetch proxy against
 * a real VOD HLS URL, then downloads the first few segments THROUGH the proxy
 * (loopback) and measures the aggregate speed. Compare with the same segments
 * fetched directly from the CDN to confirm the proxy bypasses the
 * per-connection throttle.
 *
 * Both paths use curl.exe (the only TLS stack this CDN accepts).
 *
 * Lives in desktopMain, launched by the `probeRunHlsPrefetch` Gradle task
 * (:core:player). Probe-only; not wired into DI or the app.
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "https://m.cdntv.online/f/9y6xwink2s/The_Great_Gatsby_2013.m3u8"
    val segments = args.getOrNull(1)?.toIntOrNull() ?: 5

    val logFile = File("C:\\Temp\\kilo\\hls-prefetch-probe-results.txt")
    logFile.parentFile.mkdirs()
    val writer = logFile.printWriter()
    fun log(msg: String) {
        println(msg)
        writer.println(msg)
    }

    log("=== TVNext HLS prefetch probe ===")
    log("Playlist: $url")
    log("Segments: $segments")
    log("")

    // 1) Direct CDN speed (one connection at a time — what mpv would do).
    log("--- direct CDN (sequential, single connection) ---")
    // Segments live next to the playlist name WITHOUT the .m3u8 suffix:
    // .../The_Great_Gatsby_2013.m3u8 → .../The_Great_Gatsby_2013/segmentN.ts
    val prefix = url.removeSuffix(".m3u8") + "/"
    var directTotal = 0L
    val directTime = measureTimeMillis {
        for (i in 1..segments) {
            val segUrl = "$prefix/segment$i.ts"
            val t0 = System.currentTimeMillis()
            val bytes = curlDownload(segUrl)
            val dt = System.currentTimeMillis() - t0
            if (bytes == null) {
                log("segment$i: FAILED")
                continue
            }
            directTotal += bytes.size
            log("segment$i: ${bytes.size} B in ${dt}ms = ${bytes.size / dt.toFloat() * 1000 / 1024} KB/s")
        }
    }
    log("direct total: ${directTotal} B in ${directTime}ms = " +
        "${(directTotal.toFloat() / directTime * 1000 / 1024).toInt()} KB/s " +
        "(${directTotal * 8.0 / directTime / 1000} Mbit/s)")

    log("")
    log("--- through HlsPrefetchServer (parallel + streaming) ---")
    val proxy = HlsPrefetchServer(remotePlaylistUrl = url)
    kotlinx.coroutines.runBlocking { proxy.start() }
    log("local playlist: ${proxy.localPlaylistUrl}")

    val segBase = proxy.localPlaylistUrl.removeSuffix("playlist.m3u8")
    var proxyTotal = 0L
    val proxyTime = measureTimeMillis {
        for (i in 0 until segments) {
            val segUrl = "${segBase}seg/$i"
            // Time-to-first-byte is what matters for playback start: the proxy
            // streams the growing file, so mpv gets its first TS bytes quickly.
            val t0 = System.currentTimeMillis()
            val (firstByteMs, bytes) = curlDownloadWithFirstByte(segUrl, maxTimeSec = 180)
            val dt = System.currentTimeMillis() - t0
            if (bytes == null) {
                log("seg/$i: FAILED")
                continue
            }
            proxyTotal += bytes.size
            log("seg/$i: firstByte=${firstByteMs}ms total=${bytes.size} B in ${dt}ms " +
                "(${bytes.size / dt.toFloat() * 1000 / 1024} KB/s effective)")
        }
    }
    log("proxy total: ${proxyTotal} B in ${proxyTime}ms = " +
        "${(proxyTotal.toFloat() / proxyTime * 1000 / 1024).toInt()} KB/s " +
        "(${proxyTotal * 8.0 / proxyTime / 1000} Mbit/s)")

    proxy.close()
    log("")
    log("Done. Full results: $logFile")
    writer.close()
}

/** Downloads via curl; returns (ms-to-first-byte, full bytes) or null on failure. */
private fun curlDownloadWithFirstByte(url: String, maxTimeSec: Long = 120): Pair<Long, ByteArray?> {
    val tmp = File.createTempFile("tvnext-probe-", ".bin")
    val startedAt = System.currentTimeMillis()
    val firstByteAt = java.util.concurrent.atomic.AtomicLong(-1L)
    return try {
        val cmd = listOf(
            "curl",
            "-sS",
            "--max-time", maxTimeSec.toString(),
            "--connect-timeout", "20",
            "--noproxy", "*",
            "--http1.1",
            "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
            "-H", "Accept: video/MP2T,application/octet-stream,*/*",
            "-o", tmp.absolutePath,
            url,
        )
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Poll the output file to catch the first byte arrival.
        while (process.isAlive) {
            if (firstByteAt.get() < 0L && tmp.length() > 0L) {
                firstByteAt.set(System.currentTimeMillis())
            }
            Thread.sleep(10)
        }
        process.waitFor(maxTimeSec + 10, TimeUnit.SECONDS)
        if (firstByteAt.get() < 0L && tmp.length() > 0L) firstByteAt.set(System.currentTimeMillis())
        val first = if (firstByteAt.get() >= 0L) firstByteAt.get() - startedAt else -1L
        if (process.exitValue() != 0) {
            first to null
        } else {
            first to (tmp.takeIf { it.length() > 0 }?.readBytes())
        }
    } catch (e: Exception) {
        -1L to null
    } finally {
        runCatching { tmp.delete() }
    }
}

private fun curlDownload(url: String, maxTimeSec: Long = 120): ByteArray? {
    val tmp = File.createTempFile("tvnext-probe-", ".bin")
    val err = File.createTempFile("tvnext-probe-err-", ".txt")
    return try {
        val cmd = listOf(
            "curl",
            "-sS",
            "--max-time", maxTimeSec.toString(),
            "--connect-timeout", "20",
            "--noproxy", "*",
            "--http1.1",
            "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0",
            "-H", "Accept: video/MP2T,application/octet-stream,*/*",
            "-o", tmp.absolutePath,
            url,
        )
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(err)
            .start()
        val finished = process.waitFor(maxTimeSec + 10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            println("  curl timed out: ${err.readText().take(300)}")
            null
        } else {
            if (process.exitValue() != 0) {
                null
            } else {
                tmp.takeIf { it.length() > 0 }?.readBytes()
            }
        }
    } catch (e: Exception) {
        println("  curl exception: $e")
        null
    } finally {
        runCatching { tmp.delete() }
        runCatching { err.delete() }
    }
}
