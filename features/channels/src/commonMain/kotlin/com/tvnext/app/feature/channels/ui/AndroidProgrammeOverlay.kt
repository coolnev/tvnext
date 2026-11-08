package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.ui.component.TvActionButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.image.ChannelLogo
import kotlinx.coroutines.delay

/** Approved layout 3: one timeline, two programme cards and one fixed transport row. */
@Composable
internal fun AndroidProgrammeOverlay(
    channel: Channel, epg: ChannelEpg, controller: PlaybackController,
    interactive: Boolean, onFocus: (Boolean) -> Unit, onClose: () -> Unit,
    detailsVisible: Boolean = false,
    modifier: Modifier = Modifier,
    onTogglePlayback: (() -> Unit)? = null,
    seekStepMs: Long = 60_000L,
) {
    val player by controller.state.collectAsState()
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    val archived = player.item?.kind == PlaybackContentKind.ARCHIVE
    val at = if (archived) (player.item?.archiveEpgAnchorMs ?: now) + player.positionMs else now
    LaunchedEffect(Unit) { while (true) { delay(1000); now = currentTimeMillis() } }
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
    BoxWithConstraints(modifier.fillMaxWidth().onFocusChanged { onFocus(it.hasFocus) }.focusGroup()
        .onPreviewKeyEvent { event ->
            if (event.key == Key.Back || (interactive && !detailsVisible && event.key == Key.DirectionUp)) {
                if (event.type == KeyEventType.KeyUp) onClose()
                true
            } else false
        }, contentAlignment = Alignment.BottomCenter,
    ) {
        // All dimensions derive from one 1920px design at a 960-unit baseline.
        // Density cancels out; 720p/1080p/4K retain the approved proportions.
        val scale = (maxWidth / 960.dp).coerceAtLeast(.3f)
        if (detailsVisible) {
            ProgrammeDescriptionView(channel, epg.current, scale, (maxHeight * .65f).coerceAtMost(320.dp * scale), onClose)
        } else {
            val programme = epg.current
            val duration = programme?.let { (it.stop - it.start).coerceAtLeast(0) } ?: 0L
            val position = programme?.let { (at - it.start).coerceIn(0, duration) } ?: 0L
            Column(Modifier.fillMaxWidth().background(Color(0xFF0D1216).copy(alpha = .97f))
                .padding(horizontal = 28.dp * scale, vertical = 12.dp * scale).testTag("fullscreen-programme-panel")) {
                Row(Modifier.fillMaxWidth().height(19.dp * scale), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                        ChannelLogo(channel.logoUrl, placeholderText = channel.name,
                            cornerRadius = 4.dp * scale, modifier = Modifier.size(18.dp * scale))
                        Text(channel.name, color = Color.White, fontSize = 16.sp * scale, lineHeight = 19.sp * scale,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("overlay-channel-name"))
                        if (archived) Text("Архив", color = Color.White.copy(alpha = .70f), fontSize = 9.5.sp * scale,
                            lineHeight = 12.sp * scale, modifier = Modifier.border(.5.dp * scale, Color.White.copy(alpha = .22f),
                                RoundedCornerShape(3.dp * scale)).padding(horizontal = 5.dp * scale, vertical = 2.dp * scale))
                    }
                    if (duration > 0) Text("До конца ${(duration - position + 59_999) / 60_000} мин",
                        color = Color.White.copy(alpha = .7f), fontSize = 10.5.sp * scale, lineHeight = 14.sp * scale, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp * scale))
                ProgrammeTimeline(programme, position, duration, if (archived && programme != null && now in programme.start until programme.stop)
                    ((now - programme.start).toFloat() / duration.coerceAtLeast(1)).coerceIn(0f,1f) else null, scale)
                Spacer(Modifier.height(8.dp * scale))
                Row(Modifier.fillMaxWidth().testTag("overlay-programme-cards"), horizontalArrangement = Arrangement.spacedBy(9.dp * scale)) {
                    ProgrammeCard("Текущая передача", programme?.title ?: "Нет данных EPG", null, true, scale,
                        Modifier.weight(1f).testTag("overlay-current-card"))
                    val next = epg.next
                    ProgrammeCard("Следующая передача", next?.title ?: "Нет данных EPG",
                        next?.let { "${formatTimeOfDay(it.start)}–${formatTimeOfDay(it.stop)}" }, false, scale,
                        Modifier.weight(1f).testTag("overlay-next-card"))
                }
                Spacer(Modifier.height(9.dp * scale))
                AndroidFullscreenPlaybackControls(scale = scale, paused = player.state == PlaybackState.PAUSED,
                    canPause = archived, canSeek = androidHasArchive(channel) && duration > 0,
                    isLiveChannel = player.item?.kind == PlaybackContentKind.LIVE, interactive = interactive,
                    seekStepMs = seekStepMs, onTogglePlayback = onTogglePlayback ?: {
                        if (player.state == PlaybackState.PAUSED) controller.resume() else controller.pause()
                    }, onSeekBy = controller::seekBy,
                    onLive = if (archived) ({ (controller as? TimeShiftController)?.returnToLive(); Unit }) else null,
                    onClose = onClose)
            }
        }
    }
}

@Composable
private fun ProgrammeTimeline(programme: EpgProgramme?, position: Long, duration: Long, liveEdge: Float?, scale: Float) {
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth().height(15.dp * scale)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp * scale).align(Alignment.BottomCenter).testTag("overlay-programme-progress")) {
            val middle = size.height / 2
            val width = 2.5.dp.toPx() * scale
            drawLine(Color.White.copy(alpha = .20f), Offset(width/2,middle), Offset(size.width-width/2,middle), width, StrokeCap.Round)
            val fraction = if (duration > 0) position.toFloat()/duration else 0f
            if (fraction > 0) drawLine(primary, Offset(width/2,middle), Offset((size.width*fraction).coerceAtLeast(width/2),middle), width, StrokeCap.Round)
            liveEdge?.let { x -> drawLine(Color.White.copy(alpha = .9f), Offset(size.width*x,0f), Offset(size.width*x,size.height), .8.dp.toPx()*scale) }
        }
        liveEdge?.let { fraction -> Text("Эфир", color = Color.White.copy(alpha = .8f), fontSize = 7.5.sp * scale,
            lineHeight = 8.sp * scale, modifier = Modifier.offset(x = (maxWidth * fraction + 4.dp * scale).coerceAtMost(maxWidth - 25.dp * scale))) }
    }
    Row(Modifier.fillMaxWidth().testTag("overlay-programme-times"), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(programme?.let { formatTimeOfDay(it.start) } ?: "—", color = Color.White.copy(alpha = .62f), fontSize = 9.5.sp * scale, lineHeight = 13.sp * scale)
        Text(if (duration > 0) "${panelPlaybackTime(position)} / ${panelPlaybackTime(duration)}" else "—",
            color = Color.White.copy(alpha = .86f), fontSize = 10.sp * scale, lineHeight = 13.sp * scale)
        Text(programme?.let { formatTimeOfDay(it.stop) } ?: "—", color = Color.White.copy(alpha = .62f), fontSize = 9.5.sp * scale, lineHeight = 13.sp * scale)
    }
}

@Composable
private fun ProgrammeCard(label: String, title: String, time: String?, current: Boolean, scale: Float, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(4.dp * scale)
    Column(modifier.height(45.dp * scale).background(if (current) primary.copy(alpha = .025f) else Color.White.copy(alpha = .012f), shape)
        .border(.5.dp * scale, if (current) primary.copy(alpha = .24f) else Color.White.copy(alpha = .14f), shape)
        .padding(horizontal = 9.dp * scale, vertical = 5.5.dp * scale)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = .62f), fontSize = 9.5.sp * scale, lineHeight = 14.sp * scale,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            time?.let { Text(it, color = Color.White.copy(alpha = .65f), fontSize = 10.sp * scale, lineHeight = 14.sp * scale, maxLines = 1) }
        }
        Spacer(Modifier.height(1.5.dp * scale))
        Text(title, color = if (current) Color.White else Color.White.copy(alpha = .82f),
            fontSize = 13.5.sp * scale, lineHeight = 17.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag(if (current) "overlay-programme-title" else "overlay-next-title"))
    }
}

/** The double-RIGHT reader is a separate view, not part of the now/next panel. */
@Composable
private fun ProgrammeDescriptionView(channel: Channel, programme: EpgProgramme?, scale: Float, height: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { };close.requestFocus() }
    Column(Modifier.fillMaxWidth().height(height).background(Color(0xFF0D1216).copy(alpha = .98f))
        .padding(horizontal = 28.dp * scale, vertical = 12.dp * scale).testTag("fullscreen-description-view")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(programme?.title ?: "Описание передачи", color = Color.White, fontSize = 17.sp * scale,
                    lineHeight = 21.sp * scale, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.name + (programme?.let { " · ${formatTimeOfDay(it.start)}–${formatTimeOfDay(it.stop)}" } ?: ""),
                    color = Color.White.copy(alpha = .65f), fontSize = 10.sp * scale, lineHeight = 14.sp * scale)
            }
            TvActionButton(onClick = onClose, modifier = Modifier.focusRequester(close).testTag("fullscreen-description-close")) {
                Text("Закрыть", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp * scale)
            }
        }
        Spacer(Modifier.height(6.dp * scale))
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = .65f))) {
            key(programme?.channelId, programme?.start) { ProgrammeRichDetails(programme, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

private fun panelPlaybackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val tail = "${((seconds/60)%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}"
    return if (seconds >= 3600) "${seconds/3600}:$tail" else tail
}
