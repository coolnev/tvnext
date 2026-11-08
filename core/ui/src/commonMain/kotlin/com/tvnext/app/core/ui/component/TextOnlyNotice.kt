package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Passive in-app feedback: no launcher icon, native Toast, or focus target. */
@Composable
fun TextOnlyNotice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.widthIn(max = 480.dp).testTag("text-only-notice")
        .semantics { liveRegion = LiveRegionMode.Polite }
        .background(Color.Black.copy(alpha = .88f), RoundedCornerShape(4.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp)
    }
}
