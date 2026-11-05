package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatClockTime
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.HardwareOverlayHost
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.player.PlayerDiagnostics
import com.tvnext.app.core.player.PlayerState
import com.tvnext.app.core.player.PlayerSurface
import com.tvnext.app.core.ui.theme.TVNextDimens
import kotlinx.coroutines.delay

/**
 * The shared player panel: video surface + overlays (channel name, status,
 * diagnostics, optional on-screen clock) + playback controls.
 *
 * Overlays behave the same in SOFTWARE and HARDWARE (D3D11) modes: in software
 * they are Compose content over the video; in hardware they are rendered by the
 * platform [HardwareOverlayHost] in separate opaque windows positioned over the
 * native video surface.
 *
 * [channelNameVisible] gates the channel-name overlay (the owner shows it only
 * briefly after a channel switch — the timer lives in the component, not here,
 * so recomposition never restarts it). [clockPosition] renders the on-screen
 * clock in a corner of the video while something is playing.
 */
@Composable
fun TvPlayerPanel(
    controller: PlaybackController,
    channelName: String,
    channelNameVisible: Boolean = true,
    clockPosition: ClockPosition = ClockPosition.OFF,
    showControls: Boolean,
    /**
     * Whether the VOD progress bar (position/duration + scrubbing) is shown.
     * On by default for the Media Library (VOD); channels keep the player
     * controls clean.
     */
    showSeekBar: Boolean = false,
    /** Shared seek step for VOD/archive transport controls. */
    seekStepMs: Long = 60_000L,
    rounded: Boolean = true,
    /** Whether the on-screen diagnostics plaque is visible (global toggle, key I). */
    showDiagnostics: Boolean = true,
    /**
     * Reports whether the VIDEO SURFACE (the player's own focusable node) is
     * focused. The host screens track this to decide whether OK/Enter toggles
     * the full-screen <-> browse modes on Android TV: the outer watching
     * container and this inner video node are separate focus targets, so the
     * host must know about BOTH to intercept remote keys reliably.
     */
    onVideoFocusedChange: (Boolean) -> Unit = {},
    /** Tap on video surface: open touch controls/browse mode on Android. */
    onVideoTap: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onTogglePlayback: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    channelFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    videoFocusable: Boolean = true,
    programmeStartMs: Long? = null,
    programmeDurationMs: Long? = null,
    onProgrammeSeek: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val playerState by controller.state.collectAsState()
    val displayDuration = if (isAndroidPlatform()) programmeDurationMs ?: playerState.durationMs else playerState.durationMs
    val displayPosition = if (isAndroidPlatform() && programmeStartMs != null)
        ((playerState.item?.archiveEpgAnchorMs ?: programmeStartMs) + playerState.positionMs - programmeStartMs)
            .coerceIn(0L, displayDuration.coerceAtLeast(0L)) else playerState.positionMs
    val seekDisplay: (Long) -> Unit = { position ->
        if (isAndroidPlatform() && onProgrammeSeek != null) onProgrammeSeek(position) else controller.seekTo(position)
    }
    val videoInteractionSource = remember { MutableInteractionSource() }
    val videoFocused by videoInteractionSource.collectIsFocusedAsState()

    val overlaysOutsideVideo = playerState.hardwareDecode

    // The overlay lambdas are captured by HardwareOverlayHost only ONCE (on the
    // first setContent). Reading these through State keeps them live: the
    // overlay's ComposePanel subscribes and recomposes when they change (e.g.
    // the diagnostics toggle or the channel name), without recreating the
    // overlay windows.
    val currentChannelName by rememberUpdatedState(channelName)
    val currentChannelNameVisible by rememberUpdatedState(channelNameVisible)
    val currentClockPosition by rememberUpdatedState(clockPosition)
    val currentShowDiagnostics by rememberUpdatedState(showDiagnostics)

    // Archive items carry their full label in PlaybackItem.title
    // («Россия-1 • Архив • 09.08 20:00»): the title overlay shows it instead of
    // the bare channel name, so the user always sees what they are watching.
    val effectiveTitle: String =
        if (playerState.item?.kind == PlaybackContentKind.ARCHIVE) {
            playerState.item?.title ?: channelName
        } else {
            channelName
        }
    val effectiveTitleVisible = currentChannelNameVisible ||
        playerState.item?.kind == PlaybackContentKind.ARCHIVE

    // Archive playback supports seek/pause/progress like VOD — a seek bar is
    // shown whenever the active content is archive (or the caller opted in).
    val effectiveShowSeekBar =
        showSeekBar || playerState.item?.kind == PlaybackContentKind.ARCHIVE

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (rounded) {
                        Modifier.clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
                    } else {
                        Modifier
                    },
                )
                .background(Color.Black)
                .focusable(enabled = videoFocusable, interactionSource = videoInteractionSource)
                .onFocusChanged { onVideoFocusedChange(it.isFocused) }
                .then(
                    // One gesture owner per platform: Android uses the touch
                    // layer above PlayerView below; Desktop keeps its original
                    // parent double-tap detector. Two nested detectors can
                    // compete for a single touch sequence on Android.
                    if (!isAndroidPlatform() && onToggleFullscreen != null) {
                        Modifier.pointerInput(onToggleFullscreen) {
                            detectTapGestures(onDoubleTap = { onToggleFullscreen() })
                        }
                    } else Modifier,
                )
                .then(
                    // The focus ring is drawn only in the windowed (rounded)
                    // panel. In immersive/full-screen the video fills the whole
                    // screen and a theme-green outline around it is nothing but
                    // noise (this was the "green frame" on Android TV).
                    if (videoFocused && rounded) {
                        Modifier.border(
                            width = TVNextDimens.FocusBorderWidth,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(TVNextDimens.CardCornerRadius),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            PlayerSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
            )
            if (isAndroidPlatform() && (onVideoTap != null || onToggleFullscreen != null)) {
                // PlayerView uses useController=false, but its AndroidView
                // can consume pointer events. The transparent sibling owns
                // Android video taps only when the host provided an action;
                // visible controls are composed later and remain above it.
                Box(Modifier.matchParentSize().pointerInput(onVideoTap, onToggleFullscreen) {
                    detectTapGestures(
                        onTap = { onVideoTap?.invoke() },
                        onDoubleTap = {
                            if (onToggleFullscreen != null) onToggleFullscreen()
                            else onVideoTap?.invoke()
                        },
                    )
                })
            }
            if (!overlaysOutsideVideo) {
                when (playerState.state) {
                    PlaybackState.LOADING,
                    PlaybackState.BUFFERING,
                    -> {
                        if (isAndroidPlatform()) {
                            Row(
                                Modifier.align(if (clockPosition == ClockPosition.TOP_LEFT)
                                    Alignment.TopEnd else Alignment.TopStart)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.80f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = Color.White)
                                Text(if (playerState.state == PlaybackState.BUFFERING)
                                    "Восстановление…" else "Загрузка…", color = Color.White,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        } else CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(40.dp),
                            color = Color.White,
                        )
                    }

                    PlaybackState.ERROR,
                    PlaybackState.ENDED,
                    -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(TVNextDimens.ItemSpacing),
                        ) {
                            Text(
                                text = playerState.error ?: "Ошибка воспроизведения",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.then(if (isAndroidPlatform()) Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp) else Modifier).padding(bottom = TVNextDimens.ItemSpacing),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall)) {
                                TvCardButton(
                                    onClick = onRetry,
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = PaddingValues(
                                        horizontal = TVNextDimens.ItemSpacing,
                                        vertical = TVNextDimens.ItemSpacingSmall,
                                    ),
                                ) {
                                    Text(
                                        text = "Повторить",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                TvCardButton(
                                    onClick = onClose,
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = PaddingValues(
                                        horizontal = TVNextDimens.ItemSpacing,
                                        vertical = TVNextDimens.ItemSpacingSmall,
                                    ),
                                ) {
                                    Text(
                                        text = "Закрыть",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    else -> Unit
                }
                if (showDiagnostics) {
                    playerState.diagnostics?.let { diag ->
                        DiagnosticsPanel(
                            diagnostics = diag,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(TVNextDimens.ItemSpacing),
                        )
                    }
                }
                if (effectiveTitleVisible) {
                    Text(
                        text = effectiveTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                start = TVNextDimens.ItemSpacing,
                                end = TVNextDimens.ItemSpacing,
                                top = if (clockPosition == ClockPosition.TOP_LEFT) 44.dp else TVNextDimens.ItemSpacing,
                            )
                            .background((if (isAndroidPlatform()) MaterialTheme.colorScheme.surface else Color.Black).copy(alpha = if (isAndroidPlatform() && PlayerClockStyle.withoutBackground.collectAsState().value) 0f else if (isAndroidPlatform()) 0.96f else 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (clockPosition != ClockPosition.OFF && playerState.state != PlaybackState.IDLE) {
                    PlayerClock(
                        modifier = Modifier
                            .align(
                                if (clockPosition == ClockPosition.TOP_RIGHT) {
                                    Alignment.TopEnd
                                } else {
                                    Alignment.TopStart
                                },
                            )
                            .padding(top = TVNextDimens.ItemSpacingSmall),
                    )
                }
            }
        }

        if (showControls) {
            Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))

            Column(
                verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            ) {
                // Android TV: the bottom button bar (pause / mute / favorite /
                // close) is intentionally REMOVED to free screen space — the
                // play/pause control now lives next to the progress bar (below).
                // Windows keeps the full button bar.
                if (!isAndroidPlatform()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall)) {
                        TvCardButton(
                            onClick = onTogglePlayback,
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(
                                horizontal = TVNextDimens.ItemSpacing,
                                vertical = TVNextDimens.ItemSpacingSmall,
                            ),
                        ) {
                            Text(
                                text = when (playerState.state) {
                                    PlaybackState.PLAYING,
                                    PlaybackState.READY,
                                    PlaybackState.BUFFERING,
                                    -> "Пауза"
                                    PlaybackState.PAUSED -> "Продолжить"
                                    else -> "Смотреть"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TvCardButton(
                            onClick = { controller.setMuted(!playerState.isMuted) },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(
                                horizontal = TVNextDimens.ItemSpacing,
                                vertical = TVNextDimens.ItemSpacingSmall,
                            ),
                        ) {
                            Text(
                                text = if (playerState.isMuted) "Включить звук" else "Выключить звук",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (onToggleFavorite != null) {
                            TvCardButton(
                                onClick = onToggleFavorite,
                                selected = channelFavorite,
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(
                                    horizontal = TVNextDimens.ItemSpacing,
                                    vertical = TVNextDimens.ItemSpacingSmall,
                                ),
                            ) {
                                Text(
                                    text = if (channelFavorite) "★ В избранном" else "☆ В избранное",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (channelFavorite) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TvCardButton(
                            onClick = onClose,
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(
                                horizontal = TVNextDimens.ItemSpacing,
                                vertical = TVNextDimens.ItemSpacingSmall,
                            ),
                        ) {
                            Text(
                                text = "Закрыть",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                if (!isAndroidPlatform() && playerState.item?.kind != PlaybackContentKind.LIVE && playerState.state != PlaybackState.IDLE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvSeekButton(direction = TvSeekDirection.BACKWARD, onSeek = { controller.seekBy(-seekStepMs) })
                        TvCardButton(
                            onClick = onTogglePlayback,
                            modifier = Modifier.size(52.dp),
                            contentPadding = PaddingValues(0.dp),
                            containerAlpha = 0f,
                        ) {
                            TvPlayPauseIcon(
                                paused = playerState.state == PlaybackState.PAUSED,
                                modifier = Modifier.fillMaxSize(),
                                circleSize = 52.dp,
                                backgroundAlpha = 0f,
                            )
                        }
                        TvSeekButton(direction = TvSeekDirection.FORWARD, onSeek = { controller.seekBy(seekStepMs) })
                    }
                }

                // Seek bar: shown only for VOD (Media Library) and only when
                // the stream reports a known duration. On Android TV the
                // play/pause graphic control sits LEFT of the progress bar
                // (the icon switches play <-> pause); the desktop keeps the
                // compact volume bar to the right.
                if (effectiveShowSeekBar && displayDuration > 0L && playerState.state != PlaybackState.IDLE) {
                    if (isAndroidPlatform()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TvCardButton(
                                onClick = onTogglePlayback,
                                modifier = Modifier.size(44.dp),
                                contentPadding = PaddingValues(10.dp),
                            ) {
                                TvPlayPauseIcon(
                                    paused = playerState.state == PlaybackState.PAUSED,
                                    modifier = Modifier.fillMaxSize(),
                                    circleSize = 44.dp,
                                    backgroundAlpha = 0.55f,
                                )
                            }
                            PlayerSeekBar(
                                positionMs = displayPosition,
                                durationMs = displayDuration,
                                onSeekTo = seekDisplay,
                                volume = null,
                                onVolumeChange = null,
                                liveEdgeFraction = if (programmeStartMs != null && displayDuration > 0L &&
                                    currentTimeMillis() in programmeStartMs..(programmeStartMs + displayDuration))
                                    ((currentTimeMillis() - programmeStartMs).toFloat() / displayDuration).coerceIn(0f, 1f)
                                    else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        PlayerSeekBar(
                            positionMs = displayPosition,
                            durationMs = displayDuration,
                            volume = playerState.volume.coerceIn(0f, 1f),
                            onVolumeChange = { controller.setVolume(it) },
                            onSeekTo = seekDisplay,
                        )
                    }
                }
            }
        }

        if (overlaysOutsideVideo) {
            // Hardware decode (Windows): Compose content inside the main window
            // cannot draw above the D3D11 HWND video, so the overlays are shown
            // in separate opaque top-level windows positioned over the video
            // area (HardwareOverlayHost, desktop implementation).
            //
            // Rendered LAST (after PlayerSurface): the hardware canvas is only
            // swapped to the fresh per-window canvas when PlayerSurface mounts,
            // so the overlay host must be composed after it — otherwise it sees
            // a stale canvas and silently skips creating the overlay windows.
            HardwareOverlayHost(
                controller = controller,
                title = {
                    Text(
                        text = effectiveTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            horizontal = TVNextDimens.ItemSpacing,
                            vertical = TVNextDimens.ItemSpacingSmall,
                        ),
                    )
                },
                status = {
                    PlayerStatusOverlay(
                        state = playerState,
                        onRetry = onRetry,
                        onClose = onClose,
                    )
                },
                diagnostics = {
                    playerState.diagnostics?.let { diag ->
                        DiagnosticsPanel(
                            diagnostics = diag,
                            modifier = Modifier.padding(TVNextDimens.ItemSpacing),
                        )
                    }
                },
                showTitle = effectiveTitleVisible,
                clock = {
                    PlayerClock(modifier = Modifier.padding(TVNextDimens.ItemSpacingSmall))
                },
                clockPosition = currentClockPosition,
                showDiagnostics = currentShowDiagnostics,
            )
        }
    }
}

/** Loading / buffering / error overlay content (shared between modes). */
@Composable
private fun PlayerStatusOverlay(
    state: PlayerState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    when (state.state) {
        PlaybackState.LOADING,
        PlaybackState.BUFFERING,
        -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(TVNextDimens.ItemSpacing),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                )
                Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
                Text(
                    text = if (state.state == PlaybackState.BUFFERING) {
                        "Буферизация"
                    } else {
                        "Загрузка"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                )
            }
        }

        PlaybackState.ERROR,
        PlaybackState.ENDED,
        -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(TVNextDimens.ItemSpacing),
            ) {
                Text(
                    text = state.error ?: "Ошибка воспроизведения",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                ) {
                    TvCardButton(
                        onClick = onRetry,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(
                            horizontal = TVNextDimens.ItemSpacing,
                            vertical = TVNextDimens.ItemSpacingSmall,
                        ),
                    ) {
                        Text(
                            text = "Повторить",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TvCardButton(
                        onClick = onClose,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(
                            horizontal = TVNextDimens.ItemSpacing,
                            vertical = TVNextDimens.ItemSpacingSmall,
                        ),
                    ) {
                        Text(
                            text = "Закрыть",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        else -> Unit
    }
}

/**
 * On-screen wall-clock. Ticks once per second while visible; the ticker lives
 * here (in the composable), which is cheap — this is a small text update, not a
 * new timer per recomposition.
 */
object PlayerClockStyle {
    val withoutBackground = MutableStateFlow(false)
}

@Composable
fun PlayerClock(modifier: Modifier = Modifier) {
    val withoutBackground by PlayerClockStyle.withoutBackground.collectAsState()
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = currentTimeMillis()
        }
    }
    Text(
        text = formatClockTime(now),
        style = MaterialTheme.typography.titleMedium.copy(
            shadow = if (isAndroidPlatform() && withoutBackground)
                androidx.compose.ui.graphics.Shadow(Color.Black, Offset(1f, 1f), 3f) else null
        ),
        color = if (isAndroidPlatform() && !withoutBackground) MaterialTheme.colorScheme.onSurface else Color.White,
        textAlign = TextAlign.Center,
        // Fixed-width square: without seconds the text is shorter, but the
        // background plaque keeps its previous size.
        modifier = modifier
            .widthIn(min = 64.dp)
            .background((if (isAndroidPlatform()) MaterialTheme.colorScheme.surface else Color.Black).copy(alpha = if (isAndroidPlatform() && withoutBackground) 0f else if (isAndroidPlatform()) 0.96f else 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Live diagnostics of the actual stream (Sprint 5 work-on-errors).
 * Shows what is REALLY happening (Requested vs Actual, decoder, video output,
 * codec, resolution, fps, bitrate) so AUTO/HARDWARE/SOFTWARE behaviour and
 * fallbacks can be verified without guessing.
 */
@Composable
fun DiagnosticsPanel(
    diagnostics: PlayerDiagnostics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(TVNextDimens.ItemSpacingSmall))
            .background((if (isAndroidPlatform()) MaterialTheme.colorScheme.surface else Color.Black).copy(alpha = if (isAndroidPlatform()) 0.96f else 0.6f))
            .padding(horizontal = TVNextDimens.ItemSpacingSmall, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        val fallbackText = if (diagnostics.fallback) " (fallback!)" else ""
        DiagnosticsRow("Запр.: ${diagnostics.requestedMode} | Факт.: ${diagnostics.actualMode}$fallbackText")
        if (diagnostics.transportState.isNotEmpty()) {
            DiagnosticsRow("Состояние: ${diagnostics.transportState}")
        }
        DiagnosticsRow("Decoder: ${diagnostics.decoder} | Output: ${diagnostics.videoOutput}")
        DiagnosticsRow(
            "Codec: ${diagnostics.codec ?: "—"} | ${diagnostics.resolution}" +
                (diagnostics.fps?.let { " | ${it} fps" } ?: ""),
        )
        DiagnosticsRow("Bitrate: ${diagnostics.bitrate ?: "—"}")
    }
}

@Composable
private fun DiagnosticsRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * VOD/archive progress row with an INTERACTIVE progress bar: click anywhere on
 * the bar to jump there, or press and drag to scrub. While dragging, the bar
 * and the time label follow the pointer (live preview); the seek is applied on
 * release — and instantly on a plain click. The bar spans the FULL width of the
 * row: Android TV has no volume engine, so the compact volume bar that sat to
 * the right of the duration label is not rendered there.
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Volume for the compact desktop volume bar. `null` hides the bar entirely
     * (Android TV has no volume engine — the seek bar spans the whole width).
     */
    volume: Float? = null,
    onVolumeChange: ((Float) -> Unit)? = null,
    /** Live broadcast boundary inside an archived programme (0..1). */
    liveEdgeFraction: Float? = null,
) {
    val duration = durationMs.coerceAtLeast(0L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val displayFraction: Float = if (scrubbing) {
        scrubFraction
    } else if (duration > 0L) {
        (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayPosition: Long =
        if (scrubbing) (scrubFraction * duration).toLong() else positionMs

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatMediaTime(displayPosition),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SeekBarTrack(
            fraction = displayFraction,
            enabled = duration > 0L,
            liveEdgeFraction = liveEdgeFraction,
            modifier = Modifier
                .weight(1f)
                .height(24.dp) // comfortable hit area for mouse / air-mouse
                .padding(horizontal = TVNextDimens.ItemSpacingSmall),
            onScrub = { fraction ->
                scrubbing = true
                scrubFraction = fraction
            },
            onScrubEnd = { fraction ->
                if (duration > 0L) {
                    onSeekTo((fraction * duration).toLong())
                }
                scrubbing = false
            },
        )
        Text(
            text = formatMediaTime(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Desktop volume bar: same visual language as the seek bar, narrower so
        // it does not stand out. Hidden on Android TV (no volume engine).
        if (volume != null && onVolumeChange != null) {
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            SeekBarTrack(
                fraction = volume.coerceIn(0f, 1f),
                enabled = true,
                modifier = Modifier
                    .width(96.dp)
                    .height(24.dp),
                onScrub = onVolumeChange,
                onScrubEnd = onVolumeChange,
            )
        }
    }
}

/**
 * The interactive bar itself: a 24 dp-tall hit zone over a 6 dp visual track.
 * Handles press, drag and click (down → move → up) and reports the fraction of
 * the bar width. [onScrub] fires while the pointer moves (live preview),
 * [onScrubEnd] on release (the actual seek).
 */
@Composable
fun SeekBarTrack(
    fraction: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    liveEdgeFraction: Float? = null,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .then(if (enabled) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var currentFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    onScrub(currentFraction)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        when (event.type) {
                            PointerEventType.Release -> {
                                change.consume()
                                break
                            }
                            PointerEventType.Move -> {
                                change.consume()
                                currentFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                onScrub(currentFraction)
                            }
                            else -> Unit
                        }
                    }
                    onScrubEnd(currentFraction)
                }
            } else Modifier),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            // Visual track is 20 % thinner than the classic 6 dp bar.
            val trackHeight = 4.8.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            // Track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = cornerRadius,
            )
            // Filled part.
            val filledWidth = size.width * fraction.coerceIn(0f, 1f)
            if (filledWidth > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(0f, top),
                    size = Size(filledWidth, trackHeight),
                    cornerRadius = cornerRadius,
                )
            }
            // White tick: the live broadcast starts here when watching
            // the beginning of a programme that is still on air.
            liveEdgeFraction?.let { live ->
                val x = size.width * live.coerceIn(0f, 1f)
                drawLine(Color.White, Offset(x, top - 8.dp.toPx()),
                    Offset(x, top + trackHeight + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
            // Thumb.
            drawCircle(
                color = progressColor,
                radius = 5.dp.toPx(),
                center = Offset(filledWidth, size.height / 2f),
            )
        }
    }
}

/** `mm:ss` / `h:mm:ss` media time label for the seek bar. */
fun formatMediaTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
