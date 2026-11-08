package com.tvnext.app.core.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Platform-specific video surface bound to a [PlaybackController].
 * The concrete rendering backend is provided per platform (desktop/android).
 */
@Composable
fun PlayerSurface(
    controller: PlaybackController,
    modifier: Modifier,
) {
    val scene = LocalVideoScene.current
    if (scene == null) {
        NativePlayerSurface(controller, modifier, false)
        return
    }
    val token = remember { Any() }
    DisposableEffect(scene, token) { onDispose { scene.remove(token) } }
    Box(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        scene.update(VideoViewport(token, Rect(coordinates.positionInRoot(),
            Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())), coordinates.boundsInRoot(), controller))
    })
}

@Composable
expect fun NativePlayerSurface(controller: PlaybackController, modifier: Modifier, useTextureView: Boolean)
