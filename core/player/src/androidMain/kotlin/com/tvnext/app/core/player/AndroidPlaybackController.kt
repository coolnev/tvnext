package com.tvnext.app.core.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android playback backed by Media3 ExoPlayer.
 *
 * The ExoPlayer instance is created lazily on the first [play] call and reused
 * for every subsequent item, so it survives tab switches, recompositions and
 * player surface re-attachments. It is provided as a Koin singleton (like the
 * desktop controller) and is intentionally never created inside a Composable.
 *
 * The player itself is exposed through [player] so the Android [PlayerSurface]
 * can bind a Media3 PlayerView to it without owning the player lifecycle.
 *
 * IPTV-oriented buffering:
 * - LIVE streams use a SHORT buffer (low live latency), re-buffering quickly;
 * - VOD/ARCHIVE use a LARGER buffer (smooth scrubbing/playback on weak links).
 * The [LoadControl] is baked into the ExoPlayer at build time (Media3 1.11 has
 * no runtime `setLoadControl` on the interface); when the content kind changes
 * (LIVE → VOD) the player is rebuilt with the matching buffer policy. The
 * rebuild happens only on a KIND change, never on panel/tab toggles.
 *
 * Resilience (mirrors the Windows VOD/ARCHIVE behaviour, bounded):
 * - a transient mid-playback error on VOD/ARCHIVE restarts the stream from the
 *   current position, at most [MAX_AUTO_RESTARTS] times per item;
 * - a genuinely unsupported format is never retried.
 */
class AndroidPlaybackController(
    context: Context,
    channelsRepository: com.tvnext.app.core.iptv.data.ChannelsRepository,
    archiveRepository: com.tvnext.app.core.iptv.archive.ArchiveRepository,
) : PlaybackController, TimeShiftController {
    private val context = context.applicationContext
    private val archiveResume = AndroidArchiveResumeStore(this.context)
    private var retryJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var stablePlaybackJob: Job? = null
    private var generation = 0L
    private var desiredVolume = 1f
    private var muted = false
    private var foreground = true
    private var globalDecodeMode = VideoDecodeMode.AUTO
    private var requestedDecodeMode = VideoDecodeMode.AUTO
    private var decoderPath = AndroidVideoDecoderPath.HARDWARE
    private var activeDecoderName: String? = null
    private var activeDecodePath: AndroidVideoDecoderPath? = null
    private var suspendedState: PlayerState? = null

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback must run on Main" }
    }

    /** Called from Android's existing SettingsRepository observer on the main thread. */
    fun updateDecodeMode(mode: VideoDecodeMode) {
        checkMainThread()
        if (globalDecodeMode == mode) return
        globalDecodeMode = mode
        val item = _state.value.item ?: return
        val nextRequested = decodeModeFor(item)
        if (nextRequested == requestedDecodeMode || !foreground) return
        val paused = _state.value.state == PlaybackState.PAUSED
        val position = _player.value?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
        play(item) // mode changes require a newly configured ExoPlayer
        if (item.kind != PlaybackContentKind.LIVE && position > 0L) seekLocal(position)
        if (paused) pause()
    }

    private fun decodeModeFor(item: PlaybackItem): VideoDecodeMode =
        if (item.kind == PlaybackContentKind.VOD) globalDecodeMode
        else resolveAndroidVideoDecodeMode(globalDecodeMode, readChannelPreferences(context, item.id).decodeMode)

    private fun cancelRetry() {
        generation++
        retryJob?.cancel()
        retryJob = null
        bufferingWatchdog?.cancel()
        bufferingWatchdog = null
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
    }

    /**
     * Owns only the position ticker below — never used for anything ExoPlayer
     * needs off the main thread (the player itself must stay on Main, Media3
     * is not thread-safe). Cancelled entirely in [release].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Player.Listener callbacks (onPlaybackStateChanged/onIsPlayingChanged)
     * only fire on state TRANSITIONS — ExoPlayer does not call them once a
     * second during smooth playback. Without this, [PlayerState.positionMs]
     * (the VOD/Archive seek bar) would only update at buffering/pause edges
     * and otherwise sit frozen. Ticks at 1 Hz, and ONLY while something with a
     * seek bar (VOD/Archive, not LIVE) is actually playing — started/stopped
     * from [listener] below, so it costs nothing while paused, stopped, or on
     * a LIVE channel (no seek bar to update).
     */
    private var positionTickerJob: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    init { scope.launch { state.collect {
        AndroidPlaybackSession.record(it)
        archiveResume.record(it)
    } } }

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    internal val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    /**
     * Media3 [MediaSession] bridging the ExoPlayer to external media controls
     * (hardware media buttons, Android TV "Play/Pause"). Created lazily together
     * with the ExoPlayer (on the first [play]) and released in [release].
     */
    private var mediaSession: MediaSession? = null
    private val timeShift = AndroidTimeShiftSession(channelsRepository, archiveRepository, scope,
        current = { _state.value }, open = ::play, localSeek = { position ->
            if (_player.value?.isCurrentMediaItemSeekable == true) { seekLocal(position); true } else false
        }, pause = ::pause)
    override val timeShiftState: StateFlow<TimeShiftState> = timeShift.state.asStateFlow()
    override fun seekToTime(epochMs: Long) { checkMainThread(); refreshPositionOnly(); timeShift.seekTo(epochMs) }
    override fun returnToLive() { seekToTime(System.currentTimeMillis()) }

    /** Content kind the current ExoPlayer was built with (buffering policy). */
    @Volatile
    private var activeKind: PlaybackContentKind? = null

    /** Bounded auto-restarts per item id (cleared when playback genuinely resumes). */
    private val autoRestartCounts: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())

    /**
     * Live measurements for the diagnostics panel (key I). HLS manifests often
     * omit frame-rate/bitrate in the track format, so the real values are
     * sampled from the Media3 analytics events: the input video format (fps)
     * and the bandwidth samples (transfer bitrate).
     */
    @Volatile
    private var measuredFps: String? = null
    @Volatile
    private var measuredBitrate: String? = null

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            activeDecoderName = decoderName
            refreshState()
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            if (format.frameRate != C.RATE_UNSET && format.frameRate > 0f) {
                measuredFps = java.lang.String.format(java.util.Locale.US, "%.2f", format.frameRate)
                refreshState()
            }
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            if (bitrateEstimate > 0L) {
                measuredBitrate =
                    java.lang.String.format(java.util.Locale.US, "%.1f Mbps", bitrateEstimate / 1_000_000.0)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshState()
            watchLiveBuffering()
            if (playbackState == Player.STATE_ENDED) timeShift.ended()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshState()
            updatePositionTicker(isPlaying)
            watchLiveBuffering()
            stablePlaybackJob?.cancel()
            if (isPlaying) {
                val id = _state.value.item?.id
                val token = generation
                stablePlaybackJob = scope.launch {
                    delay(30_000L)
                    if (id != null && generation == token && _player.value?.isPlaying == true) {
                        autoRestartCounts.remove(id) // recovered for 30 s; reset retry budget
                    }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            refreshPositionOnly()
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = _state.value.item
            val category = error.toPlaybackError()
            val failedVideoDecoder = error is ExoPlaybackException &&
                (error.rendererFormat?.sampleMimeType?.startsWith("video/", ignoreCase = true) == true ||
                    error.rendererName?.contains("video", ignoreCase = true) == true)
            val fallback = if (failedVideoDecoder)
                nextAndroidVideoDecoderPath(requestedDecodeMode, decoderPath, category)
                else null
            if (item != null && fallback != null && foreground) {
                // One-way AUTO transition only. Explicit HARDWARE never falls back.
                decoderPath = fallback
                cancelRetry()
                val requestGeneration = generation
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                retryJob = scope.launch {
                    delay(100L)
                    if (generation != requestGeneration || !foreground || _state.value.item != item) return@launch
                    retryJob = null
                    autoRestart(item, category)
                }
                return
            }
            // Resilience for BOTH LIVE and VOD/ARCHIVE: a transient failure
            // (network hiccup, HLS tail, provider hiccup) is auto-recovered by
            // re-preparing the stream, at most [MAX_AUTO_RESTARTS] times per
            // item. VOD/ARCHIVE resumes from the last position; LIVE simply
            // restarts (there is no meaningful position). A truly unsupported
            // format is never retried — it cannot succeed on a second try.
            if (item != null &&
                category != PlaybackError.UnsupportedFormat &&
                category != PlaybackError.Decoder &&
                canAutoRestart(item.id)
            ) {
                // Give the network/provider a moment to recover before the
                // next attempt, instead of hammering a dead endpoint.
                retryJob?.cancel()
                val requestGeneration = generation
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                retryJob = scope.launch {
                    delay(RESTART_RETRY_DELAY_MS * (autoRestartCounts[item.id] ?: 1).coerceAtMost(8))
                    // The user may have switched channels (or closed the
                    // player) while we were waiting — do not resurrect a
                    // stale item.
                    if (generation != requestGeneration || !foreground || _state.value.item != item) return@launch
                    retryJob = null
                    autoRestart(item, category)
                }
                return
            }
            if (item?.kind == PlaybackContentKind.LIVE && foreground &&
                category != PlaybackError.UnsupportedFormat && category != PlaybackError.Decoder) {
                // Keep trying slowly after the short retry budget has elapsed.
                // A bad connection must not demand a manual Close/Retry press.
                val token = generation
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                retryJob = scope.launch {
                    delay(60_000L)
                    if (generation == token && foreground && _state.value.item == item) {
                        autoRestartCounts.remove(item.id)
                        autoRestart(item, category)
                    }
                }
                return
            }
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = category.toUserMessage(),
                )
            }
        }
    }

    override fun play(item: PlaybackItem) {
        AndroidPlaybackSession.record(_state.value)
        checkMainThread()
        if (!timeShift.opening) timeShift.cancel()
        cancelRetry()
        autoRestartCounts.clear()
        requestedDecodeMode = decodeModeFor(item)
        decoderPath = androidVideoDecoderPlan(requestedDecodeMode).first()
        activeDecoderName = null
        positionTickerJob?.cancel()
        positionTickerJob = null
        if (!foreground) {
            suspendedState = PlayerState(item = item, state = PlaybackState.PAUSED,
                volume = desiredVolume, isMuted = muted)
            _state.value = suspendedState!!
            return
        }
        suspendedState = null
        measuredFps = null
        measuredBitrate = null
        activeDecoderName = null
        _state.value = PlayerState(
            state = PlaybackState.LOADING,
            item = item,
            error = null,
            volume = desiredVolume,
            isMuted = muted,
        )
        try {
            val exoPlayer = ensurePlayer(item.kind)
            applyChannelTrackPreferences(exoPlayer,
                if (item.kind == PlaybackContentKind.VOD) AndroidChannelPreferences()
                else readChannelPreferences(context, item.id))
            exoPlayer.setMediaItem(MediaItem.Builder().setMediaId(item.id).setUri(item.url).build())
            exoPlayer.prepare()
            if (item.kind == PlaybackContentKind.ARCHIVE && !timeShift.opening) {
                archiveResume.resume(item)?.let { exoPlayer.seekTo(it) }
            }
            exoPlayer.play()
            timeShift.itemOpened(item)
        } catch (e: Exception) {
            _state.value = PlayerState(
                state = PlaybackState.ERROR,
                item = item,
                error = PlaybackError.Unknown.toUserMessage(),
                volume = desiredVolume,
                isMuted = muted,
            )
        }
    }

    override fun pause() {
        checkMainThread()
        cancelRetry()
        _player.value?.pause()
        if (_state.value.item != null) _state.update { it.copy(state = PlaybackState.PAUSED) }
    }

    override fun resume() {
        checkMainThread()
        if (!foreground) return
        val exo = _player.value
        val item = _state.value.item ?: return
        if (exo == null || exo.playerError != null || exo.playbackState == Player.STATE_IDLE) {
            val position = _state.value.positionMs
            play(item)
            if (item.kind != PlaybackContentKind.LIVE) seekLocal(position)
        } else {
            exo.play()
        }
    }

    override fun stop() {
        checkMainThread()
        timeShift.cancel()
        cancelRetry()
        suspendedState = null
        positionTickerJob?.cancel()
        positionTickerJob = null
        autoRestartCounts.clear()
        _state.value = PlayerState(volume = desiredVolume, isMuted = muted)
        _player.value?.let { exoPlayer ->
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    override fun seekTo(positionMs: Long) {
        checkMainThread()
        val item = _state.value.item
        if (item?.kind == PlaybackContentKind.ARCHIVE && item.archiveEpgAnchorMs != null) {
            seekToTime(item.archiveEpgAnchorMs + positionMs)
        } else seekLocal(positionMs)
    }

    private fun seekLocal(positionMs: Long) {
        checkMainThread()
        val exo = _player.value ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val target = positionMs.coerceAtLeast(0L).let { if (duration != null) it.coerceAtMost(duration) else it }
        exo.seekTo(target)
        refreshPositionOnly()
    }

    override fun seekBy(deltaMs: Long) {
        checkMainThread()
        if (_state.value.item?.kind != PlaybackContentKind.VOD) {
            refreshPositionOnly()
            timeShift.seekBy(deltaMs)
            return
        }
        val exoPlayer = _player.value ?: return
        val actual = exoPlayer.currentPosition.coerceAtLeast(0L)
        seekTo((actual + deltaMs).coerceAtLeast(0L))
    }

    override fun setVolume(volume: Float) {
        checkMainThread()
        val clamped = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return
        desiredVolume = clamped
        _player.value?.volume = if (muted) 0f else clamped
        _state.update { it.copy(volume = clamped) }
    }

    override fun setMuted(muted: Boolean) {
        checkMainThread()
        this.muted = muted
        val exoPlayer = _player.value
        if (exoPlayer != null) {
            exoPlayer.volume = if (muted) 0f else _state.value.volume.coerceIn(0f, 1f)
        }
        _state.update { it.copy(isMuted = muted) }
    }

    /** Activity/ViewModel owns foreground policy; surfaces only bind the player. */
    fun enterBackground() {
        checkMainThread()
        if (!foreground) return
        foreground = false
        timeShift.cancel()
        cancelRetry()
        refreshPositionOnly()
        suspendedState = _state.value.takeIf { it.item != null }
        disposePlayer()
        _state.update { if (it.item == null) it else it.copy(state = PlaybackState.PAUSED) }
    }

    fun enterForeground() {
        checkMainThread()
        if (foreground) return
        foreground = true
        val saved = suspendedState ?: return
        suspendedState = null
        val item = saved.item ?: return
        play(item)
        if (item.kind != PlaybackContentKind.LIVE) {
            seekLocal(saved.positionMs)
            pause()
        } else if (saved.state == PlaybackState.PAUSED || saved.state == PlaybackState.ENDED) {
            pause()
        }
    }

    override fun release() {
        checkMainThread()
        timeShift.cancel()
        cancelRetry()
        suspendedState = null
        autoRestartCounts.clear()
        measuredFps = null
        measuredBitrate = null
        disposePlayer()
        scope.coroutineContext.cancelChildren()
        _state.value = PlayerState(volume = desiredVolume, isMuted = muted)
    }

    private fun disposePlayer() {
        positionTickerJob?.cancel()
        positionTickerJob = null
        mediaSession?.release()
        mediaSession = null
        val exo = _player.value
        _player.value = null
        activeKind = null
        activeDecodePath = null
        activeDecoderName = null
        exo?.removeListener(listener)
        exo?.removeAnalyticsListener(analyticsListener)
        exo?.release()
    }

    /**
     * Returns the current ExoPlayer, building it (with the [LoadControl] for
     * [kind]) on first use. A KIND change rebuilds the player so the buffering
     * policy matches the content; same-kind requests reuse the instance.
     */
    private fun ensurePlayer(kind: PlaybackContentKind): ExoPlayer {
        val existing = _player.value
        if (existing != null && activeKind == kind && activeDecodePath == decoderPath) return existing
        if (existing != null) disposePlayer()
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(androidVideoCodecSelector(decoderPath))
            .setEnableDecoderFallback(true) // within the SAME selected video backend only
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val newPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setLoadControl(loadControlFor(kind))
            .build()
        // IPTV streams are network-delivered: WAKE_MODE_NETWORK holds a WiFi
        // lock ONLY while actually playing (ExoPlayer manages the acquire/
        // release itself), which prevents the radio's power-saving mode from
        // causing stutters/rebuffers mid-stream. It costs nothing while
        // paused/stopped — the lock is released automatically.
        newPlayer.setWakeMode(C.WAKE_MODE_NETWORK)
        newPlayer.volume = if (muted) 0f else desiredVolume
        newPlayer.addListener(listener)
        newPlayer.addAnalyticsListener(analyticsListener)
        _player.value = newPlayer
        activeKind = kind
        activeDecodePath = decoderPath
        // External media controls become available once the player exists
        // (i.e. after the first play()). Media3 wires the callbacks itself.
        runCatching { mediaSession = MediaSession.Builder(context, newPlayer).build() }
        return newPlayer
    }

    private fun createMediaSourceFactory(): DefaultMediaSourceFactory {
        // IPTV streams are picky about the HTTP client: many hosts (including
        // YouTube-backed channels) reject unknown user agents, and several
        // streams redirect across protocols/hosts.
        val httpDataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        return DefaultMediaSourceFactory(httpDataSource)
    }

    /**
     * Content-aware buffering. LIVE keeps a short buffer to stay close to the
     * live edge (and re-buffers quickly after a stall); VOD/ARCHIVE buffers
     * generously and keeps filling while playing, so seeks and weak links do
     * not starve playback.
     */
    private fun loadControlFor(kind: PlaybackContentKind): LoadControl {
        val live = kind == PlaybackContentKind.LIVE
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (live) LIVE_MIN_BUFFER_MS else VOD_MIN_BUFFER_MS,
                if (live) LIVE_MAX_BUFFER_MS else VOD_MAX_BUFFER_MS,
                if (live) LIVE_BUFFER_FOR_PLAYBACK_MS else VOD_BUFFER_FOR_PLAYBACK_MS,
                if (live) LIVE_REBUFFER_AFTER_MS else VOD_REBUFFER_AFTER_MS,
            )
            .setTargetBufferBytes(if (live) LIVE_TARGET_BUFFER_BYTES else VOD_TARGET_BUFFER_BYTES)
            .build()
    }

    private fun canAutoRestart(itemId: String): Boolean = synchronized(autoRestartCounts) {
        val next = (autoRestartCounts[itemId] ?: 0) + 1
        val budget = if (_state.value.item?.kind == PlaybackContentKind.LIVE) 8 else MAX_AUTO_RESTARTS
        if (next > budget) {
            false
        } else {
            autoRestartCounts[itemId] = next
            true
        }
    }

    /** Media3 may buffer forever without onPlayerError (e.g. a TCP stream stalls).
     * Watch only LIVE playback while playWhenReady is true; never restart a
     * paused stream or one the user has replaced. The retry budget also covers
     * watchdog restarts, so a poor connection cannot create a busy loop. */
    private fun watchLiveBuffering() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = null
        val exo = _player.value ?: return
        val item = _state.value.item ?: return
        if (!foreground || item.kind != PlaybackContentKind.LIVE ||
            exo.playbackState != Player.STATE_BUFFERING || !exo.playWhenReady) return
        val token = generation
        bufferingWatchdog = scope.launch {
            delay(20_000L)
            val current = _player.value
            if (generation != token || !foreground || _state.value.item != item ||
                current?.playbackState != Player.STATE_BUFFERING || current.playWhenReady != true) return@launch
            if (canAutoRestart(item.id)) {
                autoRestart(item, PlaybackError.Network)
            } else {
                // Escalate to the slow recovery cadence, without showing a
                // modal error over the video.
                _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(60_000L)
                    if (generation == token && foreground && _state.value.item == item) {
                        autoRestartCounts.remove(item.id)
                        autoRestart(item, PlaybackError.Network)
                    }
                }
            }
        }
    }

    /** Restarts the current item after a transient failure. VOD/ARCHIVE resume
     *  from their last known position; LIVE restarts at the live edge. */
    private fun autoRestart(item: PlaybackItem, category: PlaybackError) {
        val positionMs = _state.value.positionMs.coerceAtLeast(0L)
        _state.update { it.copy(state = PlaybackState.LOADING, error = null) }
        try {
            val exoPlayer = ensurePlayer(item.kind)
            // After a fatal error Media3 stays in STATE_IDLE/ERROR; it must be
            // stopped+cleared before a fresh prepare, or the retry can fail
            // with "prepare() in wrong state" and never recover.
            runCatching { exoPlayer.stop() }
            runCatching { exoPlayer.clearMediaItems() }
            applyChannelTrackPreferences(exoPlayer,
                if (item.kind == PlaybackContentKind.VOD) AndroidChannelPreferences()
                else readChannelPreferences(context, item.id))
            exoPlayer.setMediaItem(MediaItem.Builder().setMediaId(item.id).setUri(item.url).build())
            exoPlayer.prepare()
            if (item.kind != PlaybackContentKind.LIVE && positionMs > 0L) {
                exoPlayer.seekTo(positionMs)
            }
            exoPlayer.play()
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    state = PlaybackState.ERROR,
                    error = category.toUserMessage(),
                )
            }
        }
    }

    private fun refreshState() {
        val exoPlayer = _player.value ?: return
        // Media3 drops to STATE_IDLE right after a fatal error; keep the ERROR
        // state stable so the UI can show the error overlay until retry/close.
        if (_state.value.item == null || _state.value.state == PlaybackState.ERROR) return
        if (retryJob?.isActive == true) return
        val backendState = when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> PlayerBackendState.BUFFERING
            Player.STATE_READY -> PlayerBackendState.READY
            Player.STATE_ENDED -> PlayerBackendState.ENDED
            else -> PlayerBackendState.IDLE
        }
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        _state.update {
            it.copy(
                state = backendState.toPlaybackState(exoPlayer.isPlaying),
                positionMs = position,
                durationMs = duration,
                diagnostics = buildDiagnostics(exoPlayer),
            )
        }
        timeShift.tick()
    }

    /**
     * Cheap 1 Hz tick target: position/duration only, no [buildDiagnostics]
     * (track iteration + string formatting) and no backend-state recompute —
     * those are already kept current by the event-driven [refreshState].
     */
    private fun refreshPositionOnly() {
        val exoPlayer = _player.value ?: return
        if (_state.value.state == PlaybackState.ERROR) return
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        _state.update { it.copy(positionMs = position, durationMs = duration) }
        timeShift.tick()
    }

    private fun updatePositionTicker(isPlaying: Boolean) {
        val shouldTick = isPlaying && _state.value.item?.kind != PlaybackContentKind.LIVE
        if (!shouldTick) {
            positionTickerJob?.cancel()
            positionTickerJob = null
            return
        }
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                refreshPositionOnly()
            }
        }
    }

    /**
     * Live stream facts for the diagnostics panel (key I). Android ExoPlayer
     * reports the ACTUAL decoder only after Media3 has initialized it.
     * The selector enforces a specific path, avoiding false hardware claims.
     */
    private fun buildDiagnostics(exoPlayer: ExoPlayer): PlayerDiagnostics? {
        val format = firstVideoFormat(exoPlayer.currentTracks) ?: return null
        val width = if (format.width != C.LENGTH_UNSET && format.width > 0) format.width else null
        val height = if (format.height != C.LENGTH_UNSET && format.height > 0) format.height else null
        val codec = format.codecs?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType?.substringAfterLast('/')?.uppercase()
        val resolution = if (width != null && height != null) "${width}x${height}" else "n/a"
        // Prefer the live measurements (HLS formats usually omit fps/bitrate),
        // fall back to the track format when the analytics have not fired yet.
        val fps = measuredFps
            ?: if (format.frameRate != C.RATE_UNSET && format.frameRate > 0f) {
                java.lang.String.format(java.util.Locale.US, "%.2f", format.frameRate)
            } else {
                null
            }
        val bitrate = measuredBitrate
            ?: if (format.bitrate != C.LENGTH_UNSET && format.bitrate > 0) {
                java.lang.String.format(java.util.Locale.US, "%.1f Mbps", format.bitrate / 1_000_000.0)
            } else {
                null
            }
        val buffering = exoPlayer.playbackState == Player.STATE_BUFFERING
        val transportState = when {
            buffering -> PlayerDiagnostics.STATE_BUFFERING
            exoPlayer.isPlaying -> PlayerDiagnostics.STATE_PLAYING
            exoPlayer.playbackState == Player.STATE_ENDED -> "Завершено"
            else -> PlayerDiagnostics.STATE_LOADING
        }
        return PlayerDiagnostics(
            requestedMode = requestedDecodeMode.name,
            actualMode = when {
                activeDecoderName == null -> PlayerDiagnostics.MODE_UNKNOWN
                decoderPath == AndroidVideoDecoderPath.HARDWARE -> PlayerDiagnostics.MODE_HARDWARE
                else -> PlayerDiagnostics.MODE_SOFTWARE
            },
            fallback = requestedDecodeMode == VideoDecodeMode.AUTO &&
                decoderPath == AndroidVideoDecoderPath.SYSTEM_SOFTWARE,
            decoder = activeDecoderName ?: "Ожидание MediaCodec",
            videoOutput = "Media3/ExoPlayer",
            codec = codec,
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
            audio = firstAudioLabel(exoPlayer.currentTracks),
            transportState = transportState,
        )
    }

    /** First selected video track's format, or null when not known yet. */
    private fun firstVideoFormat(tracks: Tracks): androidx.media3.common.Format? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                // Adaptive HLS playlists may expose multiple resolutions here.
                // Only display the track Media3 actually selected.
                if (group.isTrackSelected(i)) return format
            }
        }
        return null
    }

    /**
     * Human-readable audio description of the first audio track: channel layout
     * (Стерео, 5.1, …) + codec + sample rate, e.g. «Стерео · AAC · 48 кГц».
     */
    private fun firstAudioLabel(tracks: Tracks): String? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            val selected = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                ?: continue
            val format = group.getTrackFormat(selected)
            val parts = buildList {
                when (format.channelCount) {
                    1 -> add("Моно")
                    2 -> add("Стерео")
                    6 -> add("5.1")
                    8 -> add("7.1")
                    else -> if (format.channelCount > 0) add("${format.channelCount} каналов")
                }
                format.codecs
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(it.uppercase()) }
                    ?: format.sampleMimeType
                        ?.substringAfterLast('/')
                        ?.uppercase()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                if (format.sampleRate > 0) add("${format.sampleRate / 1000} кГц")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
        return null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /** Bounded auto-restarts per item (VOD/ARCHIVE and LIVE alike). */
        const val MAX_AUTO_RESTARTS = 3

        /**
         * Delay before each auto-restart attempt: gives the network/provider a
         * moment to recover instead of hammering a dead endpoint in a tight
         * loop (which would also keep the UI stuck on LOADING with no result).
         */
        const val RESTART_RETRY_DELAY_MS = 1_500L

        // LIVE buffering: stay close to the live edge, recover fast.
        const val LIVE_MIN_BUFFER_MS = 3_000
        const val LIVE_MAX_BUFFER_MS = 15_000
        const val LIVE_BUFFER_FOR_PLAYBACK_MS = 1_500
        const val LIVE_REBUFFER_AFTER_MS = 3_000
        const val LIVE_TARGET_BUFFER_BYTES = 4 * 1024 * 1024

        // VOD/ARCHIVE buffering: generous headroom, smooth scrubbing.
        const val VOD_MIN_BUFFER_MS = 15_000
        const val VOD_MAX_BUFFER_MS = 60_000
        const val VOD_BUFFER_FOR_PLAYBACK_MS = 2_500
        const val VOD_REBUFFER_AFTER_MS = 5_000
        const val VOD_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    }
}

/**
 * Maps a Media3 [PlaybackException] onto the platform-agnostic [PlaybackError]
 * type used by the common UI. Classification logic lives in the pure common
 * function [classifyPlaybackErrorCode] so it is unit-testable on the JVM.
 */
internal fun PlaybackException.toPlaybackError(): PlaybackError =
    classifyPlaybackErrorCode(errorCode)
