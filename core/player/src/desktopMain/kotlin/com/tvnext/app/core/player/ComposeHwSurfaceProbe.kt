package com.tvnext.app.core.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import com.sun.jna.Pointer
import java.awt.Canvas
import java.awt.Robot
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * End-to-end hardware surface probe (Sprint 5).
 *
 * Combines the two previously proven pieces into the production shape:
 * - libVLC HWND video output (D3D11VA + direct3d11) rendering into an AWT
 *   Canvas that is embedded via [SwingPanel] in a real Compose window;
 * - a Compose overlay (green box) drawn on top of the native video surface.
 *
 * Prints:
 *  HW_SURFACE centerColor=<sample of the Compose overlay area>
 *  HW_VIDEO  videoColor=<sample of the video area next to the overlay>
 *
 * If blending works, centerColor is green (#00FF00) while videoColor is the
 * actual video frame (not black). This proves the production layout: native
 * video + Compose overlays in the same window.
 *
 * Usage (Gradle task `probeRunComposeHwSurface` on :core:player):
 *   --duration=8000 --hw=d3d11va <url>
 */
fun main(args: Array<String>) {
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("skiko.renderApi", "OPENGL")
    var durationMs = 8_000L
    var hw = "d3d11va"
    var vout = "direct3d11"
    var regionTest = false
    var overlayWindowTest = false
    var url = "http://127.0.0.1:8098/hevc_4k/master.m3u8"
    args.forEach { arg ->
        when {
            arg.startsWith("--duration=") -> durationMs =
                arg.substringAfter("=").toLongOrNull() ?: durationMs
            arg.startsWith("--hw=") -> hw = arg.substringAfter("=")
            arg.startsWith("--vout=") -> vout = arg.substringAfter("=")
            arg == "--region-test" -> regionTest = true
            arg == "--overlay-window-test" -> overlayWindowTest = true
            else -> url = arg
        }
    }
    runComposeHwSurface(durationMs, hw, vout, url, regionTest, overlayWindowTest)
}

private fun runComposeHwSurface(
    durationMs: Long,
    hw: String,
    vout: String,
    url: String,
    regionTest: Boolean = false,
    overlayWindowTest: Boolean = false,
) {
    println("Skiko property=${System.getProperty("skiko.renderApi")} blending=${System.getProperty("compose.interop.blending")}")
    runCatching {
        println("Skiko actual renderApi=${org.jetbrains.skiko.SkikoProperties.renderApi}")
    }.onFailure { println("SkikoProperties not readable: $it") }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "HW Surface Probe",
            state = rememberWindowState(width = 1000.dp, height = 620.dp),
        ) {
            val canvas = remember { Canvas() }

            LaunchedEffect(Unit) {
                delay(300)
                // Wait until the SwingPanel made the canvas displayable (HWND).
                var waited = 0
                while (waited < 100 && !canvas.isDisplayable) {
                    delay(50)
                    waited++
                }
                if (!canvas.isDisplayable) {
                    println("HW_SURFACE canvas not displayable")
                    exitApplication()
                    return@LaunchedEffect
                }
                Thread.sleep(300)
                // Inspect the interop container: placeInteropAbove tells us
                // whether the SwingPanel (video) window is above Compose.
                runCatching {
                    val container = findInteropContainer(canvas)
                    if (container != null) {
                        val getter = container.javaClass.getMethod("getPlaceInteropAbove")
                        println("HW_INTEROP container=${container.javaClass.simpleName} placeInteropAbove=${getter.invoke(container)}")
                    } else {
                        println("HW_INTEROP container not found")
                    }
                }.onFailure { println("HW_INTEROP inspect failed: $it") }

                VlcRuntimeLocator.prepare()
                val factory = MediaPlayerFactory(
                    "--no-video-title-show",
                    "--avcodec-hw=$hw",
                    "--vout=$vout",
                    "--network-caching=1500",
                    "--no-osd",
                    "--verbose=2",
                )
                val markers = linkedMapOf<String, Int>()
                runCatching {
                    val log = factory.application().newLog()
                    log.setLevel(uk.co.caprica.vlcj.log.LogLevel.DEBUG)
                    log.addLogListener(uk.co.caprica.vlcj.log.LogEventListener { _, _, _, _, _, _, _, message ->
                        val key = when {
                            message.contains("Using D3D11VA", ignoreCase = true) -> "D3D11VA"
                            message.contains("Using DXVA2", ignoreCase = true) -> "DXVA2"
                            message.contains("d3d11va", ignoreCase = true) -> "d3d11va_msg"
                            message.contains("vmem", ignoreCase = true) -> "vmem"
                            message.contains("RV32", ignoreCase = true) -> "RV32"
                            message.contains("wingdi", ignoreCase = true) -> "wingdi"
                            message.contains("win32 window", ignoreCase = true) -> "win32_window"
                            message.contains("direct3d11", ignoreCase = true) -> "d3d11_vout"
                            message.contains("Win32VoutCloseWindow", ignoreCase = true) -> "vout_own_window"
                            else -> null
                        }
                        if (key != null) markers[key] = (markers[key] ?: 0) + 1
                    })
                }
                val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
                try {
                    player.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
                } catch (e: Throwable) {
                    println("HW_SURFACE attach failed: ${e.message}")
                    exitApplication()
                    return@LaunchedEffect
                }
                var playing = false
                player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun playing(mediaPlayer: MediaPlayer) {
                        playing = true
                    }

                    override fun error(mediaPlayer: MediaPlayer) {
                        println("HW_SURFACE vlc error event")
                    }
                })

                println("HW_SURFACE playing: $url hw=$hw")
                player.media().play(url)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < durationMs) {
                    delay(250)
                    if (player.status().state() == State.PLAYING) break
                }
                delay(2500) // let the video render before sampling

                // Region cut-out test: carve a hole in the libVLC vout window
                // above the green Compose overlay. If the overlay becomes
                // visible through the hole (green at center), SetWindowRgn on
                // the vout window is a viable way to show Compose overlays over
                // D3D11 hardware video without changing the render path.
                if (regionTest) {
                    runCatching {
                        val canvasPtr = Pointer(com.sun.jna.Native.getComponentID(canvas))
                        val vout = WinUserX.INSTANCE.GetWindow(canvasPtr, 5 /* GW_CHILD */)
                        if (vout != null && Pointer.nativeValue(vout) != 0L) {
                            // NEGATIVE RESULT (Sprint 7, documented in ARCHITECTURE.md):
                            // DXGI flip-model swapchains ignore SetWindowRgn — even
                            // hiding the vout window completely (NULL region) leaves
                            // the D3D11 video rendering. SetWindowRgn cut-outs are
                            // therefore NOT a viable overlay mechanism for the
                            // direct3d11 vout. Kept as a diagnostic probe.
                            WinUserX.INSTANCE.SetWindowRgn(vout, null, true)
                            println("REGION vout hidden (NULL region) — video unaffected (DXGI ignores region)")
                        } else {
                            println("REGION vout not found")
                        }
                    }.onFailure { println("REGION failed: $it") }
                    delay(1500)
                }

                // Bring the probe window to the foreground BEFORE creating the
                // overlay window, so the overlay window can stay on top of it.
                runCatching {
                    window.toFront()
                    window.isAlwaysOnTop = true
                    delay(300)
                }

                // Overlay-window test: a separate transparent AWT window holding
                // a ComposePanel (green box) positioned over the centre of the
                // video area. If the centre samples GREEN, a separate overlay
                // window is a viable way to show Compose overlays over D3D11
                // hardware video (no render-path change, no DLL).
                var overlayWindow: javax.swing.JWindow? = null
                if (overlayWindowTest) {
                    runCatching {
                        val ow = javax.swing.JWindow(window)
                        // Owned window WITHOUT alwaysOnTop: must stay above the
                        // owner's D3D11 video while never floating above other
                        // applications (owned windows are clipped by the
                        // z-order of their owner's app).
                        ow.setOpacity(0.5f)
                        val panel = androidx.compose.ui.awt.ComposePanel()
                        panel.setContent {
                            Box(
                                modifier = Modifier
                                    .size(220.dp, 120.dp)
                                    .background(Color.Black),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp, 60.dp)
                                        .align(Alignment.Center)
                                        .background(Color.Green),
                                )
                            }
                        }
                        ow.contentPane.layout = java.awt.BorderLayout()
                        ow.contentPane.add(panel, java.awt.BorderLayout.CENTER)
                        val loc = window.locationOnScreen
                        val x = loc.x + window.width / 2 - 110
                        val y = loc.y + window.height / 2 - 60
                        ow.setBounds(x, y, 220, 120)
                        ow.setVisible(true)
                        overlayWindow = ow
                        println("OVERLAY_WIN shown at $x,$y 220x120 (ComposePanel)")
                    }.onFailure { println("OVERLAY_WIN failed: $it") }
                    delay(2500)
                }

                // Window Z-order diagnostic: are the video (SwingPanel) windows
                // ABOVE the Compose window?
                runCatching {
                    println("HW_ZORDER ${windowZOrder()}")
                }.onFailure { println("HW_ZORDER failed: $it") }

                val probe = ProbeSampler(window)
                val center = probe.sampleCenter()
                val videoLeft = probe.sample(
                    window.width / 2 - window.width / 5,
                    window.height / 2,
                )
                if (overlayWindowTest && overlayWindow != null) {
                    val row = (0..4).joinToString(" ") { i ->
                        val x = window.width * (i * 2 + 1) / 10
                        probe.sample(x, window.height / 2)
                    }
                    println("OVERLAY_WIN_ROW y=center: $row")
                }
                if (regionTest) {
                    val row = (0..4).joinToString(" ") { i ->
                        val x = window.width * (i * 2 + 1) / 10
                        probe.sample(x, window.height / 2)
                    }
                    println("REGION_ROW y=center: $row")
                    val col = (0..4).joinToString(" ") { i ->
                        val y = window.height * (i * 2 + 1) / 10
                        probe.sample(window.width / 2, y)
                    }
                    println("REGION_COL x=center: $col")
                }
                println("HW_SURFACE centerColor=$center (overlay area)")
                println("HW_VIDEO videoColor=$videoLeft (video area next to overlay)")
                println("HW_MARKERS ${markers.entries.joinToString(" | ") { "${it.key}x${it.value}" }}")
                delay(500)
                player.controls().stop()
                player.release()
                factory.release()
                window.dispose()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                SwingPanel(
                    factory = { canvas },
                    modifier = Modifier.fillMaxSize(),
                )
                // Compose overlay that must stay visible above the native video.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 220.dp, height = 120.dp)
                        .background(Color.Green),
                )
            }
        }
    }
}

private class ProbeSampler(private val window: androidx.compose.ui.awt.ComposeWindow) {
    private val robot = Robot()

    fun sampleCenter(): String = sample(window.width / 2, window.height / 2)

    fun sample(relX: Int, relY: Int): String = try {
        val loc = window.locationOnScreen
        val px = robot.getPixelColor(loc.x + relX, loc.y + relY)
        "#%02X%02X%02X".format(px.red, px.green, px.blue)
    } catch (e: Throwable) {
        "n/a (${e.message})"
    }
}

private fun findInteropContainer(component: java.awt.Component): Any? {
    var c: java.awt.Component? = component
    while (c != null) {
        for (f in c.javaClass.declaredFields) {
            runCatching {
                f.isAccessible = true
                val v = f.get(c)
                if (v != null && v.javaClass.simpleName.contains("SwingInteropContainer")) return v
            }
        }
        c = c.parent
    }
    return null
}

/** Lists top-level windows of this process from top to bottom (Z-order). */
private fun windowZOrder(): String {
    val pid = ProcessHandle.current().pid().toInt()
    val result = StringBuilder()
    val proc = object : WndEnumProc {
        override fun callback(hwnd: Pointer, lParam: Pointer): Boolean {
            val lpdwProcessId = IntArray(1)
            WinUserX.INSTANCE.GetWindowThreadProcessId(hwnd, lpdwProcessId)
            if (lpdwProcessId[0] == pid) {
                val cls = CharArray(256)
                WinUserX.INSTANCE.GetClassNameW(hwnd, cls, cls.size)
                val name = String(cls).trimEnd('\u0000')
                val rect = com.sun.jna.Memory(16)
                WinUserX.INSTANCE.GetWindowRect(hwnd, rect)
                val l = rect.getInt(0); val t = rect.getInt(4)
                val r = rect.getInt(8); val b = rect.getInt(12)
                result.append("[$name ${l},${t} ${r},${b}] ")
            }
            return true
        }
    }
    WinUserX.INSTANCE.EnumWindows(proc, null)
    return result.toString()
}

private interface WndEnumProc : com.sun.jna.Callback {
    fun callback(hwnd: Pointer, lParam: Pointer): Boolean
}

private interface WinUserX : com.sun.jna.Library {
    fun EnumWindows(lpEnumFunc: WndEnumProc, lParam: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntArray?): Int
    fun GetClassNameW(hWnd: Pointer, lpClassName: CharArray, nMaxCount: Int): Int
    fun GetWindowRect(hWnd: Pointer, lpRect: Pointer): Boolean
    fun GetWindow(hWnd: Pointer, uCmd: Int): Pointer
    fun SetWindowRgn(hWnd: Pointer, hRgn: Pointer?, bRedraw: Boolean): Int

    companion object {
        val INSTANCE: WinUserX = com.sun.jna.Native.load("user32", WinUserX::class.java)
    }
}

private interface Gdi32X : com.sun.jna.Library {
    fun CreateRectRgn(x1: Int, y1: Int, x2: Int, y2: Int): Pointer
    fun CombineRgn(pDest: Pointer, pSrc1: Pointer, pSrc2: Pointer, fnCombineMode: Int): Int
    fun DeleteObject(hObject: Pointer): Boolean

    companion object {
        val INSTANCE: Gdi32X = com.sun.jna.Native.load("gdi32", Gdi32X::class.java)
    }
}
