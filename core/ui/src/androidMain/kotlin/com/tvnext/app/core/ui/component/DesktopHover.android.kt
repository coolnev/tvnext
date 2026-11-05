package com.tvnext.app.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Android implementation: no mouse hover — the modifier is a no-op. */
@Composable
actual fun Modifier.hoverEnterExit(
    enter: () -> Unit,
    exit: () -> Unit,
): Modifier = this
