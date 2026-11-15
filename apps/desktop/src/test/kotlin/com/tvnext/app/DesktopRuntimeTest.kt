package com.tvnext.app

import com.tvnext.app.core.iptv.domain.ParentalChannelGate
import com.tvnext.app.core.player.*
import kotlinx.coroutines.*
import kotlin.test.*
import java.nio.file.Files

class DesktopRuntimeTest {
    private fun isolated(block:(DesktopStorageContext)->Unit) {
        val before=System.getProperty("tvnext.data.dir")
        val root=Files.createTempDirectory("tvnext-portable-test").toFile()
        System.setProperty("tvnext.data.dir",root.absolutePath)
        try{block(DesktopStorageContext())}finally {
            if(before==null)System.clearProperty("tvnext.data.dir") else System.setProperty("tvnext.data.dir",before)
            root.deleteRecursively();ParentalChannelGate.lock()
        }
    }
    @Test fun portableProfilesPinAndChannelChangesSurviveReopeningWithoutEditingProviderData()=isolated {context ->
        val profiles=LocalRecoveryStore(context);val personal=ProfilePersonalData(context,profiles)
        val added=assertNotNull(profiles.addProfile("测试 · Семья"))
        assertTrue(profiles.switchProfile(added.id))
        assertTrue(personal.saveStyle("DARK","BLUE"))
        assertTrue(personal.editChannel("channel",ChannelCustomization(name="Мой канал",category="Моя категория")))
        val reopened=LocalRecoveryStore(DesktopStorageContext())
        assertEquals(added.id,reopened.activeProfileId())
        assertEquals("Мой канал",ProfilePersonalData(context,reopened).channelEdits()["channel"]?.name)
        assertTrue(profiles.switchProfile("main"))
        assertFalse(personal.channelEdits().containsKey("channel"))
        val pin=ParentalProtection(context)
        assertTrue(pin.verify("1234"));assertTrue(pin.changePin("1234","7391"))
        pin.unlock();assertTrue(pin.unlocked);pin.lock();assertFalse(pin.unlocked)
        assertFalse(ParentalProtection(context).verify("1234"));assertTrue(ParentalProtection(context).verify("7391"))
    }
    @Test fun preferencesRetainTypesAndUnrelatedUpdates()=isolated {context ->
        val preferences=context.getSharedPreferences("test",0)
        assertTrue(preferences.edit().putString("text","Кино 🎬").putBoolean("on",true).putInt("count",7).commit())
        assertTrue(preferences.edit().putString("other","value").commit())
        assertEquals("Кино 🎬",preferences.getString("text",null));assertEquals(true,preferences.all["on"])
        assertEquals(7,preferences.all["count"]);assertEquals("value",preferences.getString("other",null))
    }
    @Test fun activeScheduledRecordingUsesTheSameStopActionAndOwnedDeletionRules()=isolated {context ->
        val store=LocalRecoveryStore(context)
        val folder=store.folderFor("capture-1");folder.resolve("capture.ts").writeBytes(byteArrayOf(0x47,0,1))
        val recording=SavedRecording("capture-1","main","Test",folder.absolutePath,1,0,"recording",scheduled=true)
        store.saveRecording(recording)
        RecordingRuntime.started(ActiveRecording(recording.id,"main",true,"http://fixture.invalid/live.ts","channel"))
        try {
            assertEquals(RecordButtonAction.STOP,recordButtonAction(RecordingRuntime.active.value,PlaybackItem("channel","Test","http://fixture.invalid/live.ts")))
            assertFalse(store.deleteRecording(recording.id))
        }finally {RecordingRuntime.finished(recording.id)}
        assertTrue(store.deleteRecording(recording.id));assertFalse(folder.exists())
    }
    @Test fun temporaryDisplaySwitchRestoresAndDoesNotPretend59Is5994()=runBlocking {
        class Host:DesktopModeHost {
            override val original=VideoDisplayMode(1,3840,2160,60.0)
            override val modes=listOf(original,VideoDisplayMode(2,3840,2160,50.0),VideoDisplayMode(3,3840,2160,59.0))
            val switches=mutableListOf<Int>();var restores=0
            override fun apply(mode:VideoDisplayMode):Boolean {switches+=mode.id;return true}
            override fun restore(){restores++}
        }
        val host=Host();var state=AutoFrameRateStatus()
        val session=DesktopFrameRateSession(host,this){state=it}
        val prefs=AutoFrameRatePreferences(enabled=true,delayMs=500)
        session.update(prefs,VideoFrameSignal(1920,1080,25.0),true,"a")
        delay(600);assertEquals(listOf(2),host.switches);assertEquals(FrameRateMatchState.APPLIED,state.state)
        session.update(prefs,VideoFrameSignal(1920,1080,60000.0/1001),true,"b")
        assertEquals(FrameRateMatchState.NO_MATCH,state.state)
        session.update(prefs.copy(enabled=false),null,false,null)
        assertEquals(1,host.restores);session.close();assertEquals(1,host.restores)
    }
}
