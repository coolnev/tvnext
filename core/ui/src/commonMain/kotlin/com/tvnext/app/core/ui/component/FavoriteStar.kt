package com.tvnext.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.ui.i18n.uiText
import kotlin.math.*

/** Drawn as a path so its appearance does not depend on device fonts. */
@Composable
fun FavoriteStar(modifier:Modifier=Modifier,filled:Boolean=true) {
    val label=uiText("Избранное")
    Canvas(modifier.semantics {contentDescription=label}) {
        val radius=minOf(size.width,size.height)*.48f
        val path=Path().apply {
            repeat(10) {i->
                val angle=-PI/2+i*PI/5
                val r=radius*if(i%2==0)1f else .43f
                val x=size.width/2+(cos(angle)*r).toFloat()
                val y=size.height/2+(sin(angle)*r).toFloat()
                if(i==0)moveTo(x,y) else lineTo(x,y)
            };close()
        }
        drawPath(path,if(filled)Color(0xFFFFD54F) else Color.White,style=if(filled)Fill else Stroke(2.dp.toPx()))
    }
}
