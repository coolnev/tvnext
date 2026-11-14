package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Visibility affects only this passive label; EPG import continues in the background. */
@Composable
fun EpgLoadingIndicator(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val visibility = LocalPlaybackOverlayVisibility.current
    if (!enabled || visibility?.bottomPanelVisible == true || visibility?.videoLoading == true ||
        visibility?.contentOverlayVisible == true) return
    Row(modifier.testTag("epg-loading-status")
        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
        .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
