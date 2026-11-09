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
import kotlinx.coroutines.withTimeout
import io.ktor.utils.io.ByteChannel
import com.sun.net.httpserver.HttpServer
import com.tvnext.app.core.network.createHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test fun savedPrefixDoesNotKeepRetryingWhenResumedRequestsDeliverNothing() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) respond("saved-prefix", headers = headers(1_000))
            else {
                assertEquals("bytes=12-", request.headers[HttpHeaders.Range])
                respond(ByteChannel(autoFlush = true), HttpStatusCode.PartialContent,
                    headers(988, range = "bytes 12-999/1000"))
            }
        }) { install(HttpTimeout) }
        try {
            withTimeout(3_000) {
                try {
                    EpgSourceDownload(client, directory(), retryDelay = {}, stallTimeoutMs = 100)
                        .consume(url) { fail("Stalled bytes must not reach the parser") }
                    fail("Download failure expected")
                } catch (_: java.io.IOException) { }
            }
            assertEquals(3, requests) // initial prefix + two attempts without new bytes
            assertEquals(12L, directory().listFiles()!!.single { it.name.endsWith(".part") }.length())
        } finally { client.close() }
    }

    @Test fun oneDeadlineCoversAllRetriesAndLeavesPartialBytesResumable() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("prefix", headers = headers(1_000))
        }) { install(HttpTimeout) }
        try {
            withTimeout(3_000) {
                try {
                    EpgSourceDownload(client, directory(), downloadTimeoutMs = 200)
                        .consume(url) { fail("Incomplete document") }
                    fail("Budget must expire during retry delay")
                } catch (_: java.io.IOException) { }
            }
            assertEquals(1, requests)
            assertEquals(6L, directory().listFiles()!!.single { it.name.endsWith(".part") }.length())
        } finally { client.close() }
    }

    @Test fun realAndroidHttpStallClosesConnectionAndNextRunResumesItsSavedPrefix() = runBlocking<Unit> {
        val payload = "complete-guide".repeat(1_000).toByteArray()
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        var requests = 0
        val resume = java.util.concurrent.atomic.AtomicBoolean(false)
        server.createContext("/epg.xml") { exchange ->
            synchronized(this@EpgDownloadRegressionTest) { requests++ }
            exchange.responseHeaders.add("ETag", "\"v1\"")
            try {
                if (resume.get()) {
                    assertEquals("bytes=1024-", exchange.requestHeaders.getFirst("Range"))
                    exchange.responseHeaders.add("Content-Range", "bytes 1024-${payload.lastIndex}/${payload.size}")
                    exchange.sendResponseHeaders(206, (payload.size - 1024).toLong())
                    exchange.responseBody.write(payload, 1024, payload.size - 1024)
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload, 0, 1024)
                    exchange.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                }
            } finally { exchange.close() }
        }
        server.start()
        val client = createHttpClient()
        val localUrl = "http://127.0.0.1:${server.address.port}/epg.xml"
        try {
            withTimeout(6_000) {
                try {
                    EpgSourceDownload(client, directory(), retryDelay = {}, downloadTimeoutMs = 3_000, stallTimeoutMs = 350)
                        .consume(localUrl) { fail("Stalled source must not be parsed") }
                    fail("Timeout expected")
                } catch (_: java.io.IOException) { }
            }
            assertEquals(1024L, directory().listFiles()!!.single { it.name.endsWith(".part") }.length())
            resume.set(true)
            release.countDown()
            EpgSourceDownload(client, directory()).consume(localUrl) { assertArrayEquals(payload, it.readBytes()) }
            assertTrue(requests >= 2)
        } finally { release.countDown(); client.close(); server.stop(0); executor.shutdownNow() }
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

    @Test fun aServerReturningNoGuideBytesStopsAfterTwoAttempts() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("unavailable", HttpStatusCode.ServiceUnavailable) }) { install(HttpTimeout) }
        try {
            try {
                EpgSourceDownload(client, directory(), retryDelay = {}).consume(url) { fail("No document") }
                fail("HTTP failure expected")
            } catch (_: java.io.IOException) { }
            assertEquals(2, requests)
        } finally { client.close() }
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
    @Test fun unchangedFeedUsesConditionalRequestAndRetainedFileWhileChangedFeedReplacesIt() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            when (requests) {
                1 -> { assertNull(request.headers[HttpHeaders.IfNoneMatch]); respond("first", headers = headers(5)) }
                2 -> { assertEquals("\"v1\"", request.headers[HttpHeaders.IfNoneMatch]); respond("", HttpStatusCode.NotModified) }
                3 -> { assertEquals("\"v1\"", request.headers[HttpHeaders.IfNoneMatch]); respond("second", headers = headers(6, "\"v2\"")) }
                else -> { assertEquals("\"v2\"", request.headers[HttpHeaders.IfNoneMatch]); respond("", HttpStatusCode.NotModified) }
            }
        }) { install(HttpTimeout) }
        try {
            val downloader = EpgSourceDownload(client, directory())
            for (expected in listOf("first", "first", "second", "second")) {
                downloader.consume(url) { assertEquals(expected, it.readText()) }
                downloader.acknowledge(url)
            }
            assertEquals(4, requests)
            assertEquals(1, directory().listFiles()!!.count { it.name.endsWith(".cached") })
        } finally { client.close() }
    }

    @Test fun lastModifiedCanValidateRetainedFeedWithoutEtag() = runBlocking<Unit> {
        val modified = "Mon, 09 Nov 2026 08:00:00 GMT"
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) respond("guide", headers = Headers.build { append(HttpHeaders.LastModified, modified) })
            else {
                assertEquals(modified, request.headers[HttpHeaders.IfModifiedSince])
                assertNull(request.headers[HttpHeaders.IfNoneMatch])
                respond("", HttpStatusCode.NotModified)
            }
        }) { install(HttpTimeout) }
        try {
            val downloader = EpgSourceDownload(client, directory())
            repeat(2) { downloader.consume(url) { assertEquals("guide", it.readText()) }; downloader.acknowledge(url) }
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun invalidRetainedDocumentIsDiscardedInsteadOfBeingCachedAgain() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 2) respond("", HttpStatusCode.NotModified)
            else {
                if (requests == 3) assertNull(request.headers[HttpHeaders.IfNoneMatch])
                respond("guide", headers = headers(5))
            }
        }) { install(HttpTimeout) }
        try {
            val downloader = EpgSourceDownload(client, directory())
            downloader.consume(url) {}; downloader.acknowledge(url)
            try { downloader.consume(url) { throw java.io.EOFException("invalid archive") }; fail("Invalid file") }
            catch (_: java.io.EOFException) { }
            assertFalse(directory().listFiles()!!.any { it.name.endsWith(".cached") })
            downloader.consume(url) { assertEquals("guide", it.readText()) }
            assertEquals(3, requests)
        } finally { client.close() }
    }

    @Test fun compressedSourceCacheEvictsOldestFileAndValidatorWithinDiskBudget() = runBlocking<Unit> {
        val dir = directory().apply { mkdirs() }
        File(dir, "old.cached").apply { writeText("old-guide"); setLastModified(System.currentTimeMillis() - 1_000) }
        File(dir, "old.cached-meta").writeText("etag=old")
        val client = HttpClient(MockEngine { respond("new-guide", headers = headers(9)) }) { install(HttpTimeout) }
        try {
            val downloader = EpgSourceDownload(client, dir, cacheBudgetBytes = 12)
            downloader.consume(url) {}; downloader.acknowledge(url)
            assertFalse(File(dir, "old.cached").exists())
            assertFalse(File(dir, "old.cached-meta").exists())
            assertTrue(dir.listFiles()!!.filter { it.name.endsWith(".cached") }.sumOf { it.length() } <= 12)
        } finally { client.close() }
    }

}
