package com.tvnext.app.core.player

import kotlinx.coroutines.sync.withLock

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

/** libmpv playback with shared ANGLE GPU textures and a compatible copy-back path. */
class MpvPlaybackController(
    private val clock: () -> Long = { currentTimeMillis() },
    private val channelsRepository: com.tvnext.app.core.iptv.data.ChannelsRepository? = null,
    private val archiveRepository: com.tvnext.app.core.iptv.archive.ArchiveRepository? = null,
) : PlaybackController, DesktopVideoSurface, PlaybackSettingsSink, TimeShiftController {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _videoSignal=MutableStateFlow<VideoFrameSignal?>(null)
    val videoSignal:StateFlow<VideoFrameSignal?> = _videoSignal.asStateFlow()
    private val _audioOutput=MutableStateFlow(DesktopAudioOutput())
    val audioOutput:StateFlow<DesktopAudioOutput> = _audioOutput.asStateFlow()
    @Volatile private var audioOutputMode=AudioOutputMode.AUTO
    @Volatile private var aspectMode=VideoAspectMode.AUTO
    fun updateAudioOutputMode(mode:AudioOutputMode) {
        audioOutputMode=mode
        mpv?.let {applyOutputPreferences(Mpv.load(),it)}
    }
    fun updateAspect(mode:VideoAspectMode) {
        aspectMode=mode
        mpv?.let {applyOutputPreferences(Mpv.load(),it)}
    }
    private fun applyOutputPreferences(api:Mpv,ctx:Pointer) {
        api.mpv_set_property_string(ctx,"audio-channels",if(audioOutputMode==AudioOutputMode.PCM_STEREO)"stereo" else "auto-safe")
        api.mpv_set_property_string(ctx,"audio-spdif",if(audioOutputMode==AudioOutputMode.PASSTHROUGH)"ac3,eac3,dts,dts-hd,truehd" else "")
        api.mpv_set_property_string(ctx,"video-aspect-override",aspectMode.fixedRatio?.toString() ?: "-1")
        api.mpv_set_property_string(ctx,"keepaspect",if(aspectMode==VideoAspectMode.STRETCH)"no" else "yes")
        api.mpv_set_property_string(ctx,"panscan",if(aspectMode==VideoAspectMode.ZOOM)"1" else "0")
    }

    private val _decodeMode = MutableStateFlow(VideoDecodeMode.AUTO)
    val decodeMode: StateFlow<VideoDecodeMode> = _decodeMode.asStateFlow()

    private val _windowsVideoMode=MutableStateFlow(WindowsVideoMode.AUTO)
    val windowsVideoMode:StateFlow<WindowsVideoMode> = _windowsVideoMode.asStateFlow()
    private var explicitVideoMode=false
    private val _gpuOutput=MutableStateFlow(false)
    val gpuOutput:StateFlow<Boolean> = _gpuOutput.asStateFlow()
    private val _gpuRevision=MutableStateFlow(0L)
    val gpuRevision:StateFlow<Long> = _gpuRevision.asStateFlow()
    private val _renderStatus=MutableStateFlow("Совместимый вывод")
    val renderStatus:StateFlow<String> = _renderStatus.asStateFlow()
    @Volatile private var gpuWindow:java.awt.Window?=null
    @Volatile private var gpuRenderer:MpvGpuRenderer?=null
    @Volatile private var gpuFailed=false
    @Volatile private var renderSuspended=false
    @Volatile private var pipelineChanging=false
    @Volatile private var restoreVideoAfterGpuInit=false
    private var gpuWaitStarted=0L
    private var lastGpuDraw=0L
    private var gpuTimer:javax.swing.Timer?=null
    fun updateWindowsVideoMode(mode:WindowsVideoMode) {
        explicitVideoMode=true;_windowsVideoMode.value=mode;gpuFailed=false
        _decodeMode.value=when(mode){WindowsVideoMode.SOFTWARE->VideoDecodeMode.SOFTWARE;WindowsVideoMode.AUTO->VideoDecodeMode.AUTO;else->VideoDecodeMode.HARDWARE}
        mpv?.let {ctx->watcherScope.launch {loadMutex.withLock {prepareOutput(Mpv.load(),ctx);applyHwdec(Mpv.load(),ctx,currentItem ?: return@withLock)}}}
    }
    fun attachGpuWindow(window:java.awt.Window) {
        gpuWindow=window;gpuWaitStarted=0L
        gpuTimer?.stop()
        gpuTimer=javax.swing.Timer(40) {
            if(_gpuOutput.value && !renderSuspended && !pipelineChanging && System.nanoTime()-lastGpuDraw>250_000_000L) {
                try {
                    val initialized=gpuRenderer?.initialized==true
                    if(gpuRenderer?.service(window)==true){
                        if(!initialized && restoreVideoAfterGpuInit)restoreVideoTrackAsync()
                        gpuWaitStarted=0L;_renderStatus.value=gpuRenderer?.description.orEmpty()
                    }
                    else if(window.isShowing)checkGpuWait()
                }catch(error:Exception){fallbackGpu(error.message ?: "Ошибка GPU")}
            }
        }.apply {start()}
        mpv?.let {ctx->watcherScope.launch {loadMutex.withLock {prepareOutput(Mpv.load(),ctx)}}}
    }
    fun detachGpuWindow(window:java.awt.Window) {if(gpuWindow===window){gpuTimer?.stop();gpuTimer=null;gpuWindow=null}}
    fun requestGpuDraw(){_gpuRevision.update {it+1}}
    fun drawGpu(canvas:org.jetbrains.skia.Canvas,width:Int,height:Int) {
        if(renderSuspended || pipelineChanging)return
        val host=gpuWindow ?: return
        lastGpuDraw=System.nanoTime()
        val renderer=gpuRenderer ?: return
        try {
            val wasInitialized=renderer.initialized
            if(renderer.draw(canvas,host,width,height,generation.toLong(),sourceReady)) {
                if(!wasInitialized && restoreVideoAfterGpuInit)restoreVideoTrackAsync()
                gpuWaitStarted=0L
                _renderStatus.value=renderer.description
            } else {
                checkGpuWait()
            }
        }catch(error:Exception){fallbackGpu(error.message ?: "Ошибка GPU")}
    }
    private fun restoreVideoTrack(api:Mpv,ctx:Pointer) {
        val track=getString(api,ctx,"vid")?.takeUnless {it=="no"} ?: "auto"
        api.mpv_set_property_string(ctx,"vid","no")
        api.mpv_set_property_string(ctx,"vid",track)
    }
    private fun restoreVideoTrackAsync() {
        restoreVideoAfterGpuInit=false
        mpv?.let {ctx->watcherScope.launch {loadMutex.withLock {restoreVideoTrack(Mpv.load(),ctx)}}}
    }
    private fun checkGpuWait() {
        if(gpuWaitStarted==0L)gpuWaitStarted=System.nanoTime()
        if(System.nanoTime()-gpuWaitStarted>2_000_000_000L)fallbackGpu("ANGLE / GPU недоступен")
    }
    private fun fallbackGpu(reason:String) {
        if(gpuFailed)return
        gpuFailed=true;_renderStatus.value="Совместимый вывод: $reason"
        mpv?.let {ctx->watcherScope.launch {loadMutex.withLock {prepareOutput(Mpv.load(),ctx);applyHwdec(Mpv.load(),ctx,currentItem ?: return@withLock)}}}
    }
    private fun closeGpu() {
        val renderer=gpuRenderer ?: return
        val action={runCatching {renderer.close()};gpuRenderer=null}
        if(javax.swing.SwingUtilities.isEventDispatchThread())action() else javax.swing.SwingUtilities.invokeAndWait {action()}
    }
    private fun prepareOutput(api:Mpv,ctx:Pointer) {
        val wantGpu=_windowsVideoMode.value.gpu && !gpuFailed && WindowsGpuRuntime.prepared && !java.awt.GraphicsEnvironment.isHeadless() && com.sun.jna.Platform.isWindows()
        if(wantGpu && gpuRenderer!=null)return
        if(!wantGpu && renderContext!=null) {
            if(!gpuFailed)_renderStatus.value=if(_windowsVideoMode.value==WindowsVideoMode.SOFTWARE)"Программный вывод" else "Совместимый вывод"
            return
        }
        val replacing=renderContext!=null || gpuRenderer!=null
        pipelineChanging=true
        try {
            stopRenderThread()
            closeGpu()
            renderContext?.let {api.mpv_render_context_set_update_callback(it,null,null);api.mpv_render_context_free(it)}
            renderContext=null;frameStorage?.close();frameStorage=null;_frames.value=null
            _gpuOutput.value=wantGpu
            if(wantGpu) {
                restoreVideoAfterGpuInit=replacing && currentItem!=null
                gpuRenderer=MpvGpuRenderer(api,ctx,::requestGpuDraw);_renderStatus.value="GPU • подготовка"
            } else {
                createRenderContext(api,ctx)
                if(replacing && currentItem!=null)restoreVideoTrack(api,ctx)
                if(!gpuFailed)_renderStatus.value=if(_windowsVideoMode.value==WindowsVideoMode.SOFTWARE)"Программный вывод" else "Совместимый вывод"
            }
        }finally {pipelineChanging=false;requestGpuDraw()}
    }

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

    @Volatile private var eventAlive=false
    @Volatile private var sourceReady=false
    @Volatile private var nativeLoadGeneration=-1
    @Volatile private var eventSourceGeneration=-1
    @Volatile private var eventPlaylistEntry=Long.MIN_VALUE
    private var eventThread: Thread? = null
    private var renderThread: Thread? = null

    private val renderMonitor = Object()
    private var renderPending = false
    @Volatile private var renderAlive = false

    private val loadMutex=kotlinx.coroutines.sync.Mutex()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcherJob: Job? = null
    private val timeShift=if(channelsRepository!=null && archiveRepository!=null)
        BroadcastTimeShiftSession(channelsRepository,archiveRepository,watcherScope,{_state.value},::play,::seekLocal,::pause) else null
    override val timeShiftState:StateFlow<TimeShiftState> = timeShift?.state ?: MutableStateFlow(TimeShiftState())
    override fun seekToTime(epochMs:Long){timeShift?.seekTo(epochMs)}
    override fun returnToLive(){seekToTime(currentTimeMillis())}
    @Volatile private var networkCachingMs=8_000L

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
        AndroidPlaybackSession.record(_state.value)
        sourceReady=false;eventSourceGeneration=-1
        if(timeShift?.opening!=true)timeShift?.cancel()
        _videoSignal.value=null
        _audioOutput.value=DesktopAudioOutput()
        _frames.value=null
        val gen = generation + 1
        generation = gen
        currentItem = item
        resumeAfterMs=0L
        isSeeking = false
        lastPositionMs = -1L
        _state.update {
            it.copy(
                state = PlaybackState.LOADING,
                item = item,
                positionMs=0L,durationMs=0L,
                error = null,
                hardwareDecode = false,
                diagnostics = null,
            )
        }
        watcherScope.launch {
            loadMutex.withLock {
                if (gen == generation) loadFile(item, gen)
            }
        }
        startWatcher(gen)
        timeShift?.itemOpened(item)
    }

    override fun pause() {
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_set_property_string(ctx, "pause", "yes") }
        }
        _state.update { it.copy(state = PlaybackState.PAUSED) }
    }

    override fun resume() {
        mpv?.let { ctx ->
            runCatching { Mpv.load().mpv_set_property_string(ctx, "pause", "no") }
        }
        _state.update { it.copy(state = PlaybackState.PLAYING) }
    }

    override fun stop() {
        timeShift?.cancel()
        sourceReady=false;eventSourceGeneration=-1
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
        _state.update {PlayerState(state=PlaybackState.IDLE,volume=it.volume,isMuted=it.isMuted)}
    }

    override fun seekTo(positionMs:Long) {
        val item=_state.value.item
        val anchor=item?.archiveEpgAnchorMs
        if(timeShift!=null && item?.kind==PlaybackContentKind.ARCHIVE && anchor!=null)seekToTime(anchor+positionMs)
        else seekLocal(positionMs)
    }
    private fun seekLocal(positionMs:Long):Boolean {
        val ctx=mpv ?: return false
        isSeeking=true;lastPositionMs=-1L;lastPositionMoveNanos=0L
        return runCatching {Mpv.load().mpv_set_property_string(ctx,"time-pos",(positionMs.coerceAtLeast(0)/1000.0).toString())>=0}.getOrDefault(false)
    }
    override fun seekBy(deltaMs:Long) {
        val actual=positionFromMpv() ?: _state.value.positionMs
        if(timeShift!=null && _state.value.item?.kind!=PlaybackContentKind.VOD) {
            _state.update {it.copy(positionMs=actual)};timeShift.seekBy(deltaMs)
        }else seekLocal((actual+deltaMs).coerceAtLeast(0))
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
        gpuTimer?.stop();gpuTimer=null
        timeShift?.cancel()
        generation++
        currentItem = null
        watcherJob?.cancel()
        prefetchServer?.close()
        prefetchServer = null
        prefetchLocalUrl = null
        watcherScope.cancel()
        closeGpu()
        stopRenderThread()
        stopEventThread()
        renderContext?.let { rc -> runCatching { Mpv.load().mpv_render_context_free(rc) } }
        renderContext = null
        frameStorage?.close();frameStorage=null
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
            // libmpv requires an initialized client before a render context exists.
            try {
                configure(api, created, item)
                prepareOutput(api, created)
            } catch(error:Exception) {
                api.mpv_terminate_destroy(created);mpv=null
                _state.update {it.copy(state=PlaybackState.ERROR,error=error.message ?: "libmpv init failed")}
                return
            }
            created
        }
        prepareOutput(api,ctx)
        applyHwdec(api, ctx, item)
        applyOutputPreferences(api,ctx)
        api.mpv_set_property_string(ctx,"keep-open",if(item.kind==PlaybackContentKind.LIVE)"yes" else "no")
        api.mpv_set_property_string(ctx,"demuxer-readahead-secs",if(item.kind==PlaybackContentKind.VOD)"45" else (networkCachingMs/1000.0).toString())
        // VOD HLS: route the stream through a local prefetch proxy so segments
        // are downloaded with several parallel connections (the m.cdntv.online
        // CDN throttles a single connection to ~1 Mbit/s — far below the movie
        // bitrate — which made every VOD stutter every few seconds).
        if(gen!=generation)return
        val playUrl = resolvePlaybackUrl(item)
        if(gen!=generation)return
        if (debugEnabled) println("[TVNext] mpv loadfile ${item.kind} ${playUrl.take(80)}")
        nativeLoadGeneration=gen
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
            (item.url.startsWith("http://",true) || item.url.startsWith("https://",true)) &&
            item.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        if (!isVodHls) return if(item.url.startsWith("file:",true))
            runCatching {java.io.File(java.net.URI(item.url)).absolutePath}.getOrDefault(item.url) else item.url
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
            "demuxer-readahead-secs" to if (isVod) "45" else (networkCachingMs/1000.0).toString(),
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
            "keep-open" to if (item.kind == PlaybackContentKind.LIVE) "yes" else "no",
            "terminal" to "no",
            // An omitted ao selects the OS device. "auto" is NOT an mpv driver.
            "config" to "no",
            "audio-client-name" to "TVNext",
            // Render API is used instead of a video window (see class doc):
            // frames are delivered to Compose via the SW renderer.
            "vo" to "libmpv",
            "hwdec" to if(_windowsVideoMode.value==WindowsVideoMode.SOFTWARE)"no" else if(gpuWindow!=null && !gpuFailed && _windowsVideoMode.value.gpu)_windowsVideoMode.value.hwdec else "auto-copy",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
        )
        System.getProperty("tvnext.mpv.audioOutput")?.takeUnless {it.isBlank() || it=="auto"}?.let {
            opts += "ao" to it
        }
        opts += "volume" to (_state.value.volume*100).toInt().toString()
        opts += "mute" to if(_state.value.isMuted)"yes" else "no"
        opts.forEach { (name, value) ->
            runCatching { api.mpv_set_option_string(ctx, name, value) }
        }
        runCatching { api.mpv_request_log_messages(ctx, if (debugEnabled) "info" else "error") }
        val initRc = api.mpv_initialize(ctx)
        if (debugEnabled) println("[TVNext] mpv_initialize rc=$initRc api=${api.mpv_client_api_version()}")
        if (initRc < 0) {
            throw IllegalStateException("libmpv init failed (${mpvErrorMessage(api, initRc)})")
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
            throw IllegalStateException("libmpv render context failed (${mpvErrorMessage(api,rc)})")
        }
        renderContext = res.value
        runCatching { api.mpv_render_context_set_update_callback(res.value, updateCallback, null) }
        startRenderThread(api)
    }

    private fun applyHwdec(api: Mpv, ctx: Pointer, item: PlaybackItem) {
        val hwdec = if(_windowsVideoMode.value==WindowsVideoMode.SOFTWARE)"no" else if(_gpuOutput.value)_windowsVideoMode.value.hwdec else "auto-copy"
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
        renderSuspended=true;restoreVideoAfterGpuInit=gpuRenderer?.initialized==true;closeGpu()
        renderAlive = false
        synchronized(renderMonitor) { renderMonitor.notifyAll() }
        renderThread?.join(500L)
        renderThread = null
    }

    /** Resumes frame production after the window round-trip completed. */
    fun resumeRendering() {
        renderSuspended=false
        if(_gpuOutput.value) {
            mpv?.let {ctx->if(gpuRenderer==null)gpuRenderer=MpvGpuRenderer(Mpv.load(),ctx,::requestGpuDraw)}
            requestGpuDraw();return
        }
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

    private class FrameStorage(val width:Int,val height:Int):AutoCloseable {
        val stride=width*4
        val pixels=ByteArray(stride*height)
        val buffer=Memory(pixels.size.toLong())
        private val dimensions=Memory(8).apply {setInt(0,width);setInt(4,height)}
        private val format=Memory(5).apply {setString(0,"bgr0")}
        private val rowBytes=Memory(com.sun.jna.Native.SIZE_T_SIZE.toLong()).apply {
            if(com.sun.jna.Native.SIZE_T_SIZE==8)setLong(0,stride.toLong()) else setInt(0,stride)
        }
        val params=Memory(80).apply {
            clear()
            setInt(0,Mpv.MPV_RENDER_PARAM_SW_SIZE);setPointer(8,dimensions)
            setInt(16,Mpv.MPV_RENDER_PARAM_SW_FORMAT);setPointer(24,format)
            setInt(32,Mpv.MPV_RENDER_PARAM_SW_STRIDE);setPointer(40,rowBytes)
            setInt(48,Mpv.MPV_RENDER_PARAM_SW_POINTER);setPointer(56,buffer)
            setInt(64,Mpv.MPV_RENDER_PARAM_INVALID)
        }
        override fun close(){params.close();buffer.close();rowBytes.close();format.close();dimensions.close()}
    }
    private var frameStorage:FrameStorage?=null

    private fun renderFrame(api: Mpv) {
        if(!sourceReady)return
        val rctx = renderContext ?: return
        val source=_videoSignal.value
        val scale=if(source!=null && source.width>0 && source.height>0)
            maxOf(source.width.toDouble()/surfaceWidth,source.height.toDouble()/surfaceHeight).coerceAtMost(1.0) else 1.0
        val w = (surfaceWidth*scale).toInt().coerceIn(1,8192)
        val h = (surfaceHeight*scale).toInt().coerceIn(1,8192)
        val flags = runCatching { api.mpv_render_context_update(rctx) }.getOrDefault(0L)
        if (flags and Mpv.MPV_RENDER_UPDATE_FRAME == 0L) return

        val storage=frameStorage?.takeIf {it.width==w && it.height==h} ?: run {
            frameStorage?.close();FrameStorage(w,h).also {frameStorage=it}
        }
        val stride=storage.stride
        val size=storage.pixels.size
        val buffer=storage.buffer
        val params=storage.params

        val rc = runCatching { api.mpv_render_context_render(rctx, params) }.getOrDefault(-1)
        if (rc < 0) return

        val pixels=storage.pixels
        buffer.read(0,pixels,0,size)
        val bitmap = Bitmap()
        val installed = bitmap.installPixels(
            ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
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
        eventAlive=true
        eventThread = Thread {
            try {
                while (eventAlive) {
                    val eventPtr = api.mpv_wait_event(ctx, -1.0)
                    if (eventPtr == Pointer.NULL) continue
                    val event = Mpv.MpvEvent().bind(eventPtr)
                    when (event.event_id) {
                        Mpv.MPV_EVENT_SHUTDOWN -> break
                        Mpv.MPV_EVENT_LOG_MESSAGE -> {
                            val prefix=event.data?.getPointer(0)?.getString(0,"UTF-8").orEmpty()
                            val level=event.data?.getPointer(8)?.getString(0,"UTF-8").orEmpty()
                            val text=event.data?.getPointer(16)?.getString(0,"UTF-8").orEmpty()
                            if(level in listOf("error","fatal") && (prefix.startsWith("ao") || "audio device" in text))
                                _audioOutput.update {it.copy(error=text.trim().take(400))}
                        }
                        Mpv.MPV_EVENT_START_FILE -> {
                            val entry=event.data?.getLong(0)
                            val current=getString(api,ctx,"playlist/0/id")?.toLongOrNull()
                            if(entry!=null && entry==current && nativeLoadGeneration==generation) {
                                eventPlaylistEntry=entry;eventSourceGeneration=nativeLoadGeneration
                                sourceReady=false
                                _state.update {it.copy(state=PlaybackState.LOADING,error=null)}
                            }
                        }

                        Mpv.MPV_EVENT_FILE_LOADED -> {
                            if(eventSourceGeneration!=generation)continue
                            sourceReady=true
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
                            val entry=runCatching {event.data?.getLong(8)}.getOrNull()
                            if(eventSourceGeneration==generation && entry==eventPlaylistEntry)handleEndFile(reason,err)
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
        eventAlive=false
        mpv?.let { ctx -> runCatching { Mpv.load().mpv_wakeup(ctx) } }
        eventThread?.join(1_000L)
        eventThread = null
    }

    private fun handleEndFile(reason: Int, error: Int) {
        if (reason == Mpv.MPV_END_FILE_REASON_EOF || reason == Mpv.MPV_END_FILE_REASON_ERROR) {
            val item = currentItem ?: return
            if(reason==Mpv.MPV_END_FILE_REASON_EOF && item.kind==PlaybackContentKind.ARCHIVE && timeShift!=null) {
                _state.update {it.copy(state=PlaybackState.ENDED)};timeShift.ended();return
            }
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
        if(!sourceReady)return
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
        val outputDriver=getString(api,ctx,"current-ao")
        _audioOutput.update {it.copy(driver=outputDriver,
            device=getString(api,ctx,"audio-device"),error=if(outputDriver!=null)null else it.error)}
        val actualVolume=getDouble(api,ctx,"volume")?.let {(it/100).toFloat().coerceIn(0f,1f)}
        val actualMute=getFlag(api,ctx,"mute")
        val audioChannels = getInt(api, ctx, "audio-params/channel-count")
        val audioSampleRate = getInt(api, ctx, "audio-params/samplerate")
        val width = getInt(api, ctx, "width")
        val height = getInt(api, ctx, "height")
        val fps = getDouble(api, ctx, "estimated-vf-fps")?.takeIf {it in 10.0..240.0}
            ?: getDouble(api, ctx, "container-fps")?.takeIf {it in 10.0..240.0}
        val eof = getFlag(api, ctx, "eof-reached") == true

        val posMs = (timePosSec ?: 0.0).coerceAtLeast(0.0).let { (it * 1000.0).toLong() }
        val durationMs = (durationSec ?: 0.0).coerceAtLeast(0.0).let { (it * 1000.0).toLong() }
        val buffering = getFlag(api,ctx,"paused-for-cache")==true

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
            videoOutput = if(gpuRenderer?.initialized==true)gpuRenderer!!.description else if(_gpuOutput.value)"GPU • подготовка" else if(gpuFailed)"libmpv render (SW) • ${_renderStatus.value}" else "libmpv render (SW)",
            codec = videoCodec?.takeIf { it.isNotBlank() },
            resolution = if (width > 0 && height > 0) "${width}x${height}" else "n/a",
            fps = fps?.let(::frameRateNumber),
            videoWidth = width, videoHeight = height,
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

        _videoSignal.value=VideoFrameSignal(width.coerceAtLeast(0),height.coerceAtLeast(0),fps ?: 0.0)
        _state.update {
            it.copy(
                state = state,
                positionMs = posMs,
                durationMs = durationMs,
                diagnostics = diagnostics,
                volume=actualVolume ?: it.volume,isMuted=actualMute ?: it.isMuted,
                hardwareDecode = false,
            )
        }
        AndroidPlaybackSession.record(_state.value)
        timeShift?.tick()
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
        if(!explicitVideoMode){_decodeMode.value = mode;_windowsVideoMode.value=WindowsVideoMode.legacy(mode)}
    }

    override fun updateNetworkCachingMs(ms:Long) {networkCachingMs=ms.coerceIn(1000,30_000)}

    private companion object {
        const val WATCH_INTERVAL_MS = 1_000L
        const val END_RESTART_MARGIN_MS = 30_000L
        const val MAX_AUTO_RESTARTS = 3
    }
}

