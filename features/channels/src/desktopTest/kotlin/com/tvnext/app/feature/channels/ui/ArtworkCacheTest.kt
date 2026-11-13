package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.ui.image.DefaultLogoImageLoader
import com.tvnext.app.core.ui.image.LogoFetcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlin.test.*

class ArtworkCacheTest {
    private fun png() = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()
    @Test fun posterIsDecodedAndReusedFromMemoryAndAfterRestartFromDisk() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tvnext-artwork").toString()
        var calls = 0
        val fetcher = LogoFetcher { calls++; png() }
        val loader = DefaultLogoImageLoader(fetcher, directory)
        assertEquals(2, loader.load("https://example.org/poster.png")?.width)
        assertEquals(2, loader.load("https://example.org/poster.png")?.height)
        val restarted = DefaultLogoImageLoader(fetcher, directory)
        assertEquals(2, restarted.load("https://example.org/poster.png")?.width)
        assertEquals(1, calls)
    }
    @Test fun brokenDiskEntryIsRedownloadedInsteadOfStayingBlank() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tvnext-artwork-corrupt")
        val fetcher = LogoFetcher { png() }
        DefaultLogoImageLoader(fetcher, directory.toString()).load("https://example.org/frame.png")
        Files.list(directory).use { files -> files.forEach { Files.write(it, byteArrayOf(0, 1, 2)) } }
        var requests = 0
        val restarted = DefaultLogoImageLoader(LogoFetcher { requests++; png() }, directory.toString())
        assertEquals(2, restarted.load("https://example.org/frame.png")?.width)
        assertEquals(1, requests)
    }
    @Test fun concurrentCardsDeduplicateTheSamePoster() = runBlocking<Unit> {
        var requests = 0
        val loader = DefaultLogoImageLoader(LogoFetcher { requests++; delay(50); png() }, Files.createTempDirectory("tvnext-artwork-dedup").toString())
        val images = (1..12).map { async { loader.load("https://example.org/same.png") } }.awaitAll()
        assertTrue(images.all { it?.width == 2 })
        assertEquals(1, requests)
    }
    @Test fun transientProgrammeArtworkNeverWritesDiskOrEntersTheLogoCache()=runBlocking<Unit> {
        val directory=Files.createTempDirectory("tvnext-transient-art")
        var calls=0
        val loader=DefaultLogoImageLoader(LogoFetcher {calls++;png()},directory.toString())
        assertNotNull(loader.loadArtwork("https://example.org/frame.png"))
        assertNotNull(loader.loadArtwork("https://example.org/frame.png"))
        assertEquals(2,calls)
        Files.list(directory).use {assertEquals(0L,it.count())}
    }

}
