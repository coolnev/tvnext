package com.tvnext.app.core.player

import java.io.File
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State

/**
 * Alternative-backend PoC: plays a list of stream URLs through **libVLC** (vlcj)
 * and reports per-stream metrics, mirroring [PlaybackProbe] so the results are
 * directly comparable with the JavaFX backend.
 *
 * Goal: answer "does libVLC decode what JavaFX/WMF cannot?" — 4K H.264 HLS,
 * HEVC (H.265) without the Windows HEVC codec extension, CMAF HLS, etc.
 *
 * libVLC ships its own FFmpeg decoders, so HEVC/4K do NOT depend on the Windows
 * Media Foundation codec store. The probe is headless (no video output), so
 * hardware decoding is not exercised; software decode is sufficient to prove
 * codec support.
 *
 * Lives in desktopMain and is launched by the `probeRunVlc` Gradle task
 * (:core:player). Probe-only; not wired into DI or the app.
 */
fun main(args: Array<String>) {
    var durationMs = 12_000L
    val urls = mutableListOf<String>()
    args.forEach { arg ->
        when {
            arg.startsWith("--duration=") -> durationMs =
                arg.substringAfter("=").toLongOrNull() ?: durationMs
            else -> urls += arg
        }
    }
    if (urls.isEmpty()) {
        println("Usage: --duration=<ms> <url1> [<url2> ...]  (libVLC PoC probe)")
        return
    }

    val logFile = File("C:\\Temp\\kilo\\vlc-probe-results.txt")
    logFile.parentFile.mkdirs()
    val writer = logFile.printWriter()
    fun log(msg: String) {
        println(msg)
        writer.println(msg)
    }

    log("=== TVNext libVLC PoC probe ===")

    val factory = MediaPlayerFactory(
        "--no-video-title-show",
        "--avcodec-hw=none",
        "--network-caching=1500",
    )
    val mediaPlayer = factory.mediaPlayers().newMediaPlayer()

    urls.forEachIndexed { index, url ->
        log("")
        log("----- [${index + 1}] $url -----")
        runVlcStream(mediaPlayer, url, durationMs, ::log)
    }

    log("")
    log("=== SUMMARY ===")
    mediaPlayer.release()
    factory.release()
    log("=== VLC PROBE DONE ===")
    writer.flush()
    writer.close()
}

private fun runVlcStream(
    mediaPlayer: MediaPlayer,
    url: String,
    durationMs: Long,
    log: (String) -> Unit,
) {
    val started = System.currentTimeMillis()
    var playingAt = -1L
    var errorAt = -1L
    var lastError: String? = null
    var lastState = "n/a"

    val listener = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) {
            if (playingAt < 0) playingAt = System.currentTimeMillis() - started
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (errorAt < 0) errorAt = System.currentTimeMillis() - started
            lastError = "vlc error event"
        }
    }
    mediaPlayer.events().addMediaPlayerEventListener(listener)

    mediaPlayer.media().play(url)
    val end = started + durationMs
    var infoLogged = false
    while (System.currentTimeMillis() < end && errorAt < 0) {
        Thread.sleep(200)
        val state = mediaPlayer.status().state()
        if (state.name != lastState) {
            log("[+${System.currentTimeMillis() - started}ms] state: ${state.name}")
            lastState = state.name
        }
        if (!infoLogged && (state == State.PLAYING || state == State.BUFFERING)) {
            infoLogged = true
            try {
                val info = mediaPlayer.media().info()
                val videoTracks = info.videoTracks()
                val audioTracks = info.audioTracks()
                log("  videoTracks=${videoTracks.size} audioTracks=${audioTracks.size}")
                videoTracks.forEachIndexed { i, t ->
                    log(
                        "  video[$i] codec=${t.codecName()} origCodec=${t.originalCodecName()} " +
                            "resolution=${t.width()}x${t.height()} " +
                            "fps=${t.frameRate()}/${t.frameRateBase()} bitrate=${t.bitRate()} " +
                            "desc=${t.codecDescription()}",
                    )
                }
                audioTracks.forEachIndexed { i, t ->
                    log(
                        "  audio[$i] codec=${t.codecName()} bitrate=${t.bitRate()} " +
                            "desc=${t.codecDescription()}",
                    )
                }
            } catch (e: Throwable) {
                log("  track info unavailable: ${e.message}")
            }
        }
    }

    if (errorAt < 0 && playingAt < 0) {
        log("  no playback within window; final state=${mediaPlayer.status().state().name}")
    }

    log(
        "RESULT url=$url\n" +
            "  playingAt=$playingAt errorAt=$errorAt finalState=${mediaPlayer.status().state().name} error=$lastError",
    )
    mediaPlayer.controls().stop()
    mediaPlayer.events().removeMediaPlayerEventListener(listener)
}
