package com.tvnext.app.core.ui.component

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.tvnext.app.core.ui.theme.TVNextDimens
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
    requestInitialFocus: Boolean = true,
    canPause: Boolean = true,
    isLiveChannel: Boolean = false,
    liveEdgeFraction: Float? = null,
    diagnostics: PlayerDiagnostics? = null,
    onLive: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    compact: Boolean = false,
    showProgress: Boolean = true,
) {
    val aspectTitle by PlaybackUiRequests.aspectLabel.collectAsState()
    val pauseFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val aspectFocus = remember { FocusRequester() }
    LaunchedEffect(interactive, requestInitialFocus) {
        if (interactive && requestInitialFocus) {
            withFrameNanos { }
            runCatching {
                if (canPause) pauseFocus.requestFocus()
                else if (durationMs > 0L) rewindFocus.requestFocus()
                else aspectFocus.requestFocus()
            }
        }
    }
    val badges = remember(diagnostics?.resolution, diagnostics?.audio, diagnostics?.fps, diagnostics?.actualMode, com.tvnext.app.core.ui.i18n.UiLocale.language) {
        playbackFormatBadges(diagnostics?.resolution, diagnostics?.audio, diagnostics?.fps, diagnostics?.actualMode)
    }
    val buttonSize = if (compact) 28.dp else 52.dp
    val compactText = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
    Column(modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
        if (compact) {
            if (durationMs > 0 && showProgress) SeekBarTrack(
                fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f), enabled = false,
                modifier = Modifier.fillMaxWidth().height(9.dp), onScrub = {}, onScrubEnd = {},
                liveEdgeFraction = liveEdgeFraction,
            )
            if (durationMs > 0 || badges.isNotEmpty()) Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (durationMs > 0) Text("${playbackTime(positionMs)} / ${playbackTime(durationMs)}",
                    style = compactText.copy(fontSize = 10.sp, lineHeight = 12.sp), maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End)) {
                    badges.forEach { badge -> Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(3.dp))
                        .padding(horizontal=4.dp,vertical=2.dp)) {
                        PlaybackFormatBadgeContent(badge,MaterialTheme.colorScheme.onSurfaceVariant,9.sp,11.sp)
                    } }
                }
            }
        } else {
            if (durationMs > 0) {
                if (showProgress) SeekBarTrack(
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
                    Text(uiText("Белая метка — начало прямого эфира"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Technical facts come from the selected Media3 tracks, not channel names
            // or the configured output resolution. Do not show unverified values.
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
                            PlaybackFormatBadgeContent(label,MaterialTheme.colorScheme.onSurfaceVariant,
                                MaterialTheme.typography.labelSmall.fontSize,MaterialTheme.typography.labelSmall.lineHeight)
                        }
                    }
                }
            }
        }
        val buttons: @Composable () -> Unit = {
            TvCardButton(
                onClick = { PlaybackUiRequests.chooseAspect() },
                enabled = interactive,
                modifier = Modifier.focusRequester(aspectFocus)
                    .then(if (compact) Modifier.height(buttonSize).widthIn(max = 76.dp) else Modifier)
                    .semantics { contentDescription = uiText("Формат: {0}", aspectTitle) },
                contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 9.dp, vertical = if (compact) 2.dp else 6.dp),
            ) { Text(if (compact) aspectTitle else uiText("Формат: {0}", aspectTitle), color = MaterialTheme.colorScheme.onSurface,
                style = if (compact) compactText else MaterialTheme.typography.labelMedium, maxLines = 1) }
            TvCardButton(
                onClick = { PlaybackUiRequests.previousChannel() },
                enabled = interactive,
                modifier = if (compact) Modifier.size(buttonSize) else Modifier,
                contentPadding = if (compact) PaddingValues(0.dp) else PaddingValues(horizontal = 9.dp, vertical = 6.dp),
            ) { Text("↶", color = MaterialTheme.colorScheme.onSurface,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge) }
            TvSeekButton(
                direction = TvSeekDirection.BACKWARD,
                onSeek = { onSeekBy(-seekStepMs) },
                enabled = interactive && durationMs > 0L,
                focusRequester = rewindFocus,
                buttonSize = buttonSize,
                modifier = Modifier.testTag("playback-rewind"),
            )
            if (canPause) {
                TvCardButton(
                    onClick = onTogglePlayback,
                    enabled = interactive,
                    containerAlpha = 0f,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(buttonSize).focusRequester(pauseFocus).testTag("playback-pause"),
                ) {
                    TvPlayPauseIcon(
                        paused = paused,
                        modifier = Modifier.fillMaxSize(),
                        circleSize = buttonSize,
                        backgroundAlpha = 0f,
                    )
                }
            }
            TvSeekButton(
                direction = TvSeekDirection.FORWARD,
                onSeek = { onSeekBy(seekStepMs) },
                enabled = interactive && durationMs > 0L,
                buttonSize = buttonSize,
                modifier = Modifier.testTag("playback-forward"),
            )
            val captionPadding = if (compact) PaddingValues(horizontal = 4.dp, vertical = 2.dp) else PaddingValues(TVNextDimens.ScreenPaddingCompact)
            val captionModifier = if (compact) Modifier.height(buttonSize) else Modifier
            onLive?.let { TvCardButton(onClick = it, enabled = interactive, modifier = captionModifier,
                contentPadding = captionPadding) { Text("LIVE", color = MaterialTheme.colorScheme.onSurface,
                    style = if (compact) compactText else MaterialTheme.typography.bodyLarge) } }
            onDetails?.let { TvCardButton(onClick = it, enabled = interactive, modifier = captionModifier,
                contentPadding = captionPadding) { Text(uiText("Описание"), color = MaterialTheme.colorScheme.onSurface,
                    style = if (compact) compactText else MaterialTheme.typography.bodyLarge) } }
            onClose?.let { TvCardButton(onClick = it, enabled = interactive, modifier = captionModifier,
                contentPadding = captionPadding) { Text(uiText("Закрыть"), color = MaterialTheme.colorScheme.onSurface,
                    style = if (compact) compactText else MaterialTheme.typography.bodyLarge) } }
            if (isLiveChannel) {
                TvCardButton(onClick = { PlaybackUiRequests.toggleRecording() }, enabled = interactive,
                    modifier = (if (compact) Modifier.height(buttonSize) else Modifier)
                        .semantics { contentDescription = uiText("Записать эфир") },
                    contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 12.dp, vertical = if (compact) 2.dp else 6.dp)) {
                    Text(if (compact) uiText("● Запись") else uiText("● Записать эфир"), color = MaterialTheme.colorScheme.onSurface,
                        style = if (compact) compactText else MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            buttons()
        } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
    }
}

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 3600}:${((seconds / 60) % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
