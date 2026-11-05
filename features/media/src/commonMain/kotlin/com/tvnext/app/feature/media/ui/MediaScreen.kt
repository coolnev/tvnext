package com.tvnext.app.feature.media.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvnext.app.core.player.ClockPosition

/**
 * Shared entry point for the media tab.
 * Android and Desktop have different UIs and are compiled independently.
 * This also keeps Desktop-specific logic out of the Android compiler path.
 */
@Composable
expect fun MediaScreen(
    component: MediaComponent,
    modifier: Modifier = Modifier,
    clockPosition: ClockPosition = ClockPosition.OFF,
    immersive: Boolean = false,
    onImmersiveChange: (Boolean) -> Unit = {},
    onToggleFullscreen: (() -> Unit)? = null,
    onBackHome: (() -> Unit)? = null,
    showDiagnostics: Boolean = true,
    infoDurationSeconds: Int? = 3,
    onToggleDiagnostics: () -> Unit = {},
)
