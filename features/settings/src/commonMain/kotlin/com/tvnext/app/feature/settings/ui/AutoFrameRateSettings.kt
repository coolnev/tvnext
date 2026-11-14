package com.tvnext.app.feature.settings.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.i18n.uiText
import kotlinx.coroutines.launch

fun FrameRateSwitchStrategy.afrLabel()=uiText(if(this==FrameRateSwitchStrategy.SEAMLESS) "Только плавное переключение" else "С переключением экрана")
fun VideoDisplayMode.afrLabel()=uiText("{0} × {1} · {2} Гц",width,height,frameRateNumber(refreshRate))

@Composable
fun AutoFrameRateSettings(preferences:AutoFrameRatePreferences,status:AutoFrameRateStatus,
    onChange:(AutoFrameRatePreferences)->Unit,compact:Boolean=true) {
    var choice by remember {mutableStateOf<String?>(null)}
    var showModes by remember {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(uiText("Автофреймрейт"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
        @Composable fun row(label:String,value:String="",toggle:Boolean?=null,click:()->Unit) {
            TvCardButton(onClick=click,modifier=Modifier.fillMaxWidth().heightIn(min=if(compact)38.dp else 49.dp),
                contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp),shape=RoundedCornerShape(4.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(uiText(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)
                    if(toggle!=null)Switch(toggle,null,modifier=Modifier.height(28.dp))
                    else if(value.isNotEmpty())Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        row("Включить автофреймрейт",toggle=preferences.enabled){onChange(preferences.copy(enabled=!preferences.enabled))}
        row("Способ переключения",preferences.strategy.afrLabel()){choice="strategy"}
        row("Подбирать разрешение видео",toggle=preferences.matchResolution){onChange(preferences.copy(matchResolution=!preferences.matchResolution))}
        if(preferences.matchResolution && preferences.strategy==FrameRateSwitchStrategy.SEAMLESS)
            Text(uiText("Разрешение меняется только в режиме «С переключением экрана»."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        row("Удваивать низкую частоту",toggle=preferences.doubleFrameRate){onChange(preferences.copy(doubleFrameRate=!preferences.doubleFrameRate))}
        row("Только полноэкранное видео",toggle=preferences.fullscreenOnly){onChange(preferences.copy(fullscreenOnly=!preferences.fullscreenOnly))}
        row("Задержка переключения",uiText("{0} сек.",frameRateNumber(preferences.delayMs/1000.0))){choice="delay"}
        row("Поддерживаемые режимы экрана",status.modes.size.toString()){showModes=true}
        status.current?.let {Text(uiText("Экран: {0}",it.afrLabel()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        status.video?.takeIf{it.validFps}?.let {Text(uiText("Видео: {0} кадр/с",frameRateNumber(it.fps)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text(uiText(when(status.state) {
            FrameRateMatchState.OFF->"Автофреймрейт выключен"
            FrameRateMatchState.READY->"Настройки применятся при просмотре видео"
            FrameRateMatchState.WAITING->"Ожидание устойчивых параметров видео"
            FrameRateMatchState.REQUESTED->"Запрошено переключение экрана"
            FrameRateMatchState.APPLIED->"Режим экрана применён"
            FrameRateMatchState.NO_MATCH->"Подходящий режим экрана не найден"
            FrameRateMatchState.UNAVAILABLE->"Переключение экрана недоступно"
            FrameRateMatchState.REJECTED->"Устройство не применило режим — возвращён исходный"
        }),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(uiText("Экран может ненадолго погаснуть при смене режима. При выходе из просмотра исходный режим возвращается."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    choice?.let {field-> AlertDialog(shape=RoundedCornerShape(4.dp),onDismissRequest={choice=null},title={Text(uiText(if(field=="strategy")"Способ переключения" else "Задержка переключения"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(field=="strategy") FrameRateSwitchStrategy.entries.forEach {mode-> TvCardButton(onClick={onChange(preferences.copy(strategy=mode));choice=null},
                contentPadding=PaddingValues(12.dp,6.dp),modifier=Modifier.fillMaxWidth()){Text(mode.afrLabel(),color=MaterialTheme.colorScheme.onSurface)}}
            else listOf(500,1000,2000,3000).forEach {delay->TvCardButton(onClick={onChange(preferences.copy(delayMs=delay));choice=null},
                contentPadding=PaddingValues(12.dp,6.dp),modifier=Modifier.fillMaxWidth()){Text(uiText("{0} сек.",frameRateNumber(delay/1000.0)),color=MaterialTheme.colorScheme.onSurface)}}
        }
    },confirmButton={})}
    if(showModes) {
        val scroll=rememberLazyListState();val focus=remember{FocusRequester()};val scope=rememberCoroutineScope()
        val modes=status.modes.sortedWith(compareBy<VideoDisplayMode>({it.width},{it.height},{it.refreshRate})).distinctBy{Triple(it.width,it.height,it.refreshRate)}
        AlertDialog(shape=RoundedCornerShape(4.dp),onDismissRequest={showModes=false},title={Text(uiText("Поддерживаемые режимы экрана"))},text={
            LaunchedEffect(Unit){if(modes.isNotEmpty()){withFrameNanos{};focus.requestFocus()}}
            if(modes.isEmpty())Text(uiText("Устройство не сообщает доступные режимы экрана")) else
            LazyColumn(state=scroll,modifier=Modifier.fillMaxWidth().heightIn(max=340.dp).testTag("afr-mode-list").focusRequester(focus).onPreviewKeyEvent {e->
                if(e.key==Key.DirectionDown || e.key==Key.DirectionUp) {
                    if(e.type==KeyEventType.KeyDown)scope.launch {scroll.scrollToItem((scroll.firstVisibleItemIndex+if(e.key==Key.DirectionDown)1 else -1).coerceIn(0,modes.lastIndex))}
                    true
                } else false
            }.focusable(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(modes){mode->Text((if(mode.id==status.current?.id)"✓ " else "")+mode.afrLabel(),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)}
            }
        },confirmButton={TvCardButton(shape=RoundedCornerShape(4.dp),onClick={showModes=false},contentPadding=PaddingValues(12.dp,6.dp)){Text(uiText("Закрыть"),color=MaterialTheme.colorScheme.onSurface)}})
    }
}
