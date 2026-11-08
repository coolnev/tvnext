package com.tvnext.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Device preference: opening TVNext on standby wake is independent of Windows/boot autostart. */
internal object WakeLaunchSettings {
    private const val PREFS = "tvnext_wake_launch_v1"
    private const val ENABLED = "enabled"
    private const val PENDING_PERMISSION = "pending_permission"
    private const val SLEEP_OBSERVED = "sleep_observed"
    private const val FAILURE = "failure"

    fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean(ENABLED, false)
    fun canLaunch(context: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
    fun sleepObserved(context: Context) = preferences(context).getBoolean(SLEEP_OBSERVED, false)
    fun failure(context: Context) = preferences(context).getString(FAILURE, "").orEmpty()
    fun recordSleep(context: Context, asleep: Boolean) { preferences(context).edit().putBoolean(SLEEP_OBSERVED, asleep).commit() }

    fun requestPermission(context: Context) { preferences(context).edit().putBoolean(PENDING_PERMISSION, true).putString(FAILURE, "").commit() }

    fun monitoringFailed(context: Context) {
        preferences(context).edit().putBoolean(ENABLED, false).putBoolean(PENDING_PERMISSION, false)
            .putBoolean(SLEEP_OBSERVED, false).putString(FAILURE, "Не удалось включить запуск после сна.").commit()
    }

    fun permissionIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"))

    fun setEnabled(context: Context, value: Boolean): Boolean {
        val prefs = preferences(context)
        if (value && !canLaunch(context)) return false
        if (!prefs.edit().putBoolean(ENABLED, value).putBoolean(PENDING_PERMISSION, false)
                .putBoolean(SLEEP_OBSERVED, false).putString(FAILURE, "").commit()) return false
        if (!value) {
            context.stopService(Intent(context, WakeLaunchService::class.java))
            return true
        }
        if (ensureMonitoring(context)) return true
        monitoringFailed(context)
        return false
    }

    /** Called only from a visible Activity or an allowed boot/package-replacement broadcast. */
    fun ensureMonitoring(context: Context, afterBoot: Boolean = false): Boolean {
        if (!enabled(context) || !canLaunch(context)) return false
        if (afterBoot) recordSleep(context, false)
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeLaunchService::class.java)
                .setAction(if (afterBoot) WakeLaunchService.MONITOR_AFTER_BOOT else WakeLaunchService.MONITOR))
        }.isSuccess
    }

    fun onActivityResumed(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(PENDING_PERMISSION, false)) {
            setEnabled(context, canLaunch(context))
        } else if (enabled(context)) {
            if (!canLaunch(context)) setEnabled(context, false)
            else if (!ensureMonitoring(context)) monitoringFailed(context)
        }
    }
}
