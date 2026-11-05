package com.tvnext.app.core.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.tvnext.app.core.common.util.currentTimeMillis
import java.awt.Canvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Desktop playback backend built on **libmpv** (libmpv-2.dll), rendering video
 * via the mpv render API into Compose [ImageBitmap] frames (the SOFTWARE
 * renderer of libmpv — `MPV_RENDER_API_TYPE_SW`).
 *
 * Why this shape (and NOT `--wid` / HWND embedding):
 *  - the mpv manual itself warns that HWND embedding "can cause various
 *    issues, especially with GUI toolkits" — and in practice a native D3D11
 *    swapchain in an AWT canvas froze the whole window when the layout/window
 *    size changed (Media Library / F11): the UI thread stayed alive but the
 *    native render blocked the window message loop;
 *  - rendering into Compose frames uses exactly the SAME stable path the
 *    libVLC SOFTWARE mode already uses (BGRA bitmap → Compose Image), so
 *    overlays, F11, immersive mode and diagnostics all work unchanged, and
 *    window resizes simply change the frame size;
 *  - DECODING stays on the GPU: `hwdec=auto` (d3d11va/dxva2/nvdec) is kept,
 *    only the final colour conversion/copy happens in software (dxva2-copy
 *    style) — weak CPUs are not forced to decode video.
 *  - libmpv is **LGPL** (commercial-friendly), unlike vlcj (GPLv3).
 */
class MpvPlaybackController(
    private val clock: () -> Long = { currentTimeMillis() },
) : PlaybackController, DesktopVideoSurface, PlaybackSettingsSink {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _decodeMode = MutableStateFlow(VideoDecodeMode.AUTO)
    val decodeMode: StateFlow<VideoDecodeMode> = _decodeMode.asStateFlow()

    // ---- DesktopVideoSurface ---------------------------------------------------
    // Video is rendered into Compose frames (NOT a native HWND), so the surface
    // reports the "software" path: PlayerSurface draws [frames], no SwingPanel.
    private val _hardwareSurfaceActive = MutableStateFlow(false)
    override val hardwareSurfaceActive: StateFlow<Boolean> = _hardwareSurfaceActive.asStateFlow()

    private val _frames = MutableStateFlow<ImageBitmap?>(null)
    override val frames: StateFlow<ImageBitmap?> = _frames.asStateFlow()

    private val _hardwareCanvasRevision = MutableStateFlow(0L)
    override val hardwareCanvasRevision: StateFlow<Long> = _hardwareCanvasRevision.asStateFlow()

    private val stubCanvas: Canvas = newVideoCanvas()

    override val videoCanvas: Canvas
        get() = stubCanvas

    // ---- mpv state --------------------------------------------------------------
    @Volatile
    private var mpv: Pointer? = null

    @Volatile
    private var renderContext: Pointer? = null

    @Volatile
    private var currentItem: PlaybackItem? = null

    @Volatile
    private var generation = 0

    /** Active HLS VOD prefetch proxy (only for VOD playlists). */
    @Volatile
    private var prefetchServer: HlsPrefetchServer? = null

    /** Local URL handed to mpv; reused by the END_FILE auto-restart. */
    @Volatile
    private var prefetchLocalUrl: String? = null

    @Volatile
    private var resumeAfterMs = 0L

    @Volatile
    private var isSeeking = false

    @Volatile
    private var lastPositionMs = -1L

    @Volatile
    private var lastPositionMoveNanos = 0L

    @Volatile
    private var surfaceWidth = 1280

    @Volatile
    private var surfaceHeight = 720

    private var eventThread: Thread? = null
    private var renderThread: Thread? = null

    private val renderMonitor = Object()
    private var renderPending = false
    private var renderAlive = false

    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcherJob: Job? = null

    private val debugEnabled: Boolean =
        System.getProperty("tvnext.player.debug")?.toBoolean() == true

    private val autoRestartCounts: MutableMap<String, Int> = java.util.Collections.synchronizedMap(HashMap())

    private val updateCallback = object : Mpv.MpvRenderUpdateCallback {
        override fun invoke(cbCtx: Pointer?) {
            synchronized(renderMonitor) {
                renderPending = true
                renderMonitor.notifyAll()
            }
        }
    }

    // ---- PlaybackController API -------------------------------------------------

    override fun play(item: PlaybackItem) {
        val gen = generation + 1
        generation = gen
        currentItem = item
        isSeeking = false
        lastPositionMs = -1L
        _state.update {
            it.copy(
                state = PlaybackState.LOADING,
                item = item,
                error = null,
                hardwareDecode = false,
                diagnostics = null,
            )
        }
        watcherScope.launch {
            if (gen != generation) return@launch
            loadFile(item, gen)
        }
        startWatcher(gen)
    }

    override fun pause() {
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_set_property_flag(ctx, "pause", true) }
        }
        _state.update { it.copy(state = PlaybackState.PAUSED) }
    }

    override fun resume() {
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_set_property_flag(ctx, "pause", false) }
        }
        _state.update { it.copy(state = PlaybackState.PLAYING) }
    }

    override fun stop() {
        generation++
        currentItem = null
        watcherJob?.cancel()
        prefetchServer?.close()
        prefetchServer = null
        prefetchLocalUrl = null
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_command(ctx, arrayOf("stop")) }
        }
        _frames.value = null
        _state.update { PlayerState(state = PlaybackState.IDLE, positionMs = 0L, hardwareDecode = false) }
    }

    override fun seekTo(positionMs: Long) {
        val ctx = mpv ?: return
        val api = Mpv.load()
        isSeeking = true
        lastPositionMs = -1L
        lastPositionMoveNanos = 0L
        if (debugEnabled) {
            println("[TVNext] mpv seekTo($positionMs) before=${_state.value.positionMs}")
        }
        runCatching {
            api.mpv_set_property_string(ctx, "time-pos", (positionMs / 1000.0).toString())
        }
    }

    override fun seekBy(deltaMs: Long) {
        val actual = positionFromMpv() ?: _state.value.positionMs
        seekTo((actual + deltaMs).coerceAtLeast(0L))
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        mpv?.let { ctx ->
            runCatching {
                Mpv.load().mpv_set_property_string(ctx, "volume", (clamped * 100).toInt().coerceIn(0, 100).toString())
            }
        }
        _state.update { it.copy(volume = clamped) }
    }

    override fun setMuted(muted: Boolean) {
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_set_property_string(ctx, "mute", if (muted) "yes" else "no") }
        }
        _state.update { it.copy(isMuted = muted) }
    }

    override fun release() {
        generation++
        currentItem = null
        watcherJob?.cancel()
        prefetchServer?.close()
        prefetchServer = null
        prefetchLocalUrl = null
        watcherScope.cancel()
        stopRenderThread()
        stopEventThread()
        renderContext?.let { rc -> runCatching { Mpv.load().mpv_render_context_free(rc) } }
        renderContext = null
        mpv?.let { ctx -> runCatching { Mpv.load().mpv_terminate_destroy(ctx) } }
        mpv = null
    }

    // ---- loading -----------------------------------------------------------------

    private fun loadFile(item: PlaybackItem, gen: Int) {
        if (gen != generation) return
        val api = try {
            Mpv.load()
        } catch (e: Throwable) {
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = "Не удалось загрузить libmpv. Разместите libmpv-2.dll рядом с приложением или укажите TVNEXT_MPV_DIR.",
                )
            }
            return
        }
        val ctx = mpv ?: run {
            val created = api.mpv_create()
            if (created == Pointer.NULL) {
                _state.update { it.copy(state = PlaybackState.ERROR, error = "Не удалось создать libmpv") }
                return
            }
            mpv = created
            // Render context FIRST (per the mpv render-API docs: create before
            // setting options/initializing), then options + initialize.
            createRenderContext(api, created)
            configure(api, created, item)
            created
        }
        applyHwdec(api, ctx, item)
        // VOD HLS: route the stream through a local prefetch proxy so segments
        // are downloaded with several parallel connections (the m.cdntv.online
        // CDN throttles a single connection to ~1 Mbit/s — far below the movie
        // bitrate — which made every VOD stutter every few seconds).
        val playUrl = resolvePlaybackUrl(item)
        if (debugEnabled) println("[TVNext] mpv loadfile ${item.kind} ${playUrl.take(80)}")
        val rc = api.mpv_command(ctx, arrayOf("loadfile", playUrl))
        if (rc < 0) {
            _state.update {
                it.copy(state = PlaybackState.ERROR, error = "Не удалось открыть поток (${mpvErrorMessage(api, rc)})")
            }
        }
        startEventThread(api, ctx)
    }

    /**
     * Returns the URL mpv should open. VOD playlists (.m3u8) are proxied via
     * [HlsPrefetchServer] (parallel segment downloads); anything else is played
     * directly. A previous stream's proxy is always closed here (also when the
     * next stream is LIVE) so a stale server never lingers. The proxy is
     * created lazily per stream and closed on [stop]/[release]. A proxy
     * failure falls back to the original URL so a broken prefetch never blocks
     * playback.
     */
    private fun resolvePlaybackUrl(item: PlaybackItem): String {
        prefetchServer?.close()
        prefetchServer = null
        prefetchLocalUrl = null
        val isVodHls = item.kind == PlaybackContentKind.VOD &&
            item.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        println("[TVNext] resolvePlaybackUrl kind=${item.kind} url=${item.url.take(100)} isVodHls=$isVodHls")
        if (!isVodHls) return item.url
        return try {
            val server = HlsPrefetchServer(remotePlaylistUrl = item.url)
            // start() is a suspend call that downloads + parses the playlist;
            // run it off the caller thread.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(Dispatchers.Default) { server.start() }
            }
            prefetchServer = server
            val local = server.localPlaylistUrl
            prefetchLocalUrl = local
            println("[TVNext] HLS prefetch proxy READY at $local")
            local
        } catch (e: Throwable) {
            if (debugEnabled) println("[TVNext] HLS prefetch proxy failed, falling back: ${e.message}")
            println("[TVNext] HLS prefetch proxy FAILED: ${e.message}, falling back to direct URL")
            prefetchServer?.close()
            prefetchServer = null
            prefetchLocalUrl = null
            item.url
        }
    }

    private fun configure(api: Mpv, ctx: Pointer, item: PlaybackItem) {
        val isVod = item.kind == PlaybackContentKind.VOD
        val opts = mutableListOf(
            "cache" to "yes",
            // Generous read-ahead so the demuxer keeps pulling while the video
            // plays. `demuxer-max-bytes` alone caps the CACHE, but without
            // `demuxer-readahead-secs` the demuxer stops early and re-fetches
            // on every stutter — for slow HLS VOD providers this causes the
            // «plays 3-5s → freezes» pattern.
            "demuxer-readahead-secs" to if (isVod) "45" else "30",
            "demuxer-max-bytes" to (128L * 1024 * 1024).toString(),
            "demuxer-max-back-bytes" to (16L * 1024 * 1024).toString(),
            // Network socket timeout: the default 10s aborts slow-but-alive
            // CDN connections mid-download. 30s tolerates the throttled
            // VOD edge without hanging forever on a dead host.
            "network-timeout" to "30",
            // Cache sizing: for VOD keep enough seconds buffered to smooth
            // out a throttled source; LIVE keeps a smaller window (lower
            // channel-switch latency, less end-of-buffer risk on live edges).
            "cache-secs" to if (isVod) "60" else "30",
            "cache-default" to (if (isVod) 128L else 64L * 1024 * 1024).toString(),
            "keep-open" to if (item.kind == PlaybackContentKind.LIVE) "yes" else "no",
            "terminal" to "no",
            "audio-client-name" to "TVNext",
            // Render API is used instead of a video window (see class doc):
            // frames are delivered to Compose via the SW renderer.
            "vo" to "libmpv",
            "hwdec" to if (_decodeMode.value == VideoDecodeMode.SOFTWARE) "no" else "auto",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
        )
        opts.forEach { (name, value) ->
            runCatching { api.mpv_set_option_string(ctx, name, value) }
        }
        runCatching { api.mpv_request_log_messages(ctx, if (debugEnabled) "info" else "error") }
        val initRc = api.mpv_initialize(ctx)
        if (debugEnabled) println("[TVNext] mpv_initialize rc=$initRc api=${api.mpv_client_api_version()}")
        if (initRc < 0) {
            _state.update {
                it.copy(state = PlaybackState.ERROR, error = "libmpv init failed (${mpvErrorMessage(api, initRc)})")
            }
        }
    }

    private fun createRenderContext(api: Mpv, ctx: Pointer) {
        // Build the mpv_render_param array MANUALLY in native memory (JNA
        // Structure arrays proved unreliable here). Layout per element:
        //   int32 type; int32 padding; void* data  → 16 bytes.
        val params = Memory(32L)
        params.clear()
        params.setInt(0, Mpv.MPV_RENDER_PARAM_API_TYPE)
        val apiType = Memory(4L)
        apiType.setString(0, Mpv.MPV_RENDER_API_TYPE_SW)
        params.setPointer(8, apiType)
        params.setInt(16, Mpv.MPV_RENDER_PARAM_INVALID)
        params.setPointer(24, null)

        val res = PointerByReference()
        val rc = runCatching { api.mpv_render_context_create(res, ctx, params) }.getOrDefault(-1)
        if (rc < 0 || res.value == Pointer.NULL) {
            if (debugEnabled) println("[TVNext] mpv render context create failed rc=$rc (${mpvErrorMessage(api, rc)})")
            return
        }
        renderContext = res.value
        runCatching { api.mpv_render_context_set_update_callback(res.value, updateCallback, null) }
        startRenderThread(api)
    }

    private fun applyHwdec(api: Mpv, ctx: Pointer, item: PlaybackItem) {
        val hwdec = if (_decodeMode.value == VideoDecodeMode.SOFTWARE) "no" else "auto"
        runCatching { api.mpv_set_property_string(ctx, "hwdec", hwdec) }
    }

    // ---- render thread -----------------------------------------------------------

    private fun startRenderThread(api: Mpv) {
        if (renderThread?.isAlive == true) return
        renderAlive = true
        renderThread = Thread {
            try {
                while (renderAlive) {
                    var shouldRender = false
                    synchronized(renderMonitor) {
                        if (!renderPending) {
                            renderMonitor.wait(16L)
                        }
                        shouldRender = renderPending
                        renderPending = false
                    }
                    if (shouldRender) {
                        renderFrame(api)
                    }
                }
            } catch (_: InterruptedException) {
                // stopping
            }
        }.apply { isDaemon = true; name = "mpv-render"; start() }
    }

    private fun stopRenderThread() {
        renderAlive = false
        synchronized(renderMonitor) { renderMonitor.notifyAll() }
        renderThread?.join(1_000L)
        renderThread = null
    }

    /**
     * Pauses frame production while the window round-trips (F11 / double-click
     * fullscreen). The render thread keeps allocating Skia bitmaps from the
     * mpv render context; if it is still writing while the OLD window is being
     * torn down (its OpenGL surface destroyed), Skiko can corrupt the heap
     * (Windows 0xC0000374). Joining the thread here closes that race — the
     * video simply freezes for the ~100 ms the new window needs to appear.
     */
    fun suspendRendering() {
        renderAlive = false
        synchronized(renderMonitor) { renderMonitor.notifyAll() }
        renderThread?.join(500L)
        renderThread = null
    }

    /** Resumes frame production after the window round-trip completed. */
    fun resumeRendering() {
        if (renderThread?.isAlive == true) return
        val api = runCatching { Mpv.load() }.getOrNull() ?: return
        if (renderContext == null) return
        renderAlive = true
        renderThread = Thread {
            try {
                while (renderAlive) {
                    var shouldRender = false
                    synchronized(renderMonitor) {
                        if (!renderPending) {
                            renderMonitor.wait(16L)
                        }
                        shouldRender = renderPending
                        renderPending = false
                    }
                    if (shouldRender) {
                        renderFrame(api)
                    }
                }
            } catch (_: InterruptedException) {
                // stopping
            }
        }.apply { isDaemon = true; name = "mpv-render"; start() }
    }

    private fun renderFrame(api: Mpv) {
        val rctx = renderContext ?: return
        val w = surfaceWidth.coerceIn(1, 8192)
        val h = surfaceHeight.coerceIn(1, 8192)
        val flags = runCatching { api.mpv_render_context_update(rctx) }.getOrDefault(0L)
        if (flags and Mpv.MPV_RENDER_UPDATE_FRAME == 0L) return

        val stride = w * 4
        val size = stride * h
        val swSize = Memory(8L)
        swSize.setInt(0, w)
        swSize.setInt(4, h)
        val format = Memory(5L)
        format.setString(0, "bgra")
        val strideMem = Memory(4L)
        strideMem.setInt(0, stride)
        val buffer = Memory(size.toLong())
        // Manual mpv_render_param array: 5 elements × 16 bytes.
        val params = Memory(80L)
        params.clear()
        params.setInt(0, Mpv.MPV_RENDER_PARAM_SW_SIZE)
        params.setPointer(8, swSize)
        params.setInt(16, Mpv.MPV_RENDER_PARAM_SW_FORMAT)
        params.setPointer(24, format)
        params.setInt(32, Mpv.MPV_RENDER_PARAM_SW_STRIDE)
        params.setPointer(40, strideMem)
        params.setInt(48, Mpv.MPV_RENDER_PARAM_SW_POINTER)
        params.setPointer(56, buffer)
        params.setInt(64, Mpv.MPV_RENDER_PARAM_INVALID)
        params.setPointer(72, null)

        val rc = runCatching { api.mpv_render_context_render(rctx, params) }.getOrDefault(-1)
        if (rc < 0) return

        val pixels = buffer.getByteArray(0, size)
        // bgra → BGRA_8888: force the alpha byte to 0xFF (opaque).
        var i = 3
        while (i < size) {
            pixels[i] = 0xFF.toByte()
            i += 4
        }
        val bitmap = Bitmap()
        val installed = bitmap.installPixels(
            ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL),
            pixels,
            stride,
        )
        if (installed) {
            _frames.value = bitmap.asComposeImageBitmap()
        }
    }

    // ---- event thread -------------------------------------------------------------

    private fun startEventThread(api: Mpv, ctx: Pointer) {
        if (eventThread?.isAlive == true) return
        eventThread = Thread {
            try {
                while (true) {
                    val eventPtr = api.mpv_wait_event(ctx, -1.0)
                    if (eventPtr == Pointer.NULL) continue
                    val event = Mpv.MpvEvent().bind(eventPtr)
                    when (event.event_id) {
                        Mpv.MPV_EVENT_SHUTDOWN -> break
                        Mpv.MPV_EVENT_START_FILE -> {
                            _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                        }

                        Mpv.MPV_EVENT_FILE_LOADED -> {
                            if (resumeAfterMs > 0L) {
                                val pos = resumeAfterMs
                                resumeAfterMs = 0L
                                isSeeking = true
                                api.mpv_set_property_string(ctx, "time-pos", (pos / 1000.0).toString())
                            }
                            _state.update { it.copy(state = PlaybackState.PLAYING, error = null) }
                        }

                        Mpv.MPV_EVENT_SEEK -> isSeeking = true
                        Mpv.MPV_EVENT_PLAYBACK_RESTART -> isSeeking = false
                        Mpv.MPV_EVENT_END_FILE -> {
                            val reason = runCatching { event.data?.getInt(0) }.getOrNull() ?: -1
                            val err = runCatching { event.data?.getInt(4) }.getOrNull() ?: 0
                            handleEndFile(reason, err)
                        }

                        else -> Unit
                    }
                }
            } catch (_: Throwable) {
                // thread exits with the mpv handle
            }
        }.apply { isDaemon = true; name = "mpv-events"; start() }
    }

    private fun stopEventThread() {
        mpv?.let { ctx -> runCatching { Mpv.load().mpv_wakeup(ctx) } }
        eventThread?.join(1_000L)
        eventThread = null
    }

    private fun handleEndFile(reason: Int, error: Int) {
        if (reason == Mpv.MPV_END_FILE_REASON_EOF || reason == Mpv.MPV_END_FILE_REASON_ERROR) {
            val item = currentItem ?: return
            val duration = _state.value.durationMs
            val position = _state.value.positionMs
            val nearEnd = duration > 0L && position >= duration - END_RESTART_MARGIN_MS
            if (item.kind != PlaybackContentKind.LIVE && !nearEnd && canAutoRestart(item.id)) {
                if (debugEnabled) {
                    println("[TVNext] mpv END_FILE reason=$reason err=$error — auto-restarting from ${_state.value.positionMs} ms")
                }
                val pos = _state.value.positionMs
                resumeAfterMs = pos
                currentItem = item
                mpv?.let { ctx ->
                    // Restart through the same local prefetch URL if one is
                    // active (the remote CDN URL must never be re-opened).
                    val url = prefetchLocalUrl ?: item.url
                    runCatching { Mpv.load().mpv_command(ctx, arrayOf("loadfile", url)) }
                }
                return
            }
            _state.update {
                it.copy(
                    state = PlaybackState.ENDED,
                    error = if (reason == Mpv.MPV_END_FILE_REASON_ERROR) {
                        "Ошибка потока (${if (error != 0) error else "сеть"})"
                    } else {
                        "Поток завершился"
                    },
                )
            }
        }
    }

    private fun canAutoRestart(itemId: String): Boolean = synchronized(autoRestartCounts) {
        val next = (autoRestartCounts[itemId] ?: 0) + 1
        if (next > MAX_AUTO_RESTARTS) {
            autoRestartCounts.remove(itemId)
            false
        } else {
            autoRestartCounts[itemId] = next
            true
        }
    }

    // ---- watchdog -----------------------------------------------------------------

    private fun startWatcher(gen: Int) {
        watcherJob?.cancel()
        watcherJob = watcherScope.launch {
            while (isActive) {
                delay(WATCH_INTERVAL_MS)
                if (gen != generation || mpv == null) continue
                pollState(gen)
            }
        }
    }

    private fun pollState(gen: Int) {
        val ctx = mpv ?: return
        val api = Mpv.load()

        val timePosSec = getDouble(api, ctx, "time-pos")
        val durationSec = getDouble(api, ctx, "duration")
        val paused = getFlag(api, ctx, "pause") == true
        val coreIdle = getFlag(api, ctx, "core-idle") == true
        val cachePercent = getInt(api, ctx, "cache-buffering-state")
        // `hwdec-current` is the property that reports the ACTIVE decoder
        // ("d3d11va-copy", "dxva2-copy", "nvdec-copy" …) — "hwdec-active" does
        // not exist in this mpv version and returned null, which misreported
        // the mode as SOFTWARE. A non-empty value ≠ "no" means the GPU is
        // decoding (hwdec-copy: decode on GPU, colour convert in software).
        val hwdecCurrent = getString(api, ctx, "hwdec-current")
        val videoCodec = getString(api, ctx, "video-codec")
        val videoBitrate = getString(api, ctx, "video-bitrate")?.toLongOrNull()
        val audioCodec = getString(api, ctx, "audio-codec")
        val audioChannels = getInt(api, ctx, "audio-params/channel-count")
        val audioSampleRate = getInt(api, ctx, "audio-params/samplerate")
        val width = getInt(api, ctx, "width")
        val height = getInt(api, ctx, "height")
        val fps = getDouble(api, ctx, "estimated-vf-fps")
        val eof = getFlag(api, ctx, "eof-reached") == true

        val posMs = (timePosSec ?: 0.0).coerceAtLeast(0.0).let { (it * 1000.0).toLong() }
        val durationMs = (durationSec ?: 0.0).coerceAtLeast(0.0).let { (it * 1000.0).toLong() }
        val buffering = cachePercent in 0..99

        if (posMs != lastPositionMs) {
            lastPositionMs = posMs
            lastPositionMoveNanos = System.nanoTime()
        }

        val state: PlaybackState = when {
            paused -> PlaybackState.PAUSED
            eof -> PlaybackState.ENDED
            buffering -> PlaybackState.BUFFERING
            coreIdle -> PlaybackState.LOADING
            else -> PlaybackState.PLAYING
        }

        val actualHardware = hwdecCurrent != null && hwdecCurrent.isNotBlank() && hwdecCurrent != "no"
        val requested = _decodeMode.value.name
        val diagnostics = PlayerDiagnostics(
            requestedMode = requested,
            actualMode = if (actualHardware) PlayerDiagnostics.MODE_HARDWARE else PlayerDiagnostics.MODE_SOFTWARE,
            fallback = !actualHardware && requested != PlayerDiagnostics.MODE_SOFTWARE,
            decoder = if (actualHardware) hwdecCurrent else "avcodec (software)",
            videoOutput = "libmpv render (SW)",
            codec = videoCodec?.takeIf { it.isNotBlank() },
            resolution = if (width > 0 && height > 0) "${width}x${height}" else "n/a",
            fps = fps?.takeIf { it > 0 }?.let { java.lang.String.format(java.util.Locale.US, "%.2f", it) },
            bitrate = videoBitrate?.takeIf { it > 0 }?.let {
                java.lang.String.format(java.util.Locale.US, "%.1f Mbps", it / 1_000_000.0)
            },
            audio = buildAudioLabel(audioChannels, audioCodec, audioSampleRate),
            transportState = when {
                buffering -> PlayerDiagnostics.STATE_BUFFERING
                paused -> PlayerDiagnostics.STATE_PAUSED
                state == PlaybackState.ENDED -> "Завершено"
                coreIdle -> PlayerDiagnostics.STATE_LOADING
                else -> PlayerDiagnostics.STATE_PLAYING
            },
        )

        _state.update {
            it.copy(
                state = state,
                positionMs = posMs,
                durationMs = durationMs,
                diagnostics = diagnostics,
                hardwareDecode = false,
            )
        }
    }

    // ---- property helpers ----------------------------------------------------------

    private fun getString(api: Mpv, ctx: Pointer, name: String): String? {
        val p = runCatching { api.mpv_get_property_string(ctx, name) }.getOrNull() ?: return null
        return try {
            p?.takeIf { it != Pointer.NULL }?.getString(0)?.takeIf { it.isNotBlank() }
        } finally {
            if (p != null && p != Pointer.NULL) runCatching { api.mpv_free(p) }
        }
    }

    private fun getDouble(api: Mpv, ctx: Pointer, name: String): Double? =
        getString(api, ctx, name)?.toDoubleOrNull()

    private fun getFlag(api: Mpv, ctx: Pointer, name: String): Boolean? =
        getString(api, ctx, name)?.let { it == "yes" || it == "true" }

    private fun getInt(api: Mpv, ctx: Pointer, name: String): Int =
        getString(api, ctx, name)?.toIntOrNull() ?: 0

    private fun positionFromMpv(): Long? {
        val ctx = mpv ?: return null
        return getDouble(Mpv.load(), ctx, "time-pos")?.let { (it * 1000.0).toLong() }
    }

    // ---- audio diagnostics ----------------------------------------------------------

    /**
     * Human-readable audio description of the stream: channel layout (Стерео,
     * 5.1, …) + codec + sample rate, e.g. «Стерео · AAC · 48 кГц».
     */
    private fun buildAudioLabel(channels: Int, codec: String?, sampleRate: Int): String? {
        val parts = buildList {
            when (channels) {
                1 -> add("Моно")
                2 -> add("Стерео")
                6 -> add("5.1")
                8 -> add("7.1")
                else -> if (channels > 0) add("$channels каналов")
            }
            codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            if (sampleRate > 0) add("${sampleRate / 1000} кГц")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // ---- surface -------------------------------------------------------------------

    override fun attachSurface(canvas: Canvas) = Unit
    override fun detachSurface(canvas: Canvas) = Unit
    override fun onSurfaceAttached() = Unit
    override fun onSurfaceDetached() = Unit

    override fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
    }

    // ---- decode mode (settings) -----------------------------------------------------

    override fun updateDecodeMode(mode: VideoDecodeMode) {
        _decodeMode.value = mode
    }

    override fun updateNetworkCachingMs(ms: Long) = Unit // cache is fixed (demuxer-max-bytes)

    private companion object {
        const val WATCH_INTERVAL_MS = 1_000L
        const val END_RESTART_MARGIN_MS = 30_000L
        const val MAX_AUTO_RESTARTS = 3
    }
}

