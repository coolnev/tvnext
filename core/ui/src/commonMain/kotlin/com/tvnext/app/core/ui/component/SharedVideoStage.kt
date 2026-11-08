package com.tvnext.app.core.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.tvnext.app.core.player.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val RectAnimation = TwoWayConverter<Rect, AnimationVector4D>(
    {AnimationVector4D(it.left,it.top,it.right,it.bottom)}, {Rect(it.v1,it.v2,it.v3,it.v4)})

/** Keeps one native surface alive; only its bounds change between fullscreen and preview slots. */
@Composable
fun SharedVideoStage(
    scene: VideoScene, controller: PlaybackController, animated: Boolean,
    modifier: Modifier = Modifier,
    surface: @Composable (Modifier, Boolean) -> Unit = { m, texture -> NativePlayerSurface(controller,m,texture) },
) {
    val target = scene.viewport
    var held by remember { mutableStateOf<VideoViewport?>(null) }
    var previous by remember { mutableStateOf<VideoViewport?>(null) }
    var visible by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var surfaceCreated by remember { mutableStateOf(false) }
    val bounds = remember { Animatable(Rect.Zero,RectAnimation) }
    val density = LocalDensity.current
    val flightOwner = remember { arrayOf<Any?>(null) }
    SideEffect { if(target!=null) {held=target;visible=true;surfaceCreated=true} }
    LaunchedEffect(target==null) {
        if(target==null) {
            // Destination subcomposition attaches on the following frame.
            delay(100)
            visible=false;previous=null;moving=false
        }
    }
    LaunchedEffect(target?.token,target?.bounds,animated) {
        val owner=Any();flightOwner[0]=owner
        val next=target ?: return@LaunchedEffect
        val old=previous
        previous=next
        val resized=old!=null && (abs(old.bounds.width-next.bounds.width)>1f || abs(old.bounds.height-next.bounds.height)>1f)
        if(animated && old!=null && (old.token!=next.token || resized) && !bounds.value.isEmpty) {
            moving=true
            try {bounds.animateTo(next.bounds,tween(420,easing=FastOutSlowInEasing))}
            finally {if(flightOwner[0]===owner)moving=false}
        } else {moving=false;bounds.snapTo(next.bounds)}
    }
    val viewport=target ?: held
    val old=previous
    val awaitingFlight=animated && old!=null && target!=null && (old.token!=target.token ||
        abs(old.bounds.width-target.bounds.width)>1f || abs(old.bounds.height-target.bounds.height)>1f)
    val handoffFlight=target==null && visible && viewport!=null && !bounds.value.isEmpty && bounds.value!=viewport.bounds
    val flying=moving || awaitingFlight || handoffFlight
    val full=(if(bounds.value.isEmpty || old==null)viewport?.bounds ?: Rect.Zero else bounds.value).translate(-scene.origin)
    val clip=if(flying)full else (viewport?.clip ?: Rect.Zero).translate(-scene.origin)
    val usable=(target!=null || visible) && !clip.isEmpty && !full.isEmpty
    // Native content is above chrome only during flight; normal controls paint above it.
    Box(modifier.offset {IntOffset(clip.left.roundToInt(),clip.top.roundToInt())}
        .requiredSize(with(density){clip.width.coerceAtLeast(1f).toDp()},with(density){clip.height.coerceAtLeast(1f).toDp()})
        .graphicsLayer {alpha=if(usable)1f else 0f}
        .clipToBounds().zIndex(if(flying)2f else 0f).testTag("shared-video-stage")) {
        if(viewport!=null || surfaceCreated) surface(Modifier.offset {IntOffset((full.left-clip.left).roundToInt(),(full.top-clip.top).roundToInt())}
            .requiredSize(with(density){full.width.coerceAtLeast(1f).toDp()},with(density){full.height.coerceAtLeast(1f).toDp()}),animated)
    }
}
