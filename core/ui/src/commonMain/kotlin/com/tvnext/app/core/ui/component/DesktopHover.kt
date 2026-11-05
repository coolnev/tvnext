package com.tvnext.app.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop-only hover detection: invokes [enter] when the pointer enters the
 * attached area and [exit] when it leaves. On Android this is a no-op (no
 * mouse hover) — kept expect/actual so the shared screens stay cross-platform.
 */
@Composable
expect fun Modifier.hoverEnterExit(
    enter: () -> Unit,
    exit: () -> Unit,
): Modifier
