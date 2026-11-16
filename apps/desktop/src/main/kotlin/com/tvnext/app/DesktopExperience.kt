package com.tvnext.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.tvnext.app.core.app.*
import com.tvnext.app.core.app.tab.*
import com.tvnext.app.core.common.UiLanguage
import com.tvnext.app.core.common.util.*
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.player.*
import com.tvnext.app.core.tmdb.*
import com.tvnext.app.core.ui.component.*
import com.tvnext.app.core.ui.i18n.uiText
import com.tvnext.app.feature.home.ui.HomeIntent
import com.tvnext.app.feature.channels.ui.ChannelsIntent
import com.tvnext.app.feature.settings.domain.*
import com.tvnext.app.feature.settings.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.context.GlobalContext
import java.io.File
import javax.swing.JFileChooser

@Composable
internal fun DesktopExperience(hostWindow:java.awt.Window,root:DefaultAppComponent,onToggleFullscreen:()->Unit,isFullscreen:Boolean,
    immersive:Boolean,onImmersiveChange:(Boolean)->Unit,showDiagnostics:Boolean,onToggleDiagnostics:()->Unit) {
    val koin=GlobalContext.get()
    val runtime=remember {koin.get<DesktopServices>()}
    DisposableEffect(runtime.playback,hostWindow) {
        (runtime.playback as? MpvPlaybackController)?.attachGpuWindow(hostWindow)
        onDispose {(runtime.playback as? MpvPlaybackController)?.detachGpuWindow(hostWindow)}
    }
    val page by runtime.page.collectAsState()
    val notice by runtime.notice.collectAsState()
    val animation by runtime.animation.collectAsState()
    val revision by runtime.revision.collectAsState()
    val active by RecordingRuntime.active.collectAsState()
    val epg by remember {koin.get<EpgRepository>().observeState()}.collectAsState()
    val stack by root.childStack.subscribeAsState()
    val pendingPin by runtime.pinRequest.collectAsState()
    val pendingSchedule by runtime.scheduleRequest.collectAsState()
    val aspect by runtime.aspectDialog.collectAsState()
    val settings by root.settings.collectAsState()
    val fps by remember(runtime.playback) {
        (runtime.playback as? MpvPlaybackController)?.videoSignal ?: MutableStateFlow<VideoFrameSignal?>(null)
    }.collectAsState()
    val playback by runtime.playback.state.collectAsState()
    val afrScope=rememberCoroutineScope()
    var applicationFocused by remember(hostWindow){mutableStateOf(true)}
    DisposableEffect(hostWindow) {
        val listener=object:java.awt.event.WindowAdapter() {
            private fun refresh() {afrScope.launch {
                delay(180)
                var active=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                var owned=false
                while(active!=null){if(active===hostWindow){owned=true;break};active=active.owner}
                applicationFocused=owned
            }}
            override fun windowGainedFocus(event:java.awt.event.WindowEvent){refresh()}
            override fun windowLostFocus(event:java.awt.event.WindowEvent){refresh()}
        }
        hostWindow.addWindowFocusListener(listener)
        onDispose {hostWindow.removeWindowFocusListener(listener)}
    }
    val afr=remember(hostWindow){DesktopFrameRateSession(WindowsModeHost(hostWindow),afrScope)}
    DisposableEffect(afr){onDispose {afr.close()}}
    LaunchedEffect(settings.autoFrameRate,fps,playback.item?.id,playback.state,immersive,isFullscreen,stack.active.configuration,applicationFocused) {
        val watching=stack.active.configuration in listOf(AppTab.CHANNELS,AppTab.MEDIA_LIBRARY) &&
            playback.state in listOf(PlaybackState.LOADING,PlaybackState.PLAYING,PlaybackState.READY,PlaybackState.BUFFERING,PlaybackState.PAUSED)
        afr.update(settings.autoFrameRate,fps,watching && applicationFocused && (!settings.autoFrameRate.fullscreenOnly || isFullscreen && immersive),playback.item?.id)
    }
    SideEffect {root.setForceImmersiveHandler {onImmersiveChange(true)}}
    LaunchedEffect(root) {root.backExit.collect {if(it==BackExitState.CONFIRM_EXIT)
        runtime.message(uiText("Еще раз нажмите Назад (Back), чтобы выйти из TVNext"))}}
    LaunchedEffect(revision,page) {
        if(page!=DesktopPage.NONE)return@LaunchedEffect
        when(val tab=root.childStack.value.active.instance) {
            is AppTabComponent.Home->tab.component.dispatch(HomeIntent.Load)
            is AppTabComponent.Channels->tab.component.dispatch(ChannelsIntent.Load)
            else->Unit
        }
    }
    DesktopShell(root,runtime,isFullscreen,onToggleFullscreen,immersive,onImmersiveChange,animation,epg,notice,active!=null,
        settingsContent={component,back->DesktopSettings(component,runtime,back)},overlays={DesktopOverlays(runtime)})

}

@Composable
private fun DesktopOverlays(runtime:DesktopServices) {
    val page by runtime.page.collectAsState()
    val pin by runtime.pinRequest.collectAsState()
    val schedule by runtime.scheduleRequest.collectAsState()
    val aspect by runtime.aspectDialog.collectAsState()
    when(page) {
        DesktopPage.EDITOR->ProfileChannelEditor {runtime.changed();runtime.page.value=DesktopPage.NONE}
        DesktopPage.RECORDINGS->DesktopRecordings(runtime)
        DesktopPage.PROFILES->DesktopProfiles(runtime)
        DesktopPage.RECORDING_VIDEO->Unit
        DesktopPage.NONE->Unit
    }
    pin?.let {granted ->ParentalPinEntryDialog(uiText("Родительский контроль"),{runtime.pinRequest.value=null}) {value ->
        if(runtime.protection.verify(value)){runtime.protection.unlock();runtime.pinRequest.value=null;granted();true}else false
    }}
    schedule?.let {request ->AlertDialog(onDismissRequest={runtime.scheduleRequest.value=null},
        title={Text(uiText("Запланировать запись"))},text={Column {
            Text(request.programmeTitle);Text(request.channelName)
            Text(formatDateTime(request.startAt)+" — "+formatTimeOfDay(request.stopAt))
        }},confirmButton={TextButton(onClick={runtime.schedule(request)}){Text(uiText("Записать"))}},
        dismissButton={TextButton(onClick={runtime.scheduleRequest.value=null}){Text(uiText("Отмена"))}})}
    if(aspect)AlertDialog(onDismissRequest={runtime.aspectDialog.value=false},title={Text(uiText("Соотношение сторон"))},
        text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            VideoAspectMode.entries.filter {it!=VideoAspectMode.INHERIT}.forEach {mode ->
                DesktopRow(mode.displayName){runtime.setAspect(mode)}
            }
        }},confirmButton={})
}

@Composable
internal fun DesktopRow(label:String,value:String="",checked:Boolean?=null,onClick:()->Unit) {
    TvCardButton(onClick,modifier=Modifier.fillMaxWidth().heightIn(min=42.dp),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=7.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiText(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)
            if(checked!=null)Switch(checked,onCheckedChange=null,modifier=Modifier.height(26.dp))
            else if(value.isNotBlank())Text(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun DesktopSettings(component:SettingsComponent,runtime:DesktopServices,onBack:(()->Unit)?) {
    val animation by runtime.animation.collectAsState()
    val state by component.state.collectAsState()
    var choice by remember {mutableStateOf<Pair<String,List<Pair<String,()->Unit>>>?>(null)}
    val scope=rememberCoroutineScope()
    fun options(label:String,items:List<Pair<String,()->Unit>>) {choice=uiText(label) to items}
    DesktopSettingsScreen(component,onBackHome=onBack,platformSections=listOf(
        DesktopSettingsSection(uiText("Общее")) {
            DesktopRow("Экран при запуске",startScreenLabel(state.settings.startScreen)) {
                options("Экран при запуске",StartScreen.entries.map {screen ->
                    uiText(when(screen){StartScreen.HOME->"Главная";StartScreen.CHANNELS->"Каналы";StartScreen.TV_GUIDE->"ТВ-гид";StartScreen.MEDIA_LIBRARY->"Медиатека"}) to
                        {component.dispatch(SettingsIntent.StartScreenChanged(screen))}})
            }
            DesktopRow("Открывать последний канал",checked=state.settings.openLastChannel){component.dispatch(SettingsIntent.OpenLastChannelChanged(!state.settings.openLastChannel))}
            DesktopRow("Запускать на весь экран",checked=state.settings.startFullscreen){component.dispatch(SettingsIntent.StartFullscreenChanged(!state.settings.startFullscreen))}
            DesktopRow("Запускать при загрузке Windows",checked=state.settings.launchOnWindowsStartup){component.dispatch(SettingsIntent.LaunchOnWindowsStartupChanged(!state.settings.launchOnWindowsStartup))}
            DesktopRow("Разрешение окна (Windows)",state.settings.windowResolution.displayName){options("Разрешение окна (Windows)",WindowResolution.entries.map {it.displayName to {component.dispatch(SettingsIntent.WindowResolutionChanged(it))}})}
            Text("F11 · "+uiText("Полный экран")+"   Esc · "+uiText("Назад")+"   Enter · OK   I · "+uiText("Информация о потоке"),style=MaterialTheme.typography.bodySmall)
        },
        DesktopSettingsSection(uiText("Запись")) {DesktopRecordingSettings(runtime)},
        DesktopSettingsSection(uiText("Профили")) {
            DesktopRow("Профили",runtime.profiles.profiles().firstOrNull {it.id==runtime.profiles.activeProfileId()}?.name.orEmpty()) {runtime.page.value=DesktopPage.PROFILES}
        },
        DesktopSettingsSection(uiText("Родительский контроль")) {DesktopParentalSettings(runtime)},
    ),sectionContent={section,current ->when(section) {
        0->{
            Text(uiText("Воспроизведение"),style=MaterialTheme.typography.titleLarge)
            val mode by runtime.windowsVideoMode.collectAsState()
            val renderStatus by runtime.videoRenderStatus.collectAsState()
            DesktopRow(desktopText("Декодирование и вывод видео"),desktopText(mode.label)) {
                options(desktopText("Декодирование и вывод видео"),WindowsVideoMode.entries.map {desktopText(it.label) to {runtime.setWindowsVideoMode(it)}})
            }
            Text(desktopText("Совместимый режим сохраняет аппаратное декодирование и вывод через память."),style=MaterialTheme.typography.bodySmall)
            if(renderStatus.isNotBlank())Text(desktopText(renderStatus),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DesktopRow("Режим звука",audioModeLabel(current.settings.audioOutputMode)) {
                options("Режим звука",AudioOutputMode.entries.map {mode ->uiText(when(mode){AudioOutputMode.AUTO->"Авто";AudioOutputMode.PCM_STEREO->"PCM стерео";AudioOutputMode.PASSTHROUGH->"Передавать на устройство"}) to
                    {component.dispatch(SettingsIntent.AudioOutputModeChanged(mode))}})
            }
            DesktopRow("Буфер сети",uiText("{0} с",current.settings.networkCachingMs/1000)) {
                options("Буфер сети",listOf(1000,3000,5000,8000).map {ms ->uiText("{0} с",ms/1000) to {component.dispatch(SettingsIntent.NetworkCachingChanged(ms))}})
            }
            DesktopRow("Шаг перемотки",uiText("{0} с",current.settings.archiveSeekStepMs/1000)) {
                options("Шаг перемотки",listOf(10_000L,30_000L,60_000L,300_000L).map {ms ->uiText("{0} с",ms/1000) to {component.dispatch(SettingsIntent.ArchiveSeekStepChanged(ms))}})
            }
            val status by DesktopFrameRateRuntime.status.collectAsState()
            AutoFrameRateSettings(current.settings.autoFrameRate,status,{component.dispatch(SettingsIntent.AutoFrameRateChanged(it))})
            true
        }
        2->{
            Text(uiText("Оформление"),style=MaterialTheme.typography.titleLarge)
            DesktopRow("Язык интерфейса",current.settings.language.nativeName){options("Язык интерфейса",UiLanguage.entries.map {it.nativeName to {component.dispatch(SettingsIntent.LanguageChanged(it))}})}
            DesktopRow("Тема",uiText(current.settings.theme.displayName)){options("Тема",ThemePreference.entries.map {uiText(it.displayName) to {component.dispatch(SettingsIntent.ThemeChanged(it))}})}
            DesktopRow("Часы на экране",uiText(current.settings.clockPosition.displayName)){options("Часы на экране",ClockPosition.entries.map {uiText(it.displayName) to {component.dispatch(SettingsIntent.ClockPositionChanged(it))}})}
            DesktopRow("Часы без фона",checked=current.settings.clockWithoutBackground){component.dispatch(SettingsIntent.ClockWithoutBackgroundChanged(!current.settings.clockWithoutBackground))}
            DesktopRow("Подпись архива на видео",checked=current.settings.showArchiveLabel){component.dispatch(SettingsIntent.ShowArchiveLabelChanged(!current.settings.showArchiveLabel))}
            DesktopRow("Статус обновления EPG",checked=current.settings.showEpgUpdateStatus){component.dispatch(SettingsIntent.ShowEpgUpdateStatusChanged(!current.settings.showEpgUpdateStatus))}
            DesktopRow("Анимация",checked=animation){runtime.setAnimation(!animation)}
            DesktopRow("Прозрачность панелей",current.settings.panelTransparencyPercent.toString()+" %") {
                options("Прозрачность панелей",listOf(0,20,40,60,80).map {it.toString()+" %" to {component.dispatch(SettingsIntent.PanelTransparencyChanged(it))}})
            };true
        }
        4->{DesktopMovieSources(component,current);true}
        else->false
    }})
    choice?.let {selection->AlertDialog(onDismissRequest={choice=null},title={Text(selection.first)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            selection.second.forEach {(label,action)->DesktopRow(label){action();choice=null}}
        }},confirmButton={})}
}

@Composable
private fun DesktopMovieSources(component:SettingsComponent,state:SettingsState) {
    val koin=GlobalContext.get();val kino=remember {koin.get<KinopoiskKeyStore>()}
    val lookup=remember {koin.get<KinopoiskProgrammeLookup>()}
    val metadata=remember {koin.get<ProgrammeMetadataRepository>()}
    val scope=rememberCoroutineScope()
    var configured by remember {mutableStateOf(false)}
    var testing by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var edit by remember {mutableStateOf<String?>(null)}
    var key by remember {mutableStateOf("")}
    LaunchedEffect(Unit){configured=kino.getKey().isNotBlank()}
    MovieSourceSettings("TMDB",state.tmdbEnabled,state.tmdbApiKey.isNotBlank(),state.tmdbTesting,true,
        {component.dispatch(SettingsIntent.TmdbEnabledChanged(it))},{edit="TMDB";key=""},
        {component.dispatch(SettingsIntent.TmdbTestConnection)},{component.dispatch(SettingsIntent.TmdbClearCache)},state.tmdbConnectionResult?.uiMessage())
    Spacer(Modifier.height(10.dp))
    MovieSourceSettings(uiText("Кинопоиск"),state.settings.kinopoiskEnabled,configured,testing,true,
        {component.dispatch(SettingsIntent.KinopoiskEnabledChanged(it))},{edit="Кинопоиск";key=""},
        {if(!testing)scope.launch {testing=true;try {message=lookup.checkConnection().uiMessage()}finally{testing=false}}},
        {component.dispatch(SettingsIntent.KinopoiskClearCache);message=uiText("Кэш очищен")},message)
    edit?.let {provider->AlertDialog(onDismissRequest={edit=null},title={Text(uiText("API-ключ")+" · "+uiText(provider))},
        text={OutlinedTextField(key,{key=it},singleLine=true,visualTransformation=PasswordVisualTransformation())},
        confirmButton={TextButton(onClick={scope.launch {
            if(provider=="TMDB")component.dispatch(SettingsIntent.TmdbApiKeyChanged(key))
            else {kino.setKey(key);configured=kino.getKey().isNotBlank();lookup.clearCache();metadata.clearCache()}
            edit=null
        }}){Text(uiText("Сохранить"))}},dismissButton={TextButton(onClick={edit=null}){Text(uiText("Отмена"))}})}
}

@Composable
private fun DesktopRecordingSettings(runtime:DesktopServices) {
    var revision by remember {mutableIntStateOf(0)}
    val store=runtime.profiles
    DesktopRow("Каталог записей",remember(revision){store.selectedRecordingLocation()?.root?.absolutePath.orEmpty()}) {
        val chooser=JFileChooser().apply {fileSelectionMode=JFileChooser.DIRECTORIES_ONLY}
        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION && RecordingStorageLocations.choose(runtime.context,chooser.selectedFile)) {
            store.selectRecordingLocation("desktop-storage://selected");revision++
        }
    }
    DesktopRow("Лимит записей",store.limitGiB().toString()+" GiB") {
        val values=listOf(1,2,4,8,16,32,64,128);store.setLimitGiB(values[(values.indexOf(store.limitGiB())+1)%values.size]);revision++
    }
    DesktopRow("До начала передачи",store.paddingBeforeMinutes().toString()+" "+uiText("мин")) {
        store.setRecordingPadding((store.paddingBeforeMinutes()+1)%11,store.paddingAfterMinutes());revision++
    }
    DesktopRow("После окончания передачи",store.paddingAfterMinutes().toString()+" "+uiText("мин")) {
        store.setRecordingPadding(store.paddingBeforeMinutes(),(store.paddingAfterMinutes()+1)%16);revision++
    }
}

@Composable
private fun DesktopParentalSettings(runtime:DesktopServices) {
    var revision by remember {mutableIntStateOf(0)}
    val enabled=remember(revision){runtime.protection.enabled}
    var change by remember {mutableStateOf(false)}
    var old by remember {mutableStateOf("")};var replacement by remember {mutableStateOf("")}
    DesktopRow("Родительский контроль",checked=enabled) {
        val next=!runtime.protection.enabled
        if(!next && !runtime.protection.unlocked)runtime.pinRequest.value={runtime.protection.enabled=false;revision++}
        else {runtime.protection.enabled=next;revision++}
    }
    DesktopRow("Изменить PIN-код"){change=true}
    if(change)AlertDialog(onDismissRequest={change=false},title={Text(uiText("Изменить PIN-код"))},text={Column {
        OutlinedTextField(old,{old=it.filter(Char::isDigit).take(8)},label={Text(uiText("Текущий PIN-код"))},visualTransformation=PasswordVisualTransformation())
        OutlinedTextField(replacement,{replacement=it.filter(Char::isDigit).take(8)},label={Text(uiText("Новый PIN-код"))},visualTransformation=PasswordVisualTransformation())
    }},confirmButton={TextButton(onClick={if(runtime.protection.changePin(old,replacement)){change=false;revision++}else runtime.message(uiText("Неверный PIN-код"))}){Text(uiText("Сохранить"))}})
}

@Composable
private fun DesktopProfiles(runtime:DesktopServices) {
    val revision by runtime.revision.collectAsState()
    val profiles=remember(revision){runtime.profiles.profiles()}
    var add by remember {mutableStateOf(false)};var name by remember {mutableStateOf("")}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            DesktopActionButton(uiText("Назад"),{runtime.page.value=DesktopPage.NONE})
            Text(uiText("Профили"),style=MaterialTheme.typography.headlineMedium)
        }
        profiles.forEach {profile->DesktopRow(profile.name,if(profile.id==runtime.profiles.activeProfileId())"✓" else ""){runtime.switchProfile(profile.id)}}
        DesktopRow("Добавить профиль"){add=true;name=""}
    }
    if(add)AlertDialog(onDismissRequest={add=false},title={Text(uiText("Добавить профиль"))},text={OutlinedTextField(name,{name=it.take(40)},singleLine=true)},
        confirmButton={TextButton(onClick={runtime.profiles.addProfile(name)?.let {runtime.changed();add=false}}){Text(uiText("Сохранить"))}})
}

@Composable
private fun DesktopRecordings(runtime:DesktopServices) {
    val revision by runtime.revision.collectAsState();val active by RecordingRuntime.active.collectAsState()
    var records by remember {mutableStateOf<List<SavedRecording>>(emptyList())}
    var schedules by remember {mutableStateOf<List<ScheduledRecording>>(emptyList())}
    LaunchedEffect(revision){withContext(Dispatchers.IO){records=runtime.profiles.recordings();schedules=runtime.profiles.schedules().filter {it.profileId==runtime.profiles.activeProfileId()}}}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            DesktopActionButton(uiText("Назад"),{runtime.page.value=DesktopPage.NONE})
            Text(uiText("Мои записи"),style=MaterialTheme.typography.headlineMedium)
            DesktopActionButton(uiText("Открыть папку"),{val dir=runtime.profiles.selectedRecordingLocation()?.root ?: return@DesktopActionButton;dir.mkdirs();java.awt.Desktop.getDesktop().open(dir)})
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(schedules,key={"schedule:"+it.id}) {item->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text(item.title);Text(formatDateTime(item.startAt),style=MaterialTheme.typography.bodySmall)}
                DesktopActionButton(uiText("Отменить"),{runtime.removeSchedule(item.id)})
            }}
            items(records,key={it.id}) {record->Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text(record.title,style=MaterialTheme.typography.titleMedium);Text(formatDateTime(record.startedAt)+" · "+record.statusLabel(),style=MaterialTheme.typography.bodySmall)}
                    DesktopActionButton(uiText("Воспроизвести"),{runtime.playRecording(record)})
                    if(active?.id==record.id)DesktopActionButton(uiText("Остановить"),runtime::stopRecording)
                    DesktopActionButton(uiText("Удалить"),{runtime.deleteRecording(record)})
                }
                if(record.error.isNotBlank())Text(uiText(record.error),color=MaterialTheme.colorScheme.error)
            }}
            if(records.isEmpty() && schedules.isEmpty())item {Text(uiText("Нет записей"))}
        }
    }
}

@Composable
private fun DesktopActionButton(label:String,onClick:()->Unit) {TvActionButton(onClick=onClick){Text(label)}}

private fun startScreenLabel(value:StartScreen)=uiText(when(value){StartScreen.HOME->"Главная";StartScreen.CHANNELS->"Каналы";StartScreen.TV_GUIDE->"ТВ-гид";StartScreen.MEDIA_LIBRARY->"Медиатека"})
private fun audioModeLabel(value:AudioOutputMode)=uiText(when(value){AudioOutputMode.AUTO->"Авто";AudioOutputMode.PCM_STEREO->"PCM стерео";AudioOutputMode.PASSTHROUGH->"Передавать на устройство"})
