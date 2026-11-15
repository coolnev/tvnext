package com.tvnext.app.feature.channels.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*

internal class FullscreenRightInput {
    private var held=false
    fun press():Boolean {if(held)return false;held=true;return true}
    fun release() {held=false}
}

/** Observe UP before a focused panel delegates its own keys. Do not consume it. */
internal fun Modifier.trackFullscreenRightRelease(input:FullscreenRightInput):Modifier = onPreviewKeyEvent {event->
    if(event.key==Key.DirectionRight && event.type==KeyEventType.KeyUp)input.release()
    false
}
