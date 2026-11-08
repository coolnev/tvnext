package com.tvnext.app.core.ui.component

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecordingIndicator(modifier: Modifier = Modifier) {
    val red = Color(0xFFFF4545)
    Row(modifier.testTag("recording-indicator").semantics { contentDescription = uiText("Идёт запись") }
        .background(Color.Black.copy(alpha = .78f), RoundedCornerShape(4.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(red, CircleShape))
        Text("REC", color = red, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    }
}
