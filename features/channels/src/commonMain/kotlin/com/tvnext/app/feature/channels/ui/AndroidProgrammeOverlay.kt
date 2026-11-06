package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.*
import kotlinx.coroutines.delay

@Composable
internal fun AndroidProgrammeOverlay(
    channel: Channel, epg: ChannelEpg, controller: PlaybackController,
    interactive: Boolean, onFocus: (Boolean) -> Unit, onClose: () -> Unit,
    detailsVisible: Boolean = false, onShowDetails: () -> Unit = {},
    modifier: Modifier = Modifier,
    onTogglePlayback: (() -> Unit)? = null,
    seekStepMs: Long = 60_000L,
) {
    val player by controller.state.collectAsState()
    val shiftController = controller as? TimeShiftController
    val shift = shiftController?.timeShiftState?.collectAsState()?.value
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    val archived = player.item?.kind == PlaybackContentKind.ARCHIVE
    val at = if (archived) (player.item?.archiveEpgAnchorMs ?: now) + player.positionMs else now
    val programme = epg.current
    LaunchedEffect(Unit) { while (true) { delay(1000); now = currentTimeMillis() } }
    // The active transport bar manages its own first-focus request.
    // Passive information must not steal focus from the video surface.
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    run {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                .padding(horizontal = 0.dp, vertical = 8.dp)
                .onFocusChanged { onFocus(it.hasFocus) }.focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) onClose()
                        true
                    } else if (interactive && event.key == Key.DirectionUp) {
                        // Up closes the transport layer; left/right remain
                        // normal button-to-button D-pad navigation.
                        if (event.type == KeyEventType.KeyDown) onClose()
                        true
                    } else false
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(channel.name + if (archived) " • Архив" else "",
                    modifier = Modifier.padding(horizontal = 10.dp).testTag("overlay-channel-name"),
                    color = Color.White, style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (programme != null) {
                    if (androidHasArchive(channel)) SeekBarTrack(
                        fraction = ((at - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1)).coerceIn(0f, 1f),
                        enabled = false, onScrub = {}, onScrubEnd = {},
                        modifier = Modifier.fillMaxWidth().height(16.dp).testTag("overlay-programme-progress"),
                        liveEdgeFraction = if (archived && now in programme.start..programme.stop && programme.stop > programme.start)
                            ((now - programme.start).toFloat() / (programme.stop - programme.start)).coerceIn(0f, 1f)
                            else null,
                    ) else TvProgressBar(
                        progress = { ((at - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).testTag("overlay-programme-progress"),
                    )
                }
                Text(programme?.let { "${formatTimeOfDay(it.start)}–${formatTimeOfDay(it.stop)}  ${it.title}" }
                    ?: "Нет телепрограммы", modifier = Modifier.padding(horizontal = 10.dp).testTag("overlay-programme-title"),
                    style = MaterialTheme.typography.titleMedium)
                if (programme != null && !androidHasArchive(channel)) {
                    Text("${((at - programme.start).coerceAtLeast(0) / 60_000)} / ${(programme.stop - programme.start) / 60_000} мин",
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(epg.next?.let { "Далее: ${formatTimeOfDay(it.start)}–${formatTimeOfDay(it.stop)}  ${it.title}" }
                    ?: "Следующая передача: нет данных",
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp),
                    style = MaterialTheme.typography.bodyMedium)
                if (detailsVisible) key(programme?.channelId, programme?.start) {
                    ProgrammeRichDetails(programme)
                }
                shift?.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
                if (androidHasArchive(channel)) AndroidPlaybackControls(
                    paused = player.state == PlaybackState.PAUSED,
                    showProgress = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    diagnostics = player.diagnostics,
                    positionMs = programme?.let { (at - it.start).coerceAtLeast(0) } ?: 0,
                    durationMs = programme?.let { (it.stop - it.start).coerceAtLeast(0) } ?: 0,
                    onTogglePlayback = onTogglePlayback ?: { if (player.state == PlaybackState.PAUSED) controller.resume() else controller.pause() },
                    onSeekBy = controller::seekBy,
                    interactive = interactive,
                    isLiveChannel = player.item?.kind == com.tvnext.app.core.player.PlaybackContentKind.LIVE,
                    canPause = archived,
                    liveEdgeFraction = if (archived && programme != null &&
                        now in programme.start..programme.stop && programme.stop > programme.start)
                        ((now - programme.start).toFloat() / (programme.stop - programme.start)).coerceIn(0f, 1f)
                        else null,
                    onLive = if (archived) ({ shiftController?.returnToLive(); Unit }) else null,
                    onDetails = onShowDetails, onClose = onClose,
                ) else {
                    AndroidPlaybackControls(
                        paused = false,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        diagnostics = player.diagnostics,
                        positionMs = 0L,
                        durationMs = 0L,
                        onTogglePlayback = {},
                        onSeekBy = {},
                        interactive = interactive,
                        canPause = false,
                        isLiveChannel = true,
                        onDetails = onShowDetails,
                        onClose = onClose,
                    )
                }
                if (interactive) Text("←→ выбор кнопки   OK — действие   ↑ / Назад — закрыть", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
