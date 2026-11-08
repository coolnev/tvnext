package com.tvnext.app.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.tvnext.app.core.common.platform.isAndroidPlatform
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.ui.theme.TVNextDimens

@Composable
fun TvCardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(TVNextDimens.CardCornerRadius),
    contentPadding: PaddingValues = PaddingValues(TVNextDimens.ScreenPaddingCompact),
    contentAlignment: Alignment = Alignment.CenterStart,
    /**
     * Opacity of the resting (unfocused) container. Lets the channel rows and
     * the clock-icon button match their surrounding panel's translucency.
     */
    containerAlpha: Float = 0.72f,
    onFocus: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val interactionFocused by interactionSource.collectIsFocusedAsState()
    var androidFocused by remember { mutableStateOf(false) }
    val focused = if (isAndroidPlatform()) androidFocused else interactionFocused
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val android = isAndroidPlatform()
    val effectiveShape = if (android) RoundedCornerShape(4.dp) else shape

    // Subtle glass treatment shared by cards/buttons: translucent surfaces,
    // animated hover/press transitions and a clear keyboard focus ring.
    val targetContainerColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        android && focused -> MaterialTheme.colorScheme.primary.copy(alpha = .18f)
        android && pressed -> MaterialTheme.colorScheme.primary.copy(alpha = .24f)
        android -> MaterialTheme.colorScheme.onSurface.copy(alpha = .035f)
        pressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = containerAlpha)
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(140),
        label = "cardContainer",
    )
    val targetBorderWidth = when {
        focused -> TVNextDimens.FocusBorderWidth
        selected -> 1.dp
        android -> .5.dp
        else -> 0.dp
    }
    val borderWidth by animateDpAsState(
        targetValue = targetBorderWidth,
        animationSpec = tween(120),
        label = "cardBorder",
    )
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        android -> MaterialTheme.colorScheme.onSurface.copy(alpha = .15f)
        else -> Color.Transparent
    }

    val currentOnClick by rememberUpdatedState(onClick)
    var activationHeld by remember { mutableStateOf(false) }
    val inputModifier = if (isAndroidPlatform()) {
        Modifier
            .onPreviewKeyEvent { event ->
                val activate = event.key == Key.Enter || event.key == Key.Spacebar || event.key == Key.DirectionCenter
                if (!activate || !enabled) false else when (event.type) {
                    KeyEventType.KeyDown -> { activationHeld = true; true }
                    KeyEventType.KeyUp -> {
                        val click = activationHeld
                        activationHeld = false
                        if (click) currentOnClick()
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                androidFocused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
                if (!it.isFocused) activationHeld = false
            }
            // Windows 0.5.8 original UI JAR confirms this Android modifier
            // order: key handler -> focus observer -> focusable -> hoverable
            // -> clickable. Stage 9 accidentally removed the two middle nodes.
            .focusable(interactionSource = interactionSource, enabled = enabled)
            .hoverable(interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = { currentOnClick() },
            )
    } else {
        Modifier
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }
            .focusable(interactionSource = interactionSource, enabled = enabled)
            .hoverable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter ||
                        event.key == Key.Spacebar ||
                        event.key == Key.DirectionCenter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    }

    Box(
        modifier = modifier
            .clip(effectiveShape)
            .background(containerColor)
            .border(BorderStroke(if (android) targetBorderWidth else borderWidth, borderColor), effectiveShape)
            .then(inputModifier)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}
