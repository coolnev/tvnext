package com.tvnext.app

import android.app.Application
import android.os.Looper
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.player.*
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class PlaybackNetworkIsolationTest {
    private class Provider(private val stallBody:Boolean):AutoCloseable {
        val server=ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
        val entered=CountDownLatch(1);val oldClosed=CountDownLatch(1);val healthyRequested=CountDownLatch(1)
        private val sockets=CopyOnWriteArrayList<Socket>()
        init {Thread({
            while(!server.isClosed)try {
                val socket=server.accept();sockets+=socket
                Thread({handle(socket)},"fixture-stream").apply{isDaemon=true;start()}
            } catch(_:Exception) {}
        },"fixture-provider").apply{isDaemon=true;start()}}
        private fun handle(socket:Socket) {
            try {socket.use {
                val reader=it.getInputStream().bufferedReader()
                val request=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()) {}
                if("/stalled.ts" in request) {
                    if(stallBody) {
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\nConnection: close\r\n\r\n".toByteArray())
                        it.getOutputStream().write(ByteArray(64));it.getOutputStream().flush()
                    }
                    entered.countDown()
                    try {reader.read()} finally {oldClosed.countDown()}
                } else {
                    healthyRequested.countDown()
                    val body=ByteBuffer.allocate(44+16000).order(ByteOrder.LITTLE_ENDIAN).apply {
                        put("RIFF".toByteArray());putInt(36+16000);put("WAVEfmt ".toByteArray());putInt(16)
                        putShort(1);putShort(1);putInt(8000);putInt(16000);putShort(2);putShort(16)
                        put("data".toByteArray());putInt(16000)
                    }.array()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().write(body);it.getOutputStream().flush()
                }
            }} catch(_:Exception) {}
        }
        fun url(path:String)="http://127.0.0.1:${server.localPort}/$path"
        override fun close() {server.close();sockets.forEach{runCatching{it.close()}}}
    }
    private fun controller():AndroidPlaybackController {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)) {_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)) {_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(context,channels,archive)
    }
    private fun await(latch:CountDownLatch,millis:Long):Boolean {
        val end=System.nanoTime()+millis*1_000_000
        while(latch.count!=0L && System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();latch.await(10,TimeUnit.MILLISECONDS)}
        return latch.count==0L
    }
    private fun switch(stallBody:Boolean) {
        val provider=Provider(stallBody);val c=controller()
        try {
            c.play(PlaybackItem("bad","Stalled source",provider.url("stalled.ts")))
            assertTrue("The controlled bad stream must actually connect",await(provider.entered,4000))
            val fresh=PlaybackItem("good","Healthy source",provider.url("healthy.wav"))
            c.play(fresh)
            assertTrue("The next source must start without waiting for the old HTTP timeout",await(provider.healthyRequested,2000))
            assertTrue("The obsolete provider connection must be closed promptly",await(provider.oldClosed,2000))
            assertEquals(fresh,c.state.value.item)
        } finally {provider.close();c.release()}
    }
    @Test fun stalledHeadersDoNotHoldTheNextChannelOrItsProviderConnection()=switch(false)
    @Test fun stalledBodyDoesNotHoldTheNextChannelOrItsProviderConnection()=switch(true)
}
