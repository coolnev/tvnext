package com.tvnext.app

import com.tvnext.app.core.common.util.*
import com.tvnext.app.core.iptv.data.*
import com.tvnext.app.core.iptv.domain.ParentalChannelGate
import com.tvnext.app.core.player.*
import com.tvnext.app.core.ui.i18n.uiText
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class DesktopPage { NONE, RECORDINGS, EDITOR, PROFILES, RECORDING_VIDEO }
internal class DesktopServices(val playback:PlaybackController,val channels:ChannelsRepository,
    val profiles:LocalRecoveryStore,val personal:ProfilePersonalData,val protection:ParentalProtection,
    val context:DesktopStorageContext,private val settings:com.tvnext.app.feature.settings.data.SettingsRepository) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val preferences=context.getSharedPreferences("desktop_ui",0)
    val page=MutableStateFlow(DesktopPage.NONE)
    val notice=MutableStateFlow<String?>(null)
    val aspectDialog=MutableStateFlow(false)
    val pinRequest=MutableStateFlow<(() -> Unit)?>(null)
    val scheduleRequest=MutableStateFlow<RecordingScheduleRequest?>(null)
    val revision=MutableStateFlow(0L)
    val animation=MutableStateFlow(preferences.getBoolean("animation",false))
    private var captureJob:Job?=null
    private var noticeJob:Job?=null
    private var previousPlayback:PlayerState?=null
    private val mainScope get()=scope

    init {
        profiles.recoverInterrupted()
        ParentalChannelGate.handler={channel,granted ->
            if(!protection.enabled || protection.unlocked || !ParentalChannelGate.isRestricted(channel))true
            else {pinRequest.value=granted;false}
        }
        scope.launch {PlaybackUiRequests.aspectRequests.collect {aspectDialog.value=true}}
        scope.launch {PlaybackUiRequests.recordRequests.collect {toggleRecording()}}
        scope.launch {RecordingPanelEvents.requests.collect {page.value=DesktopPage.RECORDINGS}}
        scope.launch {RecordingScheduleRequests.requests.collect {scheduleRequest.value=it}}
        scope.launch {PlaybackUiRequests.previousRequests.collect {
            val id=PreviousChannelHistory.target() ?: return@collect
            val channel=withContext(Dispatchers.IO){channels.getChannels().find {it.id==id}} ?: return@collect
            if(ParentalChannelGate.request(channel){playback.play(PlaybackItem(channel.id,channel.name,channel.streamUrl))})
                playback.play(PlaybackItem(channel.id,channel.name,channel.streamUrl))
        }}
        scope.launch {playback.state.map {it.item?.id}.distinctUntilChanged().collect {id ->
            val mode=VideoAspectMode.resolved(id?.let {personal.channelPlaybackSettings(it).resize},preferences.getString("aspect","fit"))
            PlaybackUiRequests.aspectLabel.value=mode.displayName
            (playback as? MpvPlaybackController)?.updateAspect(mode)
        }}
        scope.launch {
            while(isActive) {
                val now=System.currentTimeMillis()
                val due=withContext(Dispatchers.IO){profiles.schedules().filter {it.startAt<=now}.sortedBy {it.startAt}}
                for(entry in due) {
                    val end=entry.startAt+entry.durationMs
                    if(end<=now) {withContext(Dispatchers.IO){profiles.removeSchedule(entry.id)};changed();continue}
                    if(RecordingRuntime.active.value!=null)break
                    startRecording(PlaybackItem(entry.channelId,entry.title,entry.streamUrl),end-now,true,entry.profileId)
                    withContext(Dispatchers.IO){profiles.removeSchedule(entry.id)};changed()
                }
                delay(2_000)
            }
        }
    }
    fun message(text:String) {notice.value=text;noticeJob?.cancel();noticeJob=scope.launch {delay(3_500);notice.value=null}}
    fun changed(){revision.value++}
    fun setAnimation(enabled:Boolean){preferences.edit().putBoolean("animation",enabled).apply();animation.value=enabled}
    fun setAspect(mode:VideoAspectMode) {
        preferences.edit().putString("aspect",mode.token).apply()
        PlaybackUiRequests.aspectLabel.value=mode.displayName
        (playback as? MpvPlaybackController)?.updateAspect(mode)
        aspectDialog.value=false
    }
    fun toggleRecording() {
        when(recordButtonAction(RecordingRuntime.active.value,playback.state.value.item)) {
            RecordButtonAction.START->playback.state.value.item?.let {startRecording(it)}
            RecordButtonAction.STOP->stopRecording()
            RecordButtonAction.BUSY->message(uiText("Уже идёт запись другого канала"))
            RecordButtonAction.LIVE_REQUIRED->message(uiText("Сначала включите прямой эфир"))
        }
    }
    fun startRecording(item:PlaybackItem,durationMs:Long=6*60*60_000L,scheduled:Boolean=false,owner:String=profiles.activeProfileId()) {
        if(RecordingRuntime.active.value!=null)return
        val id=UUID.randomUUID().toString()
        RecordingRuntime.started(ActiveRecording(id,owner,scheduled,item.url,item.id))
        captureJob=scope.launch(Dispatchers.IO) {
            var record:SavedRecording?=null
            try {
                val folder=profiles.folderFor(id)
                val remaining=profiles.limitGiB()*1024L*1024*1024-profiles.recordingBytes()
                if(remaining<=0)throw RecordingFailure(RecordingFailureReason.LIMIT)
                record=SavedRecording(id,owner,item.title,folder.absolutePath,System.currentTimeMillis(),0,"recording",scheduled=scheduled)
                profiles.saveRecording(record);withContext(Dispatchers.Main){changed();message(uiText("Запись начата"))}
                StreamRecordingEngine(remaining).capture(item.url,folder,durationMs.coerceIn(1,6*60*60_000L))
                record=record.copy(finishedAt=System.currentTimeMillis(),status="complete")
            }catch(cancelled:CancellationException) {
                record=record?.copy(finishedAt=System.currentTimeMillis(),status="stopped")
            }catch(error:Exception) {
                record=record?.copy(finishedAt=System.currentTimeMillis(),status="partial",error=recordingFailureMessage(error))
                withContext(NonCancellable+Dispatchers.Main){message(recordingFailureMessage(error))}
            }finally {
                withContext(NonCancellable) {
                    record?.let {profiles.saveRecording(it)}
                    withContext(Dispatchers.Main){RecordingRuntime.finished(id);changed();message(record?.error?.takeIf {it.isNotBlank()} ?: uiText("Запись остановлена"))}
                }
            }
        }
    }
    fun stopRecording(){captureJob?.cancel()}
    fun deleteRecording(record:SavedRecording) {scope.launch {
        if(RecordingRuntime.active.value?.id==record.id) {captureJob?.cancelAndJoin()}
        val deleted=withContext(Dispatchers.IO){profiles.deleteRecording(record.id,record.profileId)}
        changed();message(uiText(if(deleted)"Запись удалена" else "Запись не удалена"))
    }}
    fun schedule(request:RecordingScheduleRequest):Boolean {
        val before=profiles.paddingBeforeMinutes()*60_000L;val after=profiles.paddingAfterMinutes()*60_000L
        val start=(request.startAt-before).coerceAtLeast(System.currentTimeMillis())
        val length=(request.stopAt+after-start).coerceIn(1,6*60*60_000L)
        if(profiles.schedules().any {recordingSlotsOverlap(start,length,it.startAt,it.durationMs)}) {
            message(uiText("Запись пересекается с другой записью"));return false
        }
        profiles.schedule(ScheduledRecording(UUID.randomUUID().toString(),profiles.activeProfileId(),request.programmeTitle,
            request.streamUrl,start,length,request.channelId));changed();scheduleRequest.value=null
        message(uiText("Запись запланирована"));return true
    }
    fun removeSchedule(id:String){profiles.removeSchedule(id);changed()}
    fun playRecording(record:SavedRecording) {
        val folder=File(record.folder)
        if(!profiles.recordingFolderOwned(folder))return
        val file=listOf("recording.m3u8","capture.ts").map {File(folder,it)}.firstOrNull {it.isFile && it.length()>0}
        if(file==null){message(uiText("Файл записи не найден"));return}
        previousPlayback=playback.state.value
        playback.play(PlaybackItem("recording:${record.id}",record.title,file.toURI().toString(),PlaybackContentKind.VOD))
        page.value=DesktopPage.RECORDING_VIDEO
    }
    fun closeRecording() {
        val old=previousPlayback;previousPlayback=null
        val item=old?.item
        if(item!=null){playback.play(item);if(item.kind!=PlaybackContentKind.LIVE)playback.seekTo(old.positionMs)}
        else playback.stop()
        page.value=DesktopPage.RECORDINGS
    }
    fun switchProfile(id:String) {
        if(profiles.switchProfile(id)){playback.stop();protection.lock();PreviousChannelHistory.clear();scope.launch {settings.save(settings.load());changed();page.value=DesktopPage.NONE}}
    }
    fun close(){captureJob?.cancel();scope.cancel();ParentalChannelGate.handler=null;protection.lock()}
}
