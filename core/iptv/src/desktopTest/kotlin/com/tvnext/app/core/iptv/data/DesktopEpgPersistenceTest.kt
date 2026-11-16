package com.tvnext.app.core.iptv.data

import com.sun.net.httpserver.HttpServer
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.domain.*
import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class DesktopEpgPersistenceTest {
    @Test fun portableIndexSurvivesReopenWithoutHttpOrRetainedXml()=verifySource(false)
    @Test fun gzipSourceWithoutGzipFilenameSurvivesReopenWithoutAnotherDownload()=verifySource(true)
    private fun verifySource(compressed:Boolean)=runBlocking<Unit> {
        val root=Files.createTempDirectory("TVNext переносной ").toFile()
        val previous=System.getProperty("tvnext.data.dir")
        val calls=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val now=System.currentTimeMillis()
        val stamp=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val xml="""<tv><channel id="news"><display-name>Новости HD</display-name></channel><programme channel="news" start="${stamp.format(Instant.ofEpochMilli(now-3600000))}" stop="${stamp.format(Instant.ofEpochMilli(now+3600000))}"><title>Сохранённая программа</title><desc>Описание из EPG</desc></programme></tv>""".toByteArray()
        val wire=if(compressed)java.io.ByteArrayOutputStream().also {out->
            java.util.zip.GZIPOutputStream(out).use {it.write(xml)}
        }.toByteArray() else xml
        server.createContext("/epg.xml") {exchange->
            calls.incrementAndGet();exchange.responseHeaders.add("Content-Type",if(compressed)"application/gzip" else "application/xml")
            exchange.sendResponseHeaders(200,wire.size.toLong());exchange.responseBody.use {it.write(wire)}
        }
        server.start()
        val url="http://127.0.0.1:${server.address.port}/epg.xml"
        val playlist=Playlist("qa","Проверка","https://unused.invalid/list.m3u",epgUrl=url)
        val channel=Channel("qa","Новости HD",1,"Тест","https://unused.invalid/live",tvgId="news",playlistId="qa")
        val playlists=object:PlaylistRepository {
            override fun observePlaylists():Flow<List<Playlist>> = emptyFlow()
            override suspend fun getPlaylists()=listOf(playlist)
            override suspend fun getPlaylist(id:String)=playlist
            override suspend fun getDefaultPlaylist()=playlist
            override suspend fun addPlaylist(url:String,type:PlaylistType,name:String?):AppResult<Playlist> = error("unused")
            override suspend fun removePlaylist(id:String):AppResult<Unit> = error("unused")
            override suspend fun renamePlaylist(id:String,name:String):AppResult<Unit> = error("unused")
            override suspend fun refreshPlaylist(playlistId:String):AppResult<Playlist> = error("unused")
            override suspend fun refreshAll():AppResult<Unit> = error("unused")
            override suspend fun setDefaultPlaylist(id:String?):AppResult<Unit> = error("unused")
            override suspend fun refreshIfParserUpgraded():AppResult<Unit> = error("unused")
        }
        val channels=object:ChannelsRepository {
            override suspend fun getChannels()=listOf(channel)
            override suspend fun getChannelsByPlaylist(playlistId:String)=listOf(channel)
            override suspend fun isConfigured()=true
            override suspend fun refresh():AppResult<Unit> = error("unused")
            override suspend fun getFavoriteIds()=emptySet<String>()
            override suspend fun setFavorite(channelId:String,favorite:Boolean)=Unit
        }
        val config=object:EpgConfigProvider {
            override suspend fun isEnabled()=true
            override suspend fun userEpgUrl():String?=null
        }
        val client=HttpClient()
        try {
            System.setProperty("tvnext.data.dir",root.absolutePath)
            val first=DesktopEpgRepository(client,playlists,channels,config)
            assertIs<AppResult.Success<Unit>>(withTimeout(10000){first.refreshIfStale(true)})
            assertEquals("Сохранённая программа",first.getNowPlaying(listOf(channel),now)[channel.id]?.title)
            assertEquals(1,calls.get())
            val reopened=DesktopEpgRepository(client,playlists,channels,config)
            assertIs<AppResult.Success<Unit>>(withTimeout(2000){reopened.refreshIfStale(false)})
            assertEquals("Сохранённая программа",reopened.getNowPlaying(listOf(channel),now)[channel.id]?.title)
            delay(500)
            assertEquals(1,calls.get(),"Fresh persisted EPG must not be downloaded again")
            assertTrue(root.resolve("epg-index-v1.db").length()>0)
            assertTrue(root.resolve("epg-downloads-v1").walkTopDown().none {it.isFile && (it.extension=="xml" || it.extension=="gz")},"No second full XML cache")
        } finally {
            if(previous==null)System.clearProperty("tvnext.data.dir") else System.setProperty("tvnext.data.dir",previous)
            client.close();server.stop(0)
        }
    }
}
