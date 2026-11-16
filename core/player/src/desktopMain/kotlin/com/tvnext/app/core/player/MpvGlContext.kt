package com.tvnext.app.core.player

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference

/** A GPU render-API context; all calls are serialized on the thread with its EGL context current. */
internal class MpvGlContext(private val api:Mpv,client:Pointer,updated:()->Unit):AutoCloseable {
    private val egl=Native.load("libEGL",Egl::class.java)
    private val proc=object:ProcAddress {override fun invoke(ctx:Pointer?,name:String):Pointer?=egl.eglGetProcAddress(name)}
    private val callback=object:Mpv.MpvRenderUpdateCallback {override fun invoke(ctx:Pointer?){updated()}}
    private val context:Pointer
    init {
        val result=PointerByReference()
        val rc=Memory(48).use {params->Memory(7).use {type->Memory(16).use {init->
            params.clear();type.setString(0,"opengl")
            init.setPointer(0,CallbackReference.getFunctionPointer(proc));init.setPointer(8,null)
            params.setInt(0,1);params.setPointer(8,type);params.setInt(16,2);params.setPointer(24,init)
            api.mpv_render_context_create(result,client,params)
        }}}
        check(rc>=0 && result.value!=null){"OpenGL render: ${mpvErrorMessage(api,rc)}"}
        context=result.value
        api.mpv_render_context_set_update_callback(context,callback,null)
    }
    fun dirty()=api.mpv_render_context_update(context) and 1L!=0L
    fun render(fbo:Int,width:Int,height:Int) {
        val rc=Memory(16).use {format->Memory(4).use {flip->Memory(4).use {block->Memory(64).use {params->
            format.setInt(0,fbo);format.setInt(4,width);format.setInt(8,height);format.setInt(12,0x8058)
            flip.setInt(0,0);block.setInt(0,0)
            params.clear();params.setInt(0,3);params.setPointer(8,format);params.setInt(16,4);params.setPointer(24,flip)
            params.setInt(32,12);params.setPointer(40,block)
            api.mpv_render_context_render(context,params)
        }}}}
        check(rc>=0){"GPU frame: ${mpvErrorMessage(api,rc)}"}
    }
    fun skip() {
        Memory(4).use {skip->Memory(4).use {block->Memory(48).use {params->
            skip.setInt(0,1);block.setInt(0,0);params.clear()
            params.setInt(0,13);params.setPointer(8,skip);params.setInt(16,12);params.setPointer(24,block)
            api.mpv_render_context_render(context,params)
        }}}
    }
    override fun close(){api.mpv_render_context_set_update_callback(context,null,null);api.mpv_render_context_free(context)}
    private interface ProcAddress:Callback {fun invoke(ctx:Pointer?,name:String):Pointer?}
    fun closeWithoutCurrent() {
        val display=egl.eglGetCurrentDisplay()
        val context=egl.eglGetCurrentContext()
        val draw=egl.eglGetCurrentSurface(0x3059);val read=egl.eglGetCurrentSurface(0x305A)
        if(display!=null)egl.eglMakeCurrent(display,null,null,null)
        try {close()} finally {if(display!=null && context!=null)egl.eglMakeCurrent(display,draw,read,context)}
    }
    private interface Egl:Library {
        fun eglGetProcAddress(name:String):Pointer?
        fun eglGetCurrentDisplay():Pointer?
        fun eglGetCurrentContext():Pointer?
        fun eglGetCurrentSurface(which:Int):Pointer?
        fun eglMakeCurrent(display:Pointer?,draw:Pointer?,read:Pointer?,context:Pointer?):Int
    }
}
