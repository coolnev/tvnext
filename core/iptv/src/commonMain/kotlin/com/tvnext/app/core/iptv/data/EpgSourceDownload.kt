package com.tvnext.app.core.iptv.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.compression.appliedDecoders
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable HTTP staging. Parsing/SQLite never hold an HTTP response open. */
class EpgSourceDownload(
    private val client: HttpClient,
    private val directory: File,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Progress(val bytes: Long, val total: Long?, val attempt: Int)
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
            acknowledge(url)
            throw invalid
        } catch (invalid: java.util.zip.ZipException) {
            acknowledge(url)
            throw invalid
        } catch (invalid: java.io.EOFException) {
            acknowledge(url)
            throw invalid
        }
    }

    fun acknowledge(url: String) {
        val key = IndexedEpgRepository.sourceId(url)
        File(directory, "$key.ready").delete()
        File(directory, "$key.meta").delete()
    }

    private suspend fun fetch(url: String, progress: (Progress) -> Unit): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create EPG download directory" }
        val key = IndexedEpgRepository.sourceId(url)
        val complete = File(directory, "$key.ready")
        val partial = File(directory, "$key.part")
        val metadata = File(directory, "$key.meta")
        if (complete.isFile && complete.length() > 0 && clock() - complete.lastModified() in 0..MAX_AGE_MS) {
            progress(Progress(complete.length(), complete.length(), 0))
            return complete
        }
        complete.delete()
        if (partial.exists() && clock() - partial.lastModified() !in 0..MAX_AGE_MS) {
            partial.delete(); metadata.delete()
        }
        var lastFailure: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val previous = readMeta(metadata)
            val validator = previous.getProperty("validator").orEmpty()
            val offset = if (validator.isNotBlank() && partial.isFile) partial.length() else 0L
            progress(Progress(offset, null, attempt))
            try {
                client.prepareGet(url) {
                    header(HttpHeaders.UserAgent, "TVNext/EPG")
                    header(HttpHeaders.Accept, "application/xml, application/gzip, */*")
                    // Range offsets refer to the saved wire representation, not decoded XML.
                    header(HttpHeaders.AcceptEncoding, "identity")
                    if (offset > 0) {
                        header(HttpHeaders.Range, "bytes=$offset-")
                        header(HttpHeaders.IfRange, validator)
                    }
                    timeout { requestTimeoutMillis = 5 * 60_000L; socketTimeoutMillis = 30_000L }
                }.execute { response ->
                    val status = response.status.value
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
                    }
                    val context = currentCoroutineContext()
                    var copied = appendAt
                    var published = 0L
                    response.bodyAsChannel().toInputStream().use { input ->
                        java.io.FileOutputStream(partial, appendAt > 0).buffered(128 * 1024).use { out ->
                            // Truncate an ignored/changed range before replacing its validator.
                            saveMeta(metadata, meta)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > MAX_BYTES || (total != null && copied > total)) throw DownloadLimitException()
                                out.write(buffer, 0, count)
                                if (clock() - published >= 250) {
                                    published = clock()
                                    progress(Progress(copied, total, attempt))
                                }
                            }
                        }
                    }
                    if (copied == 0L || (total != null && copied != total)) throw IOException("EPG download was interrupted")
                    progress(Progress(copied, total ?: copied, attempt))
                    check(partial.renameTo(complete)) { "Cannot commit EPG download" }
                }
                return complete
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
        val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

internal class InvalidEpgDocumentException : IOException("Incomplete XMLTV document")
