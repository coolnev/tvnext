package com.tvnext.app

import android.app.Application
import com.tvnext.app.core.iptv.data.EpgSourceDownload
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import com.tvnext.app.core.iptv.data.openEpgTextSource
import com.tvnext.app.core.iptv.data.use
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class EpgDownloadRegressionTest {
    private val url = "https://unused.invalid/large.xml.gz"
    private fun directory() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "downloads")
    private fun headers(size: Int, etag: String = "\"v1\"", range: String? = null) = Headers.build {
        append(HttpHeaders.ContentLength, size.toString()); append(HttpHeaders.ETag, etag)
        if (range != null) append(HttpHeaders.ContentRange, range)
    }

    @Test fun interruptedDownloadResumesAndParsingStartsAfterAllBytesArrive() = runBlocking<Unit> {
        val all = "<tv>${"x".repeat(300_000)}</tv>".toByteArray()
        val split = 120_000
        var requests = 0
        var body: ByteReadChannel? = null
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) {
                body = ByteReadChannel(all.copyOfRange(0, split))
                respond(body!!, HttpStatusCode.OK, headers(all.size))
            } else {
                assertEquals("bytes=$split-", request.headers[HttpHeaders.Range])
                assertEquals("\"v1\"", request.headers[HttpHeaders.IfRange])
                body = ByteReadChannel(all.copyOfRange(split, all.size))
                respond(body!!, HttpStatusCode.PartialContent, headers(all.size - split, range = "bytes $split-${all.size - 1}/${all.size}"))
            }
        }) { install(HttpTimeout) }
        try {
            EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { file ->
                assertTrue(body!!.isClosedForRead)
                assertArrayEquals(all, file.readBytes())
            }
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun cancelledProcessCanResumeFromPersistedPartialFile() = runBlocking<Unit> {
        val all = "persistent-payload".repeat(10_000).toByteArray()
        val split = 70_000
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) respond(all.copyOfRange(0, split), headers = headers(all.size))
            else {
                assertEquals("bytes=$split-", request.headers[HttpHeaders.Range])
                respond(all.copyOfRange(split, all.size), HttpStatusCode.PartialContent,
                    headers(all.size - split, range = "bytes $split-${all.size - 1}/${all.size}"))
            }
        }) { install(HttpTimeout) }
        try {
            try {
                EpgSourceDownload(client, directory(), retryDelay = { throw CancellationException("Process stopped") })
                    .consume(url) { fail("Partial bytes must not reach the parser") }
                fail("Cancellation expected")
            } catch (_: CancellationException) { }
            EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { assertArrayEquals(all, it.readBytes()) }
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun serverIgnoringRangeReplacesPartialInsteadOfAppending() = runBlocking<Unit> {
        val replacement = "new-version".repeat(20_000).toByteArray()
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) respond("old".repeat(500), headers = headers(99_999))
            else {
                assertNotNull(request.headers[HttpHeaders.Range])
                respond(replacement, headers = headers(replacement.size, "\"v2\""))
            }
        }) { install(HttpTimeout) }
        try {
            EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { assertArrayEquals(replacement, it.readBytes()) }
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun inconsistentPartialResponseIsDiscardedAndRetriedFromZero() = runBlocking<Unit> {
        val all = "consistent-document".repeat(5_000).toByteArray()
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            when (requests) {
                1 -> respond("partial", headers = headers(all.size))
                2 -> respond("wrong", HttpStatusCode.PartialContent, headers(5, range = "bytes 0-4/${all.size}"))
                else -> {
                    assertNull(request.headers[HttpHeaders.Range])
                    respond(all, headers = headers(all.size))
                }
            }
        }) { install(HttpTimeout) }
        try {
            EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { assertArrayEquals(all, it.readBytes()) }
            assertEquals(3, requests)
        } finally { client.close() }
    }

    @Test fun completedDownloadSurvivesIndexFailureAndNeedsNoSecondHttpRequest() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("complete-guide") }) { install(HttpTimeout) }
        try {
            try {
                EpgSourceDownload(client, directory()).consume(url) { error("Index unavailable") }
                fail("Index failure expected")
            } catch (_: IllegalStateException) { }
            val restarted = EpgSourceDownload(client, directory())
            restarted.consume(url) { assertEquals("complete-guide", it.readText()) }
            assertEquals(1, requests)
            restarted.acknowledge(url)
            assertFalse(directory().listFiles().orEmpty().any { it.name.endsWith(".ready") })
        } finally { client.close() }
    }

    @Test fun gzipFileAndHttpContentEncodingAreBothDecodedWithoutCorruptRangeOffsets() = runBlocking<Unit> {
        val xml = "<tv><channel id='news'><display-name>Новости</display-name></channel></tv>"
        val compressed = java.io.ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        for (encoded in listOf(false, true)) {
            val client = HttpClient(MockEngine {
                respond(compressed, headers = Headers.build {
                    append(HttpHeaders.ContentLength, compressed.size.toString())
                    if (encoded) append(HttpHeaders.ContentEncoding, "gzip")
                })
            }) { install(HttpTimeout); install(ContentEncoding) { gzip() } }
            try {
                val download = EpgSourceDownload(client, directory())
                download.consume(url) { file ->
                    val actual = buildString {
                        openEpgTextSource(file.path).use { source ->
                            val buffer = CharArray(1024)
                            while (true) {
                                val read = source.read(buffer, 0, buffer.size)
                                if (read < 0) break
                                append(buffer, 0, read)
                            }
                        }
                    }
                    assertEquals(xml, actual)
                }
                download.acknowledge(url)
            } finally { client.close() }
        }
    }

    @Test fun missingSourceDoesNotRetryFourTimes() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("missing", HttpStatusCode.NotFound) }) { install(HttpTimeout) }
        try {
            try {
                EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { fail("No document") }
                fail("HTTP failure expected")
            } catch (_: java.io.IOException) { }
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
