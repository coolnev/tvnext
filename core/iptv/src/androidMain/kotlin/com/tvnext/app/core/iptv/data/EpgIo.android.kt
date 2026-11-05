package com.tvnext.app.core.iptv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.common.result.AppResult.Failure
import com.tvnext.app.core.common.result.AppResult.Success
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream
import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Application [Context], supplied once at startup via [installEpgAndroidContext]
 * (called from `TVNextApplication.onCreate`).
 *
 * WHY THIS EXISTS: `File.createTempFile(prefix, suffix)` (no explicit directory)
 * resolves its target directory from the `java.io.tmpdir` system property. A
 * desktop JVM always sets this to a valid, writable OS temp directory — but
 * Android does NOT reliably set it, so the two-arg overload throws
 * `IOException` on real devices before a single byte is downloaded. Every EPG
 * refresh then fails immediately with a generic "не удалось загрузить EPG",
 * even though the URL and network are fine. `Context.cacheDir` is always
 * writable and private to the app — the same directory this app already uses
 * for the logo image cache (see `TVNextApplication.logoKoinModule`).
 */
private object EpgAndroidContextHolder {
    @Volatile
    var appContext: Context? = null
}

/** Must be called once, early in app startup, before the first EPG refresh. */
fun installEpgAndroidContext(context: Context) {
    EpgAndroidContextHolder.appContext = context.applicationContext
}

/** Removes decoded XMLTV temp files left behind by a force-stop/crash. */
fun cleanupOrphanedEpgTempFiles(context: Context) {
    val dir = context.cacheDir
    runCatching {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("tvnext-epg") && it.name.endsWith(".xml") }
            ?.forEach { it.delete() }
    }
}

private fun createEpgTempFile(): File {
    val cacheDir = EpgAndroidContextHolder.appContext?.cacheDir
    return if (cacheDir != null) {
        File.createTempFile("tvnext-epg", ".xml", cacheDir)
    } else {
        // Should not happen once installEpgAndroidContext() is wired up at
        // startup; falling back keeps behavior identical to before this fix
        // rather than crashing outright.
        File.createTempFile("tvnext-epg", ".xml")
    }
}

actual fun openEpgTextSource(path: String): EpgTextSource =
    object : EpgTextSource {
        private val input = BufferedInputStream(File(path).inputStream(), 64 * 1024)
        private val source = run {
            input.mark(2)
            val b0 = input.read()
            val b1 = input.read()
            input.reset()
            val gzip = b0 == 0x1F && b1 == 0x8B
            (if (gzip) GZIPInputStream(input, 64 * 1024) else input)
                .reader(Charsets.UTF_8)
                .buffered(1 shl 20)
        }
        override fun read(into: CharArray, offset: Int, length: Int): Int {
            return source.read(into, offset, length)
        }
        override fun close() {
            runCatching { source.close() }
        }
    }

actual fun deleteEpgTempFile(path: String) {
    runCatching { File(path).delete() }
}

actual fun platformEpgCacheDir(): String? = runCatching {
    val context = EpgAndroidContextHolder.appContext ?: return@runCatching null
    // Android may evict cacheDir at any time. Keep complete programme imports
    // in persistent app-private storage (excluded from huge cloud backups).
    val dir = File(context.noBackupFilesDir, "epg_cache")
    val previous = File(context.cacheDir, "epg_cache")
    if (!dir.exists() && previous.isDirectory) {
        // Upgrade in place: both locations are on the app's private volume.
        // A failed move leaves the old import untouched for a later attempt.
        if (!previous.renameTo(dir)) {
            // Keep using the existing complete cache if migration is not
            // possible yet, rather than hiding it behind an empty directory.
            return@runCatching previous.absolutePath
        }
    }
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
    dir.absolutePath
}.getOrNull()

actual fun writeEpgCacheFile(path: String, text: String) {
    runCatching {
        val tmp = File("$path.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(path)
        check(tmp.renameTo(target)) { "Cannot commit EPG cache" }
    }
}

actual fun readEpgCacheFile(path: String): String? = runCatching {
    val file = File(path)
    if (file.isFile) file.readText(Charsets.UTF_8) else null
}.getOrNull()

actual fun readEpgCacheLines(path: String, onLine: (String) -> Boolean): Boolean = runCatching {
    val file = File(path)
    if (!file.isFile) return false
    var complete = true
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            if (!onLine(line)) {
                complete = false
                break
            }
        }
    }
    complete
}.getOrElse { false }

actual fun writeEpgCacheStreaming(path: String, header: String, lines: Sequence<String>): Boolean =
    runCatching {
        val tmp = File("$path.tmp")
        // Keep cache persistence low-priority and avoid long uninterrupted
        // write bursts that starve Media3 I/O on inexpensive Android TV boxes.
        val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(header)
                writer.newLine()
                var written = 0
                for (line in lines) {
                    // Video does not interrupt persistence. Throttle bursts
                    // when decoding and keep the atomic previous cache intact.
                    if (written % 64 == 0) checkEpgImportPriority()
                    writer.write(line)
                    writer.newLine()
                    if (++written % 64 == 0) {
                        Thread.sleep(if (AndroidEpgPlaybackGate.isVideoActive) 10L else 2L)
                    }
                }
            }
        } finally {
            android.os.Process.setThreadPriority(priority)
        }
        val target = File(path)
        check(tmp.renameTo(target)) { "Cannot commit EPG cache" }
        true
    }.getOrElse {
        runCatching { File("$path.tmp").delete() }
        false
    }

actual fun localTimezoneOffsetMs(atEpochMs: Long): Long =
    java.util.TimeZone.getDefault().getOffset(atEpochMs).toLong()

/**
 * Live streaming EPG source (gzip-aware): Ktor response → UTF-8 reader. [block]
 * runs while the HTTP response is open, so the parser consumes bytes as they
 * arrive and the first programmes appear while the rest of the document is
 * still downloading. Memory stays bounded (64 KiB chunks), safe on low-end
 * Android TV boxes.
 */
actual suspend fun withEpgStream(
    client: HttpClient,
    url: String,
    block: suspend (EpgTextSource) -> Unit,
): Boolean =
    try {
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/xml, application/gzip, */*")
            timeout {
                // Includes low-priority parsing and disk indexing of large feeds.
                // Socket timeout still detects a stalled server.
                requestTimeoutMillis = 30L * 60 * 1000
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                false
            } else {
                val readContext = currentCoroutineContext()
                BufferedInputStream(response.bodyAsChannel().toInputStream(), 64 * 1024).use { input ->
                input.mark(2)
                val b0 = input.read()
                val b1 = input.read()
                input.reset()
                val decoded = if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(input, 64 * 1024) else input
                val source = object : EpgTextSource {
                    private val reader = decoded.reader(Charsets.UTF_8).buffered(1 shl 20)
                    override fun read(into: CharArray, offset: Int, length: Int): Int {
                        readContext.ensureActive()
                        checkEpgImportPriority()
                        return reader.read(into, offset, length)
                    }
                    override fun close() {
                        runCatching { reader.close() }
                    }
                }
                try {
                    block(source)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EpgImportDeferredException) {
                    throw e
                } catch (e: EpgMemoryPressureException) {
                    throw e
                } catch (e: Exception) {
                    false
                } finally {
                    source.close()
                }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EpgImportDeferredException) {
        throw e
    } catch (e: EpgMemoryPressureException) {
        throw e
    } catch (e: Exception) {
        false
    }

/**
 * Android TV heaps are too small to hold a multi-hundred-MB XMLTV as a String
 * (a ~2x RAM copy). Keep the streaming path; the parser's buffer scanner is
 * the same code, just without the native `String.indexOf` fast paths.
 */
actual fun readEpgFileToString(path: String, maxBytes: Long): String? = null

/**
 * Streaming Ktor downloader: HTTP response → gunzip → temp file with a bounded
 * buffer. The whole document is never materialized in memory, so multi-hundred
 * MB XMLTV imports work on Android TV heaps.
 */
actual suspend fun downloadEpgToTempFile(
    client: HttpClient,
    url: String,
): AppResult<String> =
    try {
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/xml, application/gzip, */*")
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Failure(AppError.Network(cause = null))
            } else {
                val tmp = createEpgTempFile()
                try {
                    // Native Android path consumes the source stream directly as
                    // gzip/plain XML. Keep the compressed payload on disk: the C++
                    // parser inflates it incrementally in 1 MiB chunks. This avoids
                    // the old gzip -> 487 MB XML staging file and keeps peak RAM
                    // independent of the document size.
                    response.bodyAsChannel().toInputStream().use { input ->
                        tmp.outputStream().buffered(64 * 1024).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_COMPRESSED_BYTES) {
                                    throw IllegalArgumentException("EPG слишком большой (${total / 1024 / 1024} МБ)")
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    Success(tmp.absolutePath)
                } catch (e: CancellationException) {
                    tmp.delete()
                    throw e
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    Failure(AppError.Network(cause = e))
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Failure(AppError.Network(cause = e))
    }

private const val USER_AGENT = "TVNext/0.1"
private const val REQUEST_TIMEOUT_MS = 120_000L
private const val SOCKET_TIMEOUT_MS = 60_000L

/** Hard ceiling for the compressed source file kept on disk. */
private const val MAX_COMPRESSED_BYTES = 256L * 1024 * 1024

/** Cooperatively yield CPU to Media3 without suspending EPG until video ends. */
actual fun paceEpgParser() {
    // The indexed importer has bounded XML/batch buffers. An unrelated video
    // heap watermark or Activity.onStop must not discard a downloaded guide.
    // Android's BACKGROUND worker provides scheduling priority; avoid adding
    // ~82 seconds of fixed sleeps to a 1.5-million-programme feed during playback.
    Thread.yield()
    if (AndroidEpgPlaybackGate.isVideoActive) {
        try { Thread.sleep(1L) }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

/** Cache hydration is streamed on the XMLTV worker, never on the UI thread. */
actual fun paceEpgCacheHydration() {
    try { Thread.sleep(if (AndroidEpgPlaybackGate.isVideoActive) 8L else 1L) }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
}

/** Dedicated single worker at Android background priority, isolated from UI/Media3 pools. */
private val epgParserExecutor by lazy {
    java.util.concurrent.Executors.newSingleThreadExecutor { command ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            command.run()
        }, "tvnext-xmltv-worker").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
}

private val epgParserDispatcher by lazy {
    epgParserExecutor.asCoroutineDispatcher()
}

actual fun epgParseDispatcher(): kotlinx.coroutines.CoroutineDispatcher = epgParserDispatcher


actual fun checkEpgImportPriority() {
    // Never abort because the player is decoding. This prevents only imports
    // continuing after the foreground Activity has stopped.
    if (!AndroidEpgPlaybackGate.canImport) throw EpgImportDeferredException()
}

actual fun epgCacheMemoryExceeded(): Boolean {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) * 100L > runtime.maxMemory() * 78L
}
