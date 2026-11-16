package com.tvnext.app

import androidx.compose.ui.input.key.*

internal enum class DesktopCommand { PAUSE, BACK, HOME, FULLSCREEN, MUTE, VOLUME_UP, VOLUME_DOWN, SEEK_BACK, SEEK_FORWARD, PREVIOUS_CHANNEL, NEXT_CHANNEL, INFO, CHANNELS }

/** No global letter/space shortcuts while a text editor or modal owns input. */
internal class DesktopShortcuts {
    private val held=mutableSetOf<Key>()
    fun reset(){held.clear()}
    fun holds(key:Key)=key in held
    fun command(key:Key,down:Boolean,watching:Boolean,editing:Boolean,videoFocused:Boolean,alt:Boolean=false,ctrl:Boolean=false):DesktopCommand? {
        if(!down){held.remove(key);return null}
        val action=when {
            key==Key.F11 || (alt && key==Key.Enter)->DesktopCommand.FULLSCREEN
            key==Key.Escape->DesktopCommand.BACK
            alt && key==Key.MoveHome->DesktopCommand.HOME
            editing->null
            !watching->null
            key==Key.Spacebar || key==Key.K->DesktopCommand.PAUSE
            key==Key.M->DesktopCommand.MUTE
            key==Key.I->DesktopCommand.INFO
            key==Key.B->DesktopCommand.CHANNELS
            key==Key.PageUp->DesktopCommand.PREVIOUS_CHANNEL
            key==Key.PageDown->DesktopCommand.NEXT_CHANNEL
            key==Key.J || (videoFocused && key==Key.DirectionLeft)->DesktopCommand.SEEK_BACK
            key==Key.L || (videoFocused && key==Key.DirectionRight)->DesktopCommand.SEEK_FORWARD
            key==Key.Plus || key==Key.Equals || (videoFocused && key==Key.DirectionUp)->DesktopCommand.VOLUME_UP
            key==Key.Minus || (videoFocused && key==Key.DirectionDown)->DesktopCommand.VOLUME_DOWN
            videoFocused && key==Key.Enter->DesktopCommand.FULLSCREEN
            else->null
        }
        if(action!=null && !held.add(key) && action in setOf(DesktopCommand.PAUSE,DesktopCommand.FULLSCREEN,DesktopCommand.MUTE,DesktopCommand.BACK))return null
        return action
    }
}
