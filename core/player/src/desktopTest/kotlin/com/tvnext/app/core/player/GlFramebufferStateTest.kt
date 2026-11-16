package com.tvnext.app.core.player

import java.lang.reflect.Proxy
import kotlin.test.*

class GlFramebufferStateTest {
    private class Host(var draw: Int, var read: Int, var viewport: IntArray) {
        val gl = Proxy.newProxyInstance(MpvGpuRenderer.Gles::class.java.classLoader,
            arrayOf(MpvGpuRenderer.Gles::class.java)) { _, method, args ->
            when (method.name) {
                "glGetIntegerv" -> {
                    val target = args[1] as IntArray
                    when (args[0] as Int) {
                        0x8CA6 -> target[0] = draw
                        0x8CAA -> target[0] = read
                        0x0BA2 -> viewport.copyInto(target)
                        else -> error("Unexpected GL query")
                    }
                }
                "glBindFramebuffer" -> when (args[0] as Int) {
                    0x8CA9 -> draw = args[1] as Int
                    0x8CA8 -> read = args[1] as Int
                    0x8D40 -> {draw = args[1] as Int; read = draw}
                    else -> error("Unexpected GL target")
                }
                "glViewport" -> viewport = IntArray(4) {args[it] as Int}
                else -> error("Unexpected GL call: ${method.name}")
            }
            null
        } as MpvGpuRenderer.Gles
    }

    @Test fun videoResizeRestoresTheWindowBackBuffer() {
        val host = Host(0, 0, intArrayOf(0, 0, 1920, 1080))
        repeat(4) {index ->
            GlFramebufferState.capture(host.gl).use {
                host.gl.glBindFramebuffer(0x8D40, 20 + index)
                host.gl.glViewport(0, 0, 640, 360)
            }
            assertEquals(0, host.draw)
            assertEquals(0, host.read)
            assertContentEquals(intArrayOf(0, 0, 1920, 1080), host.viewport)
        }
    }

    @Test fun nestedSkiaTargetsKeepSeparateReadAndDrawBindings() {
        val host = Host(7, 11, intArrayOf(12, 24, 800, 450))
        GlFramebufferState.capture(host.gl).use {
            host.gl.glBindFramebuffer(0x8D40, 99)
            host.gl.glViewport(0, 0, 3840, 2160)
        }
        assertEquals(7, host.draw)
        assertEquals(11, host.read)
        assertContentEquals(intArrayOf(12, 24, 800, 450), host.viewport)
    }

    @Test fun aFailedVideoFrameDoesNotLeaveTheInterfaceBoundToItsBuffer() {
        val host = Host(0, 9, intArrayOf(0, 0, 1280, 720))
        assertFailsWith<IllegalStateException> {
            GlFramebufferState.capture(host.gl).use {
                host.gl.glBindFramebuffer(0x8D40, 73)
                host.gl.glViewport(5, 5, 640, 360)
                error("Render failed")
            }
        }
        assertEquals(0, host.draw)
        assertEquals(9, host.read)
        assertContentEquals(intArrayOf(0, 0, 1280, 720), host.viewport)
    }
}
