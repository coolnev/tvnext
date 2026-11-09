package com.tvnext.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class PlaybackDisplayLifecycleTest {
    @Test fun screenSleepReleasesPlaybackEvenWithoutActivityStopAndWakeResumesOnlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(true)
        val calls = mutableListOf<String>()
        val owner = PlaybackDisplayLifecycle(context, { calls += "resume" }, { calls += "release" })
        fun event(action: String) {
            context.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            owner.onStart()
            event(Intent.ACTION_SCREEN_OFF)
            event(Intent.ACTION_SCREEN_OFF)
            assertEquals(listOf("resume", "release"), calls)
            event(Intent.ACTION_SCREEN_ON)
            event(Intent.ACTION_SCREEN_ON)
            assertEquals(listOf("resume", "release", "resume"), calls)
            owner.onStop()
            event(Intent.ACTION_SCREEN_OFF)
            event(Intent.ACTION_SCREEN_ON)
            assertEquals(listOf("resume", "release", "resume", "release"), calls)
            owner.onStart()
            assertEquals("resume", calls.last())
        } finally { owner.close() }
    }

    @Test fun activityStartingWhileScreenIsOffWaitsForWakeAndRecreationResumesRetainedPlayer() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        val calls = mutableListOf<String>()
        fun create() = PlaybackDisplayLifecycle(context, { calls += "resume" }, { calls += "release" })
        val first = create()
        first.onStart()
        assertEquals(listOf("release"), calls)
        power.setIsInteractive(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        first.onStop(); first.close()
        val recreated = create()
        try {
            recreated.onStart()
            assertEquals(listOf("release", "resume", "release", "resume"), calls)
        } finally { recreated.close() }
    }
}
