package com.tvnext.app.core.player

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import org.jetbrains.skia.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer

/** Uses the same ANGLE/D3D11 device as Skia. No HWND embedding or pixel readback. */
internal class MpvGpuRenderer(private val api:Mpv,private val client:Pointer,private val updated:()->Unit):AutoCloseable {
    private var render:MpvGlContext?=null
    private var owner:GpuBinding?=null
    private var target:Surface?=null
    private var backend:BackendRenderTarget?=null
    private var snapshot:Image?=null
    private var texture=0
    private var fbo=0
    private var width=0
    private var height=0
    private var lastRevision=-1L
    private var rendererName=""
    private var draws=0L
    var frames=0L;private set
    val windowSnapshot get()="gpu_context=${owner?.context?.let {System.identityHashCode(it).toString(16)} ?: "none"} fbo=$fbo target=${width}x$height draws=$draws frames=$frames"
    val initialized get()=render!=null
    val description get()="GPU • ANGLE / Direct3D 11"+(rendererName.takeIf {it.isNotBlank()}?.let {" • $it"} ?: "")
    private val gl by lazy {Native.load("libGLESv2",Gles::class.java)}

    fun draw(canvas:Canvas,host:Window,w:Int,h:Int,revision:Long,ready:Boolean):Boolean {
        val binding=GpuBinding.acquire(host) ?: return false
        return drawWithBinding(canvas,binding,w,h,revision,ready)
    }
    fun drawWithBinding(canvas:Canvas,binding:GpuBinding,w:Int,h:Int,revision:Long,ready:Boolean):Boolean {
        return withWindowState(binding) {drawFrame(canvas,binding,w,h,revision,ready)}
    }
    private fun drawFrame(canvas:Canvas,binding:GpuBinding,w:Int,h:Int,revision:Long,ready:Boolean):Boolean {
        draws++
        ensure(binding)
        val changed=w!=width || h!=height
        if(changed)resize(binding,w,h)
        if(lastRevision!=revision) {snapshot?.close();snapshot=null;lastRevision=revision}
        if(!ready) return true
        val dirty=render!!.dirty()
        // A resized target is empty, including while paused with no new frame.
        if(dirty || changed) {
            binding.context.flush().submit(false)
            target!!.notifyContentWillChange(ContentChangeMode.DISCARD)
            render!!.render(fbo,width,height)
            binding.context.resetGLAll()
            snapshot?.close();snapshot=target!!.makeImageSnapshot()
            frames++
        }
        snapshot?.let {canvas.drawImageRect(it,Rect.makeWH(w.toFloat(),h.toFloat()))}
        return true
    }
    private fun <T> withWindowState(binding:GpuBinding,action:()->T):T {
        if(owner!=null && owner?.context!==binding.context) {
            close()
        }
        binding.current()
        binding.context.flush().submit(false)
        try {
            return GlFramebufferState.capture(gl).use {action()}
        } finally {
            // A cache reset alone leaves mpv's FBO bound at the next native resize.
            binding.context.resetGLAll()
        }
    }
    private fun ensure(binding:GpuBinding) {
        if(render==null) {
            rendererName=gl.glGetString(0x1F01)?.getString(0).orEmpty()
            check(!softwareGpu(rendererName)){"Программный графический драйвер: $rendererName"}
            owner=binding
            render=MpvGlContext(api,client,updated)
            binding.context.resetGLAll()
        }
    }
    /** Consume frames without drawing while the video is hidden or the window is minimized. */
    fun service(host:Window):Boolean {
        val binding=GpuBinding.acquire(host) ?: return false
        return withWindowState(binding) {
            ensure(binding)
            if(render!!.dirty())render!!.skip()
            true
        }
    }
    private fun resize(binding:GpuBinding,w:Int,h:Int) {
        freeTarget()
        width=w.coerceIn(1,8192);height=h.coerceIn(1,8192)
        binding.context.flush().submit(false)
        val names=IntArray(1)
        gl.glGenTextures(1,names);texture=names[0]
        gl.glBindTexture(0x0DE1,texture)
        gl.glTexParameteri(0x0DE1,0x2801,0x2601);gl.glTexParameteri(0x0DE1,0x2800,0x2601)
        gl.glTexImage2D(0x0DE1,0,0x8058,width,height,0,0x1908,0x1401,null)
        gl.glGenFramebuffers(1,names);fbo=names[0]
        gl.glBindFramebuffer(0x8D40,fbo)
        gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,texture,0)
        check(gl.glCheckFramebufferStatus(0x8D40)==0x8CD5) {"GPU framebuffer unavailable"}
        gl.glClearColor(0f,0f,0f,1f);gl.glClear(0x4000)
        binding.context.resetGLAll()
        backend=BackendRenderTarget.makeGL(width,height,0,0,fbo,0x8058)
        target=checkNotNull(Surface.makeFromBackendRenderTarget(binding.context,backend!!,SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,ColorSpace.sRGB)){"Skia GPU framebuffer недоступен"}
    }
    private fun freeTarget(deleteGl:Boolean=true) {
        snapshot?.close();snapshot=null;target?.close();target=null;backend?.close();backend=null
        if(deleteGl && fbo!=0)gl.glDeleteFramebuffers(1,intArrayOf(fbo))
        if(deleteGl && texture!=0)gl.glDeleteTextures(1,intArrayOf(texture))
        fbo=0;texture=0;width=0;height=0
    }
    override fun close() {
        val binding=owner ?: return
        val current=runCatching {binding.current();binding.context.flush().submit(false)}.isSuccess
        try {
            if(current) {
                try {
                    GlFramebufferState.capture(gl).use {
                        render?.close();render=null
                        freeTarget()
                    }
                } finally {binding.context.resetGLAll()}
            } else {
                render?.closeWithoutCurrent();render=null
                freeTarget(false)
            }
        } finally {owner=null}
    }
    internal interface Gles:Library {
        fun glGetString(name:Int):Pointer?
        fun glGetIntegerv(name:Int,result:IntArray)
        fun glViewport(x:Int,y:Int,width:Int,height:Int)
        fun glGenTextures(n:Int,result:IntArray)
        fun glDeleteTextures(n:Int,names:IntArray)
        fun glBindTexture(target:Int,name:Int)
        fun glTexParameteri(target:Int,name:Int,value:Int)
        fun glTexImage2D(target:Int,level:Int,internal:Int,w:Int,h:Int,border:Int,format:Int,type:Int,data:Pointer?)
        fun glGenFramebuffers(n:Int,result:IntArray)
        fun glDeleteFramebuffers(n:Int,names:IntArray)
        fun glBindFramebuffer(target:Int,name:Int)
        fun glFramebufferTexture2D(target:Int,attachment:Int,textureTarget:Int,texture:Int,level:Int)
        fun glCheckFramebufferStatus(target:Int):Int
        fun glClearColor(r:Float,g:Float,b:Float,a:Float)
        fun glClear(mask:Int)
    }
}

/** Skiko 0.144.6 bridge. An unsupported renderer is detected and uses compatibility output. */
internal class GpuBinding(val context:DirectContext,private val activate:()->Unit) {
    fun current()=activate()
    companion object {
        private fun field(value:Any,name:String):Any? {
            var type:Class<*>?=value.javaClass
            while(type!=null) {
                try {val field:Field=type.getDeclaredField(name);field.isAccessible=true;return field.get(value)}
                catch(_:NoSuchFieldException){type=type.superclass}
            };return null
        }
        private fun layer(component:Component):SkiaLayer? = (component as? SkiaLayer) ?: (component as? Container)?.components?.firstNotNullOfOrNull(::layer)
        fun acquire(host:Window):GpuBinding? = runCatching {
            val layer=layer(host) ?: return null
            if(layer.renderApi!=GraphicsApi.ANGLE)return null
            val redrawer=layer.javaClass.getMethod("getRedrawer\$skiko").invoke(layer) ?: return null
            val handler=field(redrawer,"contextHandler") ?: return null
            val context=field(handler,"context") as? DirectContext ?: return null
            val device=field(redrawer,"device") as? Long ?: return null
            if(device==0L)return null
            GpuBinding(context) {
                check(field(redrawer,"device")==device && field(handler,"context")===context){"GPU-контекст закрыт"}
                val bridge=Class.forName("org.jetbrains.skiko.redrawer.AngleRedrawerKt")
                bridge.getDeclaredMethod("access\$makeCurrent",Long::class.javaPrimitiveType).invoke(null,device)
            }
        }.getOrNull()
    }
}

internal fun softwareGpu(name:String):Boolean = listOf("warp","basic render","llvmpipe","swiftshader","software").any {name.contains(it,ignoreCase=true)}
