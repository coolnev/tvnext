package com.tvnext.app.core.player

import androidx.compose.ui.graphics.toPixelMap
import java.io.File

private const val POLL_MS = 250L

private val probeLogWriter: java.io.PrintWriter by lazy {
    val file = File("C:\\Temp\\kilo\\vlc-render-probe-results.txt")
    file.parentFile.mkdirs()
    java.io.PrintWriter(
        java.io.OutputStreamWriter(java.io.FileOutputStream(file, true), Charsets.UTF_8),
        true,
    )
}

/** Writes to stdout and to a UTF-8 log file (console may mangle Cyrillic). */
private fun log(msg: String) {
    println(msg)
    probeLogWriter.println(msg)
}

/**
 * libVLC render probe (Sprint 4): plays a list of stream URLs sequentially
 * through the production [DesktopPlaybackController] (libVLC backend) and
 * reports per-stream metrics: state timeline, first-frame latency, real frame
 * production (non-blank frames via [DesktopPlaybackController.frames]),
 * blank-frame ratio and frame size.
 *
 * This drives exactly the same controller + surface contract the app uses
 * ([DesktopPlaybackController.onSurfaceAttached], [DesktopPlaybackController.updateSurfaceSize],
 * [DesktopPlaybackController.frames]) — it is the "video output PoC inside the
 * application pipeline".
 *
 * Usage (Gradle task `probeRunVlcRender` on :core:player):
 *   --duration=12000 --surface=1280x720 <url1> [<url2> ...]
 */
fun main(args: Array<String>) {
    System.setProperty("tvnext.player.debug", "true")

    var durationMs = 15_000L
    var surfaceW = 1280
    var surfaceH = 720
    var switchIntervalMs = 0L
    var switchCycles = 12
    var remountTest = false
    var modeSwitchTest = false
    var mode = "auto"
    val urls = mutableListOf<String>()

    args.forEach { arg ->
        when {
            arg.startsWith("-D") -> {
                val kv = arg.removePrefix("-D")
                val eq = kv.indexOf('=')
                if (eq > 0) System.setProperty(kv.substring(0, eq), kv.substring(eq + 1))
            }
            arg.startsWith("--duration=") -> durationMs =
                arg.substringAfter("=").toLongOrNull() ?: durationMs
            arg.startsWith("--surface=") -> {
                val parts = arg.substringAfter("=").split("x")
                if (parts.size == 2) {
                    surfaceW = parts[0].toIntOrNull() ?: surfaceW
                    surfaceH = parts[1].toIntOrNull() ?: surfaceH
                }
            }
            arg.startsWith("--switch-ms=") -> switchIntervalMs =
                arg.substringAfter("=").toLongOrNull() ?: switchIntervalMs
            arg.startsWith("--switch-cycles=") -> switchCycles =
                arg.substringAfter("=").toIntOrNull() ?: switchCycles
            arg == "--remount-test" -> remountTest = true
            arg == "--mode-switch-test" -> modeSwitchTest = true
            arg.startsWith("--mode=") -> mode = arg.substringAfter("=")
            arg.startsWith("--hw=") -> System.setProperty("tvnext.player.hw", arg.substringAfter("="))
            else -> urls += arg
        }
    }

    if (urls.isEmpty()) {
        log(
            "Usage: --duration=<ms> --surface=<WxH> --mode=<auto|hardware|software> " +
                "[--switch-ms=<ms> --switch-cycles=<N>] [--remount-test] [--mode-switch-test] <url1> [<url2> ...]\n" +
                "All streams play through DesktopPlaybackController (libVLC).\n" +
                "With --switch-ms the probe cycles through the streams every N ms\n" +
                "(A->B->C->A...), verifying race protection on rapid channel changes.\n" +
                "With --remount-test the probe emulates a fullscreen round-trip\n" +
                "(onSurfaceDetached + onSurfaceAttached) and verifies playback recovers.\n" +
                "With --mode-switch-test the probe cycles AUTO->HARDWARE->SOFTWARE->AUTO\n" +
                "on the same stream, verifying backend recreation between modes.",
        )
        return
    }

    log("=== TVNext libVLC render probe ===")
    log("duration=${durationMs}ms surface=${surfaceW}x${surfaceH} mode=$mode streams=${urls.size}")

    val decodeMode = when (mode) {
        "hardware" -> VideoDecodeMode.HARDWARE
        "software" -> VideoDecodeMode.SOFTWARE
        else -> VideoDecodeMode.AUTO
    }

    val controller = DesktopPlaybackController()
    controller.updateDecodeMode(decodeMode)
    if (decodeMode != VideoDecodeMode.SOFTWARE) {
        // Hardware paths need a displayable HWND canvas — emulate the Compose
        // SwingPanel mount with a plain AWT frame (same controller contract).
        val frame = javax.swing.JFrame("VLC HW Probe Surface")
        frame.contentPane.add(controller.hardwareCanvas)
        frame.setSize(surfaceW, surfaceH)
        frame.defaultCloseOperation = javax.swing.JFrame.DISPOSE_ON_CLOSE
        javax.swing.SwingUtilities.invokeAndWait { frame.isVisible = true }
        var waited = 0
        while (waited < 100 && !controller.hardwareCanvas.isDisplayable) {
            Thread.sleep(50)
            waited++
        }
        log("hardwareCanvas displayable=${controller.hardwareCanvas.isDisplayable}")
        probeFrame = frame
    }
    controller.onSurfaceAttached()
    controller.updateSurfaceSize(surfaceW, surfaceH)
    Thread.sleep(500)

    if (remountTest) {
        urls.forEachIndexed { index, url ->
            runRemountTest(controller, index + 1, url, decodeMode)
        }
    } else if (modeSwitchTest) {
        urls.forEachIndexed { index, url ->
            runModeSwitchTest(controller, index + 1, url)
        }
    } else if (switchIntervalMs > 0L) {
        runSwitchTest(controller, urls, switchIntervalMs, switchCycles)
    } else {
        val results = urls.mapIndexed { index, url ->
            runStream(controller, index + 1, url, durationMs)
        }
        log("")
        log("=== SUMMARY ===")
        results.forEach { log(it.toLine()) }
    }

    controller.release()
    probeFrame?.let { javax.swing.SwingUtilities.invokeLater { it.dispose() } }
    log("=== VLC RENDER PROBE DONE ===")
}

private var probeFrame: javax.swing.JFrame? = null

/**
 * Rapid channel switching test: cycles A->B->C->... every [intervalMs] and
 * asserts that the active stream keeps producing frames and never gets stuck
 * in LOADING/BUFFERING, and that stale callbacks cannot break the active item.
 */
private fun runSwitchTest(
    controller: DesktopPlaybackController,
    urls: List<String>,
    intervalMs: Long,
    maxCycles: Int,
) {
    log("")
    log("=== SWITCH TEST: ${urls.size} streams, interval=${intervalMs}ms, cycles=$maxCycles ===")
    val started = System.currentTimeMillis()
    var cycle = 0
    var errors = 0
    var lastFrameAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - started < 60_000L) {
        val url = urls[cycle % urls.size]
        val itemId = "switch-${(cycle % urls.size)}"
        log("[+${System.currentTimeMillis() - started}ms] play #${cycle % urls.size} $url")
        controller.play(PlaybackItem(id = itemId, title = url.substringAfterLast('/').take(24), url = url))

        val deadline = System.currentTimeMillis() + intervalMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            val st = controller.state.value
            if (st.state == PlaybackState.PLAYING || st.state == PlaybackState.ENDED) {
                lastFrameAt = System.currentTimeMillis()
            }
            if (st.state == PlaybackState.ERROR) {
                errors++
                log(
                    "  [!] ERROR at +${System.currentTimeMillis() - started}ms " +
                        "item=${st.item?.id} error=${st.error}",
                )
            }
        }
        val final = controller.state.value
        val frame = controller.frames.value
        log(
            "  state=${final.state} item=${final.item?.id} frame=${frame?.width}x${frame?.height}" +
                " error=${final.error ?: "-"}",
        )
        cycle++
        if (cycle >= maxCycles) break
    }
    log("=== SWITCH TEST DONE: cycles=$cycle errors=$errors ===")
    controller.stop()
}

/**
 * Emulates a fullscreen round-trip / tab switch: the surface detaches and
 * re-attaches while a hardware stream is playing. In hardware mode the
 * controller must rebuild the player against the (re)mounted canvas; verify
 * that playback recovers to PLAYING with the same decoder and no error, and
 * that repeated round-trips stay stable.
 */
private fun runRemountTest(
    controller: DesktopPlaybackController,
    index: Int,
    url: String,
    mode: VideoDecodeMode,
) {
    log("")
    log("=== REMOUNT TEST [$index] $url mode=$mode ===")
    val started = System.currentTimeMillis()
    controller.play(PlaybackItem(id = "remount-$index", title = url.substringAfterLast('/').take(24), url = url))
    // Wait until PLAYING (up to 20s).
    var playing = false
    var t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < 20_000L) {
        Thread.sleep(200)
        val st = controller.state.value
        if (st.state == PlaybackState.PLAYING) {
            playing = true
            break
        }
        if (st.state == PlaybackState.ERROR) {
            log("  [!] never PLAYING, ERROR: ${st.error}")
            return
        }
    }
    if (!playing) {
        log("  [!] never PLAYING within 20s")
        return
    }
    val before = controller.state.value
    log(
        "  [+${System.currentTimeMillis() - started}ms] PLAYING item=${before.item?.id} " +
            "decoder=${before.diagnostics?.decoder} actual=${before.diagnostics?.actualMode}",
    )

    repeat(4) { round ->
        Thread.sleep(1500)
        log("  [+${System.currentTimeMillis() - started}ms] round $round: detach + attach")
        controller.onSurfaceDetached()
        Thread.sleep(400)
        controller.onSurfaceAttached()
        // Watch for recovery within 12s.
        var recovered = false
        var errorMsg: String? = null
        t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 12_000L) {
            Thread.sleep(200)
            val st = controller.state.value
            if (st.state == PlaybackState.ERROR) {
                errorMsg = st.error
                break
            }
            if (st.state == PlaybackState.PLAYING) {
                recovered = true
                break
            }
        }
        val st = controller.state.value
        log(
            "  [+${System.currentTimeMillis() - started}ms] round $round: recovered=$recovered " +
                "state=${st.state} decoder=${st.diagnostics?.decoder} actual=${st.diagnostics?.actualMode}" +
                (if (errorMsg != null) " error=$errorMsg" else ""),
        )
        if (!recovered && errorMsg == null) {
            log("  [!] round $round: stuck in ${st.state}")
        }
    }
    controller.stop()
    log("=== REMOUNT TEST DONE [$index] ===")
}

/**
 * Cycles the decode mode on the SAME controller while playing the same stream:
 * AUTO → HARDWARE → SOFTWARE → AUTO. Every switch must recreate the backend
 * (factory + player) and resume playback; the previous backend must be fully
 * released (no second audio/video pipeline, no stale state).
 */
private fun runModeSwitchTest(
    controller: DesktopPlaybackController,
    index: Int,
    url: String,
) {
    log("")
    log("=== MODE SWITCH TEST [$index] $url ===")
    val modes = listOf(
        VideoDecodeMode.AUTO,
        VideoDecodeMode.HARDWARE,
        VideoDecodeMode.SOFTWARE,
        VideoDecodeMode.AUTO,
    )
    modes.forEachIndexed { i, mode ->
        controller.updateDecodeMode(mode)
        controller.play(
            PlaybackItem(id = "mode-$index-$i", title = "${mode.name}", url = url),
        )
        // Wait for a settled state (PLAYING or ERROR) within 20s.
        var settled = "n/a"
        var errorMsg: String? = null
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 20_000L) {
            Thread.sleep(200)
            val st = controller.state.value
            if (st.state == PlaybackState.PLAYING || st.state == PlaybackState.ERROR) {
                settled = st.state.name
                errorMsg = st.error
                break
            }
        }
        val st = controller.state.value
        val diag = st.diagnostics
        log(
            "  mode=$mode → state=$settled decoder=${diag?.decoder} actual=${diag?.actualMode}" +
                " fallback=${diag?.fallback} codec=${diag?.codec} res=${diag?.resolution}" +
                (if (errorMsg != null) " error=$errorMsg" else ""),
        )
        controller.stop()
        Thread.sleep(800)
    }
    log("=== MODE SWITCH TEST DONE [$index] ===")
}

private fun runStream(
    controller: DesktopPlaybackController,
    index: Int,
    url: String,
    durationMs: Long,
): VlcRenderProbeResult {
    log("")
    log("----- [$index] $url -----")
    val label = url.substringAfterLast('/').take(40)

    val stateTimeline = StringBuilder()
    var lastState = controller.state.value.state
    var stateChangeCount = 0
    var firstPlayingMs = -1L
    var firstErrorMs = -1L
    var lastError: String? = null
    var diagnosticsLine = "decoder=n/a"
    val startedMs = System.currentTimeMillis()

    var framesSeen = 0
    var blankFrames = 0
    var nonBlankFrames = 0
    var frameWidth = 0
    var frameHeight = 0
    var lastFrameMs = -1L
    var maxFrameGapMs = 0L
    var firstFrameMs = -1L
    var lastSampledFrame: androidx.compose.ui.graphics.ImageBitmap? = null

    val cpuBeforeNanos = processCpuNanos()
    controller.play(PlaybackItem(id = "probe-$index", title = label, url = url))

    val endMs = startedMs + durationMs
    while (System.currentTimeMillis() < endMs) {
        Thread.sleep(POLL_MS)
        val st = controller.state.value
        if (st.state != lastState) {
            val now = System.currentTimeMillis()
            if (st.state == PlaybackState.PLAYING && firstPlayingMs < 0) {
                firstPlayingMs = now - startedMs
                val d = st.diagnostics
                if (d != null) {
                    diagnosticsLine = "decoder=${d.decoder} actual=${d.actualMode} " +
                        "codec=${d.codec ?: "n/a"} res=${d.resolution} fps=${d.fps ?: "n/a"}"
                }
            }
            if (st.state == PlaybackState.ERROR && firstErrorMs < 0) {
                firstErrorMs = now - startedMs
            }
            stateChangeCount++
            stateTimeline
                .append("[+${now - startedMs}ms] ")
                .append(lastState.name)
                .append(" -> ")
                .append(st.state.name)
                .append('\n')
            lastState = st.state
        }
        if (st.error != null) lastError = st.error

        val frame = controller.frames.value
        if (frame != null) {
            val now = System.currentTimeMillis()
            if (firstFrameMs < 0) firstFrameMs = now - startedMs
            if (lastFrameMs >= 0) {
                val gap = now - lastFrameMs
                if (gap > maxFrameGapMs) maxFrameGapMs = gap
            }
            lastFrameMs = now
            framesSeen++
            frameWidth = frame.width
            frameHeight = frame.height
            lastSampledFrame = frame
            val blank = isBlankComposeFrame(frame)
            if (blank) blankFrames++ else nonBlankFrames++
        }
    }

    val finalState = controller.state.value
    // Diagnostics settle only after the decoder is actually running; take the
    // last observed snapshot instead of the first.
    finalState.diagnostics?.let { d ->
        diagnosticsLine = "decoder=${d.decoder} actual=${d.actualMode} " +
            "codec=${d.codec ?: "n/a"} res=${d.resolution} fps=${d.fps ?: "n/a"}"
    }
    val lastFrame = lastSampledFrame
    if (lastFrame != null) {
        val px = lastFrame.toPixelMap()
        val cx = lastFrame.width / 2
        val cy = lastFrame.height / 2
        val c = px[cx, cy]
        val (tl, tr, bl, br) = arrayOf(
            px[0, 0], px[lastFrame.width - 1, 0], px[0, lastFrame.height - 1], px[lastFrame.width - 1, lastFrame.height - 1],
        )
        log(
            "  COLORS center=#%08X tl=#%08X tr=#%08X bl=#%08X br=#%08X".format(
                c.toArgb(), tl.toArgb(), tr.toArgb(), bl.toArgb(), br.toArgb(),
            ),
        )
    }
    controller.stop()

    val cpuAfterNanos = processCpuNanos()
    val wallNanos = (System.currentTimeMillis() - startedMs) * 1_000_000L
    // CPU usage as % of one core: (cpuNanos / wallNanos) * 100
    val cpuPct = if (wallNanos > 0) (cpuAfterNanos - cpuBeforeNanos).toDouble() / wallNanos * 100.0 else 0.0

    val result = VlcRenderProbeResult(
        url = url,
        finalState = finalState.state,
        error = lastError,
        firstPlayingMs = firstPlayingMs,
        firstErrorMs = firstErrorMs,
        stateChanges = stateChangeCount,
        framesSeen = framesSeen,
        blankFrames = blankFrames,
        nonBlankFrames = nonBlankFrames,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        maxFrameGapMs = maxFrameGapMs,
        firstFrameMs = firstFrameMs,
        cpuPct = cpuPct,
        timeline = stateTimeline.toString(),
    )

    log(
        "RESULT url=$url\n" +
            "  finalState=${result.finalState}\n" +
            "  $diagnosticsLine\n" +
            "  firstPlayingMs=${result.firstPlayingMs}\n" +
            "  firstFrameMs=${result.firstFrameMs}\n" +
            "  firstErrorMs=${result.firstErrorMs}\n" +
            "  stateChanges=${result.stateChanges}\n" +
            "  framesSeen=${result.framesSeen} blank=${result.blankFrames} nonBlank=${result.nonBlankFrames}\n" +
            "  frameSize=${result.frameWidth}x${result.frameHeight} maxGapMs=${result.maxFrameGapMs}\n" +
            "  cpuPct=${"%.1f".format(result.cpuPct)}% of one core\n" +
            "  error=${result.error}\n" +
            "  timeline:\n${result.timeline}",
    )
    return result
}

/**
 * All five sample points (center + corners) being equal means the frame is
 * almost certainly empty, not decoded video.
 */
private fun isBlankComposeFrame(frame: androidx.compose.ui.graphics.ImageBitmap): Boolean {
    if (frame.width <= 0 || frame.height <= 0) return true
    val px = frame.toPixelMap()
    val w = frame.width
    val h = frame.height
    val samples = intArrayOf(
        px[frame.width / 2, frame.height / 2].toArgb(),
        px[0, 0].toArgb(),
        px[w - 1, 0].toArgb(),
        px[0, h - 1].toArgb(),
        px[w - 1, h - 1].toArgb(),
    )
    return samples.all { it == samples[0] }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    (alpha * 255).toInt() shl 24 or
        (red * 255).toInt() shl 16 or
        (green * 255).toInt() shl 8 or
        (blue * 255).toInt()

/** CPU time consumed by this JVM process so far, in nanoseconds. */
private fun processCpuNanos(): Long {
    val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    return if (bean is com.sun.management.OperatingSystemMXBean) {
        bean.processCpuTime
    } else {
        0L
    }
}

data class VlcRenderProbeResult(
    val url: String,
    val finalState: PlaybackState,
    val error: String?,
    val firstPlayingMs: Long,
    val firstErrorMs: Long,
    val stateChanges: Int,
    val framesSeen: Int,
    val blankFrames: Int,
    val nonBlankFrames: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val maxFrameGapMs: Long,
    val firstFrameMs: Long,
    val cpuPct: Double = 0.0,
    val timeline: String,
) {
    fun toLine(): String {
        val name = url.substringAfterLast('/').take(36)
        return "$name | state=${finalState.name} | firstPlaying=${firstPlayingMs}ms | " +
            "firstFrame=${firstFrameMs}ms | frames=$framesSeen(nonBlank=$nonBlankFrames/blank=$blankFrames) | " +
            "frame=${frameWidth}x${frameHeight} | cpu=${"%.0f".format(cpuPct)}% | " +
            "firstError=${firstErrorMs}ms | error=${error?.take(60) ?: "-"}"
    }
}

