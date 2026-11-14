package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Codec identification from the selected audio track, independent of passthrough/output device. */
@Composable
fun PlaybackFormatBadgeContent(label:String,color:Color,fontSize:TextUnit,lineHeight:TextUnit,modifier:Modifier=Modifier) {
    val dolby=label=="AC3" || label.startsWith("Dolby")
    if(!dolby)Text(label,modifier=modifier.testTag("format-badge-$label"),color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=1)
    else Row(modifier.testTag("format-badge-$label").semantics {contentDescription=label},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        val iconScale=(fontSize.value/10f).takeIf{it.isFinite()}?.coerceAtLeast(.8f) ?: 1f
        Canvas(Modifier.width(20.dp*iconScale).height(12.dp*iconScale)) {
            val mark=Path().apply {
                fillType=PathFillType.EvenOdd
                moveTo(0f,0f);lineTo(4f,0f);cubicTo(12f,0f,12f,14f,4f,14f);lineTo(0f,14f);close()
                moveTo(2.5f,2.5f);lineTo(4f,2.5f);cubicTo(8.5f,2.5f,8.5f,11.5f,4f,11.5f);lineTo(2.5f,11.5f);close()
            }
            withTransform({scale(size.width/24f,size.height/14f,pivot=Offset.Zero)}) {
                drawPath(mark,color)
                withTransform({scale(-1f,1f,pivot=Offset(12f,7f))}){drawPath(mark,color)}
            }
        }
        Text(when(label){"Dolby D+"->"+";"Dolby TrueHD"->"TrueHD";"AC3"->"AC3";else->"Dolby"},
            color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=1)
    }
}
