package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import io.ktor.client.HttpClient

/**
 * Downloads an XMLTV document into a temporary file, streaming. Android keeps
 * the compressed source on disk and lets the native parser inflate it directly
 * in bounded chunks; desktop may keep the existing decoded-file path. The whole
 * document is never materialized in the application heap.
 */
interface EpgFetcher {
    /**
     * Downloads [url] into a new temp file. The returned path may be compressed
     * on Android; the platform parser is responsible for transparent decoding.
     */
    suspend fun fetchXmlToTemp(url: String): AppResult<String>
}

/**
 * Streaming Ktor-based implementation. The download itself is platform-side
 * (JVM I/O: Ktor response → file), delegated to [downloadEpgToTempFile].
 */
class KtorEpgFetcher(
    private val client: HttpClient,
) : EpgFetcher {

    override suspend fun fetchXmlToTemp(url: String): AppResult<String> =
        downloadEpgToTempFile(client, url)
}

/**
 * Downloads [url] with Ktor and streams the decoded (gzip-aware) XML into a
 * new temp file. Returns the absolute path on success.
 */
expect suspend fun downloadEpgToTempFile(client: HttpClient, url: String): AppResult<String>
