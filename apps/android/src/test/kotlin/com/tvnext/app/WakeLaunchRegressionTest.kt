package com.tvnext.app

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class WakeLaunchRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val power get() = shadowOf(context.getSystemService(PowerManager::class.java))
    private val keyguard get() = shadowOf(context.getSystemService(KeyguardManager::class.java))
    private val services = mutableListOf<ServiceController<WakeLaunchService>>()

    @Before fun prepare() {
        WakeLaunchSettings.preferences(context).edit().clear().commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ShadowSettings.setCanDrawOverlays(true)
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(false)
    }
    @After fun clean() {
        services.forEach { it.destroy() }
        WakeLaunchSettings.preferences(context).edit().clear().commit()
    }
    private fun start(action: String? = WakeLaunchService.MONITOR): WakeLaunchService {
        assertTrue(WakeLaunchSettings.setEnabled(context,true))
        return create(action)
    }
    private fun create(action: String?): WakeLaunchService {
        val controller=Robolectric.buildService(WakeLaunchService::class.java)
        services+=controller
        controller.create()
        val service=controller.get()
        service.onStartCommand(action?.let {Intent(context,WakeLaunchService::class.java).setAction(it)},0,1)
        return service
    }
    private fun event(action: String) {
        when(action) {
            Intent.ACTION_SCREEN_OFF -> power.setIsInteractive(false)
            Intent.ACTION_SCREEN_ON -> power.setIsInteractive(true)
        }
        context.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun finishWake() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WakeLaunchService.WAKE_DELAY_MS+10)) }
    private fun nextLaunch() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Test fun preferenceIsOffByDefaultAndPermissionIsRequiredOnlyOnModernAndroid() {
        assertFalse(WakeLaunchSettings.enabled(context))
        ShadowSettings.setCanDrawOverlays(false)
        if(Build.VERSION.SDK_INT>=29) {
            assertFalse(WakeLaunchSettings.setEnabled(context,true))
            assertFalse(WakeLaunchSettings.enabled(context))
            WakeLaunchSettings.requestPermission(context)
            ShadowSettings.setCanDrawOverlays(true)
            WakeLaunchSettings.onActivityResumed(context)
        } else assertTrue(WakeLaunchSettings.setEnabled(context,true))
        assertTrue(WakeLaunchSettings.enabled(context))
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,WakeLaunchSettings.permissionIntent(context).action)
        assertTrue(WakeLaunchSettings.setEnabled(context,false));assertFalse(WakeLaunchSettings.enabled(context))
        assertNull(nextLaunch())
    }

    @Test fun realReceiverLaunchesOnceAfterStandbyAndNeverForAnInitialScreenOn() {
        val service=start()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        event(Intent.ACTION_SCREEN_ON);finishWake();assertNull(nextLaunch())
        event(Intent.ACTION_SCREEN_OFF);assertTrue(WakeLaunchSettings.sleepObserved(context))
        event(Intent.ACTION_SCREEN_ON)
        assertNull(nextLaunch())
        finishWake()
        val launch=nextLaunch()!!
        assertEquals(MainActivity::class.java.name,launch.component!!.className)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(WakeLaunchSettings.sleepObserved(context))
        event(Intent.ACTION_SCREEN_ON);event(Intent.ACTION_USER_PRESENT);finishWake()
        assertNull(nextLaunch())
    }

    @Test fun disablingOrSleepingAgainCancelsPendingLaunch() {
        start()
        event(Intent.ACTION_SCREEN_OFF);event(Intent.ACTION_SCREEN_ON);event(Intent.ACTION_SCREEN_OFF)
        finishWake();assertNull(nextLaunch())
        event(Intent.ACTION_SCREEN_ON)
        WakeLaunchSettings.setEnabled(context,false)
        finishWake();assertNull(nextLaunch())
    }

    @Test fun bootArmsOnlyTheLaterStandbyCycle() {
        assertTrue(WakeLaunchSettings.setEnabled(context,true))
        WakeLaunchSettings.recordSleep(context,true)
        create(WakeLaunchService.MONITOR_AFTER_BOOT)
        event(Intent.ACTION_SCREEN_ON);event(Intent.ACTION_USER_PRESENT);finishWake()
        assertNull(nextLaunch())
        event(Intent.ACTION_SCREEN_OFF);event(Intent.ACTION_SCREEN_ON);finishWake()
        assertNotNull(nextLaunch())
    }

    @Test fun lockedScreenWaitsForUnlockAndDuplicateWakeEventsDoNotRelaunch() {
        start();event(Intent.ACTION_SCREEN_OFF)
        keyguard.setKeyguardLocked(true)
        event(Intent.ACTION_SCREEN_ON);finishWake();assertNull(nextLaunch())
        keyguard.setKeyguardLocked(false)
        event(Intent.ACTION_USER_PRESENT);event(Intent.ACTION_USER_PRESENT);finishWake()
        assertNotNull(nextLaunch());assertNull(nextLaunch())
    }

    @Test fun stickyRestartRecoversARecordedSleepWhileNormalAppEntryDoesNotLaunch() {
        assertTrue(WakeLaunchSettings.setEnabled(context,true))
        WakeLaunchSettings.recordSleep(context,true)
        power.setIsInteractive(false)
        create(null)
        event(Intent.ACTION_SCREEN_ON);finishWake();assertNotNull(nextLaunch())
        services.removeAt(0).destroy()
        WakeLaunchSettings.recordSleep(context,true)
        create(WakeLaunchService.MONITOR);finishWake()
        assertNull(nextLaunch())
    }

    @Test fun foregroundFailureTurnsTheSwitchOff() {
        assertTrue(WakeLaunchSettings.setEnabled(context,true))
        val controller=Robolectric.buildService(WakeLaunchService::class.java);services+=controller
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("Test foreground rejection"))
        controller.create()
        assertFalse(WakeLaunchSettings.enabled(context))
        assertTrue(WakeLaunchSettings.failure(context).isNotBlank())
        assertNull(nextLaunch())
    }

    @Test fun bootReceiverStartsMonitoringWithoutOpeningTheApp() {
        assertTrue(WakeLaunchSettings.setEnabled(context,true))
        val application=shadowOf(RuntimeEnvironment.getApplication())
        while(application.nextStartedService!=null) { }
        RecordingBootReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(WakeLaunchService.MONITOR_AFTER_BOOT,application.nextStartedService!!.action)
        assertNull(nextLaunch())
    }
}
