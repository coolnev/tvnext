package com.tvnext.app.core.iptv.data

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
import java.io.File
import java.util.zip.GZIPInputStream

actual fun openEpgTextSource(path: String): EpgTextSource =
    object : EpgTextSource {
        private val reader = File(path).inputStream().reader(Charsets.UTF_8).buffered(1 shl 20)
        override fun read(into: CharArray, offset: Int, length: Int): Int = reader.read(into, offset, length)
        override fun close() {
            runCatching { reader.close() }
        }
    }

actual fun deleteEpgTempFile(path: String) {
    runCatching { File(path).delete() }
}

actual fun platformEpgCacheDir(): String? = runCatching {
    val dir = File(System.getProperty("user.home"), ".tvnext/cache/epg")
    dir.mkdirs()
    dir.absolutePath
}.getOrNull()

actual fun writeEpgCacheFile(path: String, text: String) {
    runCatching {
        val tmp = File("$path.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(path)
        target.delete()
        tmp.renameTo(target)
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
        tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(header)
            writer.newLine()
            for (line in lines) {
                writer.write(line)
                writer.newLine()
            }
        }
        val target = File(path)
        target.delete()
        tmp.renameTo(target)
        true
    }.getOrElse { false }

actual fun localTimezoneOffsetMs(atEpochMs: Long): Long =
    java.util.TimeZone.getDefault().getOffset(atEpochMs).toLong()

/**
 * Live streaming EPG source: Ktor response → (gzip) → UTF-8 reader. [block]
 * runs while the HTTP response is open, so the parser consumes bytes as they
 * arrive and the first programmes appear while the rest of the document is
 * still downloading.
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
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                false
            } else {
                val raw = BufferedInputStream(response.bodyAsChannel().toInputStream(), 64 * 1024)
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val decoded = if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(raw, 64 * 1024) else raw
                val source = object : EpgTextSource {
                    private val reader = java.io.BufferedReader(
                        java.io.InputStreamReader(decoded, Charsets.UTF_8),
                        64 * 1024,
                    )
                    override fun read(into: CharArray, offset: Int, length: Int): Int =
                        runCatching { reader.read(into, offset, length) }.getOrElse { -1 }
                    override fun close() {
                        runCatching { reader.close() }
                    }
                }
                try {
                    block(source)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    } catch (e: Exception) {
        false
    }

/**
 * Desktop heaps are large enough to hold a mid-size XMLTV as a String; the
 * parser's fast paths (native `String.indexOf`) then import it several times
 * faster than the sliding-buffer scanner. Files above [maxBytes] stay on the
 * streaming path.
 */
actual fun readEpgFileToString(path: String, maxBytes: Long): String? {
    val file = File(path)
    if (!file.isFile || file.length() > maxBytes) return null
    return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
}

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
                val tmp = File.createTempFile("tvnext-epg", ".xml")
                try {
                    val raw = BufferedInputStream(response.bodyAsChannel().toInputStream(), 64 * 1024)
                    raw.mark(2)
                    val b0 = raw.read()
                    val b1 = raw.read()
                    raw.reset()
                    val gzip = b0 == 0x1F && b1 == 0x8B
                    val decoded = if (gzip) GZIPInputStream(raw) else raw
                    var tooLargeTotal = -1L
                    decoded.use { src ->
                        tmp.outputStream().buffered(64 * 1024).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = src.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_DECOMPRESSED_BYTES) {
                                    tooLargeTotal = total
                                    break
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (tooLargeTotal >= 0) {
                        tmp.delete()
                        Failure(
                            AppError.InvalidData(
                                "EPG слишком большой после распаковки (${tooLargeTotal / 1024 / 1024} МБ)",
                            ),
                        )
                    } else {
                        Success(tmp.absolutePath)
                    }
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    Failure(AppError.Network(cause = e))
                }
            }
        }
    } catch (e: Exception) {
        Failure(AppError.Network(cause = e))
    }

private const val USER_AGENT = "TVNext/0.1"
private const val REQUEST_TIMEOUT_MS = 120_000L
private const val SOCKET_TIMEOUT_MS = 60_000L

/** Hard ceiling for the DECOMPRESSED document (temp file). */
private const val MAX_DECOMPRESSED_BYTES = 2048L * 1024 * 1024

actual fun paceEpgCacheHydration() = Unit

actual fun paceEpgParser() = Unit

actual fun epgParseDispatcher(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

actual fun checkEpgImportPriority() = Unit

actual fun epgCacheMemoryExceeded(): Boolean = false
