package com.tvnext.app

import android.app.Activity
import android.app.Application
import android.os.Looper
import com.tvnext.app.core.player.*
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class AutoFrameRateRegressionTest {
    private val sixty=VideoDisplayMode(1,3840,2160,60.0,listOf(50.0))
    private val fifty=VideoDisplayMode(2,3840,2160,50.0,listOf(60.0))
    private class Host(var modes:List<VideoDisplayMode>):FrameRateDisplayHost {
        var current=modes.first();var attrs=DisplayWindowPreference(0,0f)
        val writes=mutableListOf<DisplayWindowPreference>()
        var accept=true;var fail=false;var observers=0;var changed:(()->Unit)?=null
        override fun snapshot()=DisplayModeSnapshot(current,modes)
        override fun preference()=attrs
        override fun apply(preference:DisplayWindowPreference) {
            if(fail)throw SecurityException("denied")
            writes+=preference;attrs=preference
            if(accept) current=modes.firstOrNull {if(preference.modeId>0)it.id==preference.modeId else
                if(preference.refreshRate>0)kotlin.math.abs(it.refreshRate-preference.refreshRate)<.01 else it.id==modes.first().id} ?:current
        }
        override fun observe(changed:()->Unit):AutoCloseable {this.changed=changed;observers++;return AutoCloseable{observers--;this.changed=null}}
    }
    private val signal=MutableStateFlow<VideoFrameSignal?>(VideoFrameSignal(1920,1080,25.0))
    private val playback=MutableStateFlow(PlayerState(PlaybackState.PLAYING,PlaybackItem("c","Канал","file:///a")))
    private fun enabled(engine:AndroidAutoFrameRate,prefs:AutoFrameRatePreferences=AutoFrameRatePreferences(enabled=true)) {
        engine.setContext(true,true);engine.setForeground(true);engine.updatePreferences(prefs)
    }
    private fun waitMs(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun optInDelayAppliesOnlyOnceAndDisableRestoresTheExactWindowPreference() {
        val host=Host(listOf(sixty,fifty));val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            engine.setContext(true,true);engine.setForeground(true);waitMs(2000);assertTrue(host.writes.isEmpty())
            engine.updatePreferences(AutoFrameRatePreferences(enabled=true));waitMs(900);assertTrue(host.writes.isEmpty())
            waitMs(200);assertEquals(2,host.attrs.modeId);assertEquals(FrameRateMatchState.APPLIED,engine.status.value.state)
            playback.value=playback.value.copy(state=PlaybackState.BUFFERING);waitMs(500)
            playback.value=playback.value.copy(state=PlaybackState.PLAYING);waitMs(1500);assertEquals(1,host.writes.size)
            engine.updatePreferences(AutoFrameRatePreferences());assertEquals(DisplayWindowPreference(0,0f),host.attrs)
        } finally{engine.close()};assertEquals(0,host.observers)
    }
    @Test fun channelChangeCancelsOldPendingRequestAndDoesNotRestoreBetweenTwoChannels() {
        val cinema=VideoDisplayMode(3,3840,2160,24.0)
        val host=Host(listOf(sixty,fifty,cinema));signal.value=VideoFrameSignal(1920,1080,24.0)
        val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            enabled(engine);waitMs(500)
            signal.value=VideoFrameSignal(1920,1080,25.0);playback.value=playback.value.copy(item=PlaybackItem("b","B","file:///b"))
            waitMs(700);assertTrue(host.writes.isEmpty());waitMs(500)
            assertEquals(listOf(DisplayWindowPreference(2,0f)),host.writes)
        }finally{engine.close()}
    }
    @Test fun resolutionIsOptInAndTurningItOffReturnsToOriginalDimensions() {
        val smaller=VideoDisplayMode(3,1920,1080,50.0)
        val host=Host(listOf(sixty,fifty,smaller));val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            enabled(engine,AutoFrameRatePreferences(enabled=true,matchResolution=true));waitMs(1100);assertEquals(3,host.attrs.modeId)
            engine.updatePreferences(AutoFrameRatePreferences(enabled=true));waitMs(1100);assertEquals(2,host.attrs.modeId)
            engine.setContext(false,false);assertEquals(DisplayWindowPreference(0,0f),host.attrs)
        }finally{engine.close()}
    }
    @Test fun previewDoesNotSwitchByDefaultAndSeamlessModeNeverRequestsAResolutionChange() {
        val host=Host(listOf(sixty,fifty,VideoDisplayMode(3,1920,1080,50.0)));val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            enabled(engine,AutoFrameRatePreferences(enabled=true,strategy=FrameRateSwitchStrategy.SEAMLESS,matchResolution=true))
            engine.setContext(true,false);waitMs(1500);assertTrue(host.writes.isEmpty())
            engine.setContext(true,true);waitMs(1100);assertEquals(DisplayWindowPreference(0,50f),host.attrs)
            assertEquals(3840,host.current.width)
            engine.setForeground(false);assertEquals(DisplayWindowPreference(0,0f),host.attrs)
        }finally{engine.close()}
    }
    @Test fun deviceRefusalTimesOutAndIsNotRetriedOnBuffering() {
        val host=Host(listOf(sixty,fifty));host.accept=false
        val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            enabled(engine);waitMs(1100);assertEquals(FrameRateMatchState.REQUESTED,engine.status.value.state)
            waitMs(5100);assertEquals(FrameRateMatchState.REJECTED,engine.status.value.state)
            assertEquals(DisplayWindowPreference(0,0f),host.attrs)
            val count=host.writes.size
            playback.value=playback.value.copy(state=PlaybackState.BUFFERING);waitMs(100)
            playback.value=playback.value.copy(state=PlaybackState.PLAYING);waitMs(6000);assertEquals(count,host.writes.size)
        }finally{engine.close()}
    }
    @Test fun screenOffWhilePendingCannotApplyAnOldRequestAndWakeCanStartFresh() {
        val host=Host(listOf(sixty,fifty));val engine=AndroidAutoFrameRate(host,signal,playback)
        try {
            enabled(engine);waitMs(500);engine.setForeground(false);waitMs(2000);assertTrue(host.writes.isEmpty())
            engine.setForeground(true);waitMs(1100);assertEquals(2,host.attrs.modeId)
            signal.value=null;playback.value=playback.value.copy(state=PlaybackState.PAUSED);waitMs(100)
            assertEquals(DisplayWindowPreference(0,0f),host.attrs)
        }finally{engine.close()}
    }
    @Test fun unsupportedDeviceAndWindowFailureRemainUsable() {
        val host=Host(listOf(sixty));val engine=AndroidAutoFrameRate(host,signal,playback)
        try {enabled(engine);waitMs(1500);assertEquals(FrameRateMatchState.NO_MATCH,engine.status.value.state);assertTrue(host.writes.isEmpty())}
        finally{engine.close()}
        val bad=Host(listOf(sixty,fifty));bad.fail=true
        val denied=AndroidAutoFrameRate(bad,signal,playback)
        try{enabled(denied);waitMs(1100);assertEquals(FrameRateMatchState.UNAVAILABLE,denied.status.value.state)}finally{denied.close()}
    }
    @Test fun nativeWindowAdapterKeepsOtherAttributesAndReportsAndroidDisplayModes() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val fixture=org.robolectric.util.ReflectionHelpers.callStaticMethod<android.view.Display.Mode>(
            org.robolectric.shadows.ShadowDisplayManager::class.java,"displayModeOf",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,42),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,1920),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,1080),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType,50f))
        val display=activity.windowManager.defaultDisplay
        org.robolectric.shadows.ShadowDisplayManager.setSupportedModes(display.displayId,display.mode,fixture)
        val host=AndroidDisplayModeHost(activity)
        val initial=host.preference();val flags=activity.window.attributes.flags
        val snapshot=host.snapshot();assertNotNull(snapshot.current);assertTrue(snapshot.modes.isNotEmpty())
        val observation=host.observe{}
        try {
            assertTrue(snapshot.modes.any{it.id==42 && it.width==1920 && it.height==1080 && it.refreshRate==50.0})
            host.apply(DisplayWindowPreference(42,0f))
            assertEquals(42,activity.window.attributes.preferredDisplayModeId)
            assertEquals(flags,activity.window.attributes.flags)
            host.apply(initial);assertEquals(initial,host.preference())
        }finally{observation.close();activity.finish()}
    }
}
