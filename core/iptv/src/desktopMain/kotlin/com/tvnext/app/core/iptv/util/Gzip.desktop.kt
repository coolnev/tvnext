package com.tvnext.app.core.iptv.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

actual fun gzipDecompress(bytes: ByteArray): ByteArray {
    val input = GZIPInputStream(ByteArrayInputStream(bytes))
    val output = ByteArrayOutputStream()
    input.copyTo(output)
    input.close()
    return output.toByteArray()
}
