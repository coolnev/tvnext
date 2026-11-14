package com.tvnext.app

import android.app.Application
import android.content.Context
import android.os.PowerManager
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class PlaybackSettingsReturnTest {
    private fun nativePlayer(controller:AndroidPlaybackController):Any? {
        val field=controller.javaClass.getDeclaredField("_player").apply{isAccessible=true}
        return (field.get(controller) as StateFlow<*>).value
    }
    @Test fun systemSettingsPauseReleasesTheOldPlayerAndReturnBuildsANewVideoSessionForTheSameChannel() {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)){_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)
        } as ChannelsRepository
        val archives=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        val controller=AndroidPlaybackController(context,channels,archives)
        val owner=PlaybackDisplayLifecycle(context,controller::enterForeground,controller::enterBackground)
        try {
            owner.onStart();owner.onResume()
            controller.setVolume(.6f);controller.setMuted(true)
            val item=PlaybackItem("live","Канал","file:///missing-fixture.ts")
            controller.play(item)
            val before=nativePlayer(controller)
            assertNotNull("Media3 must have been created",before)
            assertEquals(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                (before as androidx.media3.exoplayer.ExoPlayer).videoChangeFrameRateStrategy)
            owner.onPause() // Android permission settings: onStop is deliberately absent.
            assertNull(nativePlayer(controller))
            owner.onResume()
            assertNotNull(nativePlayer(controller));assertNotSame(before,nativePlayer(controller))
            assertEquals(item,controller.state.value.item)
            assertEquals(.6f,controller.state.value.volume)
            assertTrue(controller.state.value.isMuted)
        } finally {owner.close();controller.release()}
    }
}
