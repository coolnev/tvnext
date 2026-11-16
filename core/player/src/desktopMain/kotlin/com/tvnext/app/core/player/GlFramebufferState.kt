package com.tvnext.app.core.player

/** Skiko reads the current FBO when rebuilding its window surface after a resize.
 * Invalidating Skia's state cache does not restore these native GL bindings.
 */
internal class GlFramebufferState private constructor(
    private val gl: MpvGpuRenderer.Gles,
    private val draw: Int,
    private val read: Int,
    private val viewport: IntArray,
) : AutoCloseable {
    override fun close() {
        gl.glBindFramebuffer(0x8CA9, draw) // GL_DRAW_FRAMEBUFFER
        gl.glBindFramebuffer(0x8CA8, read) // GL_READ_FRAMEBUFFER
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    companion object {
        fun capture(gl: MpvGpuRenderer.Gles): GlFramebufferState {
            val draw = IntArray(1)
            val read = IntArray(1)
            val viewport = IntArray(4)
            gl.glGetIntegerv(0x8CA6, draw) // GL_DRAW_FRAMEBUFFER_BINDING
            gl.glGetIntegerv(0x8CAA, read) // GL_READ_FRAMEBUFFER_BINDING
            gl.glGetIntegerv(0x0BA2, viewport) // GL_VIEWPORT
            return GlFramebufferState(gl, draw[0], read[0], viewport)
        }
    }
}
