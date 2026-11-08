package com.tvnext.app

import android.app.Application
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class RecordingFailureRegressionTest {
    private fun server(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
        createContext("/live.ts") { exchange ->
            val data=ByteArray(376){0x47}
            exchange.responseHeaders.add("Content-Type","video/mp2t")
            exchange.sendResponseHeaders(200,data.size.toLong());exchange.responseBody.use {it.write(data)}
        }
        createContext("/live.m3u8") { exchange ->
            val data="#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\none.ts\n#EXTINF:1,\ntwo.ts\n#EXT-X-ENDLIST\n".toByteArray()
            exchange.sendResponseHeaders(200,data.size.toLong());exchange.responseBody.use {it.write(data)}
        }
        for(path in listOf("/one.ts","/two.ts"))createContext(path) { exchange ->
            val data=ByteArray(376){0x47};exchange.sendResponseHeaders(200,data.size.toLong());exchange.responseBody.use {it.write(data)}
        }
        start()
    }
    private fun folder(name: String) = File(RuntimeEnvironment.getApplication().cacheDir,name).apply {deleteRecursively();mkdirs()}
    private suspend fun failure(engine: StreamRecordingEngine, url: String, folder: File): RecordingFailure {
        try {engine.capture(url,folder,5000);fail("Expected a classified recording failure")} catch(error: RecordingFailure){return error}
        error("Capture unexpectedly completed")
    }
    @Test fun quotaKeepsCompletedHlsSegmentsAndReportsTheTvnextLimit()=runBlocking {
        val server=server();val folder=folder("quota")
        try {
            val error=failure(StreamRecordingEngine(500),"http://127.0.0.1:${server.address.port}/live.m3u8",folder)
            assertEquals(RecordingFailureReason.LIMIT,error.reason)
            assertEquals("Достигнут лимит записей TVNext",recordingFailureMessage(error))
            val index=File(folder,"recording.m3u8").readText()
            assertTrue(index.contains("#EXT-X-ENDLIST"));assertTrue(index.contains("segment_0000000.ts"))
            assertFalse(index.contains("segment_0000001.ts"));assertEquals(376,File(folder,"segment_0000000.ts").length().toInt())
        } finally {server.stop(0)}
    }
    @Test fun lowDiskSpaceIsNotReportedAsNetworkFailure()=runBlocking {
        val server=server()
        try {
            val error=failure(StreamRecordingEngine(Long.MAX_VALUE,availableBytes={0}),"http://127.0.0.1:${server.address.port}/live.ts",folder("full"))
            assertEquals(RecordingFailureReason.STORAGE_FULL,error.reason)
            assertEquals("Недостаточно свободного места для записи",recordingFailureMessage(error))
        } finally {server.stop(0)}
    }
    @Test fun actualFileOpenFailureIsClassifiedAsStorageWrite()=runBlocking {
        val server=server();val folder=folder("write-error");File(folder,"capture.ts").mkdir()
        try {
            val error=failure(StreamRecordingEngine(Long.MAX_VALUE),"http://127.0.0.1:${server.address.port}/live.ts",folder)
            assertEquals(RecordingFailureReason.STORAGE_WRITE,error.reason)
            assertEquals("Не удалось записать файл на накопитель",recordingFailureMessage(error))
        } finally {server.stop(0)}
    }
    @Test fun refusedConnectionRemainsANetworkFailure()=runBlocking {
        val server=server();val port=server.address.port;server.stop(0)
        val error=failure(StreamRecordingEngine(Long.MAX_VALUE),"http://127.0.0.1:$port/live.m3u8",folder("network"))
        assertEquals(RecordingFailureReason.NETWORK,error.reason)
        assertEquals("Сбой сети или чтения потока",recordingFailureMessage(error))
    }
}
