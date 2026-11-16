package com.tvnext.app.core.player

/** Decode and presentation are separate: compatible mode deliberately keeps copy-back. */
enum class WindowsVideoMode(val label:String,val gpu:Boolean,val hwdec:String) {
    AUTO("Авто",true,"auto"),
    HARDWARE_GPU("Аппаратный — GPU",true,"d3d11va"),
    HARDWARE_COMPATIBLE("Аппаратный — совместимый",false,"auto-copy"),
    SOFTWARE("Программный",false,"no");
    companion object {
        fun legacy(mode:VideoDecodeMode)=when(mode){VideoDecodeMode.SOFTWARE->SOFTWARE;VideoDecodeMode.HARDWARE->HARDWARE_GPU;else->AUTO}
    }
}
