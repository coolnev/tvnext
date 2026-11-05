package com.tvnext.app.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/** Desktop implementation: real hover enter/exit events. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.hoverEnterExit(
    enter: () -> Unit,
    exit: () -> Unit,
): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { enter() }
    .onPointerEvent(PointerEventType.Exit) { exit() }
