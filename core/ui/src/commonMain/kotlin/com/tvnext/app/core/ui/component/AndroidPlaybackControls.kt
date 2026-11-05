package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.PlayerDiagnostics
import com.tvnext.app.core.common.util.PlaybackUiRequests

/** Stable transport identities: toggling paused never replaces or re-focuses a button. */
@Composable
fun AndroidPlaybackControls(
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    seekStepMs: Long = 60_000L,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    canPause: Boolean = true,
    isLiveChannel: Boolean = false,
    liveEdgeFraction: Float? = null,
    diagnostics: PlayerDiagnostics? = null,
    onLive: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val aspectTitle by PlaybackUiRequests.aspectLabel.collectAsState()
    val pauseFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val aspectFocus = remember { FocusRequester() }
    LaunchedEffect(interactive, canPause) {
        if (interactive) {
            withFrameNanos { }
            runCatching {
                if (canPause) pauseFocus.requestFocus()
                else if (durationMs > 0L) rewindFocus.requestFocus()
                else aspectFocus.requestFocus()
            }
        }
    }
    Column(modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (durationMs > 0) {
            SeekBarTrack(
                fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(16.dp),
                onScrub = {}, onScrubEnd = {},
                liveEdgeFraction = liveEdgeFraction,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(playbackTime(positionMs), color = MaterialTheme.colorScheme.onSurface)
                Text(playbackTime(durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (liveEdgeFraction != null) {
                Text("Белая метка — начало прямого эфира", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Technical facts come from the selected Media3 tracks, not channel names
        // or the configured output resolution. Do not show unverified values.
        val badges = remember(diagnostics?.resolution, diagnostics?.audio) {
            playbackFormatBadges(diagnostics?.resolution, diagnostics?.audio)
        }
        if (badges.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp, androidx.compose.ui.Alignment.End),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                badges.forEach { label ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TvCardButton(
                onClick = { PlaybackUiRequests.chooseAspect() },
                enabled = interactive,
                modifier = Modifier.focusRequester(aspectFocus),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
            ) { Text("Формат: $aspectTitle", color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            TvCardButton(
                onClick = { PlaybackUiRequests.previousChannel() },
                enabled = interactive,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
            ) { Text("↶", color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge) }
            TvSeekButton(
                direction = TvSeekDirection.BACKWARD,
                onSeek = { onSeekBy(-seekStepMs) },
                enabled = interactive && durationMs > 0L,
                focusRequester = rewindFocus,
            )
            if (canPause) {
                TvCardButton(
                    onClick = onTogglePlayback,
                    enabled = interactive,
                    containerAlpha = 0f,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(52.dp).focusRequester(pauseFocus),
                ) {
                    TvPlayPauseIcon(
                        paused = paused,
                        modifier = Modifier.fillMaxSize(),
                        circleSize = 52.dp,
                        backgroundAlpha = 0f,
                    )
                }
            }
            TvSeekButton(
                direction = TvSeekDirection.FORWARD,
                onSeek = { onSeekBy(seekStepMs) },
                enabled = interactive && durationMs > 0L,
            )
            onLive?.let { TvCardButton(onClick = it) { Text("LIVE", color = MaterialTheme.colorScheme.onSurface) } }
            onDetails?.let { TvCardButton(onClick = it) { Text("Описание", color = MaterialTheme.colorScheme.onSurface) } }
            onClose?.let { TvCardButton(onClick = it) { Text("Закрыть", color = MaterialTheme.colorScheme.onSurface) } }
            Spacer(Modifier.weight(1f))
            if (isLiveChannel) {
                TvCardButton(onClick = { PlaybackUiRequests.toggleRecording() }, enabled = interactive,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("● Записать эфир", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 3600}:${((seconds / 60) % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
