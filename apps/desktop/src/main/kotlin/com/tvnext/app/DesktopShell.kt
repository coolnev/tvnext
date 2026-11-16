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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.onSizeChanged
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
    var channelOverlay by remember {mutableStateOf(false)}
    val density=androidx.compose.ui.platform.LocalDensity.current
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
    LaunchedEffect(active){editing=false;videoFocused=false;channelOverlay=false;shortcuts.reset();activity()}
    LaunchedEffect(isFullscreen){if(isFullscreen && watching)onImmersiveChange(true)}
    LaunchedEffect(lastActivity,cinema,player.state,channelOverlay){
        chrome=true
        if(cinema && !channelOverlay && player.state==PlaybackState.PLAYING){delay(3000);chrome=false}
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
                .onPointerEvent(PointerEventType.Press){activity()}
                .onPointerEvent(PointerEventType.Move){event->
                    activity()
                    if(cinema && active==AppTab.CHANNELS && page==DesktopPage.NONE) {
                        val x=event.changes.firstOrNull()?.position?.x ?: Float.MAX_VALUE
                        if(x<with(density){24.dp.toPx()})channelOverlay=true
                        else if(x>with(density){380.dp.toPx()})channelOverlay=false
                    }
                }
                .onGloballyPositioned {scene.origin=it.positionInRoot()}.testTag("desktop-shell")) {
                SharedVideoStage(scene,runtime.playback,animation,transitionKey=Triple(active,cinema,page),hasVideoDestination=recordingVideo || page==DesktopPage.NONE && when(active) {
                    AppTab.HOME,AppTab.CHANNELS,AppTab.TV_GUIDE->true
                    AppTab.MEDIA_LIBRARY->movieOpen && player.item?.kind==PlaybackContentKind.VOD
                    else->false
                })
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
                                    {editing=it},{videoFocused=it},settings.clockPosition,channelOverlay,{channelOverlay=false})
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
internal fun DesktopNavigation(active:AppTab,page:DesktopPage,navigate:(AppTab)->Unit,home:()->Unit,back:()->Unit,
    fullscreen:()->Unit,isFullscreen:Boolean,runtime:DesktopServices,modifier:Modifier=Modifier) {
    var more by remember {mutableStateOf(false)}
    val tabs=listOf(AppTab.HOME to "Главная",AppTab.CHANNELS to "Каналы",AppTab.TV_GUIDE to "ТВ-гид",
        AppTab.MEDIA_LIBRARY to "Медиатека",AppTab.SEARCH to "Поиск",AppTab.SETTINGS to "Настройки")
    val extras=listOf("Мои записи" to DesktopPage.RECORDINGS,"Редактор каналов" to DesktopPage.EDITOR,"Профили" to DesktopPage.PROFILES)
    val labels=tabs.map {uiText(it.second)}+extras.map {uiText(it.first)}
    val measure=rememberTextMeasurer();val density=androidx.compose.ui.platform.LocalDensity.current
    val style=MaterialTheme.typography.labelLarge
    val widths=labels.map {with(density){measure.measure(AnnotatedString(it),style.copy(fontWeight=FontWeight.Bold)).size.width.toDp()}+28.dp}.map {it.coerceAtLeast(64.dp)}
    val brand="TVNext ${AppVersion.NAME}"
    val brandWidth=with(density){measure.measure(AnnotatedString(brand),MaterialTheme.typography.bodyLarge).size.width.toDp()}+16.dp
    Surface(modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),tonalElevation=2.dp) {
        BoxWithConstraints {
            val available=maxWidth-brandWidth-20.dp-128.dp
            val total=widths.fold(0.dp){a,b->a+b}+4.dp*widths.size
            var used=0.dp
            val count=if(total<=available)widths.size else widths.takeWhile {width->
                (used+width+4.dp<=available-52.dp).also {if(it)used+=width+4.dp}
            }.size
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=10.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(brand,fontWeight=FontWeight.SemiBold,modifier=Modifier.width(brandWidth))
                TextButton(onClick=back,modifier=Modifier.width(60.dp).testTag("desktop-back"),contentPadding=PaddingValues(horizontal=6.dp)){Text(uiText("Назад"),maxLines=1)}
                fun activate(index:Int) {if(index<tabs.size) {val tab=tabs[index].first;if(tab==AppTab.HOME)home() else navigate(tab)} else runtime.page.value=extras[index-tabs.size].second}
                labels.take(count).forEachIndexed {index,label->
                    val selected=if(index<tabs.size)tabs[index].first==active && page==DesktopPage.NONE else extras[index-tabs.size].second==page
                    val tag=if(index<tabs.size)"desktop-nav-${tabs[index].first.name}" else "desktop-nav-${extras[index-tabs.size].second.name}"
                    TextButton(onClick={activate(index)},modifier=Modifier.width(widths[index]).testTag(tag),contentPadding=PaddingValues(horizontal=12.dp),
                        colors=ButtonDefaults.textButtonColors(contentColor=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                        Text(label,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,maxLines=1)
                    }
                }
                if(count<labels.size)Box {
                    TextButton({more=true},modifier=Modifier.width(48.dp).testTag("desktop-nav-more"),contentPadding=PaddingValues(0.dp)){Text("•••")}
                    DropdownMenu(more,{more=false}) {
                        (count until labels.size).forEach {index->DropdownMenuItem(text={Text(labels[index])},onClick={more=false;activate(index)})}
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(fullscreen,modifier=Modifier.width(60.dp).testTag("desktop-window-fullscreen"),contentPadding=PaddingValues(0.dp)){Text(if(isFullscreen)"F11 ↙" else "F11 ↗")}
            }
        }
    }
}

@Composable
internal fun DesktopTransport(controller:PlaybackController,onPause:()->Unit,onFullscreen:()->Unit,onPrevious:()->Unit,
    onRecord:()->Unit,onInfo:()->Unit,onAspect:()->Unit,modifier:Modifier=Modifier,seekStepMs:Long=10_000L,
    epg:ChannelEpg=ChannelEpg(),onReturnLive:()->Unit={(controller as? TimeShiftController)?.returnToLive()}) {
    val state by controller.state.collectAsState()
    val shift by remember(controller){(controller as? TimeShiftController)?.timeShiftState ?: kotlinx.coroutines.flow.MutableStateFlow(TimeShiftState())}.collectAsState()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(state.item?.kind){if(state.item?.kind!=PlaybackContentKind.VOD)while(true){delay(1000);now=System.currentTimeMillis()}}
    var drag by remember(state.item){mutableStateOf<Float?>(null)}
    val model=desktopTimeline(state,shift,epg,now)
    val seekable=state.item?.kind==PlaybackContentKind.VOD && state.durationMs>0 || controller is TimeShiftController &&
        (state.item?.kind==PlaybackContentKind.ARCHIVE || shift.oldestMs!=null)
    Column(modifier.fillMaxWidth().testTag("desktop-transport")) {
        if(model!=null) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                fun label(at:Long)=if(model.broadcast)formatTimeOfDay(at) else desktopTime(at)
                Text(label(drag?.let(model::target) ?: model.position),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=if(model.live!=null)17.dp else 0.dp))
                BoxWithConstraints(Modifier.weight(1f).height(if(model.live!=null)47.dp else 30.dp)) {
                    val density=androidx.compose.ui.platform.LocalDensity.current
                    DesktopSlider(value=drag ?: model.fraction(model.position),onValueChange={drag=it.coerceAtMost(model.fraction(model.seekEnd))},enabled=seekable,
                        onValueChangeFinished={drag?.let {val target=model.target(it);if(model.broadcast)(controller as? TimeShiftController)?.seekToTime(target) else controller.seekTo(target)};drag=null},
                        modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).semantics {contentDescription=desktopText("Перемотка")},tag="desktop-seek")
                    model.live?.let {live->
                        val tint=MaterialTheme.colorScheme.error
                        var textWidth by remember {mutableIntStateOf(0)}
                        val widthPx=with(density){maxWidth.toPx()}
                        val markerX=with(density){6.dp.toPx()}+(widthPx-with(density){12.dp.toPx()})*model.fraction(live)
                        Text(uiText("Эфир"),color=tint,style=MaterialTheme.typography.labelSmall,
                            modifier=Modifier.offset {IntOffset((markerX-textWidth/2f).coerceIn(0f,(widthPx-textWidth).coerceAtLeast(0f)).toInt(),0)}
                                .onSizeChanged {textWidth=it.width}.testTag("desktop-live-label"))
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).testTag("desktop-live-marker").semantics {contentDescription=uiText("Эфир")}) {
                            val x=6.dp.toPx()+(size.width-12.dp.toPx())*model.fraction(live)
                            drawLine(tint,Offset(x,7.dp.toPx()),Offset(x,23.dp.toPx()),2.dp.toPx())
                        }
                    }
                }
                Text(label(model.end),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=if(model.live!=null)17.dp else 0.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            DesktopIconButton("desktop-rewind",uiText("Назад")+" · "+uiText("{0} с",seekStepMs/1000),DesktopIcon.REWIND,seekable){controller.seekBy(-seekStepMs)}
            DesktopIconButton("desktop-pause",uiText(if(state.state==PlaybackState.PAUSED)"Продолжить" else "Пауза"),
                if(state.state==PlaybackState.PAUSED)DesktopIcon.PLAY else DesktopIcon.PAUSE,state.item!=null,onPause)
            DesktopIconButton("desktop-forward",uiText("Вперёд")+" · "+uiText("{0} с",seekStepMs/1000),DesktopIcon.FORWARD,seekable){controller.seekBy(seekStepMs)}
            Row(Modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f),RoundedCornerShape(4.dp)).padding(horizontal=5.dp),
                verticalAlignment=Alignment.CenterVertically) {
                DesktopIconButton("desktop-mute",uiText(if(state.isMuted)"Включить звук" else "Выключить звук"),
                    if(state.isMuted)DesktopIcon.MUTED else DesktopIcon.VOLUME){controller.setMuted(!state.isMuted)}
                DesktopSlider(value=if(state.isMuted)0f else state.volume,onValueChange={controller.setMuted(false);controller.setVolume(it)},
                    modifier=Modifier.width(82.dp).height(30.dp).semantics {contentDescription=desktopText("Громкость")},tag="desktop-volume")
                Text(if(state.isMuted)"0%" else "${(state.volume*100).toInt()}%",style=MaterialTheme.typography.labelSmall,modifier=Modifier.width(34.dp),textAlign=TextAlign.End)
            }
            if(state.item?.kind==PlaybackContentKind.ARCHIVE)OutlinedButton(onReturnLive,shape=RoundedCornerShape(4.dp),contentPadding=PaddingValues(horizontal=9.dp),
                modifier=Modifier.height(38.dp).testTag("desktop-return-live")){Text(desktopText("В эфир"),maxLines=1)}
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
                        DesktopIcon.PREVIOUS->{
                            val path=Path()
                            path.moveTo(w*.18f,h*.36f);path.lineTo(w*.55f,h*.36f)
                            path.cubicTo(w*.94f,h*.36f,w*.94f,h*.84f,w*.55f,h*.84f)
                            drawPath(path,tint,style=androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(),cap=StrokeCap.Round))
                            line(.18f,.36f,.39f,.15f);line(.18f,.36f,.39f,.57f)
                        }
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
