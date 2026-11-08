package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlayerDiagnostics
import com.tvnext.app.core.ui.component.AndroidPlaybackControls

/** Fixed space below the video: transport above the selected film's inline description. */
@Composable
internal fun AndroidWindowedPlaybackInfo(
    programme: EpgProgramme?,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    seekStepMs: Long,
    canPause: Boolean,
    isLiveChannel: Boolean,
    diagnostics: PlayerDiagnostics? = null,
    liveEdgeFraction: Float? = null,
    onLive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AndroidPlaybackControls(
            paused = paused, positionMs = positionMs, durationMs = durationMs,
            onTogglePlayback = onTogglePlayback, onSeekBy = onSeekBy, seekStepMs = seekStepMs,
            requestInitialFocus = false, canPause = canPause, isLiveChannel = isLiveChannel,
            diagnostics = diagnostics, liveEdgeFraction = liveEdgeFraction, onLive = onLive,
            compact = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("windowed-controls"),
        )
        Column(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("windowed-description")) {
            Text(programme?.title ?: uiText("Описание передачи"), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) Text("${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
            ProgrammeRichDetails(programme, modifier = Modifier.weight(1f).fillMaxWidth(), compact = true)
        }
    }
}
