package com.tvnext.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Event-driven standby observer: no timers while asleep and no wake lock. */
class WakeLaunchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var initialized = false
    private var sleepObserved = false
    private var pendingWake = false
    private val power get() = getSystemService(PowerManager::class.java)
    private val keyguard get() = getSystemService(KeyguardManager::class.java)

    private val launchAfterWake = Runnable {
        if (!pendingWake) return@Runnable
        if (!WakeLaunchSettings.enabled(this) || !WakeLaunchSettings.canLaunch(this)) {
            pendingWake = false
            return@Runnable
        }
        if (!power.isInteractive || keyguard.isKeyguardLocked) return@Runnable
        pendingWake = false
        WakeLaunchSettings.recordSleep(this, false)
        // singleTask brings back the existing Activity/player, or normal startup settings on a cold process.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!WakeLaunchSettings.enabled(context)) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(launchAfterWake)
                    sleepObserved = true
                    pendingWake = false
                    WakeLaunchSettings.recordSleep(context, true)
                }
                Intent.ACTION_SCREEN_ON -> wakeObserved()
                Intent.ACTION_USER_PRESENT -> {
                    // Some TVs deliver USER_PRESENT without a separate SCREEN_ON.
                    if (power.isInteractive) wakeObserved()
                    if (pendingWake) scheduleLaunch()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!WakeLaunchSettings.enabled(this) || !WakeLaunchSettings.canLaunch(this)) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Запуск после сна", NotificationManager.IMPORTANCE_MIN))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (_: RuntimeException) {
            WakeLaunchSettings.monitoringFailed(this)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!registered || !WakeLaunchSettings.enabled(this) || !WakeLaunchSettings.canLaunch(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == MONITOR_AFTER_BOOT) {
            // A cold Android boot only arms the observer for a later standby cycle.
            handler.removeCallbacks(launchAfterWake)
            pendingWake = false
            sleepObserved = false
            WakeLaunchSettings.recordSleep(this, false)
            initialized = true
        } else if (!initialized) {
            initialized = true
            sleepObserved = WakeLaunchSettings.sleepObserved(this)
            if (power.isInteractive) {
                if (intent == null && sleepObserved) wakeObserved() // sticky restart after a missed wake broadcast
                else {
                    sleepObserved = false
                    WakeLaunchSettings.recordSleep(this, false)
                }
            }
        }
        return START_STICKY
    }

    private fun wakeObserved() {
        if (!sleepObserved) return
        sleepObserved = false
        pendingWake = true
        scheduleLaunch()
    }

    private fun scheduleLaunch() {
        handler.removeCallbacks(launchAfterWake)
        handler.postDelayed(launchAfterWake, WAKE_DELAY_MS)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 45, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wake)
            .setContentTitle("TVNext · Запуск после сна")
            .setContentText("TVNext откроется при пробуждении телевизора")
            .setContentIntent(open).setOngoing(true).setSilent(true).setShowWhen(false).build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchAfterWake)
        if (registered) unregisterReceiver(screenEvents)
        registered = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val MONITOR = "com.tvnext.app.wake.MONITOR"
        const val MONITOR_AFTER_BOOT = "com.tvnext.app.wake.MONITOR_AFTER_BOOT"
        internal const val WAKE_DELAY_MS = 600L
        private const val CHANNEL = "tvnext-standby-wake"
        private const val NOTIFICATION = 40545
    }
}
