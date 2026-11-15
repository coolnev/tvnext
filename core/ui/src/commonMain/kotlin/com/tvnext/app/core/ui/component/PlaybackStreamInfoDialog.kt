package com.tvnext.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvnext.app.core.common.util.PlaybackUiRequests
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackStreamInfo
import com.tvnext.app.core.ui.i18n.uiText
import kotlinx.coroutines.launch

/** Kept above the player screens so an auto-hidden lower panel cannot dismiss the report. */
@Composable
fun PlaybackStreamInfoHost(controller:PlaybackController) {
    var snapshot by remember(controller) {mutableStateOf<PlaybackStreamInfo?>(null)}
    SideEffect {PlaybackUiRequests.streamInfoVisible.value=snapshot!=null}
    DisposableEffect(Unit) {onDispose {PlaybackUiRequests.streamInfoVisible.value=false}}
    LaunchedEffect(controller) {
        PlaybackUiRequests.streamInfoRequests.collect {snapshot=controller.streamInfo()}
    }
    snapshot?.let {info->PlaybackStreamInfoDialog(info,
        onRefresh={snapshot=controller.streamInfo()},onClose={snapshot=null})}
}

@Composable
fun PlaybackStreamInfoDialog(info:PlaybackStreamInfo,onRefresh:()->Unit,onClose:()->Unit) {
    ReportPlaybackOverlays(contentOverlay=true)
    val bodyFocus=remember {FocusRequester()}
    val refreshFocus=remember {FocusRequester()}
    val closeFocus=remember {FocusRequester()}
    val scroll=rememberScrollState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) {withFrameNanos{};bodyFocus.requestFocus()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val scheme=MaterialTheme.colorScheme
        MaterialTheme(colorScheme=scheme.copy(onSurface=Color.White,onSurfaceVariant=Color.White.copy(alpha=.65f),
            primary=if(scheme.primary.luminance()<.25f)scheme.inversePrimary else scheme.primary)) {
        Column(Modifier.fillMaxWidth(.92f).widthIn(max=1100.dp).fillMaxHeight(.9f)
            .background(Color(0xFF10171C),RoundedCornerShape(6.dp))
            .border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.6f),RoundedCornerShape(6.dp))
            .testTag("stream-info-dialog").padding(18.dp)
            .onPreviewKeyEvent {event->
                if(event.key in listOf(Key.Back,Key.Escape)) {
                    if(event.type==KeyEventType.KeyUp)onClose()
                    true
                } else false
            }) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(uiText("Информация о потоке"),color=Color.White,fontSize=20.sp,fontWeight=FontWeight.SemiBold,
                    modifier=Modifier.weight(1f))
                Text(formatDateTime(info.capturedAtMs),color=Color.White.copy(alpha=.65f),fontSize=12.sp)
            }
            Text(info.title.ifBlank{"—"},color=MaterialTheme.colorScheme.primary,fontSize=16.sp,
                modifier=Modifier.padding(top=4.dp,bottom=10.dp).testTag("stream-info-title"))
            Column(Modifier.weight(1f).fillMaxWidth().focusRequester(bodyFocus)
                .focusProperties {right=refreshFocus;down=refreshFocus}
                .onPreviewKeyEvent {event->
                    if(event.key==Key.DirectionRight) {
                        if(event.type==KeyEventType.KeyDown)refreshFocus.requestFocus()
                        true
                    } else if(event.key==Key.DirectionDown || event.key==Key.DirectionUp) {
                        if(event.type==KeyEventType.KeyDown) {
                            if(event.key==Key.DirectionDown && scroll.value>=scroll.maxValue)refreshFocus.requestFocus()
                            else scope.launch {scroll.scrollTo((scroll.value+if(event.key==Key.DirectionDown)90 else -90).coerceIn(0,scroll.maxValue))}
                        }
                        true
                    } else false
                }.focusable().verticalScroll(scroll).testTag("stream-info-body"),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                info.facts.chunked(2).forEach {row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        row.forEach {fact->
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color=Color.White.copy(alpha=.58f))) {append(uiText(fact.label)+": ")}
                                withStyle(SpanStyle(color=Color.White)) {append(uiText(fact.value))}
                            },fontSize=13.sp,lineHeight=17.sp,modifier=Modifier.weight(1f))
                        }
                        if(row.size==1)Spacer(Modifier.weight(1f))
                    }
                }
                StreamInfoText(uiText("Адрес потока"),info.sourceUrl.ifBlank{"—"},"stream-info-source")
                val requestUrl=info.lastRequestUrl
                if(!requestUrl.isNullOrBlank() && requestUrl!=info.sourceUrl)
                    StreamInfoText(uiText("Последний запрос"),requestUrl,"stream-info-request")
                StreamInfoText(uiText("Последняя ошибка"),info.lastError?.takeIf{it.isNotBlank()} ?: uiText("Нет ошибок"),"stream-info-error")
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.End)) {
                TvActionButton(onClick=onRefresh,modifier=Modifier.focusRequester(refreshFocus)
                    .focusProperties {up=bodyFocus;left=bodyFocus;right=closeFocus}
                    .onPreviewKeyEvent {event->
                        when(event.key) {
                            Key.DirectionRight->{if(event.type==KeyEventType.KeyDown)closeFocus.requestFocus();true}
                            Key.DirectionLeft,Key.DirectionUp->{if(event.type==KeyEventType.KeyDown)bodyFocus.requestFocus();true}
                            Key.DirectionDown->true
                            else->false
                        }
                    }.testTag("stream-info-refresh")) {
                    Text(uiText("Обновить"))
                }
                TvActionButton(onClick=onClose,modifier=Modifier.focusRequester(closeFocus)
                    .focusProperties {up=bodyFocus;left=refreshFocus;right=FocusRequester.Cancel}
                    .onPreviewKeyEvent {event->
                        when(event.key) {
                            Key.DirectionLeft->{if(event.type==KeyEventType.KeyDown)refreshFocus.requestFocus();true}
                            Key.DirectionUp->{if(event.type==KeyEventType.KeyDown)bodyFocus.requestFocus();true}
                            Key.DirectionDown,Key.DirectionRight->true
                            else->false
                        }
                    }.testTag("stream-info-close")) {
                    Text(uiText("Закрыть"))
                }
            }
        }
        }
    }
}

@Composable
private fun StreamInfoText(label:String,value:String,tag:String) {
    Column(Modifier.fillMaxWidth().padding(top=4.dp)) {
        Text(label,color=Color.White.copy(alpha=.58f),fontSize=11.sp,lineHeight=14.sp)
        Text(value,color=Color.White,fontSize=13.sp,lineHeight=17.sp,softWrap=true,modifier=Modifier.testTag(tag))
    }
}
