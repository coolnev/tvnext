package com.tvnext.app

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import com.tvnext.app.core.player.*
import java.awt.Window
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Windows mode changes are temporary and always restore the captured mode. */
internal interface DesktopModeHost {
    val modes:List<VideoDisplayMode>
    val original:VideoDisplayMode?
    fun apply(mode:VideoDisplayMode):Boolean
    fun restore()
}
internal class WindowsModeHost(window:Window):DesktopModeHost {
    private interface Api:StdCallLibrary {
        fun EnumDisplaySettingsW(name:WString?,index:Int,mode:Pointer):Boolean
        fun ChangeDisplaySettingsExW(name:WString?,mode:Pointer?,window:Pointer?,flags:Int,param:Pointer?):Int
        fun MonitorFromWindow(window:Pointer?,flags:Int):Pointer?
        fun GetMonitorInfoW(monitor:Pointer?,info:Pointer):Boolean
    }
    private val api=if(Platform.isWindows())runCatching {Native.load("user32",Api::class.java)}.getOrNull() else null
    private var name:WString?=null
    private val nativeModes=mutableMapOf<Int,Memory>()
    private var originalNative:Memory?=null
    override val modes:List<VideoDisplayMode>
    override val original:VideoDisplayMode?
    init {
        if(api!=null) {
            runCatching {
                val monitor=api!!.MonitorFromWindow(Native.getComponentPointer(window),2)
                val info=Memory(104).apply {clear();setInt(0,104)}
                if(api!!.GetMonitorInfoW(monitor,info))name=WString(info.getWideString(40))
            }
        }
        val current=modeMemory()
        originalNative=if(api?.EnumDisplaySettingsW(name,-1,current)==true)current else null
        val list=mutableListOf<VideoDisplayMode>()
        if(api!=null)for(index in 0 until 1024) {
            val memory=modeMemory()
            if(!api.EnumDisplaySettingsW(name,index,memory))break
            val mode=memory.toMode(index+1)
            if(mode.width<=0 || mode.height<=0 || mode.refreshRate<10 || memory.getInt(168)!=32 || memory.getInt(180) and 2!=0)continue
            if(list.any {it.width==mode.width && it.height==mode.height && it.refreshRate==mode.refreshRate})continue
            nativeModes[mode.id]=memory;list+=mode
        }
        modes=list
        original=originalNative?.toMode(0)?.let {currentMode ->
            list.firstOrNull {it.width==currentMode.width && it.height==currentMode.height && it.refreshRate==currentMode.refreshRate} ?: currentMode
        }
    }
    override fun apply(mode:VideoDisplayMode):Boolean {
        val api=api ?: return false
        val memory=nativeModes[mode.id] ?: return false
        // Resolution, color depth and refresh only; never reposition a monitor or save a registry mode.
        memory.setInt(72,0x00040000 or 0x00080000 or 0x00100000 or 0x00400000)
        if(api.ChangeDisplaySettingsExW(name,memory,null,2,null)!=0)return false
        return api.ChangeDisplaySettingsExW(name,memory,null,4,null)==0
    }
    override fun restore(){originalNative?.let {memory ->api?.ChangeDisplaySettingsExW(name,memory,null,0,null)}}
    private fun modeMemory()=Memory(220).apply {clear();setShort(68,220.toShort())}
    private fun Memory.toMode(id:Int)=VideoDisplayMode(id,getInt(172),getInt(176),getInt(184).toDouble())
}

internal object DesktopFrameRateRuntime {
    val status=MutableStateFlow(AutoFrameRateStatus())
}

internal class DesktopFrameRateSession(private val host:DesktopModeHost,
    private val scope:CoroutineScope,private val publish:(AutoFrameRateStatus)->Unit={DesktopFrameRateRuntime.status.value=it}) {
    private var pending:Job?=null
    private var applied:VideoDisplayMode?=null
    private var key:Any?=null
    fun update(preferences:AutoFrameRatePreferences,video:VideoFrameSignal?,eligible:Boolean,sourceId:String?) {
        val nextKey=listOf(preferences,video,eligible,sourceId)
        if(key==nextKey)return
        key=nextKey;pending?.cancel()
        val original=host.original
        fun report(state:FrameRateMatchState,requested:VideoDisplayMode?=null) {
            publish(AutoFrameRateStatus(host.modes,applied ?: original,requested,video,state))
        }
        if(!preferences.enabled || !eligible || sourceId==null) {
            if(applied!=null){host.restore();applied=null}
            report(if(preferences.enabled)FrameRateMatchState.WAITING else FrameRateMatchState.OFF);return
        }
        if(video==null) {
            report(FrameRateMatchState.WAITING)
            if(applied!=null)pending=scope.launch {delay(5_000);host.restore();applied=null;report(FrameRateMatchState.WAITING)}
            return
        }
        if(original==null || host.modes.isEmpty()){report(FrameRateMatchState.UNAVAILABLE);return}
        val target=chooseVideoDisplayMode(host.modes,applied ?: original,original,video,preferences)
        if(target==null){if(applied!=null){host.restore();applied=null};report(if(video.validFps)FrameRateMatchState.NO_MATCH else FrameRateMatchState.WAITING);return}
        if(target==applied || target.id==original.id && applied==null){report(FrameRateMatchState.READY,target);return}
        report(FrameRateMatchState.WAITING,target)
        pending=scope.launch {
            delay(preferences.delayMs.toLong())
            if(host.apply(target)){applied=target;report(FrameRateMatchState.APPLIED,target)}
            else {host.restore();applied=null;report(FrameRateMatchState.REJECTED,target)}
        }
    }
    fun close(){pending?.cancel();if(applied!=null)host.restore();applied=null}
}
