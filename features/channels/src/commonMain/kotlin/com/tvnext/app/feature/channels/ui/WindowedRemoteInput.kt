package com.tvnext.app.feature.channels.ui

import androidx.compose.ui.input.key.*

internal enum class WindowedPane { CATEGORIES, CHANNELS, PROGRAMMES, VIDEO }

/** Physical key ownership survives focus changes and asynchronous EPG mounting. */
internal class WindowedRemoteInput {
    private var rightHeld = false
    private var videoOkHeld = false

    fun handle(
        event: KeyEvent,
        pane: WindowedPane,
        onChannels: () -> Unit,
        onProgrammes: () -> Unit,
        onVideo: () -> Unit,
        onFullscreen: () -> Unit,
    ): Boolean {
        if (event.key == Key.DirectionRight) {
            when (event.type) {
                KeyEventType.KeyDown -> if (!rightHeld) {
                    rightHeld = true
                    when (pane) {
                        WindowedPane.CATEGORIES -> onChannels()
                        WindowedPane.CHANNELS -> onProgrammes()
                        WindowedPane.PROGRAMMES -> onVideo()
                        WindowedPane.VIDEO -> Unit
                    }
                }
                KeyEventType.KeyUp -> rightHeld = false
            }
            return true
        }
        val activation = event.key == Key.DirectionCenter || event.key == Key.Enter
        if (activation && (videoOkHeld || pane == WindowedPane.VIDEO)) {
            when (event.type) {
                KeyEventType.KeyDown -> videoOkHeld = true
                KeyEventType.KeyUp -> {
                    val activate = videoOkHeld && pane == WindowedPane.VIDEO
                    videoOkHeld = false
                    if (activate) onFullscreen()
                }
            }
            return true
        }
        return false
    }
}
