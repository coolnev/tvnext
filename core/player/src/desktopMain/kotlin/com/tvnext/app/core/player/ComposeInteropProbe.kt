package com.tvnext.app.core.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Canvas
import java.awt.Robot
import kotlinx.coroutines.delay

/**
 * Interop-blending PoC (Sprint 5).
 *
 * Proves that Compose content can be drawn ON TOP of an AWT/Swing interop view
 * (a heavyweight [Canvas] that libVLC renders into via HWND) when the
 * `compose.interop.blending` flag is enabled. This is the enabling mechanism
 * for the hardware-decode path: a native libVLC video surface inside the
 * existing Compose window while channel-name/error/controls overlays keep
 * rendering above the video.
 *
 * Run with `-Dcompose.interop.blending=true`. The probe opens a window with a
 * black Canvas and a green centred box on top; if blending works the window
 * centre is green, otherwise it stays black. The sampled centre colour is
 * printed as BLEND_RESULT.
 *
 * Usage (Gradle task `probeRunComposeInterop` on :core:player).
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose Interop Blending Probe",
        state = rememberWindowState(width = 800.dp, height = 500.dp),
    ) {
        val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }

        LaunchedEffect(Unit) {
            delay(800)
            runCatching { window.toFront() }
            delay(2000)
            val px = captureWindowCenter(window)
            println("BLEND_RESULT centerColor=$px")
            delay(500)
            window.dispose()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray),
        ) {
            SwingPanel(
                factory = { canvas },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 260.dp, height = 140.dp)
                    .background(Color.Green),
            )
        }
    }
}

private fun captureWindowCenter(window: androidx.compose.ui.awt.ComposeWindow): String = try {
    val robot = Robot()
    val loc = window.locationOnScreen
    val size = window.size
    val px = robot.getPixelColor(loc.x + size.width / 2, loc.y + size.height / 2)
    "#%02X%02X%02X".format(px.red, px.green, px.blue)
} catch (e: Throwable) {
    "n/a (${e.message})"
}
