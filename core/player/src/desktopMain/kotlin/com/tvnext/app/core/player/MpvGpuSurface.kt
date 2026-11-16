package com.tvnext.app.core.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import org.jetbrains.skia.Drawable
import org.jetbrains.skia.Rect

@Composable
internal fun MpvGpuSurface(controller:MpvPlaybackController,modifier:Modifier) {
    val revision by controller.gpuRevision.collectAsState()
    val state by controller.state.collectAsState()
    var w by remember {mutableIntStateOf(1)}
    var h by remember {mutableIntStateOf(1)}
    val drawable=remember(controller){object:Drawable(){
        override fun onGetBounds()=Rect.makeWH(w.toFloat(),h.toFloat())
        override fun onDraw(canvas:org.jetbrains.skia.Canvas?){canvas?.let {controller.drawGpu(it,w,h)}}
    }}
    DisposableEffect(drawable){onDispose {drawable.close()}}
    LaunchedEffect(revision,state.state) {
        if(controller.gpuOutput.value && controller.renderStatus.value=="GPU • подготовка") {delay(32);controller.requestGpuDraw()}
    }
    Canvas(modifier.fillMaxSize().background(Color.Black).onSizeChanged {w=it.width;h=it.height}) {
        revision;state.item
        drawIntoCanvas {drawable.notifyDrawingChanged();it.nativeCanvas.drawDrawable(drawable)}
    }
}
