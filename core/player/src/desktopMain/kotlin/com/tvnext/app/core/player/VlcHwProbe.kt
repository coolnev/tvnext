package com.tvnext.app.core.player

import java.awt.Canvas
import java.awt.Color
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogEventListener
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * Hardware-acceleration PoC probe (Sprint 5).
 *
 * Plays streams through a **real HWND video output**: an AWT [Canvas] (visible
 * window) is handed to libVLC via `libvlc_media_player_set_hwnd`
 * ([uk.co.caprica.vlcj.player.embedded.videosurface.ComponentVideoSurface]).
 * With `--avcodec-hw=d3d11va|dxva2` and `--vout=direct3d11|direct3d9` libVLC can
 * use the GPU decoder and render into the HWND.
 *
 * Goals:
 *  - prove that a hardware decoder (D3D11VA/DXVA2) is actually selected;
 *  - measure CPU for H.264/HEVC 1080p/4K;
 *  - capture the libVLC log markers (decoder module, pix_fmt, vout module);
 *  - detect "hardware requested but not available" so the production AUTO
 *    fallback semantics can be built on facts, not assumptions.
 *
 * Usage (Gradle task `probeRunVlcHw` on :core:player):
 *   --duration=12000 --hw=d3d11va --vout=direct3d11 --windowed
 *     <url1> [<url2> ...]
 *
 * Writes to C:\Temp\kilo\vlc-hw-probe-results.txt.
 */
fun main(args: Array<String>) {
    var durationMs = 12_000L
    var hwMode = "d3d11va"
    var vout = "direct3d11"
    var windowed = false
    val urls = mutableListOf<String>()

    args.forEach { arg ->
        when {
            arg.startsWith("--duration=") -> durationMs =
                arg.substringAfter("=").toLongOrNull() ?: durationMs
            arg.startsWith("--hw=") -> hwMode = arg.substringAfter("=")
            arg.startsWith("--vout=") -> vout = arg.substringAfter("=")
            arg == "--windowed" -> windowed = true
            else -> urls += arg
        }
    }
    if (urls.isEmpty()) {
        println(
            "Usage: --duration=<ms> --hw=<d3d11va|dxva2|any|none> " +
                "--vout=<direct3d11|direct3d9|gl|auto> [--windowed] <url>...",
        )
        return
    }

    val logFile = File("C:\\Temp\\kilo\\vlc-hw-probe-results.txt")
    logFile.parentFile.mkdirs()
    val writer = logFile.printWriter()
    fun log(msg: String) {
        println(msg)
        writer.println(msg)
    }

    log("=== TVNext libVLC HW probe ===")
    log("duration=${durationMs}ms hw=$hwMode vout=$vout windowed=$windowed streams=${urls.size}")

    // AWT window with a heavyweight Canvas — its HWND becomes the video output.
    probeFrame = JFrame("VLC HW Probe")
    probeCanvas = Canvas().apply { background = Color.BLACK }
    probeFrame.contentPane.add(probeCanvas)
    probeFrame.setSize(960, 540)
    probeFrame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    SwingUtilities.invokeAndWait { probeFrame.isVisible = true }
    Thread.sleep(300)

    val factoryArgs = buildList {
        add("--no-video-title-show")
        add("--avcodec-hw=$hwMode")
        add("--vout=$vout")
        add("--network-caching=1500")
        add("--http-user-agent=${VlcHwUserAgent}")
        add("--http-reconnect")
        add("--no-osd")
        add("--verbose=2")
        // Full native log goes to a file for offline analysis of decoder/vout
        // markers (pix_fmt: d3d11va_vld proves the GPU decoder).
        add("--file-logging")
        add("--logfile=C:\\Temp\\kilo\\vlc-hw-probe-full.log")
        add("--logmode=text")
    }.toTypedArray()

    val factory = try {
        MediaPlayerFactory(*factoryArgs)
    } catch (e: Throwable) {
        log("FATAL factory: ${e}")
        writer.flush(); writer.close()
        SwingUtilities.invokeLater { probeFrame.dispose() }
        return
    }

    // Collect native log markers (decoder/vout modules, hw fallbacks).
    val nativeLog = try {
        factory.application().newLog().apply {
            setLevel(LogLevel.DEBUG)
            addLogListener(LogEventListener { level, _, _, _, _, _, _, message ->
                logEntry(level, message)
            })
        }
    } catch (e: Throwable) {
        null
    }

    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
    try {
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(probeCanvas))
    } catch (e: Throwable) {
        log("WARN video surface attach failed: ${e.message} (continuing)")
    }

    urls.forEachIndexed { index, url ->
        log("")
        log("----- [${index + 1}] hw=$hwMode vout=$vout $url -----")
        logMarkers.reset()
        runStream(player, url, durationMs, ::log)
        log("  markers: ${logMarkers.summary()}")
        log("  DECODER VERDICT: ${logMarkers.verdict()}")
    }

    log("")
    log("=== SUMMARY ===")
    player.release()
    runCatching { nativeLog?.release() }
    runCatching { factory.release() }
    SwingUtilities.invokeLater { probeFrame.dispose() }
    log("=== VLC HW PROBE DONE ===")
    writer.flush()
    writer.close()
}

private lateinit var probeFrame: JFrame
private lateinit var probeCanvas: Canvas

/** Samples the centre pixel of the probe window to prove video is rendered. */
private fun captureCenterColor(): String = try {
    runCatching {
        SwingUtilities.invokeAndWait {
            probeFrame.toFront()
            probeFrame.isAlwaysOnTop = true
        }
    }
    Thread.sleep(200)
    val robot = java.awt.Robot()
    val loc = probeFrame.locationOnScreen
    val size = probeFrame.size
    val px = robot.getPixelColor(loc.x + size.width / 2, loc.y + size.height / 2)
    "#%02X%02X%02X".format(px.red, px.green, px.blue)
} catch (e: Throwable) {
    "n/a (${e.message})"
}

private fun runStream(
    player: EmbeddedMediaPlayer,
    url: String,
    durationMs: Long,
    log: (String) -> Unit,
) {
    val started = System.currentTimeMillis()
    var playingAt = -1L
    var errorAt = -1L
    var lastError: String? = null
    var lastState = "n/a"
    var lastFrameDims = "0x0"

    val listener = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) {
            if (playingAt < 0) playingAt = System.currentTimeMillis() - started
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (errorAt < 0) errorAt = System.currentTimeMillis() - started
            lastError = "vlc error event"
        }
    }
    player.events().addMediaPlayerEventListener(listener)

    val cpuBefore = processCpuNanos()
    player.media().play(url)
    val end = started + durationMs
    var infoLogged = false
    var screenshotTaken = false
    while (System.currentTimeMillis() < end && errorAt < 0) {
        Thread.sleep(200)
        val state = player.status().state()
        if (state.name != lastState) {
            log("[+${System.currentTimeMillis() - started}ms] state: ${state.name}")
            lastState = state.name
        }
        if (!infoLogged && (state == State.PLAYING || state == State.BUFFERING)) {
            infoLogged = true
            try {
                val info = player.media().info()
                info.videoTracks().forEachIndexed { i, t ->
                    log(
                        "  video[$i] codec=${t.codecName()} origCodec=${t.originalCodecName()} " +
                            "resolution=${t.width()}x${t.height()} " +
                            "fps=${t.frameRate()}/${t.frameRateBase()}",
                    )
                }
            } catch (e: Throwable) {
                log("  track info unavailable: ${e.message}")
            }
            runCatching {
                val dim = player.video().videoDimension()
                log("  videoDimension=${dim.width}x${dim.height}")
            }.onFailure { log("  videoDimension unavailable: ${it.message}") }
            // videoDimension may be 0x0 until the first decoded frame; poll for it.
            var polled = 0
            while (polled < 8) {
                Thread.sleep(500)
                polled++
                val dim = runCatching { player.video().videoDimension() }.getOrNull()
                if (dim != null && dim.width > 0 && dim.height > 0) {
                    log("  videoDimension(final)=${dim.width}x${dim.height}")
                    break
                }
            }
        }
        if (!screenshotTaken && state == State.PLAYING && System.currentTimeMillis() - playingAt > 2000) {
            screenshotTaken = true
            lastFrameDims = logMarkers.frameDimensions() ?: "n/a"
            val centerColor = captureCenterColor()
            log("  canvasCenterColor=$centerColor frameDimsFromLog=$lastFrameDims")
        }
    }

    if (errorAt < 0 && playingAt < 0) {
        log("  no playback within window; final state=${player.status().state().name}")
    }

    val cpuAfter = processCpuNanos()
    val wallNanos = (System.currentTimeMillis() - started) * 1_000_000L
    val cpuPct = if (wallNanos > 0) {
        (cpuAfter - cpuBefore).toDouble() / wallNanos * 100.0
    } else 0.0

    log(
        "RESULT url=$url\n" +
            "  playingAt=$playingAt errorAt=$errorAt finalState=${player.status().state().name} error=$lastError\n" +
            "  cpuPct=${"%.1f".format(cpuPct)}% of one core",
    )
    player.controls().stop()
    player.events().removeMediaPlayerEventListener(listener)
}

/** CPU time consumed by this JVM process so far, in nanoseconds. */
private fun processCpuNanos(): Long {
    val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    return if (bean is com.sun.management.OperatingSystemMXBean) {
        bean.processCpuTime
    } else {
        0L
    }
}

// ---- Native log marker collection -------------------------------------------

private object logMarkers {
    private val hits = LinkedHashMap<String, Int>()
    private val reinitContexts = LinkedHashSet<String>()

    @Synchronized
    fun reset() {
        hits.clear()
        reinitContexts.clear()
    }

    @Synchronized
    operator fun invoke(level: LogLevel, message: String) {
        // "Reinit context to 1920x1088, pix_fmt: d3d11va_vld" — the most direct
        // proof of the actual decode path and resolution.
        val reinit = Regex("""Reinit context to (\d+)x(\d+).*pix_fmt: (\S+)""")
            .find(message)
        if (reinit != null) {
            val dims = "${reinit.groupValues[1]}x${reinit.groupValues[2]}"
            val fmt = reinit.groupValues[3]
            reinitContexts += "$dims@$fmt"
            return
        }
        val key = when {
            message.contains("using vout display module", ignoreCase = true) ->
                "VOUT:${message.substringAfter("vout display module").trim().take(24)}"
            message.contains("Using D3D11VA", ignoreCase = true) -> "D3D11VA_USED"
            message.contains("Using DXVA2", ignoreCase = true) -> "DXVA2_USED"
            message.contains("d3d11va", ignoreCase = true) -> "d3d11va_msg"
            message.contains("dxva2", ignoreCase = true) -> "dxva2_msg"
            message.contains("hardware decoder", ignoreCase = true) -> "hw_decoder_msg"
            message.contains("pix_fmt", ignoreCase = true) -> "pix_fmt:${message.substringAfter("pix_fmt").trim().take(32)}"
            message.contains("avcodec-hw", ignoreCase = true) -> "avcodec-hw_msg"
            message.contains("direct3d11", ignoreCase = true) -> "d3d11_vout_msg"
            message.contains("direct3d9", ignoreCase = true) -> "d3d9_vout_msg"
            message.contains("Failed to open", ignoreCase = true) && message.contains("d3d", ignoreCase = true) ->
                "FAIL_OPEN_D3D"
            message.contains("no supported combination", ignoreCase = true) ||
                message.contains("failed to init", ignoreCase = true) &&
                (message.contains("d3d", ignoreCase = true) || message.contains("gl", ignoreCase = true))
            -> "HW_INIT_FAIL"
            message.contains("swscale", ignoreCase = true) -> "swscale_msg"
            message.contains("Using software", ignoreCase = true) ||
                message.contains("software decoder", ignoreCase = true)
            -> "SW_USED"
            message.contains("nvidia", ignoreCase = true) -> "nvidia_msg"
            else -> return
        }
        hits[key] = (hits[key] ?: 0) + 1
    }

    fun summary(): String = hits.entries.joinToString(" | ") { "${it.key}x${it.value}" }

    fun frameDimensions(): String? = reinitContexts.lastOrNull()

    fun verdict(): String {
        if (reinitContexts.any { it.contains("d3d11va") }) return "HARDWARE (D3D11VA)"
        if (reinitContexts.any { it.contains("dxva2") }) return "HARDWARE (DXVA2)"
        if (reinitContexts.isNotEmpty()) {
            return "DECODED pix_fmt=${reinitContexts.joinToString(", ")}"
        }
        if (hits.containsKey("D3D11VA_USED")) return "HARDWARE (D3D11VA)"
        if (hits.containsKey("DXVA2_USED")) return "HARDWARE (DXVA2)"
        if (hits.containsKey("SW_USED")) return "SOFTWARE"
        if (hits.containsKey("FAIL_OPEN_D3D") || hits.containsKey("HW_INIT_FAIL")) {
            return "HARDWARE_REQUESTED_BUT_FAILED"
        }
        return "UNDETERMINED"
    }
}

private fun logEntry(level: LogLevel, message: String) {
    logMarkers(level, message)
}

private const val VlcHwUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
