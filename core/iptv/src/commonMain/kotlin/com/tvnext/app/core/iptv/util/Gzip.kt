package com.tvnext.app.core.iptv.util

/**
 * Decompresses gzip data. Used for `.gz` XMLTV sources that are served as
 * `application/gzip` (without an HTTP `Content-Encoding: gzip` header, which
 * Ktor would otherwise handle transparently).
 */
expect fun gzipDecompress(bytes: ByteArray): ByteArray
