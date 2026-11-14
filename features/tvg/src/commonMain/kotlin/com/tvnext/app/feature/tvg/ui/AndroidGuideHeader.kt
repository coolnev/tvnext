package com.tvnext.app.feature.tvg.ui

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlayerSurface

/** The singleton playback session stays mounted while browsing channels/days/categories. */
@Composable
internal fun AndroidGuideHeader(
    controller: PlaybackController, category: String, date: String,
    toolbarFocus: FocusRequester, gridFocus: FocusRequester, onFocus: (Boolean) -> Unit,
    onCategories: () -> Unit, onPreviousDay: () -> Unit, onNow: () -> Unit,
    onNextDay: () -> Unit, onRefresh: () -> Unit, onVideo: (() -> Unit)?,
    onCategoriesFocus: (Boolean) -> Unit = {},
) {
    val playback by controller.state.collectAsState()
    val previous = remember { FocusRequester() }
    val now = remember { FocusRequester() }
    val next = remember { FocusRequester() }
    val refresh = remember { FocusRequester() }
    val video = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("tvg-header")
        .onFocusChanged { onFocus(it.hasFocus) }.focusGroup()) {
        val previewWidth = (maxWidth * .24f).coerceIn(176.dp, 288.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(uiText("ТВ-гид"), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(date, style = MaterialTheme.typography.bodySmall)
                }
                GuideOutlineButton(category, onCategories, Modifier.widthIn(max = 260.dp)
                    .testTag("tvg-categories").focusRequester(toolbarFocus)
                    .onFocusChanged { onCategoriesFocus(it.isFocused) }
                    .guideDirections(right = previous, down = now))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GuideOutlineButton(uiText("- День"), onPreviousDay, Modifier.testTag("tvg-day-previous")
                        .focusRequester(previous).guideDirections(toolbarFocus, now, toolbarFocus, gridFocus))
                    GuideOutlineButton(uiText("Сейчас"), onNow, Modifier.testTag("tvg-now")
                        .focusRequester(now).guideDirections(previous, next, toolbarFocus, gridFocus))
                    GuideOutlineButton(uiText("+ День"), onNextDay, Modifier.testTag("tvg-day-next")
                        .focusRequester(next).guideDirections(now, refresh, toolbarFocus, gridFocus))
                    GuideOutlineButton(uiText("Обновить"), onRefresh, Modifier.testTag("tvg-refresh")
                        .focusRequester(refresh).guideDirections(next, if (onVideo != null) video else FocusRequester.Cancel, toolbarFocus, gridFocus))
                }
            }
            Column(Modifier.width(previewWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var focused by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().aspectRatio(16f/9).testTag("tvg-video-preview")
                    .background(com.tvnext.app.core.player.videoSurfaceBackground(Color.Black)).border(if (focused) 2.dp else .5.dp,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), RoundedCornerShape(2.dp))
                    .focusRequester(video).onFocusChanged { focused = it.hasFocus }
                    .guideDirections(left = refresh, down = gridFocus)
                    .guideActivation { onVideo?.invoke() }
                    .clickable(enabled = onVideo != null, role = Role.Button, indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onVideo?.invoke() }) {
                    PlayerSurface(controller, Modifier.fillMaxSize())
                }
                Text(playback.item?.title ?: uiText("Нет воспроизведения"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("tvg-playing-channel"))
            }
        }
    }
}

/** Transparent, nearly square controls, matching the full-screen transport panel. */
@Composable
internal fun GuideOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val foreground = MaterialTheme.colorScheme.onSurface
    Box(modifier.height(34.dp).onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 1.5.dp else .5.dp,
            if (focused) MaterialTheme.colorScheme.primary else foreground.copy(alpha = .25f), RoundedCornerShape(4.dp))
        .guideActivation(onClick)
        .clickable(role = Role.Button, indication = null,
            interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = foreground, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.guideDirections(
    left: FocusRequester = FocusRequester.Cancel, right: FocusRequester = FocusRequester.Cancel,
    up: FocusRequester = FocusRequester.Cancel, down: FocusRequester = FocusRequester.Cancel,
) = onPreviewKeyEvent { event ->
    val requester = when (event.key) {
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        Key.DirectionUp -> up
        Key.DirectionDown -> down
        else -> null
    }
    if (requester == null) false else {
        if (event.type == KeyEventType.KeyDown && requester != FocusRequester.Cancel) requester.requestFocus()
        true
    }
}

@Composable
private fun Modifier.guideActivation(onClick: () -> Unit): Modifier {
    var held by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    return onFocusChanged { if (!it.hasFocus) held = false }.onPreviewKeyEvent { event ->
        if (event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)) false
        else {
            if (event.type == KeyEventType.KeyDown) held = true
            else if (event.type == KeyEventType.KeyUp) {
                val activate = held
                held = false
                if (activate) click()
            }
            true
        }
    }
}
