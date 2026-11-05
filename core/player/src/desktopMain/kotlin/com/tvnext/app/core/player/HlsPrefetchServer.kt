package com.tvnext.app.core.player

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Local HTTP proxy that serves a remote HLS **VOD** stream to libmpv while
 * downloading the segments in **parallel**.
 *
 * Why this exists: the `m.cdntv.online` VOD CDN throttles every TCP
 * connection to ~1 Mbit/s. libmpv/ffmpeg's HLS demuxer fetches segments one
 * after another over a single connection, so a 3.5–9 Mbit/s movie stutters
 * every few seconds. Fetching 8 segments concurrently sums to ~7 Mbit/s
 * (verified) — enough to outrun the playback bitrate.
 *
 * Why curl.exe: the CDN (Cloud.ru, "evo" server) fingerprints the TLS stack
 * and rejects non-schannel clients — Java's JSSE HttpClient gets a 50-byte
 * stub, Python/OpenSSL hangs, but curl.exe (Windows schannel) and ffmpeg
 * download fine. Since Windows 10, curl.exe ships in System32, so the
 * prefetcher shells out to it for every fetch.
 *
 * How it works:
 *  1. [start] downloads the remote `.m3u8`, resolves the segment URLs and
 *     rewrites the playlist so every segment points at
 *     `http://127.0.0.1:{port}/seg/{index}`.
 *  2. libmpv opens `http://127.0.0.1:{port}/playlist.m3u8` instead of the
 *     remote URL. All segment traffic goes through the loopback interface, so
 *     it is fast regardless of the CDN.
 *  3. Each segment is downloaded into its OWN temp file by a curl process.
 *     The `/seg/{index}` handler STREAMS that file to libmpv as it grows
 *     (chunked transfer): mpv can start decoding the first bytes long before
 *     the segment is complete — exactly like playing from the CDN directly,
 *     only faster because the segment files fill up in parallel.
 *  4. A background prefetcher keeps `[concurrentLoads]` curl processes going
 *     AHEAD of the current playback position, so by the time mpv asks for
 *     segment N the file is usually already complete.
 *  5. Seeking far ahead is handled too: a requested segment that has no
 *     download task yet is started immediately and the handler streams it as
 *     it fills.
 *
 * The cache is bounded: segments behind the playback position are deleted
 * after the LRU cap, so a 2-hour movie does not balloon the disk.
 *
 * ONLY intended for VOD playlists. LIVE/ARCHIVE go straight to libmpv.
 */
class HlsPrefetchServer(
    private val remotePlaylistUrl: String,
    private val concurrentLoads: Int = 8,
    private val lookaheadSegments: Int = 20,
    private val maxCachedSegments: Int = 60,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(concurrentLoads)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val port: Int = server.address.port

    /** Rewritten playlist served to libmpv. */
    private var localPlaylist: ByteArray = EMPTY_PLAYLIST
    private var segmentUrls: List<String> = emptyList()

    /** Segment download state: index → { file, known size, completion future }. */
    private class Segment(
        val file: File,
        val done: CompletableFuture<Unit> = CompletableFuture(),
    ) {
        /** Expected segment size from the CDN Content-Length header (-1 = unknown). */
        @Volatile
        var expectedSize: Long = -1L
    }

    private val segments = ConcurrentHashMap<Int, Segment>()
    private val cacheOrder = ConcurrentLinkedQueue<Int>()
    private val lastRequested = AtomicInteger(-1)
    private val cacheDir: File

    /** URL libmpv should open instead of [remotePlaylistUrl]. */
    val localPlaylistUrl: String = "http://127.0.0.1:$port/playlist.m3u8"

    @Volatile
    private var started = false

    init {
        cacheDir = Files.createTempDirectory("tvnext-hls").toFile()
        cacheDir.deleteOnExit()
    }

    /** Fetches the remote playlist and prepares the local proxy. May throw. */
    suspend fun start() {
        if (started) return
        started = true
        val playlistText = withContext(Dispatchers.IO) { fetchText(remotePlaylistUrl) }
        val (segmentText, resolved) = resolvePlaylist(playlistText, remotePlaylistUrl)
        segmentUrls = resolved
        localPlaylist = buildLocalPlaylist(segmentText, resolved.size).toByteArray(Charsets.UTF_8)
        println("[HlsPrefetch] resolved ${resolved.size} segments; local playlist:\n${String(localPlaylist, Charsets.UTF_8).take(600)}")

        server.createContext("/playlist.m3u8") { exchange ->
            try {
                println("[HlsPrefetch] playlist request from ${exchange.remoteAddress}")
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                exchange.sendResponseHeaders(200, localPlaylist.size.toLong())
                exchange.responseBody.use { it.write(localPlaylist) }
            } catch (_: Exception) {
                // client (mpv) closed the connection; nothing to do
            }
        }
        server.createContext("/seg") { exchange ->
            // Path is /seg/{index} or /seg/{index}.ts — strip any extension.
            val rawIndex = exchange.requestURI.path.removePrefix("/seg/").removeSuffix(".ts")
            val index = rawIndex.toIntOrNull()
            if (index == null || index !in segmentUrls.indices) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            lastRequested.set(index)
            streamSegment(exchange, index)
        }
        server.executor = null // default: creates a thread per request
        server.start()

        // Prefetch the first batch immediately so playback can begin without
        // waiting for the first segment to be queued by an mpv request.
        prefetchLoop()
    }

    override fun close() {
        started = false
        runCatching { server.stop(0) }
        runCatching { cacheDir.deleteRecursively() }
        scope.cancel()
    }

    // ---- prefetch -----------------------------------------------------------

    private fun prefetchLoop() = scope.launch {
        var next = 0
        while (isActive) {
            val requested = lastRequested.get()
            val target = if (requested < 0) {
                lookaheadSegments
            } else {
                (requested + lookaheadSegments).coerceAtMost(segmentUrls.size)
            }
            if (next < segmentUrls.size && next <= target) {
                ensureDownloading(next)
                next++
            } else {
                if (next >= segmentUrls.size) break
                delay(250)
            }
        }
    }

    /**
     * Guarantees a download task exists for [index]; the task is shared by all
     * callers (mpv may retry a segment after a connection drop).
     */
    private fun ensureDownloading(index: Int): Segment {
        return segments.computeIfAbsent(index) {
            cacheOrder.add(index)
            evictOldFinished()
            val segment = Segment(fileFor(index))
            scope.launch {
                try {
                    gate.withPermit { fetchSegmentToFile(index, segment) }
                    segment.done.complete(Unit)
                } catch (e: Throwable) {
                    println("[HlsPrefetch] segment $index FAILED: ${e.message}")
                    segment.done.completeExceptionally(e)
                }
            }
            segment
        }
    }

    private fun fileFor(index: Int): File = File(cacheDir, "seg_$index.ts")

    private suspend fun fetchSegmentToFile(index: Int, segment: Segment) {
        val url = segmentUrls.getOrNull(index) ?: throw IOException("segment $index out of range")
        val curl = findCurl() ?: throw IOException("curl not found")
        var attempt = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    runCurlToFile(curl, url, segment.file, timeoutSec = FETCH_TIMEOUT_SEC) { headers ->
                        // Parse Content-Length from curl's -D header dump: ffmpeg's
                        // HLS demuxer requires a known segment size to start
                        // decoding (it refuses chunked responses). Expose it as
                        // soon as the headers arrive so the streaming handler can
                        // answer with an exact Content-Length.
                        val cl = headers.lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.toLongOrNull()
                        if (cl != null && cl > 0) segment.expectedSize = cl
                    }
                }
                return
            } catch (e: Exception) {
                if (attempt >= MAX_FETCH_RETRIES) throw e
                attempt++
                delay(FETCH_RETRY_DELAY_MS * attempt)
            }
        }
    }

    // ---- streaming handler ---------------------------------------------------

    /**
     * Streams segment [index] to [exchange]. ffmpeg's HLS demuxer requires an
     * exact Content-Length to start decoding — it refuses chunked responses.
     * So we wait for the CDN's Content-Length (from curl's -D header dump,
     * usually available within a second) and then answer with that exact
     * length, writing the growing file body as it downloads. The first bytes
     * reach libmpv within a couple of seconds; the rest arrives as the file
     * fills.
     */
    private fun streamSegment(exchange: com.sun.net.httpserver.HttpExchange, index: Int) {
        val segment = ensureDownloading(index)
        val file = segment.file
        println("[HlsPrefetch] stream request seg/$index done=${segment.done.isDone} file=${file.exists()} len=${if (file.exists()) file.length() else -1}")
        try {
            exchange.responseHeaders.add("Content-Type", "video/MP2T")
            exchange.responseHeaders.add("Cache-Control", "no-cache")

            // Wait for the Content-Length to become known (or the download to
            // finish), bounded — a stuck CDN must not hang mpv forever.
            var waited = 0L
            while (segment.expectedSize < 0L && !segment.done.isDone && waited < CONTENT_LENGTH_WAIT_MS) {
                Thread.sleep(50)
                waited += 50
            }

            val expected = segment.expectedSize
            println("[HlsPrefetch] seg/$index expectedSize=$expected done=${segment.done.isDone} len=${file.length()}")
            if (segment.done.isDone) {
                // Full segment already on disk: plain send with known size.
                val size = file.length()
                if (size <= 0) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return
                }
                exchange.sendResponseHeaders(200, size)
                Files.newInputStream(file.toPath()).use { input ->
                    input.copyTo(exchange.responseBody)
                }
            } else if (expected > 0) {
                // Content-Length known: stream the growing file with an exact
                // length (what ffmpeg needs). The server will not close the
                // response until `expected` bytes have been written, so mpv
                // receives data as it arrives and starts decoding early.
                exchange.sendResponseHeaders(200, expected)
                streamFileUpTo(exchange.responseBody, file, segment, expected)
            } else {
                // Unknown size: fall back to chunked (rare; usually the header
                // arrives in time).
                println("[HlsPrefetch] seg/$index fallback to chunked")
                exchange.sendResponseHeaders(200, 0)
                streamGrowingFile(exchange.responseBody, file, segment)
            }
        } catch (e: Exception) {
            // Client closed or write failed; the download continues in the
            // background and stays cached for a retry.
        } finally {
            runCatching { exchange.close() }
        }
    }

    /**
     * Writes the file body until [targetSize] bytes have been output (or the
     * download fails). Reads the growing file, so bytes are delivered as they
     * arrive; ffmpeg can begin decoding as soon as it has enough TS data.
     */
    private fun streamFileUpTo(
        out: java.io.OutputStream,
        file: File,
        segment: Segment,
        targetSize: Long,
    ) {
        // curl spawns, then creates+writes the file; wait for it to appear.
        var waited = 0L
        while (!file.exists() && !segment.done.isDone && waited < IDLE_STREAM_TIMEOUT_MS) {
            Thread.sleep(25)
            waited += 25
        }
        if (!file.exists()) return
        val raf = RandomAccessFile(file, "r")
        try {
            var pos = 0L
            var lastWrite = System.currentTimeMillis()
            while (pos < targetSize && !segment.done.isCompletedExceptionally) {
                val len = file.length().coerceAtMost(targetSize)
                if (len > pos) {
                    raf.seek(pos)
                    val buf = ByteArray(64 * 1024)
                    while (pos < len) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.flush()
                    lastWrite = System.currentTimeMillis()
                } else {
                    // No new bytes yet; avoid busy-waiting.
                    if (System.currentTimeMillis() - lastWrite > IDLE_STREAM_TIMEOUT_MS) {
                        return // give up, client can retry
                    }
                    Thread.sleep(50)
                }
            }
            out.flush()
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun streamGrowingFile(
        out: java.io.OutputStream,
        file: File,
        segment: Segment,
    ) {
        // curl spawns, then creates+writes the file; wait for it to appear.
        var waited = 0L
        while (!file.exists() && !segment.done.isDone && waited < IDLE_STREAM_TIMEOUT_MS) {
            Thread.sleep(25)
            waited += 25
        }
        if (!file.exists()) return // download never started; client can retry
        val raf = RandomAccessFile(file, "r")
        try {
            var pos = 0L
            var lastWrite = System.currentTimeMillis()
            while (!segment.done.isDone) {
                val len = file.length()
                if (len > pos) {
                    raf.seek(pos)
                    val buf = ByteArray(64 * 1024)
                    while (pos < len) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.flush()
                    lastWrite = System.currentTimeMillis()
                } else {
                    // No new bytes yet; avoid busy-waiting.
                    if (System.currentTimeMillis() - lastWrite > IDLE_STREAM_TIMEOUT_MS) {
                        return // give up, client can retry
                    }
                    Thread.sleep(50)
                }
            }
            // Download finished: write whatever remains, then finish the chunk.
            val len = file.length()
            if (len > pos) {
                raf.seek(pos)
                val buf = ByteArray(64 * 1024)
                while (pos < len) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            out.flush()
        } finally {
            runCatching { raf.close() }
        }
    }

    // ---- LRU ----------------------------------------------------------------

    private fun evictOldFinished() {
        while (cacheOrder.size > maxCachedSegments) {
            val oldest = cacheOrder.poll() ?: break
            // Only evict COMPLETED segments: an in-flight one must stay.
            val segment = segments[oldest]
            if (segment != null && segment.done.isDone && !segment.done.isCancelled) {
                segments.remove(oldest)
                runCatching { segment.file.delete() }
            } else {
                cacheOrder.add(oldest)
                break
            }
        }
    }

    // ---- curl-based fetching ------------------------------------------------

    private fun fetchText(url: String): String {
        val curl = findCurl() ?: throw IOException("curl not found")
        val tmp = File.createTempFile("tvnext-playlist-", ".m3u8")
        try {
            val ok = runCurlToFile(curl, url, tmp, timeoutSec = FETCH_TIMEOUT_SEC)
            if (!ok) throw IOException("curl failed to fetch $url")
            return tmp.readText(Charsets.UTF_8)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * Runs curl.exe writing to [outFile]. Returns true on success. The file
     * grows as bytes arrive — the caller can stream it before completion.
     * The file is truncated first so a stale partial download from a previous
     * attempt cannot be streamed as if it were the fresh download.
     *
     * @param onHeaders invoked with curl's `-D` header dump as soon as it is
     *   written (before the body download completes), used to learn the
     *   Content-Length.
     */
    private fun runCurlToFile(
        curl: String,
        url: String,
        outFile: File,
        timeoutSec: Long,
        onHeaders: ((String) -> Unit)? = null,
    ): Boolean {
        runCatching { outFile.delete() }
        // Ensure the file exists BEFORE curl starts: a reader may open it via
        // RandomAccessFile the moment the download task is queued.
        runCatching { outFile.createNewFile() }
        val headerFile = File.createTempFile("tvnext-hdr-", ".txt")
        val cmd = listOf(
            curl,
            "-sS",              // silent but show errors on stderr
            "--max-time", timeoutSec.toString(),
            "--connect-timeout", "20",
            "--retry", "2",
            "--retry-delay", "1",
            "--retry-connrefused",
            "--http1.1",        // CDN answers HTTP/1.1 cleanly; HTTP/2 RST_STREAMs
            // The app may run under a JVM/Gradle shell that exports
            // HTTP_PROXY/HTTPS_PROXY; curl honours those env vars and would
            // route the video through a broken proxy (tiny error replies).
            "--noproxy", "*",
            "-H", "User-Agent: $USER_AGENT",
            "-H", "Accept: video/MP2T,application/octet-stream,*/*",
            "-D", headerFile.absolutePath,
            "-o", outFile.absolutePath,
            url,
        )
        return try {
            val process = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // Poll the header file so the Content-Length is known as early as
            // possible (ffmpeg needs it to start decoding).
            val headerWatcher = Thread {
                var waited = 0L
                while (process.isAlive && waited < timeoutSec * 1000) {
                    if (headerFile.length() > 0L) {
                        runCatching {
                            val text = headerFile.readText(Charsets.UTF_8)
                            if (text.isNotBlank()) onHeaders?.invoke(text)
                        }
                        break
                    }
                    Thread.sleep(50)
                    waited += 50
                }
            }.apply { isDaemon = true; name = "tvnext-hdr-watcher"; start() }

            val finished = process.waitFor(timeoutSec + 10, TimeUnit.SECONDS)
            headerWatcher.join(2_000L)
            if (!finished) {
                process.destroy()
                process.waitFor(5, TimeUnit.SECONDS)
                false
            } else {
                process.exitValue() == 0 && outFile.length() > 0
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { headerFile.delete() }
        }
    }

    private fun findCurl(): String? {
        if (cachedCurl != null) return cachedCurl
        // Prefer the System32 curl.exe (always present on Windows 10+); fall
        // back to whatever `curl` resolves to in PATH.
        val candidates = buildList {
            System.getenv("SystemRoot")?.let { root -> add("$root\\System32\\curl.exe") }
            add("C:\\Windows\\System32\\curl.exe")
            add("curl")
        }
        val found = candidates.firstOrNull { candidate ->
            val file = File(candidate)
            file.isFile && file.canExecute() || candidate == "curl"
        } ?: "curl"
        cachedCurl = found
        return found
    }

    // ---- playlist parsing ----------------------------------------------------

    /**
     * Resolves a (possibly master) playlist to its flat segment list. Returns
     * the FLATTENED playlist text too, so [buildLocalPlaylist] rewrites the
     * playlist that actually lists the segments (a master playlist lists only
     * variants — rewriting that instead would produce a playlist whose URLs are
     * the variant, not the segments).
     */
    private fun resolvePlaylist(text: String, playlistUrl: String): Pair<String, List<String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hasVariants = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        if (hasVariants) {
            val variant = lines.firstOrNull { !it.startsWith("#") }
                ?: throw IOException("master playlist without a variant")
            val variantUrl = resolveUrl(variant, playlistUrl)
            val variantText = fetchText(variantUrl)
            return resolvePlaylist(variantText, variantUrl)
        }
        val base = URI(playlistUrl)
        val segments = lines.mapNotNull { line ->
            if (line.startsWith("#")) null
            else resolveUrl(line, playlistUrl)
        }
        return text to segments
    }

    private fun resolveUrl(raw: String, baseUrl: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = URI(baseUrl)
        return base.resolve(raw).toString()
    }

    /** Rewrites segment lines to the loopback proxy; keeps all #EXT tags. */
    private fun buildLocalPlaylist(original: String, segmentCount: Int): String {
        val out = StringBuilder()
        var segmentIndex = 0
        val lines = original.lines().map { it.trim() }
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            if (line.startsWith("#")) {
                out.append(line).append('\n')
                lineIndex++
                continue
            }
            if (segmentIndex < segmentCount) {
                // .ts extension matters: ffmpeg's HLS demuxer decides the
                // segment format by file extension and refuses to probe a
                // segment without one.
                out.append("http://127.0.0.1:$port/seg/$segmentIndex.ts\n")
                segmentIndex++
            }
            lineIndex++
        }
        return out.toString()
    }

    private companion object {
        @Volatile
        var cachedCurl: String? = null

        val EMPTY_PLAYLIST = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:0,\n\n".toByteArray()
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        const val MAX_FETCH_RETRIES = 3
        const val FETCH_RETRY_DELAY_MS = 500L
        const val FETCH_TIMEOUT_SEC = 120L
        const val IDLE_STREAM_TIMEOUT_MS = 30_000L
        /** How long to wait for the CDN Content-Length header before falling back to chunked. */
        const val CONTENT_LENGTH_WAIT_MS = 3_000L
    }
}
