package com.tvnext.app

import java.awt.Frame
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class DesktopBorderlessFullscreenTest {
    private class Host : BorderlessWindowHost {
        override var bounds = Rectangle(120, 80, 900, 600)
        override var frameState = Frame.NORMAL
        override var resizable = true
        override var topmost = false
        override var monitorBounds = Rectangle(-1920, 0, 1920, 1080)
        var activations = 0
        override fun activate() { activations++ }
        override fun describe() = "test-window"
    }

    @Test fun fillsTheSelectedMonitorAndRestoresWindowAcrossRepeatedToggles() {
        val host = Host()
        val original = Rectangle(host.bounds)
        val controller = DesktopBorderlessFullscreen(host)
        repeat(3) {
            controller.enter();controller.enter()
            assertTrue(controller.isFullscreen)
            assertEquals(host.monitorBounds, host.bounds)
            assertFalse(host.resizable)
            assertEquals(Frame.NORMAL, host.frameState)
            assertTrue(host.topmost)
            controller.activeChanged(false);assertFalse(host.topmost)
            controller.activeChanged(true);assertTrue(host.topmost)
            controller.exit();controller.exit()
            assertEquals(original, host.bounds)
            assertTrue(host.resizable)
            assertFalse(host.topmost)
        }
        assertEquals(3, host.activations)
    }

    @Test fun returningToAMaximizedWindowPreservesItsFloatingRestoreBounds() {
        val host = Host()
        val floating = Rectangle(host.bounds)
        val controller = DesktopBorderlessFullscreen(host)
        host.frameState = Frame.MAXIMIZED_BOTH
        host.bounds = Rectangle(-1920, 0, 1920, 1040)
        controller.windowChanged()
        controller.enter();controller.exit()
        assertEquals(Frame.MAXIMIZED_BOTH, host.frameState)
        assertEquals(floating, host.bounds)
    }

    @Test fun monitorChangesFollowFullBoundsAndCloseRestoresUserWindowFlags() {
        val host = Host().apply {topmost=true;resizable=false}
        val original = Rectangle(host.bounds)
        val controller = DesktopBorderlessFullscreen(host)
        controller.enter()
        host.monitorBounds=Rectangle(0, 0, 1280, 720)
        controller.windowChanged()
        assertEquals(host.monitorBounds, host.bounds)
        controller.activeChanged(false);assertTrue(host.topmost)
        controller.close()
        assertFalse(controller.isFullscreen)
        assertEquals(original, host.bounds)
        assertFalse(host.resizable)
        assertTrue(host.topmost)
    }

    @Test fun diagnosticLogRotatesWithinTheStorageLimit() {
        val folder=Files.createTempDirectory("window-log-test").toFile()
        try {
            val file=folder.resolve("window-transitions.log")
            repeat(100) {appendWindowDiagnostic(file,"stage\n"+"x".repeat(5000))}
            assertTrue(file.length()<=128*1024)
            assertTrue(folder.resolve(file.name+".1").length()<=128*1024)
            assertEquals(2,folder.listFiles()!!.size)
            assertFalse(file.readText().contains("stage\n"))
        } finally {folder.deleteRecursively()}
    }
}
