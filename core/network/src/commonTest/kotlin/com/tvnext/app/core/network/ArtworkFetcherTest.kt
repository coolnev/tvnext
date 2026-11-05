package com.tvnext.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.test.*

class ArtworkFetcherTest {
    @Test fun retriesServerFailureButNeverAnInvalidUrl() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else respond(byteArrayOf(1, 2, 3), headers = headersOf("Content-Type", "image/jpeg"))
        }) { install(HttpTimeout) }
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), fetchArtworkBytes(client, "https://example.org/poster.jpg"))
            assertEquals(2, requests)
            assertNull(fetchArtworkBytes(client, "file:///tmp/image"))
            assertEquals(2, requests)
        } finally { client.close() }
    }
    @Test fun missingImagesHtmlErrorsAndOversizeImagesAreNotCachedAsPictures() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            when (request.url.encodedPath) {
                "/missing" -> respond("gone", HttpStatusCode.NotFound)
                "/html" -> respond("<html>error</html>", headers = headersOf("Content-Type", "text/html"))
                else -> respond("x", headers = headersOf("Content-Length", "99999999"))
            }
        }) { install(HttpTimeout) }
        try {
            for (path in listOf("missing", "html", "large")) assertNull(fetchArtworkBytes(client, "https://example.org/$path"))
            assertEquals(3, requests)
        } finally { client.close() }
    }
    @Test fun cancelledDownloadDoesNotRetry() = runBlocking<Unit> {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; delay(60_000); respond("x") }) { install(HttpTimeout) }
        try {
            assertNull(withTimeoutOrNull(100) { fetchArtworkBytes(client, "https://example.org/slow.jpg") })
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
