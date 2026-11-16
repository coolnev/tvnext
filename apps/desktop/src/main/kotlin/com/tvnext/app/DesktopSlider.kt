@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tvnext.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Track and thumb share an explicit centre line; Material's default end caps are omitted. */
@Composable
internal fun DesktopSlider(value:Float,onValueChange:(Float)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    onValueChangeFinished:(()->Unit)?=null,tag:String) {
    val active=if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=.35f)
    val inactive=MaterialTheme.colorScheme.onSurface.copy(alpha=.18f)
    Slider(value=value,onValueChange=onValueChange,enabled=enabled,onValueChangeFinished=onValueChangeFinished,
        thumb={Box(Modifier.width(12.dp).height(30.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(10.dp).testTag("$tag-thumb")) {drawCircle(active)}
        }},
        track={state->Canvas(Modifier.fillMaxWidth().height(30.dp).testTag("$tag-track")) {
            val y=size.height/2;val thickness=3.dp.toPx()
            drawLine(inactive,Offset(0f,y),Offset(size.width,y),thickness,StrokeCap.Round)
            drawLine(active,Offset(0f,y),Offset(size.width*state.value.coerceIn(0f,1f),y),thickness,StrokeCap.Round)
        }},modifier=modifier.testTag(tag))
}
