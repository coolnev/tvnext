package com.tvnext.app.feature.media.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvnext.app.core.player.ClockPosition

/** Native Android TV media browser; does not compile Desktop layout on Android. */
@Composable
actual fun MediaScreen(
    component: MediaComponent,
    modifier: Modifier,
    clockPosition: ClockPosition,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onBackHome: (() -> Unit)?,
    showDiagnostics: Boolean,
    infoDurationSeconds: Int?,
    onToggleDiagnostics: () -> Unit,
) {
    AndroidMediaScreen(
        component = component,
        modifier = modifier,
        clockPosition = clockPosition,
        onImmersiveChange = onImmersiveChange,
        onToggleFullscreen = onToggleFullscreen,
        onBackHome = onBackHome,
        showDiagnostics = showDiagnostics,
        infoDurationSeconds = infoDurationSeconds,
    )
}
