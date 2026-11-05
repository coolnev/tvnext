package com.tvnext.app.core.iptv.util

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GzipTest {

    @Test
    fun gzipRoundTrip() {
        val original = "Привет, TVNext! ".repeat(100).encodeToByteArray()
        val compressed = gzipCompress(original)
        val decompressed = gzipDecompress(compressed)
        assertContentEquals(original, decompressed)
    }

    @Test
    fun decompressesRealGzip() {
        val text = "<?xml version=\"1.0\"?><tv></tv>"
        val gz = gzipCompress(text.encodeToByteArray())
        assertEquals(text, gzipDecompress(gz).decodeToString())
    }

    private fun gzipCompress(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
