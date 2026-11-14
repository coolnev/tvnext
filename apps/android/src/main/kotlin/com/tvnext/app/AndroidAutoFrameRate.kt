package com.tvnext.app

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tvnext.app.core.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class DisplayModeSnapshot(val current: VideoDisplayMode?, val modes: List<VideoDisplayMode>)
internal data class DisplayWindowPreference(val modeId: Int, val refreshRate: Float)
internal interface FrameRateDisplayHost {
    fun snapshot(): DisplayModeSnapshot
    fun preference(): DisplayWindowPreference
    fun apply(preference: DisplayWindowPreference)
    fun observe(changed: () -> Unit): AutoCloseable
}

/** Never changes global Android settings; requests apply only to TVNext's own Window. */
internal class AndroidDisplayModeHost(private val activity: Activity): FrameRateDisplayHost {
    @Suppress("DEPRECATION")
    private fun display() = activity.window.decorView.display ?: activity.windowManager.defaultDisplay
    override fun snapshot(): DisplayModeSnapshot = try {
        val d=display()
        fun mode(compat:androidx.core.view.DisplayCompat.ModeCompat):VideoDisplayMode {
            val native=compat.toMode() ?: d.mode
            return VideoDisplayMode(native.modeId,compat.physicalWidth,compat.physicalHeight,native.refreshRate.toDouble(),
                if(Build.VERSION.SDK_INT>=31)native.alternativeRefreshRates.map(Float::toDouble) else emptyList())
        }
        DisplayModeSnapshot(mode(androidx.core.view.DisplayCompat.getMode(activity,d)),
            androidx.core.view.DisplayCompat.getSupportedModes(activity,d).map(::mode).distinctBy{it.id})
    } catch(_:Exception){DisplayModeSnapshot(null,emptyList())}
    override fun preference()=activity.window.attributes.let {DisplayWindowPreference(it.preferredDisplayModeId,it.preferredRefreshRate)}
    override fun apply(preference:DisplayWindowPreference) {
        val params=activity.window.attributes
        params.preferredDisplayModeId=preference.modeId
        params.preferredRefreshRate=preference.refreshRate
        activity.window.attributes=params
    }
    override fun observe(changed:()->Unit):AutoCloseable {
        val manager=activity.getSystemService(DisplayManager::class.java)
        val listener=object:DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId:Int)=changed()
            override fun onDisplayRemoved(displayId:Int)=changed()
            override fun onDisplayChanged(displayId:Int)=changed()
        }
        manager.registerDisplayListener(listener,Handler(Looper.getMainLooper()))
        return AutoCloseable {manager.unregisterDisplayListener(listener)}
    }
}

/** Main-thread, Activity-owned AFR session, independent from the long-lived player singleton. */
internal class AndroidAutoFrameRate(
    private val host:FrameRateDisplayHost,
    signal:StateFlow<VideoFrameSignal?>,
    playback:StateFlow<PlayerState>,
) : AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val _status=MutableStateFlow(AutoFrameRateStatus())
    val status:StateFlow<AutoFrameRateStatus> = _status.asStateFlow()
    private var preferences=AutoFrameRatePreferences()
    private var foreground=false
    private var visible=false
    private var fullscreen=false
    private var video:VideoFrameSignal?=null
    private var player=playback.value
    private var original=host.snapshot().current
    private var savedPreference=host.preference()
    private var ownsPreference=false
    private var closed=false
    private var pending:Job?=null
    private var verification:Job?=null
    private var pendingKey:RequestKey?=null
    private var rejected:RequestKey?=null
    private data class RequestKey(val item:String?,val video:VideoFrameSignal,val preferences:AutoFrameRatePreferences,val mode:Int)
    private val observation=host.observe { if(!closed)refresh() }

    init {
        scope.launch { combine(signal,playback.map{Triple(it.item,it.state,it.item?.url)}.distinctUntilChanged()) { s, p -> s to p }
            .collect {(s,p)-> video=s;player=player.copy(item=p.first,state=p.second);refresh()} }
        refresh()
    }
    fun updatePreferences(value:AutoFrameRatePreferences) {
        val normalized=value.normalized()
        if(preferences==normalized)return
        preferences=normalized;rejected=null;cancelPending();refresh()
    }
    fun setContext(videoVisible:Boolean, isFullscreen:Boolean) {
        if(visible==videoVisible && fullscreen==isFullscreen)return
        visible=videoVisible;fullscreen=isFullscreen;refresh()
    }
    fun setForeground(value:Boolean) {
        if(foreground==value)return
        foreground=value
        if(value && !ownsPreference) {original=host.snapshot().current;savedPreference=host.preference();rejected=null}
        refresh()
    }
    private fun cancelPending(){pending?.cancel();pending=null;pendingKey=null;verification?.cancel();verification=null}
    private fun restore() {
        cancelPending()
        if(ownsPreference) {
            ownsPreference=false
            val modes=host.snapshot().modes
            val saved=if(savedPreference.modeId==0 || modes.any{it.id==savedPreference.modeId})savedPreference else DisplayWindowPreference(0,0f)
            runCatching {host.apply(saved)}
        }
    }
    private fun refresh() {
        if(closed)return
        val snapshot=host.snapshot();val current=snapshot.current
        if(original?.id !in snapshot.modes.map{it.id})original=current
        val base=AutoFrameRateStatus(snapshot.modes,current,video=video)
        if(!preferences.enabled){restore();_status.value=base.copy(state=FrameRateMatchState.OFF);return}
        val active=foreground && visible && (!preferences.fullscreenOnly || fullscreen) && player.item!=null &&
            player.state !in setOf(PlaybackState.IDLE,PlaybackState.ENDED,PlaybackState.ERROR)
        if(!active){restore();_status.value=base.copy(state=FrameRateMatchState.READY);return}
        if(current==null || original==null || snapshot.modes.isEmpty()) {
            restore();_status.value=base.copy(state=FrameRateMatchState.UNAVAILABLE);return
        }
        val content=video
        if(content==null || (!content.validFps && !preferences.matchResolution)) {
            if(player.state==PlaybackState.PAUSED)restore() else {pending?.cancel();pending=null;pendingKey=null}
            _status.value=base.copy(state=FrameRateMatchState.WAITING);return
        }
        if(player.state==PlaybackState.PAUSED) {pending?.cancel();pending=null;pendingKey=null;return}
        val target=chooseVideoDisplayMode(snapshot.modes,current,original!!,content,preferences)
        if(target==null){restore();_status.value=base.copy(state=FrameRateMatchState.NO_MATCH);return}
        val key=RequestKey(player.item?.url,content,preferences,target.id)
        if(rejected==key){_status.value=base.copy(state=FrameRateMatchState.REJECTED);return}
        if(current.id==target.id) {
            pending?.cancel();pending=null;verification?.cancel();verification=null;pendingKey=null
            _status.value=base.copy(requested=target,state=FrameRateMatchState.APPLIED);return
        }
        if(pendingKey==key)return
        cancelPending();pendingKey=key
        _status.value=base.copy(requested=target,state=FrameRateMatchState.WAITING)
        pending=scope.launch {
            delay(preferences.delayMs.toLong())
            if(pendingKey!=key || closed)return@launch
            try {
                if(!ownsPreference){savedPreference=host.preference();ownsPreference=true}
                // A mode ID explicitly permits HDMI/resolution changes. Refresh-only requests
                // are used for seamless matching; the policy admits only reported alternatives.
                host.apply(if(preferences.strategy==FrameRateSwitchStrategy.ALWAYS)
                    DisplayWindowPreference(target.id,0f) else DisplayWindowPreference(0,target.refreshRate.toFloat()))
                _status.value=_status.value.copy(requested=target,state=FrameRateMatchState.REQUESTED)
                verification=scope.launch {
                    delay(5000)
                    if(host.snapshot().current?.id==target.id)refresh() else {
                        rejected=key;restore()
                        val now=host.snapshot()
                        _status.value=AutoFrameRateStatus(now.modes,now.current,video=video,state=FrameRateMatchState.REJECTED)
                    }
                }
                if(host.snapshot().current?.id==target.id)refresh()
            } catch(_:Exception) {
                rejected=key;restore();_status.value=base.copy(state=FrameRateMatchState.UNAVAILABLE)
            }
        }
    }
    override fun close() {
        if(closed)return
        closed=true;restore();observation.close();scope.cancel()
    }
}
