package com.tvnext.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded, cancellable downloads shared by channel logos, posters and backdrops. */
suspend fun fetchArtworkBytes(client: HttpClient, url: String): ByteArray? {
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
    return withTimeoutOrNull(15_000L) {
        repeat(2) { attempt ->
            var retry = false
            try {
                val bytes = client.prepareGet(url) {
                    header("User-Agent", "TVNext/0.5.29")
                    header("Accept", "image/*")
                    timeout { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
                }.execute { response ->
                    val status = response.status.value
                    retry = status in setOf(408, 429, 500, 502, 503, 504)
                    if (status !in 200..299) return@execute null
                    val type = response.headers["Content-Type"].orEmpty().lowercase()
                    if (type.startsWith("text/") || type.contains("json")) return@execute null
                    if ((response.headers["Content-Length"]?.toLongOrNull() ?: 0) > MAX_IMAGE_BYTES) return@execute null
                    val channel = response.bodyAsChannel()
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (output.size() + count > MAX_IMAGE_BYTES) return@execute null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().takeIf { it.isNotEmpty() }
                }
                if (bytes != null) return@withTimeoutOrNull bytes
                if (!retry) return@withTimeoutOrNull null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retry = true }
            if (attempt == 0 && retry) delay(300)
        }
        null
    }
}

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
