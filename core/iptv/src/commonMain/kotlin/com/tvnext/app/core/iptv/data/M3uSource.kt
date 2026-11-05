package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.common.result.AppResult.Failure
import com.tvnext.app.core.common.result.AppResult.Success
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

interface M3uSource {
    suspend fun fetch(url: String): AppResult<String>
}

class KtorM3uSource(
    private val client: HttpClient,
) : M3uSource {

    override suspend fun fetch(url: String): AppResult<String> =
        try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/x-mpegurl, text/plain, */*")
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
            if (!response.status.isSuccess()) {
                Failure(AppError.Network(cause = null))
            } else {
                val bytes = response.bodyAsBytes()
                if (bytes.size > MAX_PLAYLIST_BYTES) {
                    Failure(AppError.InvalidPlaylist("Плейлист слишком большой (${bytes.size / 1024} КБ)"))
                } else {
                    Success(bytes.decodeToString())
                }
            }
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            Failure(AppError.Network(cause = e))
        }

    private companion object {
        const val USER_AGENT = "TVNext/0.1"
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val SOCKET_TIMEOUT_MS = 15_000L
        const val MAX_PLAYLIST_BYTES = 20 * 1024 * 1024
    }
}
