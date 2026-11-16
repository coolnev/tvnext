package com.tvnext.app.core.player

import java.io.File

object WindowsGpuRuntime {
    var prepared:Boolean=false;private set
    /** Load Skia and ANGLE from one directory so their EGL context is shared with libmpv. */
    fun prepare() {
        if(!com.sun.jna.Platform.isWindows())return
        val dir=MpvRuntimeLocator.locate() ?: return
        if(listOf("skiko-windows-x64.dll","libEGL.dll","libGLESv2.dll").all {File(dir,it).isFile}) {
            prepared=true
            System.setProperty("skiko.library.path",dir)
            if(System.getProperty("skiko.renderApi")==null)System.setProperty("skiko.renderApi","ANGLE")
            com.sun.jna.NativeLibrary.addSearchPath("libEGL",dir)
            com.sun.jna.NativeLibrary.addSearchPath("libGLESv2",dir)
        }
    }
}
