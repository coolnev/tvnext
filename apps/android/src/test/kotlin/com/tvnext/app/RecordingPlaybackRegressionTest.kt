package com.tvnext.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.tvnext.app.core.player.AndroidPlaybackDataSources
import com.tvnext.app.core.player.PlaybackItem
import kotlinx.coroutines.*
import com.tvnext.app.core.player.PlaybackContentKind
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class RecordingPlaybackRegressionTest {
    private fun read(uri: Uri): ByteArray {
        val source=AndroidPlaybackDataSources.factory(RuntimeEnvironment.getApplication()).createDataSource()
        try {
            source.open(DataSpec(uri))
            val out=ByteArrayOutputStream();val buffer=ByteArray(256)
            while(true) {val n=source.read(buffer,0,buffer.size);if(n==C.RESULT_END_OF_INPUT)break;out.write(buffer,0,n)}
            return out.toByteArray()
        } finally {source.close()}
    }

    @Test fun localTransportAndRelativeHlsMediaAreReadableWithoutHttp() {
        val folder=File(RuntimeEnvironment.getApplication().cacheDir,"запись с пробелами").apply {mkdirs()}
        val ts=File(folder,"capture.ts").apply {writeBytes(ByteArray(376) {if(it%188==0)0x47 else (it%256).toByte()})}
        val record=SavedRecording("saved","main","Моя запись",folder.path,1,2,"complete")
        val direct=record.playbackItem()!!
        assertEquals(PlaybackContentKind.VOD,direct.kind)
        assertArrayEquals(ts.readBytes(),read(Uri.parse(direct.url)))
        val index=File(folder,"recording.m3u8").apply {writeText("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\ncapture.ts\n#EXT-X-ENDLIST\n")}
        val hls=record.playbackItem()!!
        assertEquals(Uri.fromFile(index),Uri.parse(hls.url))
        assertArrayEquals(index.readBytes(),read(Uri.parse(hls.url)))
        val segment=java.net.URI(hls.url).resolve("capture.ts").toString()
        assertArrayEquals(ts.readBytes(),read(Uri.parse(segment)))
        index.writeBytes(byteArrayOf());ts.writeBytes(byteArrayOf())
        assertNull(record.playbackItem())
    }

    @Test fun stoppedServiceClearsRecAndFinalizesPlayableOfflineHls() {
        val context=RuntimeEnvironment.getApplication()
        val media=ByteArray(188*3) {if(it%188==0)0x47 else (it%127).toByte()}
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/live.m3u8") {exchange ->
            val text="#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n".toByteArray()
            exchange.sendResponseHeaders(200,text.size.toLong());exchange.responseBody.use {it.write(text)}
        }
        server.createContext("/segment.ts") {exchange ->
            exchange.sendResponseHeaders(200,media.size.toLong());exchange.responseBody.use {it.write(media)}
        }
        server.start()
        val store=LocalRecoveryStore(context)
        val serviceController=Robolectric.buildService(RecordingService::class.java).create()
        val service=serviceController.get()
        try {
            service.onStartCommand(Intent(context,RecordingService::class.java).setAction(RecordingService.START)
                .putExtra(RecordingService.URL,"http://127.0.0.1:${server.address.port}/live.m3u8")
                .putExtra(RecordingService.OWNER,store.activeProfileId()).putExtra(RecordingService.TITLE,"Запись теста")
                .putExtra(RecordingService.SCHEDULED,true).putExtra(RecordingService.CHANNEL_ID,"channel"),0,1)
            val active=RecordingRuntime.active.value!!
            await {
                store.getRecording(active.id)?.let {File(it.folder,"recording.m3u8").isFile}==true
            }
            assertEquals(active.id,RecordingRuntime.active.value?.id)
            assertTrue(active.scheduled)
            val live = PlaybackItem("channel","Текущий канал",active.streamUrl)
            assertEquals(RecordButtonAction.STOP,recordButtonAction(active,live))
            assertEquals(RecordButtonAction.STOP,recordButtonAction(active,live.copy(kind=PlaybackContentKind.ARCHIVE,url="http://example.invalid/archive")))
            assertEquals(RecordButtonAction.BUSY,recordButtonAction(active,live.copy(id="other",url="http://example.invalid/other")))
            assertFalse(store.deleteRecording(active.id))
            service.onStartCommand(Intent(context,RecordingService::class.java).setAction(RecordingService.STOP)
                .putExtra(RecordingService.RECORDING_ID,"different-recording"),0,2)
            assertEquals(active.id,RecordingRuntime.active.value?.id)
            val stopped = CoroutineScope(Dispatchers.Default).async {stopActiveRecording(context,active.id)}
            var stopIntent: Intent? = null
            await {if (stopIntent==null) stopIntent=shadowOf(context).nextStartedService;stopIntent?.action==RecordingService.STOP}
            service.onStartCommand(stopIntent,0,3)
            await {RecordingRuntime.active.value==null}
            assertTrue(runBlocking {stopped.await()})
            val saved=store.getRecording(active.id)!!
            assertEquals("partial",saved.status)
            assertEquals("Остановлена",saved.statusLabel())
            assertEquals("Записана частично",saved.copy(error="Network failure").statusLabel())
            val item=saved.playbackItem()!!
            val index=read(Uri.parse(item.url)).toString(Charsets.UTF_8)
            assertTrue(index.contains("#EXT-X-ENDLIST"))
            server.stop(0)
            val segment=index.lineSequence().first {it.startsWith("segment_")}
            assertArrayEquals(media,read(Uri.parse(java.net.URI(item.url).resolve(segment).toString())))
            assertTrue(runBlocking {deleteRecordingAfterStop(context,active.id,store.activeProfileId())})
            assertFalse(File(saved.folder).exists())
        } finally {server.stop(0);serviceController.destroy()}
    }

    @Test fun lateCompletionCannotClearANewerRecordingIndicator() {
        val first=ActiveRecording("first","main",false)
        val next=ActiveRecording("next","main",true)
        try {
            RecordingRuntime.started(first);RecordingRuntime.started(next);RecordingRuntime.finished(first.id)
            assertEquals(next,RecordingRuntime.active.value)
            RecordingRuntime.finished(next.id);assertNull(RecordingRuntime.active.value)
        } finally {RecordingRuntime.active.value?.id?.let(RecordingRuntime::finished)}
    }

    @Test fun orphanedRecordingFlagDoesNotPreventDeletionAfterTheServiceHasStopped() {
        val context=RuntimeEnvironment.getApplication();val store=LocalRecoveryStore(context)
        val folder=store.folderFor("orphaned-recording").apply {mkdirs()}
        File(folder,"capture.ts").writeBytes(ByteArray(376){0x47})
        val record=SavedRecording("orphaned-recording",store.activeProfileId(),"Прерванная запись",folder.path,1,0,"recording")
        store.saveRecording(record)
        assertNull(RecordingRuntime.active.value)
        assertEquals("Остановлена",record.statusLabel())
        assertTrue(runBlocking {deleteRecordingAfterStop(context,record.id,record.profileId)})
        assertFalse(folder.exists())
    }

    private fun await(condition: () -> Boolean) {
        val end=System.nanoTime()+8_000_000_000L
        while(!condition() && System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(15)}
        assertTrue("Recording did not reach the expected service/file state",condition())
    }
}
