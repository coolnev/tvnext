package com.tvnext.app.core.player

/** Actual initialized audio output, independent of the discovered stream format. */
data class DesktopAudioOutput(val driver:String?=null,val device:String?=null,val error:String?=null)
