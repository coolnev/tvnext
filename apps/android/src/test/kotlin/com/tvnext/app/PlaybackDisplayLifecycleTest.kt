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
            owner.onStart(); owner.onResume()
            event(Intent.ACTION_SCREEN_OFF)
            event(Intent.ACTION_SCREEN_OFF)
            assertEquals(listOf("release", "resume", "release"), calls)
            event(Intent.ACTION_SCREEN_ON)
            event(Intent.ACTION_SCREEN_ON)
            assertEquals(listOf("release", "resume", "release", "resume"), calls)
            owner.onStop()
            event(Intent.ACTION_SCREEN_OFF)
            event(Intent.ACTION_SCREEN_ON)
            assertEquals(listOf("release", "resume", "release", "resume", "release"), calls)
            owner.onStart(); owner.onResume()
            assertEquals("resume", calls.last())
        } finally { owner.close() }
    }

    @Test fun returningFromTranslucentAndroidSettingsRecreatesPlaybackWithoutAnActivityStop() {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val calls=mutableListOf<String>()
        val owner=PlaybackDisplayLifecycle(context,{calls+="resume"},{calls+="release"})
        try {
            owner.onStart();owner.onResume()
            assertEquals("resume",calls.last())
            owner.onPause();owner.onPause()
            assertEquals(listOf("release","resume","release"),calls)
            // The Activity remained STARTED behind the system permission page.
            owner.onResume();owner.onResume()
            assertEquals(listOf("release","resume","release","resume"),calls)
        } finally {owner.close()}
    }

    @Test fun activityStartingWhileScreenIsOffWaitsForWakeAndRecreationResumesRetainedPlayer() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        val calls = mutableListOf<String>()
        fun create() = PlaybackDisplayLifecycle(context, { calls += "resume" }, { calls += "release" })
        val first = create()
        first.onStart(); first.onResume()
        assertEquals(listOf("release"), calls)
        power.setIsInteractive(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        first.onStop(); first.close()
        val recreated = create()
        try {
            recreated.onStart(); recreated.onResume()
            assertEquals(listOf("release", "resume", "release", "release", "resume"), calls)
        } finally { recreated.close() }
    }
}
