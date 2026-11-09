package com.tvnext.app.core.iptv.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.compression.appliedDecoders
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable HTTP staging. Parsing/SQLite never hold an HTTP response open. */
class EpgSourceDownload(
    private val client: HttpClient,
    private val directory: File,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val downloadTimeoutMs: Long = 5 * 60_000L,
    private val stallTimeoutMs: Long = 15_000L,
    private val cacheBudgetBytes: Long = CACHE_BUDGET_BYTES,
) {
    data class Progress(val bytes: Long, val total: Long?, val attempt: Int, val resumed: Boolean = false,
        val reused: Boolean = false)
    private val lock = Mutex()

    suspend fun consume(url: String, progress: (Progress) -> Unit = {},
        ready: () -> Unit = {}, consume: suspend (File) -> Unit): Boolean = lock.withLock {
        val file = fetch(url, progress)
        ready()
        try {
            consume(file)
            // Keep the file until the database generation has committed (acknowledge()).
            true
        } catch (invalid: InvalidEpgDocumentException) {
            discard(file)
            throw invalid
        } catch (invalid: java.util.zip.ZipException) {
            discard(file)
            throw invalid
        } catch (invalid: java.io.EOFException) {
            discard(file)
            throw invalid
        }
    }

    private fun discard(file: File) {
        file.delete()
        val suffix = if (file.name.endsWith(".cached")) ".cached-meta" else ".meta"
        File(directory, file.name.substringBeforeLast('.') + suffix).delete()
    }

    fun acknowledge(url: String) {
        val key = IndexedEpgRepository.sourceId(url)
        val ready = File(directory, "$key.ready")
        val metadata = File(directory, "$key.meta")
        val cached = File(directory, "$key.cached")
        if (ready.isFile) {
            // Retain the compressed representation for conditional HTTP checks
            // and reindexing, bounded across every source on the device.
            if (ready.length() <= cacheBudgetBytes) {
                val cachedMeta = File(directory, "$key.cached-meta")
                // A crash between the two renames may lose a validator, never
                // pair an old validator with a new document.
                cachedMeta.delete()
                cached.delete()
                if (ready.renameTo(cached)) {
                    metadata.renameTo(cachedMeta)
                }
            }
            ready.delete()
        }
        metadata.delete()
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".cached") }.sortedByDescending { it.lastModified() }
        var bytes = 0L
        for (file in files) {
            if (bytes + file.length() > cacheBudgetBytes || clock() - file.lastModified() !in 0..MAX_CACHE_AGE_MS) {
                file.delete(); File(directory, file.name.removeSuffix(".cached") + ".cached-meta").delete()
            } else bytes += file.length()
        }
    }

    private suspend fun fetch(url: String, progress: (Progress) -> Unit): File {
        // One deadline for ALL retries, including Range requests. A stalled
        // source must not consume five minutes on each of four attempts.
        return withTimeoutOrNull(downloadTimeoutMs) { fetchWithinBudget(url, progress) }
            ?: throw IOException("EPG download exceeded its time budget")
    }

    private suspend fun fetchWithinBudget(url: String, progress: (Progress) -> Unit): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create EPG download directory" }
        val key = IndexedEpgRepository.sourceId(url)
        val complete = File(directory, "$key.ready")
        val partial = File(directory, "$key.part")
        val metadata = File(directory, "$key.meta")
        val cached = File(directory, "$key.cached")
        val cachedMeta = readMeta(File(directory, "$key.cached-meta"))
        val canRevalidate = cached.isFile && cached.length() > 0L && clock() - cached.lastModified() in 0..MAX_CACHE_AGE_MS
        if (complete.isFile && complete.length() > 0 && clock() - complete.lastModified() in 0..MAX_AGE_MS) {
            progress(Progress(complete.length(), complete.length(), 0, reused = true))
            return complete
        }
        complete.delete()
        if (partial.exists() && clock() - partial.lastModified() !in 0..MAX_AGE_MS) {
            partial.delete(); metadata.delete()
        }
        var lastFailure: IOException? = null
        var noProgressFailures = 0
        for (attempt in 1..ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val previous = readMeta(metadata)
            val validator = previous.getProperty("validator").orEmpty()
            val offset = if (validator.isNotBlank() && partial.isFile) partial.length() else 0L
            progress(Progress(offset, null, attempt, resumed = offset > 0))
            var received = 0L
            var downloaded = complete
            try {
                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent, "TVNext/EPG")
                    header(HttpHeaders.Accept, "application/xml, application/gzip, */*")
                    // Range offsets refer to the saved wire representation, not decoded XML.
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (offset > 0) {
                        header(HttpHeaders.Range, "bytes=$offset-")
                        header(HttpHeaders.IfRange, validator)
                    } else if (canRevalidate) {
                        cachedMeta.getProperty("etag")?.takeIf(String::isNotBlank)?.let { header(HttpHeaders.IfNoneMatch, it) }
                            ?: cachedMeta.getProperty("modified")?.takeIf(String::isNotBlank)?.let { header(HttpHeaders.IfModifiedSince, it) }
                    }
                    timeout { requestTimeoutMillis = 5 * 60_000L; connectTimeoutMillis = 10_000L; socketTimeoutMillis = 15_000L }
                }.execute { response ->
                    val status = response.status.value
                    if (status == 304) {
                        if (!canRevalidate || offset > 0 ||
                            (cachedMeta.getProperty("etag").isNullOrBlank() && cachedMeta.getProperty("modified").isNullOrBlank()))
                            throw IOException("EPG source returned 304 without a cached document")
                        cached.setLastModified(clock())
                        partial.delete(); metadata.delete()
                        progress(Progress(cached.length(), cached.length(), attempt, reused = true))
                        downloaded = cached
                        return@execute
                    }
                    if (status == 416) {
                        partial.delete(); metadata.delete()
                        throw IOException("EPG range no longer available")
                    }
                    if (status != 200 && status != 206) throw DownloadHttpException(status)
                    val decoded = response.appliedDecoders.isNotEmpty()
                    val newValidator = response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") }
                        ?: response.headers[HttpHeaders.LastModified].orEmpty()
                    var appendAt = 0L
                    var total = if (decoded) null else response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (response.status == HttpStatusCode.PartialContent) {
                        val range = RANGE.matchEntire(response.headers[HttpHeaders.ContentRange].orEmpty())
                        val start = range?.groupValues?.get(1)?.toLongOrNull()
                        val end = range?.groupValues?.get(2)?.toLongOrNull()
                        val full = range?.groupValues?.get(3)?.toLongOrNull()
                        if (decoded || offset <= 0 || start != offset || end == null || full == null ||
                            end < offset || end >= full || (total != null && total != end - offset + 1) ||
                            (newValidator.isNotBlank() && newValidator != validator)) {
                            partial.delete(); metadata.delete()
                            throw IOException("EPG response does not match the saved range")
                        }
                        appendAt = offset
                        total = full
                    }
                    if ((total ?: 0L) > MAX_BYTES) throw DownloadLimitException()
                    val meta = Properties().apply {
                        setProperty("validator", if (decoded) "" else newValidator)
                        setProperty("etag", response.headers[HttpHeaders.ETag].orEmpty())
                        setProperty("modified", response.headers[HttpHeaders.LastModified].orEmpty())
                    }
                    val context = currentCoroutineContext()
                    var copied = appendAt
                    var published = 0L
                    val input = response.bodyAsChannel()
                    try {
                        java.io.FileOutputStream(partial, appendAt > 0).buffered(128 * 1024).use { out ->
                            // Truncate an ignored/changed range before replacing its validator.
                            saveMeta(metadata, meta)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                // Read the cancellable channel directly: the Java
                                // InputStream bridge can block the parser worker
                                // while its coroutine has already timed out.
                                val count = withTimeoutOrNull(stallTimeoutMs) {
                                    input.readAvailable(buffer, 0, buffer.size)
                                } ?: throw IOException("EPG source stopped sending data")
                                if (count < 0) break
                                received += count
                                copied += count
                                if (copied > MAX_BYTES || (total != null && copied > total)) throw DownloadLimitException()
                                out.write(buffer, 0, count)
                                if (clock() - published >= 250) {
                                    published = clock()
                                    progress(Progress(copied, total, attempt, resumed = appendAt > 0))
                                }
                            }
                        }
                    } finally { input.cancel(null) }
                    if (copied == 0L || (total != null && copied != total)) throw IOException("EPG download was interrupted")
                    progress(Progress(copied, total ?: copied, attempt, resumed = appendAt > 0))
                    check(partial.renameTo(complete)) { "Cannot commit EPG download" }
                }
                return downloaded
            } catch (cancelled: CancellationException) {
                // Leave both partial data and its HTTP validator for the next foreground run.
                throw cancelled
            } catch (limit: DownloadLimitException) {
                partial.delete(); metadata.delete()
                throw limit
            } catch (http: DownloadHttpException) {
                if (http.status !in setOf(408, 429) && http.status < 500) throw http
                lastFailure = http
            } catch (failure: IOException) {
                lastFailure = failure
            }
            // A saved prefix is not progress in the current request.
            noProgressFailures = if (received == 0L) noProgressFailures + 1 else 0
            if (noProgressFailures >= 2) break
            if (attempt < ATTEMPTS) retryDelay(500L shl (attempt - 1))
        }
        throw lastFailure ?: IOException("EPG download failed")
    }

    private fun readMeta(file: File) = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }
    private fun saveMeta(file: File, values: Properties) {
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { values.store(it, null) }
        check(temp.renameTo(file)) { "Cannot save EPG download state" }
    }
    private class DownloadHttpException(val status: Int) : IOException("EPG HTTP $status")
    private class DownloadLimitException : IOException("EPG exceeds the download size limit")
    private companion object {
        const val ATTEMPTS = 4
        const val MAX_AGE_MS = 12 * 60 * 60_000L
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val CACHE_BUDGET_BYTES = 256L * 1024 * 1024
        const val MAX_CACHE_AGE_MS = 24 * 60 * 60_000L
        val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

internal class InvalidEpgDocumentException : IOException("Incomplete XMLTV document")
