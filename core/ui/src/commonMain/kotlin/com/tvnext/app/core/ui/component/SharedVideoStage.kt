package com.tvnext.app.core.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.tvnext.app.core.player.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

private val RectAnimation = TwoWayConverter<Rect, AnimationVector4D>(
    {AnimationVector4D(it.left,it.top,it.right,it.bottom)}, {Rect(it.v1,it.v2,it.v3,it.v4)})

/** Keeps native layout fixed during flight; frame updates only transform its graphics layer. */
@Composable
fun SharedVideoStage(
    scene: VideoScene, controller: PlaybackController, animated: Boolean,
    modifier: Modifier = Modifier,
    transitionKey: Any? = Unit,
    hasVideoDestination: Boolean = true,
    surface: @Composable (Modifier, Boolean) -> Unit = { m, texture -> NativePlayerSurface(controller,m,texture) },
) {
    val target = scene.viewport
    val android = com.tvnext.app.core.common.platform.isAndroidPlatform()
    val surfaceSelection = remember(controller) { VideoSurfaceSelection().apply {
        update(animated,android,controller.state.value.diagnostics)
    } }
    val texture by remember(controller, animated, android) {
        controller.state.map { surfaceSelection.update(animated,android,it.diagnostics) }.distinctUntilChanged()
    }.collectAsState(initial = surfaceSelection.usesTextureView)
    val hasItem by remember(controller) {
        controller.state.map { it.item != null }.distinctUntilChanged()
    }.collectAsState(initial = controller.state.value.item != null)
    var presentedNavigation by remember { mutableStateOf(transitionKey) }
    val navigationLayout = remember(transitionKey) { scene.layoutRevision }
    val navigationPending = animated && presentedNavigation != transitionKey
    LaunchedEffect(transitionKey, animated) {
        if (navigationPending) {
            // Keep the old video above destination chrome from its first frame,
            // before the new placeholder has been measured and flight can start.
            snapshotFlow { scene.layoutRevision }.first { it > navigationLayout }
        }
        presentedNavigation = transitionKey
    }
    var held by remember { mutableStateOf<VideoViewport?>(null) }
    var previous by remember { mutableStateOf<VideoViewport?>(null) }
    var laidOutViewport by remember { mutableStateOf<VideoViewport?>(null) }
    var visible by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var surfaceCreated by remember { mutableStateOf(false) }
    val bounds = remember { Animatable(Rect.Zero,RectAnimation) }
    val density = LocalDensity.current
    val flightOwner = remember { arrayOf<Any?>(null) }
    SideEffect { if(target!=null) {held=target;visible=true;surfaceCreated=true} }
    LaunchedEffect(target==null, navigationPending, hasVideoDestination, hasItem, animated) {
        if(target==null) {
            if (animated && navigationPending && hasVideoDestination && hasItem) return@LaunchedEffect
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
        presentedNavigation=transitionKey
        val resized=old!=null && (abs(old.bounds.width-next.bounds.width)>1f || abs(old.bounds.height-next.bounds.height)>1f)
        if(animated && old!=null && (old.token!=next.token || resized) && !bounds.value.isEmpty) {
            moving=true
            try {
                bounds.animateTo(next.bounds,tween(420,easing=FastOutSlowInEasing))
                if(flightOwner[0]===owner)laidOutViewport=next
            }
            finally {if(flightOwner[0]===owner)moving=false}
        } else {moving=false;bounds.snapTo(next.bounds);laidOutViewport=next}
    }
    val viewport=target ?: held
    val old=previous
    val awaitingFlight=animated && old!=null && target!=null && (old.token!=target.token ||
        abs(old.bounds.width-target.bounds.width)>1f || abs(old.bounds.height-target.bounds.height)>1f)
    val handoffFlight=animated && target==null && visible && viewport!=null
    val flying=moving || awaitingFlight || handoffFlight || navigationPending
    // Only navigation/settling changes native dimensions. Reading bounds.value
    // here would recompose and resize the Android SurfaceView on every frame.
    val layoutViewport=if(flying)laidOutViewport ?: viewport else viewport
    val layoutBounds=layoutViewport?.bounds ?: Rect.Zero
    val layoutClip=if(flying)layoutBounds else viewport?.clip ?: Rect.Zero
    val full=layoutBounds.translate(-scene.origin)
    val clip=layoutClip.translate(-scene.origin)
    val usable=(target!=null || visible) && !clip.isEmpty && !full.isEmpty
    // Native content is above chrome only during flight; normal controls paint above it.
    Box(modifier.offset {IntOffset(clip.left.roundToInt(),clip.top.roundToInt())}
        .requiredSize(with(density){clip.width.coerceAtLeast(1f).toDp()},with(density){clip.height.coerceAtLeast(1f).toDp()})
        .graphicsLayer {
            val frame=if(flying)bounds.value.takeUnless {it.isEmpty} ?: layoutBounds else layoutBounds
            transformOrigin=TransformOrigin(0f,0f)
            scaleX=if(flying)frame.width/layoutBounds.width.coerceAtLeast(1f) else 1f
            scaleY=if(flying)frame.height/layoutBounds.height.coerceAtLeast(1f) else 1f
            translationX=if(flying)frame.left-layoutBounds.left else 0f
            translationY=if(flying)frame.top-layoutBounds.top else 0f
            alpha=if(usable)1f else 0f
            this.clip=true
        }
        .zIndex(if(flying)2f else 0f).testTag("shared-video-stage")) {
        if(viewport!=null || surfaceCreated) surface(Modifier.offset {IntOffset((full.left-clip.left).roundToInt(),(full.top-clip.top).roundToInt())}
            .requiredSize(with(density){full.width.coerceAtLeast(1f).toDp()},with(density){full.height.coerceAtLeast(1f).toDp()}),texture)
    }
}
