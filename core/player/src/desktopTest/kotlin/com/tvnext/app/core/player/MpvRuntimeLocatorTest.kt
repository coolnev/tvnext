package com.tvnext.app.core.player

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MpvRuntimeLocatorTest {

    @Test
    fun locateFindsDllViaSystemProperty() {
        val dir = createTempDirectory("mpv-locator-test").toFile()
        try {
            File(dir, "libmpv-2.dll").writeBytes(byteArrayOf(1, 2, 3))
            System.setProperty("tvnext.mpv.dir", dir.absolutePath)
            try {
                val found = MpvRuntimeLocator.locate()
                assertNotNull(found, "locate() must find libmpv-2.dll via tvnext.mpv.dir")
                assertEquals(dir.absolutePath, found)
            } finally {
                System.clearProperty("tvnext.mpv.dir")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun locateUsesWorkingDirectoryItself() {
        // Regression: the old locator searched the PARENT of the working
        // directory, so a libmpv-2.dll sitting in the cwd was never found.
        val cwd = File(".").absoluteFile.absolutePath
        val dll = File(cwd, "libmpv-2.dll")
        val existedBefore = dll.exists()
        if (!existedBefore) {
            dll.writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            val found = MpvRuntimeLocator.locate()
            assertNotNull(found, "locate() must find libmpv-2.dll in the working directory")
            assertEquals(cwd, found)
        } finally {
            if (!existedBefore) {
                dll.delete()
            }
        }
    }
}
