@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
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
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.i18n.uiText
import com.tvnext.app.feature.channels.ui.*
import com.tvnext.app.feature.media.ui.*
import kotlinx.coroutines.*
import org.koin.core.context.GlobalContext

@Composable
internal fun DesktopPlayerView(controller:PlaybackController,cinema:Boolean,controlsVisible:Boolean,onPause:()->Unit,
    onFullscreen:()->Unit,onPrevious:()->Unit,onRecord:()->Unit,onInfo:()->Unit,onAspect:()->Unit,onVideoFocus:(Boolean)->Unit,
    epg:ChannelEpg=ChannelEpg(),modifier:Modifier=Modifier,clockPosition:ClockPosition=ClockPosition.OFF) {
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
                showControls=false,showDiagnostics=false,rounded=false,clockPosition=clockPosition,onVideoTap=onPause,
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
            DesktopTransport(controller,onPause,onFullscreen,onPrevious,onRecord,onInfo,onAspect,seekStepMs=LocalDesktopSettings.current.archiveSeekStepMs)
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
@Composable
internal fun <T> DesktopCursorList(items:List<T>,id:(T)->String,selected:String?,tag:String,
    onFocus:(T)->Unit={},onActivate:(T)->Unit,onSelect:(T)->Unit=onActivate,onLeft:(()->Unit)?=null,onRight:(()->Unit)?=null,
    modifier:Modifier=Modifier,content:@Composable (T,Boolean)->Unit) {
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val requesters=remember {mutableMapOf<String,FocusRequester>()}
    var cursor by remember(tag){mutableStateOf(selected)}
    LaunchedEffect(selected,items.size){
        if(cursor==null)cursor=selected
        val i=items.indexOfFirst {id(it)==selected}
        if(i>=0 && list.layoutInfo.visibleItemsInfo.none {it.index==i})list.scrollToItem((i-2).coerceAtLeast(0))
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
    onEditing:(Boolean)->Unit,onVideoFocus:(Boolean)->Unit,clockPosition:ClockPosition=ClockPosition.OFF) {
    val state by component.state.collectAsState()
    val player by component.playbackController.state.collectAsState()
    val nowPlaying by component.nowPlaying.collectAsState()
    val playingEpg by component.playingEpg.collectAsState()
    val timeline by component.timeline.collectAsState()
    var categories by remember {mutableStateOf(false)}
    var previewId by remember(component){mutableStateOf(player.item?.id ?: state.selectedChannelId)}
    val channel=state.channels.firstOrNull {it.id==(previewId ?: state.selectedChannelId)} ?: state.filteredChannels.firstOrNull()
    val channelFocus=remember {FocusRequester()}
    val programmeFocus=remember {FocusRequester()}
    val videoFocus=remember {FocusRequester()}
    var programme by remember(channel?.id){mutableStateOf<EpgProgramme?>(null)}
    var anchored by remember(channel?.id){mutableStateOf(false)}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(1000);now=System.currentTimeMillis()}}
    LaunchedEffect(component){component.effects.collect {effect->if(effect is ChannelsEffect.ShowError)runtime.message(effect.error.toUserMessage())}}
    DisposableEffect(component){component.setScreenVisible(true);onDispose {component.setScreenVisible(false);component.setBackHandler {false}}}
    LaunchedEffect(channel?.id){channel?.let {component.dispatch(ChannelsIntent.ShowChannelTimeline(it.id))}}
    LaunchedEffect(channel?.id,timeline.programmes){
        if(!anchored && timeline.channelId==channel?.id && timeline.programmes.isNotEmpty()) {
            val at=if(player.item?.id==channel?.id && player.item?.kind==PlaybackContentKind.ARCHIVE)
                (player.item?.archiveEpgAnchorMs ?: now)+player.positionMs else now
            programme=timeline.programmes.firstOrNull {at>=it.start && at<it.stop};anchored=true
        }
    }
    fun choose(c:Channel,play:Boolean){previewId=c.id;component.dispatch(ChannelsIntent.FocusChannel(c.id));if(play)component.dispatch(ChannelsIntent.SelectChannel(c.id))}
    fun playProgramme(p:EpgProgramme){val c=channel ?: return
        if(p.start<=now && p.stop>now)component.dispatch(ChannelsIntent.SelectChannel(c.id))
        else if(p.start<now)component.dispatch(ChannelsIntent.ArchiveProgrammeClicked(p,c.id))
        else runtime.scheduleRequest.value=RecordingScheduleRequest(c.name,p.title,c.streamUrl,p.start,p.stop,c.id)
    }
    val video:@Composable (Modifier)->Unit = {m->DesktopPlayerView(component.playbackController,cinema,chrome,pause,fullscreen,
        PlaybackUiRequests::previousChannel,runtime::toggleRecording,PlaybackUiRequests::showStreamInfo,
        {runtime.aspectDialog.value=true},onVideoFocus,playingEpg,m,clockPosition)}
    if(cinema){video(Modifier.fillMaxSize());return}
    if(state.isLoading && state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Text(uiText("Пожалуйста, подождите"))}};return
    }
    if(!state.hasPlaylist && state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(uiText("Добавьте M3U-плейлист в настройках."))};return
    }
    Row(Modifier.fillMaxSize().padding(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxHeight().weight(.46f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                Box {
                    OutlinedButton({categories=true},shape=RoundedCornerShape(4.dp)){Text(when(state.selectedCategory){null->uiText("Все каналы");"__favorites"->uiText("Избранное");else->state.selectedCategory.orEmpty()},maxLines=1)}
                    DropdownMenu(categories,{categories=false},modifier=Modifier.heightIn(max=420.dp)) {
                        (listOf<String?>(null,"__favorites")+state.categories).forEach {category->
                            DropdownMenuItem(text={Text(when(category){null->uiText("Все каналы");"__favorites"->uiText("Избранное");else->category})},onClick={
                                categories=false;component.dispatch(ChannelsIntent.CategorySelected(category))
                            })
                        }
                    }
                }
                OutlinedTextField(state.query,{component.dispatch(ChannelsIntent.SearchChanged(it))},singleLine=true,
                    placeholder={Text(uiText("Поиск"))},textStyle=MaterialTheme.typography.bodyMedium,
                    modifier=Modifier.weight(1f).onFocusChanged {onEditing(it.isFocused)}.testTag("desktop-channel-search"))
            }
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(uiText("Каналы"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=6.dp))
                    DesktopCursorList(state.filteredChannels,Channel::id,player.item?.id,"desktop-channels",
                        onFocus={choose(it,false)},onActivate={choose(it,true)},onRight={if(timeline.channelId==channel?.id && timeline.programmes.isNotEmpty())programmeFocus.requestFocus() else videoFocus.requestFocus()},modifier=Modifier.fillMaxSize().focusRequester(channelFocus).focusGroup()) {c,selected->
                        Row(Modifier.fillMaxWidth().heightIn(min=62.dp).padding(8.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            if(LocalDesktopSettings.current.showChannelNumbers)Text(c.number.toString(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(c.name,style=MaterialTheme.typography.bodyMedium,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
                                nowPlaying[c.id]?.let {Text(it.title,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
                Column(Modifier.weight(1.15f)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(channel?.name.orEmpty(),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        TextButton({programme=timeline.programmes.firstOrNull {now in it.start until it.stop};anchored=false}){Text(uiText("Сейчас"))}
                    }
                    val programmes=if(timeline.channelId==channel?.id)timeline.programmes else emptyList()
                    if(programmes.isEmpty())Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(timeline.loading || epgState.isLoading)uiText("Пожалуйста, подождите") else uiText("Нет данных EPG"),style=MaterialTheme.typography.bodySmall)
                        if(!epgState.isLoading)OutlinedButton(refreshEpg,shape=RoundedCornerShape(4.dp)){Text(uiText("Обновить EPG"))}
                        epgState.lastError?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    } else DesktopCursorList(programmes,{it.start.toString()},programme?.start?.toString(),"desktop-programmes",
                        onFocus={programme=it},onActivate={programme=it;playProgramme(it)},onSelect={programme=it},onLeft={if(state.filteredChannels.isNotEmpty())channelFocus.requestFocus()},onRight={videoFocus.requestFocus()},modifier=Modifier.weight(1f).focusRequester(programmeFocus).focusGroup()) {p,selected->
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
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        programme?.let {Text(it.title,style=MaterialTheme.typography.titleMedium);Text(formatDateTime(it.start),style=MaterialTheme.typography.labelSmall)}
        (remote?.frameUrl ?: remote?.posterUrl)?.let {ChannelLogo(it,Modifier.fillMaxWidth().heightIn(max=180.dp).aspectRatio(16f/9f),showPlaceholder=false)}
        Text(description ?: uiText("Описание отсутствует"),style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DesktopMedia(component:MediaComponent,runtime:DesktopServices,open:Boolean,onOpen:(Boolean)->Unit,
    cinema:Boolean,chrome:Boolean,pause:()->Unit,fullscreen:()->Unit,back:()->Unit,onEditing:(Boolean)->Unit,onVideoFocus:(Boolean)->Unit,clockPosition:ClockPosition=ClockPosition.OFF) {
    val state by component.state.collectAsState()
    var categories by remember {mutableStateOf(false)}
    var sources by remember {mutableStateOf(false)}
    val gridState=rememberLazyGridState()
    LaunchedEffect(open,state.filteredItems,state.preferences.rememberPosition) {
        if(!open && state.preferences.rememberPosition) {
            val index=state.filteredItems.indexOfFirst {it.id==state.browser.itemId}
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
                    DropdownMenuItem(text={Text(uiText("Все источники"))},onClick={sources=false;component.dispatch(MediaIntent.SourceSelected(null))})
                    state.content.sources.forEach {source->DropdownMenuItem(text={Text(source.name)},onClick={sources=false;component.dispatch(MediaIntent.SourceSelected(source.id))})}
                }
            }
            Box {
                OutlinedButton({categories=true},shape=RoundedCornerShape(4.dp)){Text(state.selectedCategory ?: uiText("Все категории"))}
                DropdownMenu(categories,{categories=false},modifier=Modifier.heightIn(max=420.dp)) {
                    DropdownMenuItem(text={Text(uiText("Все категории"))},onClick={categories=false;component.dispatch(MediaIntent.CategorySelected(null))})
                    state.categories.forEach {category->DropdownMenuItem(text={Text(category)},onClick={categories=false;component.dispatch(MediaIntent.CategorySelected(category))})}
                }
            }
            OutlinedTextField(state.query,{component.dispatch(MediaIntent.SearchChanged(it))},singleLine=true,
                placeholder={Text(uiText("Поиск"))},modifier=Modifier.weight(1f).onFocusChanged {onEditing(it.isFocused)}.testTag("desktop-media-search"))
            TextButton({component.dispatch(MediaIntent.Refresh)}){Text(uiText("Обновить"))}
        }
        if(state.isLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(GridCells.Adaptive(state.preferences.posterSize.width.dp),state=gridState,horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(state.filteredItems,key={it.id}) {item->
                Card(onClick={component.dispatch(MediaIntent.BrowserMoved(com.tvnext.app.feature.settings.domain.MediaBrowserPosition(
                    state.selectedSourceId,state.selectedCategory,item.id,inFilms=true)));component.dispatch(MediaIntent.SelectItem(item.id));onOpen(true)},shape=RoundedCornerShape(4.dp),modifier=Modifier.testTag("desktop-movie-${item.id}")) {
                    ChannelLogo(item.logoUrl,Modifier.fillMaxWidth().aspectRatio(2f/3f),placeholderText=item.name)
                    Text(item.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp))
                    TextButton({component.dispatch(MediaIntent.FavoriteToggled(item.id))}){Text(if(item.isFavorite)"★" else "☆")}
                }
            }
        }
    }
    state.pendingResume?.let {pending->AlertDialog(onDismissRequest={component.dispatch(MediaIntent.ResumeCancelled);onOpen(false)},
        title={Text(pending.title)},text={Text(uiText("Продолжить просмотр")+" · "+desktopTime(pending.positionMs))},
        confirmButton={TextButton({component.dispatch(MediaIntent.ResumeChosen(true))}){Text(uiText("Продолжить"))}},
        dismissButton={TextButton({component.dispatch(MediaIntent.ResumeChosen(false))}){Text(uiText("Сначала"))}})}
}
