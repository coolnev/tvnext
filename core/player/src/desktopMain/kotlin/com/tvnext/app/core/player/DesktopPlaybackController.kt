package com.tvnext.app.core.player

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Canvas
import java.awt.Color
import java.awt.Rectangle
import java.util.Collections
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
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogEventListener
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * Desktop playback backed by **libVLC** (via vlcj).
 *
 * Two video-output pipelines (Sprint 5):
 *
 * ```
 * SOFTWARE ──> callback video surface (RV32 → BGRA ImageBitmap → Compose)
 *               (the proven Sprint 4 pipeline; CPU decode)
 *
 * HARDWARE ──> libvlc_media_player_set_hwnd(AWT Canvas) + --vout=direct3d9
 *               + --avcodec-hw=dxva2 → GPU decoder renders into the HWND;
 *               the Compose window keeps drawing overlays above the native
 *               surface via interop blending (compose.interop.blending=true).
 *               (DXVA2+Direct3D9: the stable hardware pairing on Windows —
 *               a direct3d11 vout forces the d3d11va decoder and froze on
 *               adaptive HLS; see factoryArguments.)
 *
 * AUTO ──────> starts HARDWARE; if the GPU decoder is not confirmed within a
 *               timeout (or fails to init), recreates the backend as SOFTWARE
 *               and keeps playing — no user-visible crash.
 * ```
 *
 * The active mode comes from [AppSettings.videoDecodeMode] ([updateDecodeMode],
 * wired by the application). A mode change takes effect on the next [play]; the
 * current stream is not interrupted.
 *
 * Lifecycle:
 * - A [MediaPlayerFactory] + [EmbeddedMediaPlayer] pair is created lazily per
 *   decode mode and reused for every stream of that mode; switching modes (or
 *   the AUTO→SOFTWARE fallback) releases the old backend and builds a new one.
 * - [release] releases the media player and native log; the libVLC instance
 *   itself is released by a JVM shutdown hook.
 *
 * Race protection:
 * - Every stream gets a monotonically increasing `generation`. Event callbacks
 *   only apply once libVLC confirmed the new media via `mediaChanged`
 *   ([startedGeneration]) and are dropped when the generation no longer
 *   matches, so stale callbacks from a previous stream can never overwrite the
 *   active stream's state.
 * - Hardware playback waits for the AWT Canvas to become displayable (its HWND
 *   is created by the Compose `SwingPanel`) before attaching the video output.
 *
 * Errors:
 * - libVLC's `error` event plus the collected native log are mapped onto the
 *   platform-agnostic [PlaybackError].
 * - HARDWARE mode never silently falls back: if the GPU decoder is not
 *   confirmed, a user-facing error explains why.
 * - A watchdog covers streams that never reach PLAYING (→ Timeout), silent
 *   software video failures (→ Decoder) and the AUTO hardware confirmation.
 */
class DesktopPlaybackController : PlaybackController, DesktopVideoSurface, PlaybackSettingsSink {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _frames = MutableStateFlow<ImageBitmap?>(null)
    override val frames: StateFlow<ImageBitmap?> = _frames.asStateFlow()

    private val _decodeMode = MutableStateFlow(VideoDecodeMode.AUTO)
    val decodeMode: StateFlow<VideoDecodeMode> = _decodeMode.asStateFlow()

    /**
     * Network buffer (ms) applied to every stream via a per-media libVLC
     * option (`--network-caching`). Configurable from the app settings; the
     * default preserves the long-standing behaviour (8 s) that was found
     * necessary for choppy HLS providers. Lower values reduce the live delay
     * (and VOD start latency) at the cost of more stutter on weak sources.
     */
    @Volatile
    private var networkCachingMs: Long = DEFAULT_NETWORK_CACHING_MS

    /**
     * Sets the network buffer from the app settings. Applied on the next
     * [play]; the currently playing stream is not interrupted.
     */
    override fun updateNetworkCachingMs(ms: Long) {
        if (ms <= 0L) return
        networkCachingMs = ms
    }

    /**
     * True while the active backend renders into the native HWND
     * ([hardwareCanvas]) instead of producing [ImageBitmap] frames.
     */
    private val _hardwareSurfaceActive = MutableStateFlow(false)
    override val hardwareSurfaceActive: StateFlow<Boolean> = _hardwareSurfaceActive.asStateFlow()

    /**
     * Bumped by the in-place HARDWARE repair: PlayerSurface keys its AWT canvas
     * on this value, so a repair re-creates the canvas → a brand-new HWND →
     * a brand-new D3D11 swapchain. That is exactly what an F11 window
     * round-trip does (and the only thing that actually restores the picture
     * after a big HLS seek — an in-place player rebuild on the SAME HWND
     * cannot re-create the swapchain and stays white).
     */
    override val hardwareCanvasRevision = MutableStateFlow(0L)

    /**
     * Heavyweight AWT canvas used as the HWND video output in hardware modes.
     * Not focusable: a focused AWT component would steal keyboard input
     * (L / Esc / Enter) from the Compose layer. Mouse events over the video are
     * consumed by the libVLC vout (it subclasses this HWND); a double click
     * fullscreen toggle is therefore provided via the on-screen fullscreen
     * button in the status bar (software mode keeps the native Compose
     * double-click handling).
     */
    internal var hardwareCanvas: Canvas = newVideoCanvas()
        private set

    override val videoCanvas: Canvas
        get() = hardwareCanvas

    /**
     * Called by [PlayerSurface] when a (re)created window mounts: the surface
     * supplies a FRESH canvas for THIS window. Reusing the same canvas across a
     * window round-trip (fullscreen F11) left it attached to the closing window
     * (never became displayable again → 10+ second HWND stall), so each window
     * gets its own canvas.
     */
    override fun attachSurface(canvas: Canvas) {
        hardwareCanvas = canvas
    }

    /** Called when a window unmounts; the canvas is discarded with the window. */
    override fun detachSurface(canvas: Canvas) {
        if (hardwareCanvas === canvas) {
            attachedHwnd = 0L
            // Prevent any stale poll from reusing the removed canvas.
            hardwareCanvas = newVideoCanvas()
        }
    }

    /**
     * Invoked on a double click over the native video surface (hardware mode).
     * Set by the application to toggle fullscreen. Detection uses a WH_MOUSE
     * hook on libVLC's vout-window thread (see [VideoDoubleClickDetector]).
     */
    @Volatile
    var onVideoDoubleClick: (() -> Unit)? = null

    private val doubleClickDetector = VideoDoubleClickDetector(
        boundsProvider = {
            if (_hardwareSurfaceActive.value && hardwareCanvas.isShowing) {
                runCatching {
                    val loc = hardwareCanvas.locationOnScreen
                    Rectangle(loc.x, loc.y, hardwareCanvas.width, hardwareCanvas.height)
                }.getOrNull()
            } else {
                null
            }
        },
        onDoubleClick = { onVideoDoubleClick?.invoke() },
    )

    @Volatile
    private var mediaPlayerFactory: MediaPlayerFactory? = null

    @Volatile
    private var mediaPlayer: EmbeddedMediaPlayer? = null

    @Volatile
    private var nativeLog: NativeLog? = null

    /** Decode mode of the currently created backend (factory args). */
    @Volatile
    private var backendMode: VideoDecodeMode? = null

    @Volatile
    private var generation: Int = 0

    /**
     * Generation whose media start was confirmed by libVLC (`mediaChanged`).
     * Events are ignored until the active generation is "started".
     */
    @Volatile
    private var startedGeneration: Int = 0

    @Volatile
    private var surfaceAttached: Boolean = false

    @Volatile
    private var hasVideoTrack: Boolean = false

    @Volatile
    private var lastFrameNanos: Long = 0L

    @Volatile
    private var playingSinceNanos: Long = 0L

    /**
     * True while libVLC reports an ACTIVE buffering phase (newCache < 100).
     * Distinguishes a real network/HLS buffering — where the player must be
     * left alone to recover — from a decoder stall. Reset to false when the
     * buffering completes (newCache >= 100) or playback restarts.
     */
    @Volatile
    private var isBufferingNow: Boolean = false

    /** Last time a `timeChanged` event was pushed to the Compose state. */
    @Volatile
    private var lastTimeChangedNanos: Long = 0L

    /**
     * Stall detection for AUTO + HARDWARE on VOD/ARCHIVE. D3D11VA can freeze on
     * adaptive HLS (decoded frames stop, the position no longer advances while
     * the demuxer keeps downloading). When the position has not moved for
     * [STALL_FALLBACK_TIMEOUT_NANOS] while the state is PLAYING, the watchdog
     * falls back to the software pipeline (which plays the same streams
     * flawlessly). Hardware stays in use whenever it actually works, so weak
     * CPUs are NOT forced onto the software path.
     */
    @Volatile
    private var lastPositionMs: Long = -1L

    @Volatile
    private var lastPositionMoveNanos: Long = 0L

    /** gen in which the stall fallback already ran (one per play attempt). */
    @Volatile
    private var hwStallFallbackAppliedFor: Int = -1

    /**
     * In-place hardware repairs performed per item in this session (bounded —
     * a fundamentally broken stream must not repair-loop forever). Cleared when
     * playback actually resumes ([playing]).
     */
    private val stallRepairAttempts: MutableMap<String, Int> = java.util.Collections.synchronizedMap(HashMap())

    /**
     * Auto-restarts of a mid-playback VOD/ARCHIVE stream (bounded per item —
     * a stream that dies on every restart finally surfaces the error). Cleared
     * when playback actually resumes ([playing]).
     */
    private val autoRestartCounts: MutableMap<String, Int> = java.util.Collections.synchronizedMap(HashMap())

    @Volatile
    private var pendingItem: PlaybackItem? = null

    @Volatile
    private var pendingGeneration: Int = -1

    /** Set once AUTO already fell back for a generation (prevents re-fallback). */
    @Volatile
    private var hwFallbackAppliedFor: Int = -1

    /**
     * Set once HARDWARE mode retried the (transient) hardware init for a
     * generation. A second failure surfaces the strict user-facing error.
     */
    @Volatile
    private var hwRetryAttemptedFor: Int = -1

    /** HWND of the canvas the current hardware player is attached to. */
    @Volatile
    private var attachedHwnd: Long = 0L

    /**
     * Last known track facts (codec/resolution/fps/bitrate), cached so the
     * diagnostics panel keeps showing the stream's real data when it reaches
     * an ERROR/ENDED state (where [updateDiagnostics] is called with no fresh
     * media info). Reset on every [play] and AUTO→SOFTWARE fallback so a new
     * stream never shows the previous stream's facts.
     */
    @Volatile
    private var lastCodec: String? = null

    @Volatile
    private var lastResolution: String? = null

    @Volatile
    private var lastFps: String? = null

    @Volatile
    private var lastBitrate: String? = null

    private fun resetDiagnosticsCache() {
        lastCodec = null
        lastResolution = null
        lastFps = null
        lastBitrate = null
    }

    /**
     * Channels on which the AUTO mode already failed over to software in this
     * session. Re-selecting such a channel starts directly in software mode —
     * no hardware attempt, no 6-second wait, no heavy backend recreation
     * (this was the cause of UI stutter when flipping through broken channels).
     * Only applies while the setting is AUTO; explicit HARDWARE always tries
     * the GPU path. Bounded to avoid unbounded growth during long sessions.
     */
    private val softwareOnlyChannelIds: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet())

    private fun rememberSoftwareOnlyChannel(channelId: String) {
        synchronized(softwareOnlyChannelIds) {
            if (softwareOnlyChannelIds.size >= MAX_SOFTWARE_ONLY_CHANNELS) {
                val oldest = softwareOnlyChannelIds.iterator().next()
                softwareOnlyChannelIds.remove(oldest)
            }
            softwareOnlyChannelIds.add(channelId)
        }
    }

    private val renderSurface: VlcRenderSurface = VlcRenderSurface(::publishFrame)

    private val decoderDetector = VlcDecoderDetector()

    /**
     * Strong reference to the native-log JNA callback. vlcj hands the listener
     * to libVLC as a JNA callback; if this object is garbage collected while
     * libVLC still holds the function pointer, the next log message invokes
     * freed memory → EXCEPTION_ACCESS_VIOLATION ("JNA: callback object has
     * been garbage collected"). Holding it here prevents that crash.
     */
    private var logListener: LogEventListener? = null

    /**
     * Strong reference to the MediaPlayer event listener (JNA callback). It is
     * also kept by vlcj, but keeping it here guarantees the Kotlin object (and
     * its JNA bridge) outlives a player rebuild — otherwise "callback object
     * has been garbage collected" from libVLC's event thread, eventually
     * leading to a native crash.
     */
    private var mediaPlayerListener: MediaPlayerEventAdapter? = null

    /** Strong refs to the video surface / buffer-format JNA callbacks. */
    private var videoSurface: VideoSurface? = null
    private var bufferFormatCallback: BufferFormatCallback? = null

    /**
     * Every JNA-callback object ever handed to libVLC is kept alive here.
     * vlcj/libVLC may still dispatch callbacks from an ALREADY-RELEASED player
     * (async event/log threads), so overwriting the "current" refs lets the GC
     * collect a callback the native side still invokes → "callback object has
     * been garbage collected" and, after a few rebuilds, a hard access
     * violation (observed as NTSTATUS 0xC0000005).
     *
     * The list is bounded by a very high ceiling ([MAX_RETAINED_CALLBACKS], a
     * month-long heavy-zapping session's worth of rebuilds) so the JVM side
     * never grows without bound while the safety window stays effectively
     * unlimited.
     */
    private val retainedCallbacks = Collections.synchronizedList(ArrayList<Any>())

    /** Adds a JNA-callback reference, dropping the oldest beyond the cap. */
    private fun retain(obj: Any) {
        retainedCallbacks.add(obj)
        while (retainedCallbacks.size > MAX_RETAINED_CALLBACKS) {
            retainedCallbacks.removeAt(0)
        }
    }

    /** Strong reference to the software video-surface callback (same reason). */
    private var softwareSurfaceListener: uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback? = null

    private val errorLogMessages: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Raw native log ring (debug diagnostics for decoder investigation). */
    private val logRing: java.util.ArrayDeque<String> = java.util.ArrayDeque()

    @Volatile
    private var logMessageCount: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Scope used exclusively for releasing native resources (player/factory).
     * Kept SEPARATE from [scope] so [release] can cancel the playback scope
     * without losing the scheduled `release()` calls: releasing a libVLC
     * player can block for seconds, and cancelling the scope first would make
     * those launches no-ops → native player/factory leaked forever.
     */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var watcherJob: Job? = null

    /** Cancelled on every rebuild/release so it never touches a freed player. */
    private var seekRetryJob: Job? = null

    /**
     * Position to restore after a surface rebuild (window/fullscreen switch).
     * Without this a VOD film restarts from 0 after F11.
     */
    private var resumeAfterPlayMs: Long = 0L
    private var resumeAfterPlayItemId: String? = null

    /** Timestamp of the last rebuild (diagnostics for the F11 slowness). */
    private var rebuildStartedAtNanos: Long = 0L

    /**
     * Sets the requested decode mode (from the app settings). Takes effect on
     * the next [play]; the currently playing stream is not interrupted.
     */
    override fun updateDecodeMode(mode: VideoDecodeMode) {
        if (_decodeMode.value == mode) return
        _decodeMode.value = mode
    }

    /**
     * Resolves the actual decode mode for a playback request.
     *
     * AUTO short-circuits to software only for items that already proved
     * unplayable on the GPU in this session. Explicit HARDWARE is STRICT: it
     * always stays hardware (many users run weak CPUs — software decoding is
     * not an option for them); explicit SOFTWARE stays software.
     */
    private fun effectiveDecodeMode(item: PlaybackItem, requested: VideoDecodeMode): VideoDecodeMode =
        if (requested == VideoDecodeMode.AUTO && item.id in softwareOnlyChannelIds) {
            VideoDecodeMode.SOFTWARE
        } else {
            requested
        }

    override fun play(item: PlaybackItem) {
        val gen = generation + 1
        generation = gen
        startedGeneration = 0
        hasVideoTrack = false
        lastFrameNanos = 0L
        playingSinceNanos = 0L
        isBufferingNow = false
        lastTimeChangedNanos = 0L
        lastPositionMs = -1L
        hwStallFallbackAppliedFor = -1
        errorLogMessages.clear()
        decoderDetector.reset()
        _frames.value = null
        resetDiagnosticsCache()
        val mode = effectiveDecodeMode(item, _decodeMode.value)
        _state.update {
            PlayerState(
                state = PlaybackState.LOADING,
                item = item,
                error = null,
                isMuted = it.isMuted,
                volume = it.volume,
                hardwareDecode = mode.usesHwndOutput,
            )
        }
        startWatcher(gen)
        try {
            ensureFactory(mode)
            if (gen != generation) return
            schedulePlayback(item, gen, mode)
        } catch (e: Throwable) {
            if (gen != generation) return
            watcherJob?.cancel()
            if (e is UnsatisfiedLinkError || isVlcUnavailable(e)) {
                _state.update {
                    it.copy(
                        state = PlaybackState.ERROR,
                        error = "Не удалось загрузить видео-движок (libVLC). " +
                            "Убедитесь, что libVLC runtime установлен.",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        state = PlaybackState.ERROR,
                        error = PlaybackError.Unknown.toUserMessage(),
                    )
                }
            }
        }
    }

    override fun pause() {
        runCatching { mediaPlayer?.controls()?.pause() }
    }

    override fun resume() {
        runCatching { mediaPlayer?.controls()?.play() }
    }

    override fun stop() {
        generation++
        startedGeneration = 0
        pendingItem = null
        watcherJob?.cancel()
        _state.update { PlayerState(state = PlaybackState.IDLE, positionMs = 0L) }
        _frames.value = null
        runCatching { mediaPlayer?.controls()?.stop() }
    }

    override fun seekBy(deltaMs: Long) {
        val player = mediaPlayer
        // Actual engine position (state may lag behind right after a seek).
        val actual = runCatching { player?.status()?.time() }.getOrNull()
            ?: _state.value.positionMs
        seekTo((actual + deltaMs).coerceAtLeast(0L))
    }

    override fun seekTo(positionMs: Long) {
        val player = mediaPlayer
        val duration = _state.value.durationMs
        val before = _state.value.positionMs
        // A seek legitimately freezes the position for a moment while VLC
        // locates the target segment — reset the stall tracker so the watchdog
        // never mistakes a user seek (or the resume restore) for a decoder
        // stall.
        lastPositionMs = -1L
        lastPositionMoveNanos = 0L
        if (debugEnabled) {
            println("[TVNext] seekTo($positionMs) duration=$duration before=$before seekable=${runCatching { player?.status()?.isSeekable() }.getOrNull()}")
        }
        runCatching {
            val controls = player?.controls() ?: return
            // Absolute time first, then relative position — some HLS manifests
            // honour only one of the two.
            controls.setTime(positionMs)
            if (duration > 0L) {
                controls.setPosition((positionMs.toFloat() / duration).coerceIn(0f, 1f))
            }
        }.onFailure { e ->
            if (debugEnabled) println("[TVNext] seekTo failed: ${e::class.simpleName}: ${e.message}")
        }
        // Read back a bit later; retry only when the position did NOT move at
        // all (a couple of HLS setups only honour seeks while paused). The
        // threshold and delay are deliberately gentle: an aggressive retry that
        // paused+replayed on every scrubbing step made VOD rewinding stutter
        // (while live channels, which never seek, stayed smooth). The retry is
        // cancelled on every rebuild/release so it can never touch a freed
        // player (that caused a hard crash).
        seekRetryJob?.cancel()
        seekRetryJob = scope.launch {
            delay(SEEK_RETRY_DELAY_MS)
            if (mediaPlayer !== player || player == null) return@launch
            val actual = runCatching { player.status()?.time() }.getOrNull()
            if (debugEnabled) println("[TVNext] seekTo($positionMs) actual=${actual ?: -1}")
            if (actual != null && kotlin.math.abs(actual - positionMs) > SEEK_RETRY_THRESHOLD_MS) {
                runCatching {
                    val controls = player.controls() ?: return@launch
                    val wasPlaying = _state.value.state == PlaybackState.PLAYING
                    controls.pause()
                    controls.setTime(positionMs)
                    controls.setPosition((positionMs.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f))
                    if (wasPlaying) controls.play()
                }.onFailure { e ->
                    if (debugEnabled) println("[TVNext] seek retry failed: ${e::class.simpleName}: ${e.message}")
                }
            }
            // A large seek on HLS can KILL the stream entirely: status().time()
            // returns -1 and the player reports not-seekable (observed after a
            // 60-minute forward seek — position froze, "ошибка потока" next).
            // For VOD/ARCHIVE that means the current player is unusable —
            // restart it from the intended position instead of waiting for a
            // stall/timeout. Explicit HARDWARE stays hardware.
            val item = _state.value.item
            if (actual != null &&
                actual < 0L &&
                item != null &&
                item.kind != PlaybackContentKind.LIVE &&
                canAutoRestart(item.id)
            ) {
                if (debugEnabled) {
                    println("[TVNext] seek failed (actual=$actual, stream not seekable) — auto-restarting VOD")
                }
                autoRestartVod(item)
            }
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        runCatching {
            mediaPlayer?.audio()?.setVolume((clamped * 100).toInt().coerceIn(0, 100))
        }
        _state.update { it.copy(volume = clamped) }
    }

    override fun setMuted(muted: Boolean) {
        runCatching { mediaPlayer?.audio()?.setMute(muted) }
        _state.update { it.copy(isMuted = muted) }
    }

    override fun release() {
        generation++
        startedGeneration = 0
        pendingItem = null
        watcherJob?.cancel()
        _state.update { PlayerState() }
        _frames.value = null
        scope.cancel()
        // Native resources are released through [releaseScope], which is NOT
        // cancelled here — otherwise the async release() calls would never run.
        releaseBackend()
        seekRetryJob?.cancel()
        seekRetryJob = null
    }

    // ---- Surface integration (called from PlayerSurface) ------------------

    override fun onSurfaceAttached() {
        surfaceAttached = true
        val current = _state.value
        val mode = _decodeMode.value
        // Hardware mode: the canvas (and its HWND) was just (re)mounted —
        // fullscreen toggles, tab switches, window round-trips. The libVLC
        // vout renders into the canvas HWND captured at creation time; when
        // the canvas moves to another window its HWND is recreated (or kept
        // but hidden), so the player MUST be rebuilt against the current
        // canvas, otherwise the video goes blank/white while audio continues.
        if (mode.usesHwndOutput &&
            mediaPlayer != null &&
            current.item != null &&
            current.state != PlaybackState.IDLE
        ) {
            if (debugEnabled) {
                println("[TVNext] hw surface remounted, rebuilding player (channel ${current.item.id})")
            }
            val item = current.item
            // Remember where we were so the rebuilt player resumes (F11 / window
            // round-trip must not restart a VOD film from the beginning).
            resumeAfterPlayMs = _state.value.positionMs
            resumeAfterPlayItemId = item.id
            rebuildStartedAtNanos = System.nanoTime()
            if (debugEnabled) println("[TVNext] hw rebuild start (item ${item.id})")
            val gen = generation + 1
            generation = gen
            startedGeneration = 0
            hasVideoTrack = false
            lastFrameNanos = 0L
            playingSinceNanos = 0L
            isBufferingNow = false
            lastTimeChangedNanos = 0L
            lastPositionMs = -1L
            hwStallFallbackAppliedFor = -1
            decoderDetector.reset()
            _frames.value = null
            _state.update { it.copy(state = PlaybackState.LOADING, error = null, hardwareDecode = true) }
            startWatcher(gen)
            runCatching { releaseMediaPlayer() }
            schedulePlayback(item, gen, mode)
            return
        }
        // Recovery after a detach when the player was released (e.g. stop).
        if (mode.usesHwndOutput &&
            mediaPlayer == null &&
            current.item != null &&
            current.state != PlaybackState.IDLE
        ) {
            val item = current.item
            resumeAfterPlayMs = _state.value.positionMs
            resumeAfterPlayItemId = item.id
            val gen = generation + 1
            generation = gen
            startedGeneration = 0
            hasVideoTrack = false
            lastFrameNanos = 0L
            playingSinceNanos = 0L
            isBufferingNow = false
            lastTimeChangedNanos = 0L
            lastPositionMs = -1L
            hwStallFallbackAppliedFor = -1
            decoderDetector.reset()
            _frames.value = null
            _state.update { it.copy(state = PlaybackState.LOADING, error = null, hardwareDecode = true) }
            startWatcher(gen)
            schedulePlayback(item, gen, mode)
            return
        }
        val item = pendingItem ?: return
        val gen = pendingGeneration
        if (gen != generation) return
        schedulePlayback(item, gen, _decodeMode.value)
    }

    override fun onSurfaceDetached() {
        surfaceAttached = false
        // Release the vout while the window (and its HWND) is still alive:
        // a player left attached to a canvas whose window is closing crashes
        // with an access violation when the rebuild releases it after the HWND
        // is already gone (observed on exit from fullscreen in the Media
        // Library). The state/item/position are retained and restored after the
        // next [onSurfaceAttached] rebuild.
        releaseMediaPlayer()
    }

    /**
     * Kept for API compatibility. The Compose layer scales the callback frames
     * itself, and the HWND video output scales inside the native canvas.
     */
    override fun updateSurfaceSize(width: Int, height: Int) = Unit

    // ---- Internals ---------------------------------------------------------

    private fun publishFrame(bitmap: ImageBitmap) {
        if (!surfaceAttached) return
        hasVideoTrack = true
        lastFrameNanos = System.nanoTime()
        _frames.value = bitmap
    }

    /**
     * Starts playback. Software mode starts immediately; hardware modes wait
     * for the AWT canvas to be displayable (its HWND must exist before
     * [uk.co.caprica.vlcj.player.embedded.videosurface.ComponentVideoSurface]
     * can attach) — this happens on the first composition, or is retried from
     * [onSurfaceAttached] when the surface mounts later.
     */
    private fun schedulePlayback(item: PlaybackItem, gen: Int, mode: VideoDecodeMode) {
        if (!mode.usesHwndOutput) {
            startPlayback(item, gen, mode)
            return
        }
        _hardwareSurfaceActive.value = true
        pendingItem = item
        pendingGeneration = gen
        if (!surfaceAttached) return // PlayerSurface will call onSurfaceAttached
        scope.launch {
            // The canvas must be displayable, have a real size AND a valid
            // HWND: a 0x0 HWND makes the D3D11 swapchain fail, and hwnd=0
            // makes libVLC create its own window (grey video, no overlay).
            // HARDWARE mode waits longer (a fullscreen window round-trip can
            // briefly detach the canvas) but NEVER falls back to software.
            val polls = if (mode == VideoDecodeMode.HARDWARE) {
                HWND_READY_POLLS_HARDWARE
            } else {
                HWND_READY_POLLS
            }
            var waited = 0
            while (waited < polls &&
                !(hardwareCanvas.isDisplayable &&
                    hardwareCanvas.width > 0 &&
                    hardwareCanvas.height > 0 &&
                    componentId(hardwareCanvas) != 0L)
            ) {
                if (debugEnabled) {
                    println(
                        "[TVNext] hw wait i=$waited displayable=${hardwareCanvas.isDisplayable} " +
                            "size=${hardwareCanvas.width}x${hardwareCanvas.height} hwnd=${componentId(hardwareCanvas)}",
                    )
                }
                delay(HWND_READY_POLL_MS)
                waited++
            }
            if (gen != generation) return@launch
            if (!hardwareCanvas.isDisplayable ||
                hardwareCanvas.width <= 0 ||
                hardwareCanvas.height <= 0 ||
                componentId(hardwareCanvas) == 0L
            ) {
                if (mode == VideoDecodeMode.HARDWARE) {
                    // Strict HARDWARE mode: never silently switch to software.
                    // The canvas never became ready — surface a user-visible
                    // error instead (the next play() retries cleanly).
                    watcherJob?.cancel()
                    _state.update {
                        it.copy(
                            state = PlaybackState.ERROR,
                            error = "Аппаратное декодирование недоступно " +
                                "(не удалось создать видео-поверхность). " +
                                "Проверьте поддержку H.264/HEVC видеокартой и драйвером " +
                                "или выберите «Программное» декодирование.",
                        )
                    }
                    return@launch
                }
                // AUTO: the canvas did not become ready in time (e.g. right
                // after a fullscreen window switch). Instead of stalling for
                // many seconds, fall back to the software path so the picture
                // appears immediately; the user can switch back to hardware
                // on the next play.
                if (debugEnabled) {
                    println("[TVNext] HWND not ready after ${waited * HWND_READY_POLL_MS}ms — software fallback")
                }
                val swGen = generation + 1
                generation = swGen
                startedGeneration = 0
                hasVideoTrack = false
                lastFrameNanos = 0L
                playingSinceNanos = 0L
                errorLogMessages.clear()
                decoderDetector.reset()
                _frames.value = null
                resetDiagnosticsCache()
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                runCatching { releaseBackend(mode) }
                startPlayback(item, swGen, VideoDecodeMode.SOFTWARE)
                return@launch
            }
            startPlayback(item, gen, mode)
        }
    }

    private fun startPlayback(item: PlaybackItem, gen: Int, mode: VideoDecodeMode) {
        pendingItem = null
        if (rebuildStartedAtNanos != 0L) {
            if (debugEnabled) {
                println("[TVNext] playback started ${(System.nanoTime() - rebuildStartedAtNanos) / 1_000_000}ms after rebuild start")
            }
            rebuildStartedAtNanos = 0L
        }
        try {
            val mp = obtainMediaPlayer(mode)
            if (gen != generation) return
            applyVolumeAndMute(mp)
            mp.media().play(item.url, *mediaOptions(item))
            // Restore the position captured before a surface rebuild (F11
            // window switch), so a VOD film continues instead of restarting.
            if (resumeAfterPlayItemId == item.id && resumeAfterPlayMs > 0L) {
                val resumeMs = resumeAfterPlayMs
                resumeAfterPlayMs = 0L
                resumeAfterPlayItemId = null
                scope.launch {
                    delay(RESUME_AFTER_REBUILD_DELAY_MS)
                    if (gen == generation && _state.value.item?.id == item.id) {
                        seekTo(resumeMs)
                    }
                }
            }
        } catch (e: Throwable) {
            if (gen != generation) return
            watcherJob?.cancel()
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = if (e is UnsatisfiedLinkError || isVlcUnavailable(e)) {
                        "Не удалось загрузить видео-движок (libVLC). " +
                            "Убедитесь, что libVLC runtime установлен."
                    } else {
                        PlaybackError.Unknown.toUserMessage()
                    },
                )
            }
        }
    }

    private fun ensureFactory(mode: VideoDecodeMode) {
        if (backendMode == mode && mediaPlayerFactory != null) {
            // Factory survived a surface detach (player was released) — make
            // sure the native log listener is still attached for the rebuilt
            // player's decoder detection.
            if (nativeLog == null) {
                setupNativeLog(mediaPlayerFactory!!)
            }
            return
        }
        releaseBackend(mode)
        VlcRuntimeLocator.prepare()
        val args = factoryArguments(mode)
        if (debugEnabled) {
            println("[TVNext] creating factory mode=$mode args=${args.joinToString(" ")}")
        }
        val factory = try {
            MediaPlayerFactory(*args)
        } catch (e: UnsatisfiedLinkError) {
            throw e
        } catch (e: Throwable) {
            throw VlcInitializationException(e)
        }
        mediaPlayerFactory = factory
        backendMode = mode
        setupNativeLog(factory)
        registerShutdownHook()
    }

    /**
     * Collects libVLC native log messages so the (otherwise detail-free)
     * `error` event can be classified and the actual decoder can be detected.
     *
     * The level is DEBUG because the hardware-decoder markers (`Using D3D11VA`,
     * `Using DXVA2`) are emitted below WARNING; without them the decoder
     * detector cannot confirm the GPU path.
     */
    private fun setupNativeLog(factory: MediaPlayerFactory) {
        try {
            val log = factory.application().newLog()
            log.setLevel(LogLevel.DEBUG)
            val listener = LogEventListener { level, _, _, _, _, _, _, message ->
                logMessageCount++
                synchronized(logRing) {
                    logRing.addLast(message)
                    while (logRing.size > MAX_LOG_RING) logRing.removeFirst()
                }
                decoderDetector.onMessage(message)
                if (level == LogLevel.ERROR || level == LogLevel.WARNING) {
                    synchronized(errorLogMessages) {
                        errorLogMessages += message
                        if (errorLogMessages.size > MAX_LOG_MESSAGES) {
                            errorLogMessages.removeAt(0)
                        }
                    }
                }
            }
            // Keep a strong reference: without it the JNA callback is GC'd and
            // libVLC later calls into freed memory (crash).
            logListener?.let { retain(it) }
            nativeLog?.let { retain(it) }
            logListener = listener
            log.addLogListener(listener)
            nativeLog = log
        } catch (e: Throwable) {
            // Logging is best-effort; playback must not depend on it.
        }
    }

    private fun createPlayer(mode: VideoDecodeMode): EmbeddedMediaPlayer {
        val factory = mediaPlayerFactory ?: throw IllegalStateException("factory not ready")
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        _hardwareSurfaceActive.value = mode.usesHwndOutput
        if (mode.usesHwndOutput) {
            // HWND video output: libVLC renders with D3D11 directly into the
            // AWT canvas (GPU decode path, d3d11va/dxva2).
            if (debugEnabled) {
                val hwnd = runCatching { componentId(hardwareCanvas) }.getOrElse { 0L }
                val loc = runCatching { hardwareCanvas.locationOnScreen }.getOrElse { java.awt.Point() }
                println(
                    "[TVNext] hw canvas attach displayable=${hardwareCanvas.isDisplayable} " +
                        "showing=${hardwareCanvas.isShowing} size=${hardwareCanvas.width}x${hardwareCanvas.height} " +
                        "loc=${loc.x},${loc.y} hwnd=$hwnd",
                )
            }
            attachedHwnd = runCatching { componentId(hardwareCanvas) }.getOrElse { 0L }
            // Disable libVLC's own mouse handling via the vlcj input API
            // (libvlc_video_set_mouse_input). Experimentally proven (VlcMouseProbe):
            // the D3D11 vout otherwise consumes all clicks over the canvas and,
            // on a double click, tries its own fullscreen — which fails in
            // embedded mode ("Failed to set fullscreen"). Note that the
            // `--no-mouse-events` factory argument does NOT disable this; only
            // this per-player call does.
            runCatching { player.input().enableMouseInputHandling(false) }
            videoSurface?.let { retain(it) }
            videoSurface = factory.videoSurfaces().newVideoSurface(hardwareCanvas)
            player.videoSurface().set(videoSurface)
        } else {
            // Software callback pipeline (Sprint 4, proven): RV32 → ImageBitmap.
            val bufferFormatCallback = object : BufferFormatCallbackAdapter() {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                    RV32BufferFormat(sourceWidth, sourceHeight)
            }
            // Keep strong references to the JNA callbacks so they are not
            // garbage collected while libVLC holds their function pointers.
            this.bufferFormatCallback?.let { retain(it) }
            this.bufferFormatCallback = bufferFormatCallback
            softwareSurfaceListener = renderSurface
            videoSurface?.let { retain(it) }
            videoSurface = factory.videoSurfaces().newVideoSurface(
                bufferFormatCallback,
                renderSurface,
                false,
            )
            player.videoSurface().set(videoSurface)
        }
        bindEventHandlers(player)
        mediaPlayer = player
        return player
    }

    private fun obtainMediaPlayer(mode: VideoDecodeMode): EmbeddedMediaPlayer {
        ensureFactory(mode)
        mediaPlayer?.let { return it }
        return createPlayer(mode)
    }

    private fun bindEventHandlers(player: EmbeddedMediaPlayer) {
        val listener = object : MediaPlayerEventAdapter() {
            override fun mediaChanged(mediaPlayer: MediaPlayer, mediaRef: uk.co.caprica.vlcj.media.MediaRef) {
                if (mediaPlayer !== this@DesktopPlaybackController.mediaPlayer) {
                    if (debugEnabled) println("[TVNext] event mediaChanged STALE player=${System.identityHashCode(mediaPlayer)} current=${System.identityHashCode(this@DesktopPlaybackController.mediaPlayer)}")
                    return
                }
                startedGeneration = generation
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                if (!isCurrent(mediaPlayer)) {
                    if (debugEnabled) println("[TVNext] event playing IGNORED startedGen=$startedGeneration gen=$generation samePlayer=${mediaPlayer === this@DesktopPlaybackController.mediaPlayer}")
                    return
                }
                // Playback genuinely resumed — reset the stall-repair and
                // auto-restart budgets so a later freeze/failure gets fresh
                // recovery attempts.
                stallRepairAttempts.clear()
                autoRestartCounts.clear()
                val info = readMediaInfo(mediaPlayer)
                hasVideoTrack = info?.videoTracks()?.isNotEmpty() == true
                playingSinceNanos = System.nanoTime()
                updateDiagnostics(info)
                if (debugEnabled) logPlayerInfo(info)
                _state.update { it.copy(state = PlaybackState.PLAYING) }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                if (!isCurrent(mediaPlayer)) return
                _state.update { it.copy(state = PlaybackState.PAUSED) }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                if (!isCurrent(mediaPlayer)) return
                val current = _state.value
                if (current.state != PlaybackState.ERROR && current.state != PlaybackState.ENDED) {
                    _state.update { it.copy(state = PlaybackState.IDLE, positionMs = 0L) }
                }
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (!isCurrent(mediaPlayer)) return
                // libVLC reports the pre-roll progress (0..100): 100 means the
                // buffering phase has COMPLETED and playback resumes. Failing to
                // switch back to PLAYING left the UI stuck on "Буферизация"
                // forever after the first real network buffering.
                // Log only the TRANSITIONS (idle→buffering, buffering→done), not
                // every progress tick (libVLC emits ~25 events per phase).
                if (newCache >= BUFFERED_CACHE_LEVEL) {
                    if (isBufferingNow) {
                        isBufferingNow = false
                        if (debugEnabled) {
                            println("[TVNext] buffering END (position ${_state.value.positionMs} ms)")
                        }
                    }
                    val current = _state.value
                    if (current.state == PlaybackState.BUFFERING) {
                        _state.update { it.copy(state = PlaybackState.PLAYING) }
                    }
                } else {
                    if (!isBufferingNow) {
                        isBufferingNow = true
                        if (debugEnabled) {
                            println("[TVNext] buffering START (position ${_state.value.positionMs} ms)")
                        }
                    }
                    val current = _state.value
                    if (current.state != PlaybackState.LOADING && current.state != PlaybackState.BUFFERING) {
                        _state.update { it.copy(state = PlaybackState.BUFFERING) }
                    }
                }
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (!isCurrent(mediaPlayer)) return
                // Push the position to Compose at a bounded rate (≈6-7/s): the
                // UI progress bars do not need every VLC event, and each
                // _state.update triggers a recomposition of the whole player
                // panel. This throttling does NOT affect actual playback.
                val now = System.nanoTime()
                if (now - lastTimeChangedNanos >= TIME_CHANGED_THROTTLE_NANOS) {
                    lastTimeChangedNanos = now
                    _state.update { it.copy(positionMs = newTime.coerceAtLeast(0L)) }
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (!isCurrent(mediaPlayer)) return
                _state.update { it.copy(durationMs = newLength.coerceAtLeast(0L)) }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                if (!isCurrent(mediaPlayer)) return
                val category = classifyVlcError(
                    messages = errorLogMessages.toList(),
                    hadVideoTrack = hasVideoTrack,
                )
                val item = _state.value.item
                // VOD/ARCHIVE: a transient failure mid-playback (network hiccup,
                // HLS tail after a seek) is auto-recovered by restarting the
                // stream from the SAME position in the SAME decode mode —
                // instead of dropping the user to an error screen minutes into
                // a film. A truly unsupported format is never retried.
                if (item != null &&
                    item.kind != PlaybackContentKind.LIVE &&
                    category != PlaybackError.UnsupportedFormat &&
                    canAutoRestart(item.id)
                ) {
                    if (debugEnabled) {
                        println(
                            "[TVNext] error(${category}) — auto-restarting VOD " +
                                "from pos=${_state.value.positionMs} ms",
                        )
                    }
                    watcherJob?.cancel()
                    autoRestartVod(item)
                    return
                }
                _state.update {
                    it.copy(state = PlaybackState.ERROR, error = category.toUserMessage())
                }
                watcherJob?.cancel()
                // Reflect the failure in the diagnostics panel ("Факт.: ERROR").
                updateDiagnostics(null)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (!isCurrent(mediaPlayer)) return
                val current = _state.value
                val item = current.item
                val duration = current.durationMs
                val position = current.positionMs
                // VOD/ARCHIVE "ended" long before the real end (a broken HLS
                // tail after a big seek) is auto-recovered, not shown as an
                // error. A position at/near the actual duration is a genuine end.
                val nearEnd = duration > 0L && position >= duration - END_RESTART_MARGIN_MS
                if (item != null &&
                    item.kind != PlaybackContentKind.LIVE &&
                    !nearEnd &&
                    canAutoRestart(item.id)
                ) {
                    if (debugEnabled) {
                        println(
                            "[TVNext] finished mid-playback (pos=$position of $duration ms) — auto-restarting",
                        )
                    }
                    watcherJob?.cancel()
                    autoRestartVod(item)
                    return
                }
                _state.update {
                    it.copy(
                        state = PlaybackState.ENDED,
                        error = "Поток завершился. Попробуйте повторить.",
                    )
                }
                watcherJob?.cancel()
                updateDiagnostics(null)
            }
        }
        mediaPlayerListener?.let { retain(it) }
        mediaPlayerListener = listener
        player.events().addMediaPlayerEventListener(listener)
    }

    private fun isCurrent(player: MediaPlayer): Boolean =
        mediaPlayer === player && startedGeneration == generation

    private fun applyVolumeAndMute(player: MediaPlayer) {
        val current = _state.value
        runCatching {
            player.audio().setVolume((current.volume.coerceIn(0f, 1f) * 100).toInt().coerceIn(0, 100))
            player.audio().setMute(current.isMuted)
        }
    }

    private fun readMediaInfo(player: MediaPlayer): uk.co.caprica.vlcj.media.InfoApi? = try {
        player.media().info()
    } catch (e: Throwable) {
        null
    }

    private fun logPlayerInfo(info: uk.co.caprica.vlcj.media.InfoApi?) {
        runCatching {
            val ringCopy: List<String>
            synchronized(logRing) { ringCopy = logRing.toList() }
            val interesting = ringCopy.filter {
                it.contains("Using", ignoreCase = true) ||
                    it.contains("pix_fmt", ignoreCase = true) ||
                    it.contains("d3d", ignoreCase = true) ||
                    it.contains("dxva", ignoreCase = true) ||
                    it.contains("avcodec", ignoreCase = true) ||
                    it.contains("hardware", ignoreCase = true) ||
                    it.contains("software", ignoreCase = true) ||
                    it.contains("vout", ignoreCase = true) ||
                    it.contains("swapchain", ignoreCase = true) ||
                    it.contains("device", ignoreCase = true) ||
                    it.contains("wingdi", ignoreCase = true) ||
                    it.contains("failed", ignoreCase = true) ||
                    it.contains("chroma", ignoreCase = true)
            }.take(25)
            println(
                "[TVNext] vlc playing mode=${_decodeMode.value} " +
                    "decoder=${decoderDetector.decoderName()} logMessages=$logMessageCount\n" +
                    interesting.joinToString("\n") { "  LOG: $it" },
            )
        }
    }

    private fun updateDiagnostics(info: uk.co.caprica.vlcj.media.InfoApi?) {
        val track = info?.videoTracks()?.firstOrNull()
        val dim = runCatching { mediaPlayer?.video()?.videoDimension() }.getOrNull()
        val fps = track?.let { t ->
            val base = t.frameRateBase().takeIf { it > 0 }
            if (t.frameRate() > 0 && base != null) {
                "%.2f".format(t.frameRate().toDouble() / base)
            } else null
        }
        val codec = track?.codecName()?.let(::normalizeCodecName)
        val stats = runCatching { mediaPlayer?.media()?.info()?.statistics() }.getOrNull()
        if (debugEnabled && stats != null) {
            println(
                "[TVNext] stats inputBitrate=${stats.inputBitrate()} demuxBitrate=${stats.demuxBitrate()} " +
                    "decodedVideo=${stats.decodedVideo()} demuxBytes=${stats.demuxBytesRead()}",
            )
        }
        // Empirically (VlcRenderProbe): libVLC's f_demux_bitrate is exposed by
        // vlcj as MB/s (e.g. 2.07 while demuxBytesRead grows at ~2.2 MB/s), so
        // ×8 yields Mbps. inputBitrate is always 0 for HLS, so it is unused.
        val bitrate = stats
            ?.takeIf { it.demuxBitrate() > 0f }
            ?.let { "%.1f Mbps (demux)".format(it.demuxBitrate() * 8f) }

        // Cache the real track facts when available, so an ERROR/ENDED state
        // (no fresh media info) keeps showing the stream's actual data instead
        // of wiping the panel to "—"/"n/a".
        if (codec != null) lastCodec = codec
        if (fps != null) lastFps = fps
        if (bitrate != null) lastBitrate = bitrate

        // videoDimension() can legitimately be 0x0 (or return a stale/zero
        // pair) before the stream's geometry is known — a "0x0" plaque would be
        // misleading. Fall back to the track's declared size in that case.
        val resolution = dim
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { "${it.width}x${it.height}" }
            ?: track?.let { "${it.width()}x${it.height()}" }
            ?: lastResolution
            ?: "n/a"
        if (track != null && resolution != "n/a") lastResolution = resolution

        val requested = _decodeMode.value
        val actualMode = if (_state.value.state == PlaybackState.ERROR) {
            // The pipeline is not running: report ERROR, never claim that the
            // requested mode is actually active (e.g. HARDWARE requested but
            // unavailable must show "Факт.: ERROR", not "Факт.: HARDWARE").
            PlayerDiagnostics.MODE_ERROR
        } else {
            when (backendMode) {
                VideoDecodeMode.HARDWARE -> PlayerDiagnostics.MODE_HARDWARE
                VideoDecodeMode.SOFTWARE -> PlayerDiagnostics.MODE_SOFTWARE
                else -> if (decoderDetector.isHardwareConfirmed()) {
                    PlayerDiagnostics.MODE_HARDWARE
                } else {
                    PlayerDiagnostics.MODE_UNKNOWN
                }
            }
        }
        val fallback = requested == VideoDecodeMode.AUTO && actualMode == PlayerDiagnostics.MODE_SOFTWARE

        val decoder = if (actualMode == PlayerDiagnostics.MODE_ERROR) {
            // A hardware-failure reason is only meaningful when the hardware
            // path was the active attempt. In a SOFTWARE backend (or after an
            // AUTO→SOFTWARE fallback) softwareSeen is the norm, so showing
            // failureDetail() here would misattribute e.g. a network error to
            // "the decoder switched to software".
            if (backendMode == VideoDecodeMode.HARDWARE) {
                decoderDetector.failureDetail().let {
                    if (it == "аппаратный декодер не подтвердился") "не подтверждён" else it
                }
            } else {
                decoderDetector.decoderName()
            }
        } else {
            decoderDetector.decoderName().let { raw ->
                // In a SOFTWARE backend the decoder is guaranteed avcodec even
                // when libVLC did not emit a software marker for this codec.
                if (raw == VlcDecoderDetector.UNKNOWN_LABEL && actualMode == PlayerDiagnostics.MODE_SOFTWARE) {
                    VlcDecoderDetector.SOFTWARE_LABEL
                } else {
                    raw
                }
            }
        }

        val diagnostics = PlayerDiagnostics(
            requestedMode = requested.name,
            actualMode = actualMode,
            fallback = fallback,
            decoder = decoder,
            videoOutput = if (actualMode == PlayerDiagnostics.MODE_ERROR) {
                "—"
            } else if (actualMode == PlayerDiagnostics.MODE_HARDWARE) {
                "Direct3D11"
            } else {
                "RV32 callback"
            },
            codec = codec ?: lastCodec,
            resolution = resolution,
            fps = fps ?: lastFps,
            bitrate = bitrate ?: lastBitrate,
            transportState = currentTransportState(),
        )
        _state.update { it.copy(diagnostics = diagnostics) }
    }

    /**
     * Human-readable transport state for the diagnostics plaque: an active
     * buffering phase wins over everything (the demuxer is catching up — that
     * is normal, not a failure), then the playback state; a PLAYING position
     * frozen for several seconds without buffering is reported as a decoder
     * stall (the watchdog resolves it via the AUTO software fallback).
     */
    private fun currentTransportState(): String {
        if (isBufferingNow) return PlayerDiagnostics.STATE_BUFFERING
        return when (_state.value.state) {
            PlaybackState.PLAYING -> {
                val frozenMs = (System.nanoTime() - lastPositionMoveNanos) / 1_000_000L
                if (frozenMs > STALL_DIAGNOSTIC_THRESHOLD_MS) {
                    PlayerDiagnostics.STATE_STALL
                } else {
                    PlayerDiagnostics.STATE_PLAYING
                }
            }

            PlaybackState.BUFFERING -> PlayerDiagnostics.STATE_BUFFERING
            PlaybackState.LOADING -> PlayerDiagnostics.STATE_LOADING
            PlaybackState.PAUSED -> PlayerDiagnostics.STATE_PAUSED
            PlaybackState.ERROR -> PlayerDiagnostics.STATE_ERROR
            PlaybackState.ENDED -> "Завершено"
            else -> ""
        }
    }

    private fun normalizeCodecName(raw: String): String = when (raw.lowercase()) {
        "h264", "avc", "avc1" -> "H264"
        "hevc", "h265", "hevc1", "hvc1" -> "HEVC"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        "mpeg2", "mpeg2video" -> "MPEG2"
        else -> raw
    }

    // ---- Watchdog (timeout + silent software video + AUTO hw confirm) -------

    private fun startWatcher(gen: Int) {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            val startedAt = System.nanoTime()
            while (isActive && gen == generation) {
                delay(WATCH_INTERVAL_MS)
                if (gen != generation) return@launch
                val current = _state.value
                when (current.state) {
                    PlaybackState.LOADING,
                    PlaybackState.BUFFERING,
                    -> {
                        if (System.nanoTime() - startedAt > LOAD_TIMEOUT_NANOS) {
                            _state.update {
                                it.copy(state = PlaybackState.ERROR, error = PlaybackError.Timeout.toUserMessage())
                            }
                            runCatching { mediaPlayer?.controls()?.stop() }
                            updateDiagnostics(null)
                            return@launch
                        }
                    }

                    PlaybackState.PLAYING -> {
                        val mode = _decodeMode.value
                        val currentItem = current.item
                        if (mode.usesHwndOutput && hwFallbackAppliedFor != gen) {
                            // Fallback ONLY on solid decoder signals:
                            // - an explicit hardware-init failure, or
                            // - the decoder marker never arriving within the
                            //   confirmation window (the GPU path did not start).
                            // videoDimension() is deliberately NOT part of the
                            // decision: in HWND mode it is often 0 even while the
                            // GPU renders (measured), so using it caused bogus
                            // AUTO→software switches on healthy streams.
                            if (decoderDetector.isHardwareFailed() ||
                                (playingSinceNanos > 0L &&
                                    System.nanoTime() - playingSinceNanos > HW_CONFIRM_TIMEOUT_NANOS &&
                                    !decoderDetector.isHardwareConfirmed())
                            ) {
                                handleHwNotConfirmed(gen, mode)
                                return@launch
                            }
                        }
                        // Stall detection (hardware + non-live content):
                        // D3D11VA periodically freezes on adaptive HLS VOD —
                        // after a big seek too (the position stops advancing,
                        // the video goes white, audio+video stop together).
                        // After the stall window:
                        //  - AUTO      → fall back to the proven software path;
                        //  - HARDWARE  → rebuild the player IN PLACE on the
                        //    same hardware pipeline (the user confirmed that an
                        //    F11 window round-trip, which does exactly this
                        //    rebuild, restores the picture) — with the position
                        //    preserved. Explicit HARDWARE never switches to
                        //    software.
                        // Deliberately NOT triggered:
                        //  - while libVLC reports an active buffering phase
                        //    (real network/HLS starvation — leave it alone);
                        //  - right after a seek (the position is expected to
                        //    freeze briefly while the new segment is located).
                        if (currentItem != null &&
                            currentItem.kind != PlaybackContentKind.LIVE &&
                            mode.usesHwndOutput &&
                            hwStallFallbackAppliedFor != gen &&
                            !isBufferingNow
                        ) {
                            val pos = current.positionMs
                            if (pos != lastPositionMs) {
                                lastPositionMs = pos
                                lastPositionMoveNanos = System.nanoTime()
                            } else if (shouldRepairOnStall(
                                    input = StallDecisionInput(
                                        isNonLive = currentItem.kind != PlaybackContentKind.LIVE,
                                        isHardwareOutput = true,
                                        fallbackAlreadyApplied = false,
                                        isBuffering = isBufferingNow,
                                        positionFrozenForMs =
                                        (System.nanoTime() - lastPositionMoveNanos) / 1_000_000L,
                                    ),
                                    stallTimeoutMs = STALL_FALLBACK_TIMEOUT_NANOS / 1_000_000L,
                                )
                            ) {
                                if (debugEnabled) {
                                    println("[TVNext] watchdog: hardware decoder stalled (item ${currentItem.id})")
                                }
                                hwStallFallbackAppliedFor = gen
                                watcherJob?.cancel()
                                if (mode == VideoDecodeMode.AUTO) {
                                    fallbackToSoftware(
                                        gen = gen,
                                        item = currentItem,
                                        reason = "hardware decoder stalled on HLS, fallback to software",
                                    )
                                } else {
                                    repairHardwarePlayer(gen, currentItem)
                                }
                                return@launch
                            }
                        }
                        // Refresh diagnostics: the hardware-decoder markers may
                        // arrive after the first `playing` event.
                        mediaPlayer?.let { updateDiagnostics(readMediaInfo(it)) }
                        // Install the double-click hook once the libVLC vout
                        // created its "VLC video main" child window (retried
                        // every watchdog tick until it succeeds).
                        if (mode.usesHwndOutput) {
                            doubleClickDetector.installIfNeeded(attachedHwnd)
                        }
                        val lastFrame = lastFrameNanos
                        if (hasVideoTrack &&
                            lastFrame > 0L &&
                            System.nanoTime() - lastFrame > SILENT_VIDEO_TIMEOUT_NANOS
                        ) {
                            _state.update {
                                it.copy(
                                    state = PlaybackState.ERROR,
                                    error = "Видео не отображается. Поток, вероятно, " +
                                        "использует неподдерживаемый формат.",
                                )
                            }
                            watcherJob?.cancel()
                            return@launch
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Hardware decoding was requested but not confirmed:
     * - HARDWARE mode → strict user-facing error, never a silent fallback;
     * - AUTO mode → recreate the backend as SOFTWARE and replay the stream.
     */
    private fun handleHwNotConfirmed(gen: Int, mode: VideoDecodeMode) {
        if (gen != generation) return
        watcherJob?.cancel()
        if (mode == VideoDecodeMode.HARDWARE) {
            // One automatic HW re-attempt: transient D3D11 init failures (e.g.
            // the previous vout still releasing the GPU device during fast
            // zapping) usually succeed on the second try. This is NOT a silent
            // software fallback — the backend stays HARDWARE. Only a second
            // failure surfaces the strict user-facing error.
            if (hwRetryAttemptedFor != gen) {
                hwRetryAttemptedFor = gen
                val item = _state.value.item ?: run {
                    failHardwareUnavailable()
                    return
                }
                // Keep the position so the re-attempt continues, not restarts.
                val resumeMs = _state.value.positionMs
                val gen2 = generation + 1
                generation = gen2
                startedGeneration = 0
                hasVideoTrack = false
                lastFrameNanos = 0L
                playingSinceNanos = 0L
                lastPositionMs = -1L
                isBufferingNow = false
                lastTimeChangedNanos = 0L
                errorLogMessages.clear()
                decoderDetector.reset()
                _frames.value = null
                resetDiagnosticsCache()
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                startWatcher(gen2)
                try {
                    releaseBackend(VideoDecodeMode.HARDWARE)
                    Thread.sleep(300)
                    val mp = obtainMediaPlayer(VideoDecodeMode.HARDWARE)
                    if (gen2 != generation) return
                    applyVolumeAndMute(mp)
                    mp.media().play(item.url, *mediaOptions(item))
                    scheduleResumeRestore(gen2, item, resumeMs)
                    if (debugEnabled) {
                        println("[TVNext] HARDWARE: hardware init retry (channel ${item.id})")
                    }
                } catch (e: Throwable) {
                    if (gen2 != generation) return
                    _state.update {
                        it.copy(
                            state = PlaybackState.ERROR,
                            error = if (e is UnsatisfiedLinkError || isVlcUnavailable(e)) {
                                "Не удалось загрузить видео-движок (libVLC). " +
                                    "Убедитесь, что libVLC runtime установлен."
                            } else {
                                PlaybackError.Unknown.toUserMessage()
                            },
                        )
                    }
                }
                return
            }
            // Strict HARDWARE: after the automatic re-attempt failed, surface
            // the user-facing error. NO software fallback — the user asked for
            // hardware only (many run weak CPUs).
            runCatching { mediaPlayer?.controls()?.stop() }
            failHardwareUnavailable()
            return
        }
        // AUTO: fall back to the proven software path and keep playing.
        val item = _state.value.item ?: return
        fallbackToSoftware(
            gen = gen,
            item = item,
            reason = "hw decoder not usable, fallback to software",
        )
    }

    /**
     * AUTO-mode software fallback: a frozen/never-confirmed hardware stream is
     * handed to the proven software pipeline. The item's id is remembered so
     * the next play() starts directly in software (no repeat of the stall
     * cycle). Only reached in AUTO — explicit HARDWARE never comes here.
     */
    private fun fallbackToSoftware(
        gen: Int,
        item: PlaybackItem,
        reason: String,
    ) {
        if (gen != generation) return
        // Keep the resume position BEFORE the backend is torn down, so the
        // rebuilt software player continues from the same spot instead of
        // restarting the film from zero.
        val resumeMs = _state.value.positionMs
        recreatePlayer(gen, item, VideoDecodeMode.SOFTWARE, resumeMs, reason)
    }

    /**
     * In-place HARDWARE repair (used when D3D11VA/vout froze — e.g. a white
     * screen after a big HLS seek): the AWT canvas is re-created, giving libVLC
     * a brand-new HWND + D3D11 swapchain, and the surface remount rebuilds the
     * player with the position preserved (exactly the F11 window round-trip
     * that the user confirmed restores the picture). Explicit HARDWARE never
     * switches to software. Bounded (per item) so a fundamentally broken
     * stream cannot repair-loop.
     */
    private fun repairHardwarePlayer(gen: Int, item: PlaybackItem) {
        if (gen != generation) return
        val key = item.id
        val attempts = synchronized(stallRepairAttempts) {
            val next = (stallRepairAttempts[key] ?: 0) + 1
            stallRepairAttempts[key] = next
            next
        }
        if (attempts > MAX_STALL_REPAIRS) {
            synchronized(stallRepairAttempts) { stallRepairAttempts.remove(key) }
            watcherJob?.cancel()
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = "Аппаратное декодирование нестабильно на этом потоке: " +
                        "видео зависает после перемотки. Попробуйте снова или " +
                        "выберите другой режим декодирования.",
                )
            }
            updateDiagnostics(null)
            return
        }
        if (debugEnabled) {
            println("[TVNext] HARDWARE repair: re-creating video canvas (item ${item.id})")
        }
        // The canvas now lives in the controller (PlayerSurface reads it via
        // videoCanvas): swap it and bump the revision so the surface re-embeds
        // the fresh canvas (new HWND + D3D11 swapchain).
        hardwareCanvas = newVideoCanvas()
        attachedHwnd = 0L
        hardwareCanvasRevision.update { it + 1 }
    }

    /**
     * Recreates the backend ([mode]) for the same item, preserving the resume
     * position. Shared by the AUTO software fallback, the HARDWARE in-place
     * repair and the surface-rebuild path. Guarded by [gen] so a superseded
     * rebuild can never touch the current player.
     */
    private fun recreatePlayer(
        gen: Int,
        item: PlaybackItem,
        mode: VideoDecodeMode,
        resumeMs: Long,
        reason: String,
    ) {
        if (gen != generation) return
        if (mode == VideoDecodeMode.SOFTWARE) rememberSoftwareOnlyChannel(item.id)
        val gen2 = generation + 1
        generation = gen2
        hwFallbackAppliedFor = gen2
        startedGeneration = 0
        hasVideoTrack = false
        lastFrameNanos = 0L
        playingSinceNanos = 0L
        lastPositionMs = -1L
        hwStallFallbackAppliedFor = -1
        isBufferingNow = false
        lastTimeChangedNanos = 0L
        errorLogMessages.clear()
        decoderDetector.reset()
        // Pin the detector to software: buffered log messages from the released
        // hardware backend must not make the diagnostics report D3D11VA while
        // the recreated backend is actually the software callback pipeline.
        if (mode == VideoDecodeMode.SOFTWARE) {
            decoderDetector.forceSoftware()
        }
        _frames.value = null
        resetDiagnosticsCache()
        _state.update {
            it.copy(
                state = PlaybackState.LOADING,
                error = null,
                hardwareDecode = mode.usesHwndOutput,
            )
        }
        startWatcher(gen2)
        try {
            releaseBackend(mode)
            // Give libVLC's audio output (mmdevice) time to fully release the
            // device before the recreated backend claims it again — otherwise
            // the rebuilt player may start without audio.
            Thread.sleep(300)
            val mp = obtainMediaPlayer(mode)
            if (gen2 != generation) return
            applyVolumeAndMute(mp)
            mp.media().play(item.url, *mediaOptions(item))
            if (debugEnabled) {
                println("[TVNext] recreate mode=$mode reason=$reason (item ${item.id})")
            }
            scheduleResumeRestore(gen2, item, resumeMs)
        } catch (e: Throwable) {
            if (gen2 != generation) return
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = if (e is UnsatisfiedLinkError || isVlcUnavailable(e)) {
                        "Не удалось загрузить видео-движок (libVLC). " +
                            "Убедитесь, что libVLC runtime установлен."
                    } else {
                        PlaybackError.Unknown.toUserMessage()
                    },
                )
            }
        }
    }

    /**
     * True (and counted) while the per-item auto-restart budget is not
     * exhausted. Bounded so a stream that dies on every restart finally
     * surfaces the error instead of looping forever.
     */
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

    /**
     * Restarts a VOD/ARCHIVE stream that died mid-playback (error / premature
     * end): same decode mode, position preserved — the film continues from
     * where it was, never from zero, never switched to software.
     */
    private fun autoRestartVod(item: PlaybackItem) {
        val resumeMs = _state.value.positionMs
        recreatePlayer(
            gen = generation,
            item = item,
            mode = _decodeMode.value,
            resumeMs = resumeMs,
            reason = "VOD stream lost mid-playback, restarted in place",
        )
    }

    /**
     * Seeks a freshly (re)created player back to [resumeMs] shortly after the
     * stream starts, so a forced rebuild (surface remount, AUTO stall-fallback,
     * hardware-init retry) continues the film — never restarts it. Guarded by
     * [gen]/[item.id] so it can never seek a freed or unrelated player.
     */
    private fun scheduleResumeRestore(gen: Int, item: PlaybackItem, resumeMs: Long) {
        if (resumeMs <= 0L) return
        scope.launch {
            delay(RESUME_AFTER_REBUILD_DELAY_MS)
            if (gen == generation && _state.value.item?.id == item.id) {
                if (debugEnabled) println("[TVNext] resume restore → ${resumeMs} ms (item ${item.id})")
                seekTo(resumeMs)
            }
        }
    }

    /** Strict HARDWARE-mode error (never a silent software fallback). */
    private fun failHardwareUnavailable() {
        runCatching { mediaPlayer?.controls()?.stop() }
        _state.update {
            it.copy(
                state = PlaybackState.ERROR,
                error = "Аппаратное декодирование недоступно" +
                    " (${decoderDetector.failureDetail()}). " +
                    "Проверьте поддержку H.264/HEVC видеокартой и драйвером " +
                    "или выберите «Программное» декодирование.",
            )
        }
        updateDiagnostics(null)
    }

    private fun releaseBackend(nextMode: VideoDecodeMode? = null) {
        doubleClickDetector.uninstall()
        releaseMediaPlayer()
        val factory = mediaPlayerFactory
        mediaPlayerFactory = null
        backendMode = null
        // Keep the hardware surface active when the next mode is also a
        // hardware mode: unmounting the SwingPanel (by flipping the flag to
        // false) destroys the canvas HWND, and a later play() would re-attach
        // libVLC to hwnd=0 → grey video with working audio.
        if (nextMode == null || !nextMode.usesHwndOutput) {
            _hardwareSurfaceActive.value = false
        }
        if (factory != null) {
            // Same rule as player.release(): factory.release() must not run on
            // the UI thread (it can block for seconds on an active network
            // stream). Released on the dedicated release dispatcher.
            releaseScope.launch {
                runCatching { factory.release() }
            }
        }
        // The JNA-callback references (native log, event listener, surfaces) are
        // intentionally NOT cleared here: libVLC's log/event threads can still
        // dispatch a few pending callbacks asynchronously AFTER factory.release()
        // returns, and clearing the strong refs lets the GC collect them while
        // the native side still invokes them → "callback object has been garbage
        // collected" (and, in the worst case, a hard access violation). The
        // refs are simply overwritten on the next createPlayer/setupNativeLog,
        // which is a tiny, safe leak.
    }

    private fun releaseMediaPlayer() {
        // The vout thread dies with the player; a hook left on it would be
        // stale (clicks never observed again). Remove it so the next playback
        // reinstalls on the new vout thread.
        doubleClickDetector.uninstall()
        // A pending seek-retry must never touch the freed player (hard crash).
        seekRetryJob?.cancel()
        seekRetryJob = null
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            // CRITICAL: vlcj's MediaPlayer.release() can block the calling
            // thread for seconds (libVLC stops the vout and the network
            // stream). This is called from Compose's surface detach/attach —
            // i.e. on the UI thread — during a fullscreen F11 window switch,
            // where a synchronous release froze the whole UI for ~17 s.
            // Releasing on the dedicated release dispatcher keeps the switch
            // instant; the new player is created independently (mediaPlayer
            // was already nulled, so there is no race). The release scope is
            // never cancelled by [release], so these calls always run.
            releaseScope.launch {
                runCatching { player.release() }
            }
        }
        // The native log listener is NOT released/cleared here on purpose:
        // libVLC may still dispatch pending log messages asynchronously, and
        // dropping the strong JNA-callback reference while the native side can
        // still invoke it produces "callback object has been garbage collected"
        // and, after repeated factory rebuilds, a hard access violation. The
        // factory (which owns the native callback) is released in
        // [releaseBackend]; only then are the callback references cleared.
    }

    private fun registerShutdownHook() {
        if (shutdownHook != null) return
        val hook = Thread {
            runCatching { mediaPlayerFactory?.release() }
        }
        shutdownHook = hook
        Runtime.getRuntime().addShutdownHook(hook)
    }

    private fun factoryArguments(mode: VideoDecodeMode): Array<String> = buildList {
        add("--no-video-title-show")
        add("--avcodec-hw=${mode.avcodecHw}")
        if (mode.usesHwndOutput) {
            // VideoLAN docs: a `direct3d11` vout REQUIRES the d3d11va decoder
            // (d3d11 vout consumes frames only from d3d11va) — requesting
            // dxva2 while vout=direct3d11 is silently ignored. That d3d11
            // vout+d3d11va combination is exactly what froze on adaptive HLS
            // (quality switches → "Reinit context" → white screen / stalled
            // decoder / broken seek). The proven stable HARDWARE pairing on
            // Windows is DXVA2 decoder + Direct3D9 vout: both re-create their
            // context cleanly across HLS seeks and quality switches.
            add("--vout=direct3d9")
        }
        add("--http-user-agent=$USER_AGENT")
        add("--http-reconnect")
        add("--no-osd")
        add(if (debugEnabled) "--verbose=2" else "--verbose=1")
    }.toTypedArray()

    /**
     * Per-media playback options.
     *
     * - LIVE keeps the full 8 s buffer and `--clock-synchro=0` (choppy HLS live
     *   needs both). VOD/ARCHIVE use the 8 s buffer (ТЗ: never shrunk for a
     *   faster start) and NORMAL A/V synchronisation: with clock-synchro
     *   disabled, audio started seconds before the picture while the video
     *   decoder was warming up, which read as "sound plays, picture frozen".
     *   Right after a surface rebuild (F11 / window round-trip) an even smaller
     *   buffer brings the picture back fast on the already-warm stream.
     * - On a HARDWARE backend the decoder is DXVA2 at the factory level (see
     *   [VideoDecodeMode.avcodecHw]). NOTE: adaptive quality is deliberately
     *   left to libVLC's own logic (no `--adaptive-logic`): pinning the HIGHEST
     *   variant made the demuxer outrun slow VOD servers and re-buffer every
     *   few seconds (`buffering START` bursts in the logs). Quality switches
     *   ("Reinit context") are handled by the watchdog repair instead.
     */
    private fun mediaOptions(item: PlaybackItem): Array<String> = buildList {
        val cachingMs = when {
            rebuildStartedAtNanos != 0L -> REBUILD_NETWORK_CACHING_MS
            item.kind == PlaybackContentKind.LIVE -> networkCachingMs
            item.kind == PlaybackContentKind.VOD -> VOD_NETWORK_CACHING_MS
            else -> ARCHIVE_NETWORK_CACHING_MS
        }
        add("--network-caching=$cachingMs")
        if (item.kind == PlaybackContentKind.LIVE) {
            add("--clock-synchro=0")
        }
    }.toTypedArray()

    private fun isVlcUnavailable(e: Throwable): Boolean = when (e) {
        is VlcInitializationException -> true
        else -> e.message?.lowercase()?.let {
            "libvlc" in it || "vlc" in it || "cannot find" in it
        } ?: false
    }

    private val debugEnabled: Boolean = System.getProperty("tvnext.player.debug") == "true"

    private companion object {
        @Volatile
        var shutdownHook: Thread? = null

        const val WATCH_INTERVAL_MS = 1_000L
        const val LOAD_TIMEOUT_NANOS = 20_000_000_000L
        const val SILENT_VIDEO_TIMEOUT_NANOS = 7_000_000_000L
        const val HW_CONFIRM_TIMEOUT_NANOS = 6_000_000_000L

        /**
         * How long a PLAYING position may stay frozen before the watchdog hands
         * the stream to the software pipeline (measured stalls lasted 10-15 s;
         * 6 s catches them fast while still ignoring ordinary buffering, which
         * reports BUFFERING, not PLAYING, and is suppressed here anyway).
         */
        const val STALL_FALLBACK_TIMEOUT_NANOS = 6_000_000_000L

        /** Frozen-position threshold for the diagnostics plaque's STALL label. */
        const val STALL_DIAGNOSTIC_THRESHOLD_MS = 5_000L

        /** Max in-place HARDWARE repairs per item before a user-facing error. */
        const val MAX_STALL_REPAIRS = 2

        /** Max auto-restarts of a mid-playback VOD/ARCHIVE stream per item. */
        const val MAX_AUTO_RESTARTS = 3

        /**
         * Positions within this margin of the known duration count as "the
         * film really ended" — an auto-restart is NOT triggered there.
         */
        const val END_RESTART_MARGIN_MS = 30_000L
        const val MAX_LOG_MESSAGES = 50
        const val MAX_LOG_RING = 200
        const val MAX_SOFTWARE_ONLY_CHANNELS = 200

        /**
         * Bound for the retained JNA-callback list. This is a HARD safety
         * ceiling, not a small ring: native callbacks can arrive noticeably
         * later than the corresponding `factory.release()` (libVLC's log/event
         * threads drain asynchronously), so GC'ing a referenced callback means
         * an access violation on the next dispatch. ~4 entries are added per
         * backend rebuild → 100 000 entries covers ~25 000 rebuilds (a
         * month-long heavy-zapping session), costs ~0.8 MB, and guarantees the
         * JVM side still never grows without bound.
         */
        const val MAX_RETAINED_CALLBACKS = 100_000

        /** Wait before restoring the position captured before a surface rebuild. */
        const val RESUME_AFTER_REBUILD_DELAY_MS = 400L

        /** Default network buffer (8 s — the long-standing production value). */
        const val DEFAULT_NETWORK_CACHING_MS = 8_000L

        /**
         * VOD buffer (ТЗ: 8 s, never shrunk for a faster start). A small buffer
         * re-buffers every few seconds on moderately slow/uneven HLS; 8 s is
         * the same proven value LIVE uses and absorbs short network hiccups
         * without audible/video stutters.
         */
        const val VOD_NETWORK_CACHING_MS = 8_000L

        /** Archive (catch-up) buffer: catch-up HLS needs the same cushion. */
        const val ARCHIVE_NETWORK_CACHING_MS = 8_000L

        /**
         * Reduced buffer used for the restart right after a surface rebuild
         * (F11 / window switch): the stream is already warm, so a small buffer
         * brings the picture back in ~1 s.
         */
        const val REBUILD_NETWORK_CACHING_MS = 2_000L

        /**
         * libVLC buffering event level at which the pre-roll is COMPLETE and
         * playback resumes (also the signal that a network-buffering phase is
         * over — the UI returns to PLAYING and the watchdog may resume stall
         * detection).
         */
        const val BUFFERED_CACHE_LEVEL = 100f

        /**
         * Minimum interval between position updates pushed to Compose
         * (≈6-7 updates/s). The UI progress does not need every VLC event, and
         * each state update recomposes the whole player panel; actual playback
         * is never affected.
         */
        const val TIME_CHANGED_THROTTLE_NANOS = 150_000_000L

        /**
         * How long to wait for the canvas HWND after a window switch before
         * falling back to the software path (2 s — NOT 15 s, which made
         * fullscreen toggles stall for ~17 s on any machine).
         */
        const val HWND_READY_POLLS = 40

        /**
         * HARDWARE mode waits longer for the HWND (a fullscreen round-trip can
         * briefly detach the canvas) but never falls back to software: after
         * this window a user-visible error is surfaced instead.
         */
        const val HWND_READY_POLLS_HARDWARE = 120

        const val HWND_READY_POLL_MS = 50L

        /** Wait before the seek-verification retry fires (gentle, no stutter). */
        const val SEEK_RETRY_DELAY_MS = 1_200L

        /** Position drift accepted before the paused seek-retry kicks in. */
        const val SEEK_RETRY_THRESHOLD_MS = 10_000L

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}

/** Marker for "libVLC runtime could not be loaded". */
internal class VlcInitializationException(cause: Throwable) : RuntimeException(cause)

/**
 * Pure inputs to the stall/repair decision (kept OUT of the watcher so the
 * logic is unit-testable). See [shouldRepairOnStall].
 */
internal data class StallDecisionInput(
    val isNonLive: Boolean,
    val isHardwareOutput: Boolean,
    /** True when a stall repair/fallback already ran for this play attempt. */
    val fallbackAlreadyApplied: Boolean,
    /** True while libVLC reports an active network-buffering phase. */
    val isBuffering: Boolean,
    /** How long the PLAYING position has been frozen, in ms. */
    val positionFrozenForMs: Long,
)

/**
 * Decides whether a frozen HARDWARE stream needs an intervention. Deliberately
 * conservative — the goal is to distinguish a real D3D11VA/vout stall from
 * everything else:
 *
 *  - only non-live content (LIVE hardware is stable);
 *  - only while an active network-buffering phase is NOT reported — during
 *    real HLS starvation the player must be left alone to recover;
 *  - only after the position has been frozen for [stallTimeoutMs] at PLAYING
 *    (a just-performed seek resets the tracker, so seeking is never mistaken
 *    for a stall);
 *  - only once per play attempt (no repair loop).
 *
 * The ACTION is chosen by the caller: AUTO → software fallback; HARDWARE →
 * in-place hardware rebuild (vout/decoder re-created, same GPU pipeline) —
 * never a switch to software in explicit HARDWARE mode.
 */
internal fun shouldRepairOnStall(
    input: StallDecisionInput,
    stallTimeoutMs: Long,
): Boolean = input.isNonLive &&
    input.isHardwareOutput &&
    !input.fallbackAlreadyApplied &&
    !input.isBuffering &&
    input.positionFrozenForMs >= stallTimeoutMs

/** Fresh video canvas for a (new) window — see [DesktopPlaybackController.attachSurface]. */
internal fun newVideoCanvas(): java.awt.Canvas = java.awt.Canvas().apply {
    background = java.awt.Color.BLACK
    isFocusable = false
}

/** Native window handle (HWND) of an AWT component, 0 if unavailable. */
internal fun componentId(component: java.awt.Component): Long = try {
    com.sun.jna.Native.getComponentID(component)
} catch (e: Throwable) {
    0L
}

/**
 * Maps libVLC failures onto the platform-agnostic [PlaybackError] so the
 * common UI renders stable messages. The `error` event carries no details, so
 * the messages collected from the native log drive the classification.
 */
internal fun classifyVlcError(
    messages: List<String>,
    hadVideoTrack: Boolean,
): PlaybackError {
    val haystack = messages.joinToString("\n") { it.lowercase() }
    if (hadVideoTrack) {
        return PlaybackError.Decoder
    }
    return when {
        haystack.contains("no suitable demux") ||
            haystack.contains("unsupported") ||
            haystack.contains("unknown format") ||
            haystack.contains("cannot guess") ||
            haystack.contains("no demux module") ||
            haystack.contains("incorrect header") ||
            haystack.contains("invalid data")
        -> PlaybackError.UnsupportedFormat

        haystack.contains("connection") ||
            haystack.contains("timed out") ||
            haystack.contains("timeout") ||
            haystack.contains("refused") ||
            haystack.contains("404") ||
            haystack.contains("403") ||
            haystack.contains("dns") ||
            haystack.contains("host not found") ||
            haystack.contains("could not connect") ||
            haystack.contains("network is unreachable") ||
            haystack.contains("could not resolve") ||
            haystack.contains("resolve host") ||
            haystack.contains("getaddrinfo") ||
            haystack.contains("name or service not known") ||
            haystack.contains("ssl") ||
            haystack.contains("tls")
        -> PlaybackError.Network

        else -> PlaybackError.Unknown
    }
}

