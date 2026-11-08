package com.tvnext.app.core.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Android Media3 surface; retains the singleton player and D-pad focus ownership. */
@Composable
actual fun NativePlayerSurface(controller: PlaybackController, modifier: Modifier, useTextureView: Boolean) {
    val androidController = controller as? AndroidPlaybackController
    if (androidController == null) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
        return
    }

    val context = LocalContext.current
    val player by androidController.player.collectAsState()
    val playbackState by controller.state.collectAsState()
    var prefsRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val global = AndroidVideoAspectSettings.preferences(context)
        val personal = context.getSharedPreferences("tvnext_profile_personal_v1", Context.MODE_PRIVATE)
        val local = context.getSharedPreferences("tvnext_restored_local_data", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefsRevision++ }
        global.registerOnSharedPreferenceChangeListener(listener)
        personal.registerOnSharedPreferenceChangeListener(listener)
        local.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            global.unregisterOnSharedPreferenceChangeListener(listener)
            personal.unregisterOnSharedPreferenceChangeListener(listener)
            local.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val item = playbackState.item
    val aspect = remember(context, item, prefsRevision) {
        val channelMode = if (item != null && item.kind != PlaybackContentKind.VOD) {
            readChannelPreferences(context, item.id).resize
        } else VideoAspectMode.INHERIT.token
        VideoAspectMode.resolved(channelMode, AndroidVideoAspectSettings.get(context).token)
    }
    key(useTextureView) {
    AndroidView(
        factory = { AndroidVideoViewport(it, useTextureView) },
        modifier = modifier.fillMaxSize(),
        update = { viewport ->
            if (viewport.videoView.player !== player) viewport.videoView.player = player
            viewport.aspect = aspect
        },
        onRelease = { it.videoView.player = null },
    )
    }
}
