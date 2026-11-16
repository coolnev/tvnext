@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.*
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.iptv.data.EpgState
import com.tvnext.app.core.iptv.domain.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.*
import com.tvnext.app.core.tmdb.*
import com.tvnext.app.core.ui.component.TvPlayerPanel
import com.tvnext.app.core.ui.component.FavoriteStar
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.i18n.uiText
import com.tvnext.app.feature.channels.ui.*
import com.tvnext.app.feature.media.ui.*
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext

@Composable
internal fun DesktopPlayerView(controller:PlaybackController,cinema:Boolean,controlsVisible:Boolean,onPause:()->Unit,
    onFullscreen:()->Unit,onPrevious:()->Unit,onRecord:()->Unit,onInfo:()->Unit,onAspect:()->Unit,onVideoFocus:(Boolean)->Unit,
    epg:ChannelEpg=ChannelEpg(),modifier:Modifier=Modifier,clockPosition:ClockPosition=ClockPosition.OFF,
    onReturnLive:()->Unit={(controller as? TimeShiftController)?.returnToLive()}) {
    val state by controller.state.collectAsState()
    val audio by remember(controller){(controller as? MpvPlaybackController)?.audioOutput ?: kotlinx.coroutines.flow.MutableStateFlow(DesktopAudioOutput())}.collectAsState()
    val viewport: @Composable (Modifier)->Unit = {m ->
        Box(m.onFocusChanged {onVideoFocus(it.hasFocus)}
            .onPointerEvent(PointerEventType.Scroll){event->
                val delta=event.changes.sumOf {it.scrollDelta.y.toDouble()}
                if(delta!=0.0){controller.setVolume((controller.state.value.volume-if(delta>0).05f else -.05f).coerceIn(0f,1f));event.changes.forEach {it.consume()}}
            }
            .testTag("desktop-video")) {
            TvPlayerPanel(controller=controller,channelName=state.item?.title.orEmpty(),channelNameVisible=false,
                showControls=false,showDiagnostics=false,rounded=false,clockPosition=clockPosition,onVideoTap={if(state.item?.kind!=PlaybackContentKind.LIVE)onPause()},
                onVideoFocusedChange=onVideoFocus,onRetry={state.item?.let(controller::play)},onClose=controller::stop,
                onTogglePlayback=onPause,onToggleFullscreen=onFullscreen,modifier=Modifier.fillMaxSize())
        }
    }
    val dock:@Composable ()->Unit = {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha=.96f)).padding(10.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(state.item?.title ?: uiText("Каналы"),modifier=Modifier.testTag("desktop-playing-title"),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            epg.current?.let {Text(it.title,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)}
            epg.next?.let {Text(uiText("Далее")+": "+formatTimeOfDay(it.start)+" · "+it.title,
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
            state.diagnostics?.let {facts->
                Text(listOfNotNull(if(facts.actualMode==PlayerDiagnostics.MODE_HARDWARE)"А" else "П",facts.resolution,
                    facts.fps?.let {"$it fps"},facts.audio).joinToString("   ·   "),
                    modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.End,
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            DesktopTransport(controller,onPause,onFullscreen,onPrevious,onRecord,onInfo,onAspect,seekStepMs=LocalDesktopSettings.current.archiveSeekStepMs,epg=epg,onReturnLive=onReturnLive)
            audio.error?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error,maxLines=2)}
        }
    }
    if(cinema)Box(modifier.fillMaxSize()) {
        viewport(Modifier.fillMaxSize())
        if(controlsVisible)Box(Modifier.align(Alignment.BottomCenter)){dock()}
    } else BoxWithConstraints(modifier.fillMaxWidth()) {
        // Reserve transport space before sizing a wide desktop video viewport.
        val natural=maxWidth*(9f/16f)
        val videoHeight=if(maxHeight.value.isFinite())natural.coerceAtMost((maxHeight-180.dp).coerceAtLeast(100.dp)) else natural
        Column(Modifier.fillMaxWidth()) {
            viewport(Modifier.fillMaxWidth().height(videoHeight))
            dock()
        }
    }
}

/** Row cursors scroll before moving focus, so arrows work beyond the visible viewport. */
internal data class DesktopListAnchor(val id:String,val offsetPx:Float?=null)

@Composable
internal fun <T> DesktopCursorList(items:List<T>,id:(T)->String,selected:String?,tag:String,
    onFocus:(T)->Unit={},onActivate:(T)->Unit,onSelect:(T)->Unit=onActivate,onLeft:(()->Unit)?=null,onRight:(()->Unit)?=null,
    modifier:Modifier=Modifier,list:LazyListState=rememberLazyListState(),anchor:DesktopListAnchor?=null,content:@Composable (T,Boolean)->Unit) {
    val scope=rememberCoroutineScope()
    val requesters=remember {mutableMapOf<String,FocusRequester>()}
    var cursor by remember(tag){mutableStateOf(selected)}
    LaunchedEffect(selected,items.size){
        if(cursor==null)cursor=selected
        val i=items.indexOfFirst {id(it)==selected}
        if(i>=0 && list.layoutInfo.visibleItemsInfo.none {it.index==i})list.scrollToItem((i-2).coerceAtLeast(0))
    }
    LaunchedEffect(anchor,items.size) {
        val target=anchor ?: return@LaunchedEffect
        val index=items.indexOfFirst {id(it)==target.id};if(index<0)return@LaunchedEffect
        withFrameNanos {}
        list.scrollToItem(index)
        withFrameNanos {}
        val height=list.layoutInfo.visibleItemsInfo.firstOrNull {it.index==index}?.size ?: return@LaunchedEffect
        val center=(target.offsetPx ?: (list.layoutInfo.viewportSize.height*.45f)).coerceIn(height/2f,(list.layoutInfo.viewportSize.height-height/2f).coerceAtLeast(height/2f))
        list.scrollToItem(index,-(center-height/2f).toInt())
    }
    LazyColumn(modifier.testTag(tag),state=list,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        itemsIndexed(items,key={_,item->id(item)}) {index,item->
            val key=id(item);val requester=remember(key){FocusRequester().also {requesters[key]=it}}
            DisposableEffect(key){onDispose {requesters.remove(key)}}
            var focused by remember {mutableStateOf(false)}
            Surface(color=if(key==selected)MaterialTheme.colorScheme.primary.copy(alpha=.15f)
                else if(focused)MaterialTheme.colorScheme.onSurface.copy(alpha=.08f) else Color.Transparent,
                shape=RoundedCornerShape(4.dp),border=if(focused)BorderStroke(1.dp,MaterialTheme.colorScheme.primary) else null,
                modifier=Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
                    focused=it.isFocused;if(it.isFocused){cursor=key;onFocus(item)}
                }.onPreviewKeyEvent {event->
                    if(event.type!=KeyEventType.KeyDown)return@onPreviewKeyEvent false
                    when(event.key){
                        Key.DirectionUp,Key.DirectionDown->{
                            val next=(index+if(event.key==Key.DirectionDown)1 else -1).coerceIn(0,items.lastIndex)
                            scope.launch {list.scrollToItem((next-2).coerceAtLeast(0));withFrameNanos {};requesters[id(items[next])]?.requestFocus()};true
                        }
                        Key.DirectionLeft->{onLeft?.invoke();onLeft!=null}
                        Key.DirectionRight->{onRight?.invoke();onRight!=null}
                        Key.Enter,Key.NumPadEnter->{onActivate(item);true}
                        else->false
                    }
                }.clickable {cursor=key;onSelect(item)}.testTag("$tag-$key")) {
                content(item,key==selected)
            }
        }
    }
}

@Composable
internal fun DesktopChannels(component:ChannelsComponent,runtime:DesktopServices,cinema:Boolean,chrome:Boolean,
    pause:()->Unit,fullscreen:()->Unit,home:()->Unit,guide:()->Unit,refreshEpg:()->Unit,epgState:EpgState,
    onEditing:(Boolean)->Unit,onVideoFocus:(Boolean)->Unit,clockPosition:ClockPosition=ClockPosition.OFF,
    showChannelOverlay:Boolean=false,onCloseOverlay:()->Unit={}) {
    val state by component.state.collectAsState()
    val player by component.playbackController.state.collectAsState()
    val nowPlaying by component.nowPlaying.collectAsState()
    val playingEpg by component.playingEpg.collectAsState()
    val timeline by component.timeline.collectAsState()
    var previewId by remember(component){mutableStateOf(player.item?.id ?: state.selectedChannelId)}
    val channel=state.channels.firstOrNull {it.id==(previewId ?: state.selectedChannelId)} ?: state.filteredChannels.firstOrNull()
    val channelList=rememberLazyListState()
    val programmeList=rememberLazyListState()
    val channelOffset by remember(channel?.id){derivedStateOf {
        channelList.layoutInfo.visibleItemsInfo.firstOrNull {it.key==channel?.id}?.let {it.offset+it.size/2f}
    }}
    val channelFocus=remember {FocusRequester()}
    val programmeFocus=remember {FocusRequester()}
    val videoFocus=remember {FocusRequester()}
    var programme by remember(channel?.id){mutableStateOf<EpgProgramme?>(null)}
    var anchored by remember(channel?.id,player.item?.id,player.item?.kind,player.item?.archiveEpgAnchorMs){mutableStateOf(false)}
    var programmeAnchor by remember(channel?.id){mutableStateOf<String?>(null)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(1000);now=System.currentTimeMillis()}}
    LaunchedEffect(component){component.effects.collect {effect->if(effect is ChannelsEffect.ShowError)runtime.message(effect.error.toUserMessage())}}
    DisposableEffect(component){component.setScreenVisible(true);onDispose {component.setScreenVisible(false);component.setBackHandler {false}}}
    LaunchedEffect(player.item?.id){
        player.item?.takeIf {it.kind!=PlaybackContentKind.VOD}?.let {previewId=it.id}
    }
    LaunchedEffect(cinema) {
        if(!cinema) {
            player.item?.takeIf {it.kind!=PlaybackContentKind.VOD}?.let {previewId=it.id}
            anchored=false
        }
    }
    LaunchedEffect(channel?.id){channel?.let {component.dispatch(ChannelsIntent.ShowChannelTimeline(it.id))}}
    LaunchedEffect(channel?.id,timeline.channelId,timeline.programmes,anchored){
        if(!anchored && timeline.channelId==channel?.id && timeline.programmes.isNotEmpty()) {
            val at=if(player.item?.id==channel?.id && player.item?.kind==PlaybackContentKind.ARCHIVE)
                (player.item?.archiveEpgAnchorMs ?: now)+player.positionMs else now
            programme=timeline.programmes.firstOrNull {at>=it.start && at<it.stop}
            programmeAnchor=programme?.start?.toString();anchored=true
        }
    }
    fun choose(c:Channel,play:Boolean){
        previewId=c.id
        component.dispatch(ChannelsIntent.FocusChannel(c.id))
        // A channel click always tunes live; only an explicit programme action opens archive.
        if(play){programme=null;programmeAnchor=null;anchored=false;component.dispatch(ChannelsIntent.SelectChannel(c.id))}
    }
    fun playProgramme(p:EpgProgramme){val c=channel ?: return
        if(timeline.channelId!=c.id || p !in timeline.programmes)return
        if(p.start<=now && p.stop>now)component.dispatch(ChannelsIntent.SelectChannel(c.id))
        else if(p.start<now)component.dispatch(ChannelsIntent.ArchiveProgrammeClicked(p,c.id))
        else runtime.scheduleRequest.value=RecordingScheduleRequest(c.name,p.title,c.streamUrl,p.start,p.stop,c.id)
    }
    val video:@Composable (Modifier)->Unit = {m->DesktopPlayerView(component.playbackController,cinema,chrome,pause,fullscreen,
        PlaybackUiRequests::previousChannel,runtime::toggleRecording,PlaybackUiRequests::showStreamInfo,
        {runtime.aspectDialog.value=true},onVideoFocus,playingEpg,m,clockPosition,
        onReturnLive={player.item?.id?.let {component.dispatch(ChannelsIntent.SelectChannel(it))};anchored=false})}
    if(cinema){
        Box(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxSize())
            if(showChannelOverlay)Surface(Modifier.align(Alignment.CenterStart).width(360.dp).fillMaxHeight().padding(top=52.dp).testTag("desktop-channel-overlay"),
                color=MaterialTheme.colorScheme.surface.copy(alpha=.98f),shadowElevation=8.dp) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(uiText("Каналы"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onCloseOverlay){Text(uiText("Закрыть"))}
                    }
                    DesktopChannelCategories(state.selectedCategory,state.categories,{
                        if(state.query.isNotBlank())component.dispatch(ChannelsIntent.SearchChanged(""))
                        component.dispatch(ChannelsIntent.CategorySelected(it))
                    },
                        Modifier.fillMaxWidth().padding(bottom=8.dp),"desktop-hover-categories",true)
                    key(state.selectedCategory,state.query) {
                        DesktopCursorList(state.filteredChannels,Channel::id,player.item?.id,"desktop-hover-channels",onActivate={choose(it,true)},
                            anchor=player.item?.id?.let {DesktopListAnchor(it)},modifier=Modifier.fillMaxSize()) {c,selected->
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(c.name,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
                                nowPlaying[c.id]?.let {DesktopChannelProgramme(c.id,it,now)}
                            }
                        }
                    }
                }
            }
        };return
    }
    if(state.isLoading && state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Text(uiText("Пожалуйста, подождите"))}};return
    }
    if(!state.hasPlaylist && state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(uiText("Добавьте M3U-плейлист в настройках."))};return
    }
    Row(Modifier.fillMaxSize().padding(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxHeight().weight(.46f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                DesktopChannelCategories(state.selectedCategory,state.categories,{component.dispatch(ChannelsIntent.CategorySelected(it))})
                OutlinedTextField(state.query,{component.dispatch(ChannelsIntent.SearchChanged(it))},singleLine=true,
                    placeholder={Text(uiText("Поиск"))},textStyle=MaterialTheme.typography.bodyMedium,
                    modifier=Modifier.weight(1f).onFocusChanged {onEditing(it.isFocused)}.testTag("desktop-channel-search"))
            }
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(uiText("Каналы"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.height(40.dp).wrapContentHeight(Alignment.CenterVertically))
                    DesktopCursorList(state.filteredChannels,Channel::id,player.item?.id,"desktop-channels",
                        list=channelList,anchor=player.item?.id?.let {DesktopListAnchor(it)},onFocus={choose(it,false)},onActivate={choose(it,true)},onRight={if(timeline.channelId==channel?.id && timeline.programmes.isNotEmpty())programmeFocus.requestFocus() else videoFocus.requestFocus()},modifier=Modifier.fillMaxSize().focusRequester(channelFocus).focusGroup()) {c,selected->
                        Row(Modifier.fillMaxWidth().heightIn(min=62.dp).padding(8.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            if(LocalDesktopSettings.current.showChannelNumbers)Text(c.number.toString(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(c.name,style=MaterialTheme.typography.bodyMedium,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
                                nowPlaying[c.id]?.let {DesktopChannelProgramme(c.id,it,now)}
                            }
                        }
                    }
                }
                Column(Modifier.weight(1.15f)) {
                    Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(channel?.name.orEmpty(),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        TextButton({programme=timeline.programmes.firstOrNull {now in it.start until it.stop};anchored=false}){Text(uiText("Сейчас"))}
                    }
                    val programmes=if(timeline.channelId==channel?.id)timeline.programmes else emptyList()
                    if(programmes.isEmpty())Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(timeline.loading || epgState.isLoading)uiText("Пожалуйста, подождите") else uiText("Нет данных EPG"),style=MaterialTheme.typography.bodySmall)
                        if(!epgState.isLoading)OutlinedButton(refreshEpg,shape=RoundedCornerShape(4.dp)){Text(uiText("Обновить EPG"))}
                        epgState.lastError?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    } else DesktopCursorList(programmes,{it.start.toString()},programme?.start?.toString(),"desktop-programmes",
                        list=programmeList,anchor=programmeAnchor?.let {DesktopListAnchor(it,channelOffset)},onFocus={programme=it},onActivate={programme=it;playProgramme(it)},onSelect={programme=it},onLeft={if(state.filteredChannels.isNotEmpty())channelFocus.requestFocus()},onRight={videoFocus.requestFocus()},modifier=Modifier.weight(1f).focusRequester(programmeFocus).focusGroup()) {p,selected->
                        Column(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=5.dp).combinedClickable(
                            onClick={programme=p},onDoubleClick={programme=p;playProgramme(p)})) {
                            Text(p.title,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                                fontWeight=if(now in p.start until p.stop)FontWeight.Bold else FontWeight.Normal)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(formatTimeOfDay(p.start)+"–"+formatTimeOfDay(p.stop),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatDate(p.start),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    programme?.let {p->OutlinedButton({playProgramme(p)},shape=RoundedCornerShape(4.dp),modifier=Modifier.fillMaxWidth()) {
                        Text(uiText(if(p.start>now)"Запланировать запись" else if(p.stop<=now)"Смотреть архив" else "Смотреть"))
                    }}
                }
            }
        }
        Column(Modifier.weight(.54f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            video(Modifier.fillMaxWidth().focusRequester(videoFocus))
            if(epgState.isLoading && LocalDesktopSettings.current.showEpgUpdateStatus)Text(epgState.notice ?: uiText("Обновление EPG"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DesktopProgrammeDetails(programme ?: playingEpg.current,Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun DesktopProgrammeDetails(programme:EpgProgramme?,modifier:Modifier=Modifier) {
    val finder=remember {GlobalContext.get().getOrNull<ProgrammeMetadataRepository>()}
    val request=remember(programme){programme?.let {ProgrammeMetadataRequest(it.title,it.category,it.description,
        ((it.stop-it.start)/60_000).toInt(),it.episodeNum)}}
    var remote by remember(request){mutableStateOf(request?.let {finder?.cached(it)})}
    LaunchedEffect(request){val query=request?.takeIf {it.canEnrich} ?: return@LaunchedEffect
        delay(200);try {finder?.observe(query)?.collect {remote=it}}
        catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){} }
    DisposableEffect(request){onDispose {request?.let {finder?.forget(it)}}}
    val description=remote?.overview?.takeIf(::hasProgrammeSynopsis) ?: programme?.description?.takeIf(::hasProgrammeSynopsis)
    DesktopDescription(programme?.title.orEmpty(),programme?.let {formatDateTime(it.start)}.orEmpty(),
        remote?.frameUrl ?: remote?.posterUrl,remote?.frameUrl!=null,description ?: uiText("Описание отсутствует"),modifier)
}

/** Artwork has finite bounds even inside a vertically scrolling description. */
@Composable
internal fun DesktopDescription(title:String,date:String,art:String?,frame:Boolean,text:String,modifier:Modifier=Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(title.isNotBlank())Text(title,style=MaterialTheme.typography.titleMedium)
        if(date.isNotBlank())Text(date,style=MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width=(maxWidth*.34f).coerceAtMost(if(frame)200.dp else 140.dp)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                art?.let {
                    Box(Modifier.width(width).height(if(frame)width*9f/16f else width*1.5f).testTag("desktop-description-art")) {
                        ChannelLogo(it,Modifier.fillMaxSize(),showPlaceholder=false)
                    }
                }
                Text(text,Modifier.weight(1f).testTag("desktop-description-text"),style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun DesktopChannelProgramme(channelId:String,programme:EpgProgramme,now:Long) {
    Text(programme.title,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
    val fraction=((now-programme.start).toDouble()/(programme.stop-programme.start).coerceAtLeast(1)).toFloat().coerceIn(0f,1f)
    val active=MaterialTheme.colorScheme.primary
    val track=MaterialTheme.colorScheme.onSurface.copy(alpha=.16f)
    Canvas(Modifier.fillMaxWidth().padding(top=4.dp).height(2.dp).testTag("desktop-channel-progress-$channelId")
        .semantics {progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f)}) {
        val y=size.height/2
        drawLine(track,androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width,y),size.height)
        drawLine(active,androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width*fraction,y),size.height)
    }
}

@Composable
internal fun DesktopChannelCategories(selected:String?,categories:List<String>,onSelect:(String?)->Unit,
    modifier:Modifier=Modifier,tag:String="desktop-channel-categories",showLabel:Boolean=false) {
    var expanded by remember {mutableStateOf(false)}
    fun label(category:String?)=when(category){null->uiText("Все каналы");"__favorites"->uiText("Избранное");else->category}
    Box(modifier) {
        OutlinedButton({expanded=true},shape=RoundedCornerShape(4.dp),modifier=(if(showLabel)Modifier.fillMaxWidth() else Modifier).testTag(tag)) {
            Text((if(showLabel)uiText("Категории")+": " else "")+label(selected),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=420.dp)) {
            (listOf<String?>(null,"__favorites")+categories).distinct().forEach {category->
                DropdownMenuItem(text={Text(label(category),fontWeight=if(category==selected)FontWeight.Bold else FontWeight.Normal)},
                    modifier=Modifier.testTag("$tag-${category ?: "all"}"),onClick={expanded=false;if(category!=selected)onSelect(category)})
            }
        }
    }
}

internal fun desktopMediaItems(state:MediaState,favoritesOnly:Boolean):List<MediaItem> =
    (if(favoritesOnly)state.copy(selectedCategory=null) else state).filteredItems.filter {!favoritesOnly || it.isFavorite}

@Composable
internal fun DesktopFavoriteToggle(item:MediaItem,onToggle:()->Unit,modifier:Modifier=Modifier) {
    val label=desktopText(if(item.isFavorite)"Удалить из избранного" else "Добавить в избранное")
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip {Text(label)}},state=rememberTooltipState()) {
        Surface(checked=item.isFavorite,onCheckedChange={onToggle()},shape=RoundedCornerShape(4.dp),color=Color.Black.copy(alpha=.75f),
            modifier=modifier.size(44.dp).testTag("desktop-favorite-${item.id}").semantics {contentDescription=label}) {
            Box(contentAlignment=Alignment.Center) {FavoriteStar(Modifier.size(30.dp),filled=item.isFavorite)}
        }
    }
}

@Composable
internal fun DesktopMedia(component:MediaComponent,runtime:DesktopServices,open:Boolean,onOpen:(Boolean)->Unit,
    cinema:Boolean,chrome:Boolean,pause:()->Unit,fullscreen:()->Unit,back:()->Unit,onEditing:(Boolean)->Unit,onVideoFocus:(Boolean)->Unit,clockPosition:ClockPosition=ClockPosition.OFF) {
    val state by component.state.collectAsState()
    var categories by remember {mutableStateOf(false)}
    var sources by remember {mutableStateOf(false)}
    val favoritesOnly by runtime.mediaFavoritesOnly.collectAsState()
    val hasFavorites=state.content.allItems.any {it.isFavorite}
    val films=desktopMediaItems(state,favoritesOnly)
    LaunchedEffect(hasFavorites,state.isLoading){if(!state.isLoading && !hasFavorites)runtime.mediaFavoritesOnly.value=false}
    val gridState=rememberLazyGridState()
    LaunchedEffect(open,films,state.preferences.rememberPosition) {
        if(!open && state.preferences.rememberPosition) {
            val index=films.indexOfFirst {it.id==state.browser.itemId}
            if(index>=0 && gridState.firstVisibleItemIndex==0)gridState.scrollToItem(index)
        }
    }
    LaunchedEffect(state.openPlayer){if(state.openPlayer){onOpen(true);component.dispatch(MediaIntent.PlayerShown)}}
    if(open || state.openPlayer) {
        Column(Modifier.fillMaxSize()) {
            DesktopPlayerView(component.playbackController,cinema,chrome,pause,fullscreen,PlaybackUiRequests::previousChannel,
                runtime::toggleRecording,PlaybackUiRequests::showStreamInfo,{runtime.aspectDialog.value=true},onVideoFocus,
                modifier=if(cinema)Modifier.fillMaxSize() else Modifier.weight(3f).fillMaxWidth(),clockPosition=clockPosition)
            if(!cinema)Column(Modifier.weight(1f).padding(12.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton({onOpen(false)},shape=RoundedCornerShape(4.dp)){Text(uiText("Медиатека"))}
                state.tmdbMetadata?.overview?.let {Text(it)}
            }
        }
    } else Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton({sources=true},shape=RoundedCornerShape(4.dp)){Text(state.content.sources.firstOrNull {it.id==state.selectedSourceId}?.name ?: uiText("Все источники"))}
                DropdownMenu(sources,{sources=false}) {
                    DropdownMenuItem(text={Text(uiText("Все источники"))},onClick={sources=false;runtime.mediaFavoritesOnly.value=false;component.dispatch(MediaIntent.SourceSelected(null))})
                    state.content.sources.forEach {source->DropdownMenuItem(text={Text(source.name)},onClick={sources=false;runtime.mediaFavoritesOnly.value=false;component.dispatch(MediaIntent.SourceSelected(source.id))})}
                }
            }
            Box {
                OutlinedButton({categories=true},shape=RoundedCornerShape(4.dp),modifier=Modifier.testTag("desktop-media-categories")) {
                    Text(if(favoritesOnly)uiText("Избранное") else state.selectedCategory ?: uiText("Все категории"))
                }
                DropdownMenu(categories,{categories=false},modifier=Modifier.heightIn(max=420.dp)) {
                    DropdownMenuItem(text={Text(uiText("Все категории"))},modifier=Modifier.testTag("desktop-media-category-all"),onClick={categories=false;runtime.mediaFavoritesOnly.value=false;if(state.selectedCategory!=null)component.dispatch(MediaIntent.CategorySelected(null))})
                    if(hasFavorites)DropdownMenuItem(text={Text(uiText("Избранное"))},modifier=Modifier.testTag("desktop-media-category-favorites"),onClick={
                        categories=false;runtime.mediaFavoritesOnly.value=true;if(state.selectedCategory!=null)component.dispatch(MediaIntent.CategorySelected(null))
                    })
                    state.categories.forEach {category->DropdownMenuItem(text={Text(category)},onClick={categories=false;runtime.mediaFavoritesOnly.value=false;if(state.selectedCategory!=category)component.dispatch(MediaIntent.CategorySelected(category))})}
                }
            }
            OutlinedTextField(state.query,{component.dispatch(MediaIntent.SearchChanged(it))},singleLine=true,
                placeholder={Text(uiText("Поиск"))},modifier=Modifier.weight(1f).onFocusChanged {onEditing(it.isFocused)}.testTag("desktop-media-search"))
            TextButton({component.dispatch(MediaIntent.Refresh)}){Text(uiText("Обновить"))}
        }
        if(state.isLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(!state.isLoading && films.isEmpty())Text(uiText("Ничего не найдено"),modifier=Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Adaptive(state.preferences.posterSize.width.dp),state=gridState,horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(films,key={it.id}) {item->
                Card(onClick={component.dispatch(MediaIntent.BrowserMoved(com.tvnext.app.feature.settings.domain.MediaBrowserPosition(
                    state.selectedSourceId,state.selectedCategory,item.id,favorites=favoritesOnly,inFilms=true)));component.dispatch(MediaIntent.SelectItem(item.id));onOpen(true)},shape=RoundedCornerShape(4.dp),modifier=Modifier.testTag("desktop-movie-${item.id}")) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f/3f).testTag("desktop-poster-${item.id}")) {
                        ChannelLogo(item.logoUrl,Modifier.fillMaxSize(),placeholderText=item.name)
                        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                            DesktopFavoriteToggle(item,{component.dispatch(MediaIntent.FavoriteToggled(item.id))})
                        }
                    }
                    Text(item.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp))
                }
            }
        }
    }
    state.pendingResume?.let {pending->AlertDialog(onDismissRequest={component.dispatch(MediaIntent.ResumeCancelled);onOpen(false)},
        title={Text(pending.title)},text={Text(uiText("Продолжить просмотр")+" · "+desktopTime(pending.positionMs))},
        confirmButton={TextButton({component.dispatch(MediaIntent.ResumeChosen(true))}){Text(uiText("Продолжить"))}},
        dismissButton={TextButton({component.dispatch(MediaIntent.ResumeChosen(false))}){Text(uiText("Сначала"))}})}
}
