package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.ui.i18n.uiText

val LocalArchiveLabelEnabled = staticCompositionLocalOf { true }

/** Passive overlays never take remote focus from the video or its controls. */
@Composable
fun VideoPlaybackStatus(
    state: PlaybackState,
    archiveTitle: String?,
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val showArchive = LocalArchiveLabelEnabled.current
    Box(modifier.fillMaxSize()) {
        if (state == PlaybackState.LOADING || state == PlaybackState.BUFFERING) {
            Row(Modifier.align(Alignment.Center).testTag("video-loading-status")
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Text(uiText(if (state == PlaybackState.BUFFERING) "Восстановление…" else "Загрузка…"),
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showArchive && !archiveTitle.isNullOrBlank()) {
            Text(archiveTitle, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 12.dp)
                    .testTag("archive-video-label")
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
