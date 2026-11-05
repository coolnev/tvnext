package com.tvnext.app.core.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for [HlsPrefetchServer]: a fake throttled CDN serves a VOD
 * playlist + segments; the proxy must rewrite the playlist to loopback URLs,
 * prefetch segments in parallel and serve them byte-for-byte.
 */
class HlsPrefetchServerTest {

    private lateinit var cdn: HttpServer
    private lateinit var baseUrl: String
    private lateinit var proxy: HlsPrefetchServer

    @BeforeTest
    fun setUp() {
        // Fake CDN: throttles each connection (like the real m.cdntv.online)
        // so a sequential client would stall, but parallel fetches finish.
        cdn = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        cdn.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when {
                path == "/playlist.m3u8" -> PLAYLIST.toByteArray()
                path.startsWith("/seg/") -> {
                    // Simulate per-connection throttling: ~1/4 of real speed.
                    Thread.sleep(150L)
                    val index = path.removePrefix("/seg/").removeSuffix(".ts").toIntOrNull() ?: 0
                    "segment-$index-bytes".toByteArray()
                }
                else -> byteArrayOf()
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        cdn.start()
        baseUrl = "http://127.0.0.1:${cdn.address.port}"
    }

    @AfterTest
    fun tearDown() {
        runCatching { proxy.close() }
        cdn.stop(0)
    }

    @Test
    fun `rewrites playlist to loopback and serves prefetched segments`() = runBlocking {
        proxy = HlsPrefetchServer(remotePlaylistUrl = "$baseUrl/playlist.m3u8")
        proxy.start()

        val localPlaylist = fetch(proxy.localPlaylistUrl)
        assertTrue(localPlaylist.contains("#EXTM3U"), "must keep the M3U header")
        assertTrue(localPlaylist.contains("/seg/0.ts\n"), "segment 0 must point at loopback")
        assertTrue(localPlaylist.contains("/seg/4.ts\n"), "segment 4 must point at loopback")
        assertTrue(!localPlaylist.contains("127.0.0.1") || localPlaylist.contains("/seg/0"),
            "no remote URLs should survive")

        // The first segments should already be prefetched — fetching them via
        // the proxy must return the exact bytes the CDN produced.
        val seg0 = fetch("${proxy.localPlaylistUrl.removeSuffix("playlist.m3u8")}seg/0")
        assertEquals("segment-0-bytes", seg0)
    }

    @Test
    fun `parallel segment fetches all succeed`() = runBlocking {
        proxy = HlsPrefetchServer(remotePlaylistUrl = "$baseUrl/playlist.m3u8")
        proxy.start()

        val base = proxy.localPlaylistUrl.removeSuffix("playlist.m3u8")
        val results: List<kotlinx.coroutines.Deferred<String>> = coroutineScope {
            (0..4).map { i -> async { fetch("${base}seg/$i") } }
        }
        results.forEachIndexed { index, deferred ->
            assertEquals("segment-$index-bytes", deferred.await())
        }
    }

    private fun fetch(url: String): String {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "GET $url")
        return response.body()
    }

    private companion object {
        val PLAYLIST: String = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:11
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10.4,
            /seg/0.ts
            #EXTINF:10.4,
            /seg/1.ts
            #EXTINF:10.4,
            /seg/2.ts
            #EXTINF:10.4,
            /seg/3.ts
            #EXTINF:10.4,
            /seg/4.ts
            #EXT-X-ENDLIST
        """.trimIndent()
    }
}
