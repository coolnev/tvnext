@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tvnext.app

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.tvnext.app.core.app.*
import com.tvnext.app.core.app.tab.*
import com.tvnext.app.core.common.AppVersion
import com.tvnext.app.core.common.util.*
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import com.tvnext.app.core.tmdb.*
import com.tvnext.app.core.ui.component.*
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.i18n.*
import com.tvnext.app.core.ui.theme.TVNextTheme
import com.tvnext.app.feature.channels.ui.*
import com.tvnext.app.feature.home.ui.HomeScreen
import com.tvnext.app.feature.media.ui.*
import com.tvnext.app.feature.search.ui.SearchScreen
import com.tvnext.app.feature.settings.domain.*
import com.tvnext.app.feature.settings.ui.SettingsComponent
import com.tvnext.app.feature.tvg.ui.TvgScreen
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext

internal val LocalDesktopSettings=staticCompositionLocalOf {AppSettings()}

/** Windows chrome and input live here; Android screens and their input handlers are unchanged. */
@Composable
internal fun DesktopShell(root:DefaultAppComponent,runtime:DesktopServices,isFullscreen:Boolean,
    onToggleFullscreen:()->Unit,immersive:Boolean,onImmersiveChange:(Boolean)->Unit,
    animation:Boolean,epg:EpgState,notice:String?,recording:Boolean,
    settingsContent:@Composable (SettingsComponent,()->Unit)->Unit,overlays:@Composable ()->Unit) {
    val stack by root.childStack.subscribeAsState()
    val settings by root.settings.collectAsState()
    val player by runtime.playback.state.collectAsState()
    val page by runtime.page.collectAsState()
    val pin by runtime.pinRequest.collectAsState()
    val schedule by runtime.scheduleRequest.collectAsState()
    val aspect by runtime.aspectDialog.collectAsState()
    val active=stack.active.configuration
    val movieOpen by runtime.moviePlayerOpen.collectAsState()
    var editing by remember {mutableStateOf(false)}
    var videoFocused by remember {mutableStateOf(false)}
    var lastActivity by remember {mutableLongStateOf(System.currentTimeMillis())}
    var chrome by remember {mutableStateOf(true)}
    val shortcuts=remember {DesktopShortcuts()}
    val focus=remember {FocusRequester()}
    val scene=remember(runtime.playback){VideoScene()}
    val scope=rememberCoroutineScope()
    val recordingVideo=page==DesktopPage.RECORDING_VIDEO
    val watching=recordingVideo || page==DesktopPage.NONE && (active==AppTab.CHANNELS || active==AppTab.MEDIA_LIBRARY && movieOpen)
    val cinema=watching && (immersive || recordingVideo)
    fun activity(){val now=System.currentTimeMillis();if(now-lastActivity>200)lastActivity=now;chrome=true}
    fun home(){if(recordingVideo)runtime.closeRecording();runtime.page.value=DesktopPage.NONE;editing=false;onImmersiveChange(false);root.selectTab(AppTab.HOME)}
    fun navigate(tab:AppTab){runtime.page.value=DesktopPage.NONE;if(tab==AppTab.MEDIA_LIBRARY)runtime.moviePlayerOpen.value=false;editing=false;onImmersiveChange(false);root.selectTab(tab)}
    fun pause(){if(player.item!=null){if(player.state==PlaybackState.PAUSED)runtime.playback.resume() else runtime.playback.pause()};activity()}
    fun fullscreen(){if(watching)onImmersiveChange(!isFullscreen);onToggleFullscreen();activity()}
    fun back(){
        when {
            recordingVideo->runtime.closeRecording()
            page!=DesktopPage.NONE->runtime.page.value=DesktopPage.NONE
            isFullscreen->{onImmersiveChange(false);onToggleFullscreen()}
            watching && immersive->onImmersiveChange(false)
            active==AppTab.MEDIA_LIBRARY && movieOpen->runtime.moviePlayerOpen.value=false
            active!=AppTab.HOME->home()
            else->root.handleBack()
        };activity()
    }
    fun refreshEpg(){scope.launch(Dispatchers.IO){GlobalContext.get().get<EpgRepository>().refreshIfStale(true)}}
    fun channelStep(delta:Int){
        val channels=(stack.active.instance as? AppTabComponent.Channels)?.component ?: return
        val list=channels.state.value.filteredChannels.ifEmpty {channels.state.value.channels}
        if(list.isNotEmpty()) {
            val index=list.indexOfFirst {it.id==player.item?.id}.coerceAtLeast(0)
            channels.dispatch(ChannelsIntent.SelectChannel(list[(index+delta+list.size)%list.size].id))
        }
    }
    LaunchedEffect(active){editing=false;videoFocused=false;shortcuts.reset();activity()}
    LaunchedEffect(isFullscreen){if(isFullscreen && watching)onImmersiveChange(true)}
    LaunchedEffect(lastActivity,cinema,player.state){
        chrome=true
        if(cinema && player.state==PlaybackState.PLAYING){delay(3000);chrome=false}
    }
    LaunchedEffect(Unit){focus.requestFocus()}
    SideEffect {UiLocale.language=settings.language;PlayerClockStyle.withoutBackground.value=settings.clockWithoutBackground}
    TVNextTheme(darkTheme=when(settings.theme){ThemePreference.LIGHT->false;ThemePreference.DARK->true;else->isSystemInDarkTheme()},accentColor=settings.accentColor,
        panelTransparencyPercent=settings.panelTransparencyPercent) {
        CompositionLocalProvider(LocalVideoScene provides scene,LocalArchiveLabelEnabled provides settings.showArchiveLabel,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,LocalDesktopSettings provides settings) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .focusRequester(focus)
                .onPreviewKeyEvent {event->
                    if(pin!=null || schedule!=null || aspect)return@onPreviewKeyEvent false
                    val held=shortcuts.holds(event.key)
                    val command=shortcuts.command(event.key,event.type==KeyEventType.KeyDown,
                        watching && (page==DesktopPage.NONE || recordingVideo),editing,videoFocused || cinema,event.isAltPressed,event.isCtrlPressed)
                    if(command!=null){activity();when(command){
                        DesktopCommand.PAUSE->pause()
                        DesktopCommand.BACK->back()
                        DesktopCommand.HOME->home()
                        DesktopCommand.FULLSCREEN->fullscreen()
                        DesktopCommand.MUTE->runtime.playback.setMuted(!player.isMuted)
                        DesktopCommand.VOLUME_UP->runtime.playback.setVolume((player.volume+.05f).coerceAtMost(1f))
                        DesktopCommand.VOLUME_DOWN->runtime.playback.setVolume((player.volume-.05f).coerceAtLeast(0f))
                        DesktopCommand.SEEK_BACK->runtime.playback.seekBy(if(event.isCtrlPressed)-60_000 else -10_000)
                        DesktopCommand.SEEK_FORWARD->runtime.playback.seekBy(if(event.isCtrlPressed)60_000 else 10_000)
                        DesktopCommand.PREVIOUS_CHANNEL->channelStep(-1)
                        DesktopCommand.NEXT_CHANNEL->channelStep(1)
                        DesktopCommand.INFO->PlaybackUiRequests.showStreamInfo()
                        DesktopCommand.CHANNELS->onImmersiveChange(!immersive)
                    };true}else held
                }.focusable()
                .onPointerEvent(PointerEventType.Move){activity()}
                .onGloballyPositioned {scene.origin=it.positionInRoot()}.testTag("desktop-shell")) {
                SharedVideoStage(scene,runtime.playback,animation,transitionKey=Triple(active,cinema,page),hasVideoDestination=true)
                Column(Modifier.fillMaxSize()) {
                    if(!cinema)DesktopNavigation(active,page,::navigate,::home,::back,::fullscreen,isFullscreen,runtime)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            recordingVideo->DesktopPlayerView(runtime.playback,true,chrome,::pause,::fullscreen,::back,
                                runtime::toggleRecording,{PlaybackUiRequests.showStreamInfo()},{runtime.aspectDialog.value=true},
                                {videoFocused=it},modifier=Modifier.fillMaxSize(),clockPosition=settings.clockPosition)
                            page!=DesktopPage.NONE->overlays()
                            else->when(val tab=stack.active.instance) {
                                is AppTabComponent.Home->HomeScreen(tab.component,
                                    homeCardLimit=settings.homeCardLimit,showHomeUpcoming=settings.showHomeUpcoming,
                                    showHomeRecent=settings.showHomeRecent,showHomeFavorites=settings.showHomeFavorites,
                                    showHomeRecentVod=settings.showHomeRecentVod,showHomeFavoriteVod=settings.showHomeFavoriteVod,
                                    playbackController=runtime.playback,onOpenPlayingVideo={
                                        if(player.item?.kind==PlaybackContentKind.VOD){runtime.moviePlayerOpen.value=true;root.selectTab(AppTab.MEDIA_LIBRARY)}
                                        else root.selectTab(AppTab.CHANNELS)
                                        onImmersiveChange(true)
                                    })
                                is AppTabComponent.Channels->DesktopChannels(tab.component,runtime,immersive,chrome,
                                    ::pause,::fullscreen,::home,{navigate(AppTab.TV_GUIDE)},::refreshEpg,epg,
                                    {editing=it},{videoFocused=it},settings.clockPosition)
                                is AppTabComponent.Tvg->TvgScreen(tab.component,onBackHome={navigate(AppTab.CHANNELS)})
                                is AppTabComponent.Media->DesktopMedia(tab.component,runtime,movieOpen,{runtime.moviePlayerOpen.value=it},
                                    immersive,chrome,::pause,::fullscreen,::back,{editing=it},{videoFocused=it},settings.clockPosition)
                                is AppTabComponent.Search->SearchScreen(tab.component,onBackHome=::home)
                                is AppTabComponent.Settings->settingsContent(tab.component,::home)
                            }
                        }
                    }
                }
                if(cinema && chrome)DesktopNavigation(active,page,::navigate,::home,::back,::fullscreen,isFullscreen,runtime,
                    Modifier.align(Alignment.TopCenter))
                if(recording)RecordingIndicator(Modifier.align(Alignment.TopEnd).padding(top=if(!cinema || chrome)60.dp else 12.dp,end=16.dp))
                notice?.let {TextOnlyNotice(it,Modifier.align(Alignment.BottomCenter).padding(bottom=100.dp))}
                if(page==DesktopPage.NONE || recordingVideo)overlays()
                PlaybackStreamInfoHost(runtime.playback)
            }
        }
    }
}

@Composable
private fun DesktopNavigation(active:AppTab,page:DesktopPage,navigate:(AppTab)->Unit,home:()->Unit,back:()->Unit,
    fullscreen:()->Unit,isFullscreen:Boolean,runtime:DesktopServices,modifier:Modifier=Modifier) {
    var more by remember {mutableStateOf(false)}
    Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),tonalElevation=2.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp).horizontalScroll(rememberScrollState()).padding(horizontal=10.dp),
            horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("TVNext ${AppVersion.NAME}",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(end=10.dp))
            TextButton(onClick=back,modifier=Modifier.testTag("desktop-back")){Text(uiText("Назад"))}
            listOf(AppTab.HOME to "Главная",AppTab.CHANNELS to "Каналы",AppTab.TV_GUIDE to "ТВ-гид",
                AppTab.MEDIA_LIBRARY to "Медиатека",AppTab.SEARCH to "Поиск",AppTab.SETTINGS to "Настройки").forEach {(tab,label)->
                TextButton(onClick={if(tab==AppTab.HOME)home() else navigate(tab)},
                    modifier=Modifier.testTag("desktop-nav-${tab.name}"),
                    colors=ButtonDefaults.textButtonColors(contentColor=if(tab==active && page==DesktopPage.NONE)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                    Text(uiText(label),fontWeight=if(tab==active)FontWeight.Bold else FontWeight.Normal)
                }
            }
            Box {
                TextButton({more=true}){Text("•••")}
                DropdownMenu(more,{more=false}) {
                    listOf("Мои записи" to DesktopPage.RECORDINGS,"Редактор каналов" to DesktopPage.EDITOR,"Профили" to DesktopPage.PROFILES).forEach {(label,destination)->
                        DropdownMenuItem(text={Text(uiText(label))},onClick={more=false;runtime.page.value=destination})
                    }
                }
            }
            TextButton(fullscreen,modifier=Modifier.testTag("desktop-window-fullscreen")){Text(if(isFullscreen)"F11 ↙" else "F11 ↗")}
        }
    }
}

@Composable
internal fun DesktopTransport(controller:PlaybackController,onPause:()->Unit,onFullscreen:()->Unit,onPrevious:()->Unit,
    onRecord:()->Unit,onInfo:()->Unit,onAspect:()->Unit,modifier:Modifier=Modifier,seekStepMs:Long=10_000L) {
    val state by controller.state.collectAsState()
    var drag by remember(state.item?.id){mutableStateOf<Float?>(null)}
    val duration=state.durationMs.coerceAtLeast(0)
    val seekable=state.item?.kind==PlaybackContentKind.VOD && duration>0 ||
        (controller as? TimeShiftController)?.timeShiftState?.value?.oldestMs!=null
    Column(modifier.fillMaxWidth().testTag("desktop-transport")) {
        if(state.item?.kind==PlaybackContentKind.VOD && duration>0) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(desktopTime(((drag ?: (state.positionMs.toFloat()/duration))*duration).toLong()),style=MaterialTheme.typography.labelMedium)
                Slider(value=drag ?: (state.positionMs.toFloat()/duration).coerceIn(0f,1f),onValueChange={drag=it},
                    onValueChangeFinished={drag?.let {controller.seekTo((duration*it).toLong())};drag=null},
                    modifier=Modifier.weight(1f).height(30.dp).testTag("desktop-seek"))
                Text(desktopTime(duration),style=MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            DesktopIconButton("desktop-rewind",uiText("Назад")+" · "+uiText("{0} с",seekStepMs/1000),DesktopIcon.REWIND,seekable){controller.seekBy(-seekStepMs)}
            DesktopIconButton("desktop-pause",uiText(if(state.state==PlaybackState.PAUSED)"Продолжить" else "Пауза"),
                if(state.state==PlaybackState.PAUSED)DesktopIcon.PLAY else DesktopIcon.PAUSE,state.item!=null,onPause)
            DesktopIconButton("desktop-forward",uiText("Вперёд")+" · "+uiText("{0} с",seekStepMs/1000),DesktopIcon.FORWARD,seekable){controller.seekBy(seekStepMs)}
            DesktopIconButton("desktop-mute",uiText(if(state.isMuted)"Включить звук" else "Выключить звук"),
                if(state.isMuted)DesktopIcon.MUTED else DesktopIcon.VOLUME){controller.setMuted(!state.isMuted)}
            Slider(value=if(state.isMuted)0f else state.volume,onValueChange={controller.setMuted(false);controller.setVolume(it)},
                modifier=Modifier.width(82.dp).height(30.dp).semantics {contentDescription="Громкость"}.testTag("desktop-volume"))
            DesktopIconButton("desktop-previous",uiText("Предыдущий канал"),DesktopIcon.PREVIOUS,state.item?.kind!=PlaybackContentKind.VOD,onClick=onPrevious)
            DesktopIconButton("desktop-aspect",uiText("Соотношение сторон"),DesktopIcon.ASPECT,onClick=onAspect)
            DesktopIconButton("desktop-record",uiText("Запись"),DesktopIcon.RECORD,state.item?.kind==PlaybackContentKind.LIVE,onRecord)
            DesktopIconButton("desktop-info",uiText("Информация о потоке"),DesktopIcon.INFO,onClick=onInfo)
            DesktopIconButton("desktop-fullscreen",uiText("Полный экран")+" · F11",DesktopIcon.FULLSCREEN,onClick=onFullscreen)
        }
    }
}

internal enum class DesktopIcon { PLAY,PAUSE,REWIND,FORWARD,VOLUME,MUTED,PREVIOUS,ASPECT,RECORD,INFO,FULLSCREEN }
@Composable
internal fun DesktopIconButton(tag:String,label:String,icon:DesktopIcon,enabled:Boolean=true,onClick:()->Unit) {
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip={PlainTooltip {Text(label)}},state=rememberTooltipState()) {
        OutlinedButton(onClick,enabled=enabled,shape=RoundedCornerShape(4.dp),contentPadding=PaddingValues(0.dp),
            modifier=Modifier.size(38.dp).testTag(tag).semantics {contentDescription=label}) {
            val tint=LocalContentColor.current
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                if(icon==DesktopIcon.INFO)Text("i",textAlign=TextAlign.Center,fontWeight=FontWeight.Bold,fontSize=20.sp,
                    modifier=Modifier.testTag("$tag-glyph"))
                else Canvas(Modifier.size(19.dp).testTag("$tag-glyph")) {
                    val w=size.width;val h=size.height
                    fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(tint,Offset(w*x,h*y),Offset(w*x2,h*y2),1.8.dp.toPx(),StrokeCap.Round)
                    fun triangle(reverse:Boolean=false,offset:Float=0f){val path=Path();val a=if(reverse).75f else .25f;val b=1f-a
                        path.moveTo((a+offset)*w,.16f*h);path.lineTo((b+offset)*w,.5f*h);path.lineTo((a+offset)*w,.84f*h);path.close();drawPath(path,tint)}
                    when(icon) {
                        DesktopIcon.PLAY->triangle()
                        DesktopIcon.PAUSE->{drawRect(tint,Offset(w*.23f,h*.16f),androidx.compose.ui.geometry.Size(w*.17f,h*.68f));drawRect(tint,Offset(w*.60f,h*.16f),androidx.compose.ui.geometry.Size(w*.17f,h*.68f))}
                        DesktopIcon.REWIND->{line(.55f,.2f,.2f,.5f);line(.2f,.5f,.55f,.8f);line(.85f,.2f,.5f,.5f);line(.5f,.5f,.85f,.8f)}
                        DesktopIcon.FORWARD->{line(.15f,.2f,.5f,.5f);line(.5f,.5f,.15f,.8f);line(.45f,.2f,.8f,.5f);line(.8f,.5f,.45f,.8f)}
                        DesktopIcon.PREVIOUS->{triangle(true,.08f);line(.17f,.16f,.17f,.84f)}
                        DesktopIcon.RECORD->drawCircle(Color(0xfff45b68),radius=w*.32f)
                        DesktopIcon.ASPECT->drawRect(tint,Offset(w*.08f,h*.2f),androidx.compose.ui.geometry.Size(w*.84f,h*.6f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.7.dp.toPx()))
                        DesktopIcon.FULLSCREEN->{line(.1f,.35f,.1f,.1f);line(.1f,.1f,.35f,.1f);line(.65f,.1f,.9f,.1f);line(.9f,.1f,.9f,.35f);line(.1f,.65f,.1f,.9f);line(.1f,.9f,.35f,.9f);line(.65f,.9f,.9f,.9f);line(.9f,.9f,.9f,.65f)}
                        DesktopIcon.VOLUME,DesktopIcon.MUTED->{val p=Path();p.moveTo(.1f*w,.35f*h);p.lineTo(.3f*w,.35f*h);p.lineTo(.52f*w,.15f*h);p.lineTo(.52f*w,.85f*h);p.lineTo(.3f*w,.65f*h);p.lineTo(.1f*w,.65f*h);p.close();drawPath(p,tint)
                            if(icon==DesktopIcon.MUTED){line(.67f,.35f,.92f,.65f);line(.92f,.35f,.67f,.65f)}else {line(.72f,.3f,.85f,.5f);line(.85f,.5f,.72f,.7f)}}
                        else->Unit
                    }
                }
            }
        }
    }
}

internal fun desktopTime(ms:Long):String {val s=ms.coerceAtLeast(0)/1000;return if(s>=3600)"%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60)}
