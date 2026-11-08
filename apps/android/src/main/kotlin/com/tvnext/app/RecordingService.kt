package com.tvnext.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/** Records one stream independently from a visible Activity; never changes the playing channel. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: Job? = null
    private var runningId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingScheduledStart: Intent? = null
    private var currentScheduled = false
    private var destroying = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Запись TVNext", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            pendingScheduledStart = null
            capture?.cancel()
            if (capture == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START) return START_NOT_STICKY
        if (capture != null) {
            // At most one capture. A scheduled recording has priority over a
            // manually started one; finish the manual partial first.
            if (intent.getBooleanExtra(SCHEDULED, false) && !currentScheduled) {
                pendingScheduledStart = Intent(intent)
                capture?.cancel()
            }
            return START_NOT_STICKY
        }
        beginCapture(intent)
        return START_NOT_STICKY
    }

    private fun beginCapture(intent: Intent) {
        val url = intent.getStringExtra(URL) ?: return
        val title = intent.getStringExtra(TITLE)?.take(180) ?: "Телеканал"
        val owner = intent.getStringExtra(OWNER) ?: return
        val store = LocalRecoveryStore(this)
        if (store.profiles().none { it.id == owner }) return
        val isScheduled = intent.getBooleanExtra(SCHEDULED, false)
        val duration = intent.getLongExtra(DURATION, if (isScheduled) 6 * HOUR else 2 * HOUR)
            .coerceIn(1_000, if (isScheduled) 6 * HOUR else 2 * HOUR)
        val id = UUID.randomUUID().toString()
        val folder = runCatching { store.folderFor(id) }.getOrElse {
            // A removable volume might have disappeared between choosing it
            // and starting the service. Never crash or write elsewhere silently.
            stopSelf()
            return
        }
        runningId = id
        currentScheduled = isScheduled
        try {
            startForeground(NOTIFICATION_ID, notify("Запись: $title"))
        } catch (error: RuntimeException) {
            runningId = null
            currentScheduled = false
            stopSelf()
            return
        }
        val initial = SavedRecording(id, owner, title, folder.canonicalPath,
            System.currentTimeMillis(), 0L, "recording", scheduled = isScheduled)
        store.saveRecording(initial)
        RecordingRuntime.started(ActiveRecording(id, owner, isScheduled))
        capture = scope.launch {
            var status = "complete"
            var error = ""
            try {
                val remaining = store.limitGiB().toLong() * 1024 * 1024 * 1024 - store.recordingBytes()
                require(remaining > 0) { "No remaining recording storage" }
                StreamRecordingEngine(remaining).capture(url, folder, duration)
            } catch (cancel: CancellationException) {
                status = "partial"
            } catch (failure: Exception) {
                status = "partial"
                // Do not persist source URLs, response bodies or credentials.
                error = when (failure) {
                    is java.io.IOException -> "Сбой сети или чтения потока"
                    else -> "Не удалось полностью записать поток"
                }
            } finally {
                store.saveRecording(initial.copy(finishedAt = System.currentTimeMillis(),
                    status = status, error = error))
                mainHandler.post {
                    RecordingRuntime.finished(id)
                    runningId = null
                    currentScheduled = false
                    capture = null
                    val queued = if (!destroying) pendingScheduledStart else null
                    pendingScheduledStart = null
                    if (queued != null) {
                        beginCapture(queued)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }
    private fun notify(title: String): Notification {
        val stop = PendingIntent.getService(this, 5, Intent(this,RecordingService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL)
            .setContentTitle("TVNext · Запись")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",stop)
            .build()
    }
    override fun onDestroy() {
        destroying = true
        runningId?.let(RecordingRuntime::finished)
        pendingScheduledStart = null
        capture?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val START = "com.tvnext.app.recording.START"
        const val STOP = "com.tvnext.app.recording.STOP"
        const val URL = "streamUrl"
        const val TITLE = "title"
        const val OWNER = "ownerId"
        const val DURATION = "durationMs"
        const val SCHEDULED = "scheduled"
        private const val CHANNEL = "tvnext-restored-recording"
        private const val NOTIFICATION_ID = 40521
        const val HOUR = 60L * 60 * 1000

        fun start(context: Context, url: String, title: String, profile: String, duration: Long = 2 * HOUR, scheduled: Boolean = false) {
            val intent = Intent(context, RecordingService::class.java).setAction(START)
                .putExtra(URL,url).putExtra(TITLE,title).putExtra(OWNER,profile)
                .putExtra(DURATION,duration).putExtra(SCHEDULED,scheduled)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context,RecordingService::class.java).setAction(STOP))
        }
    }
}

/** Exact alarms are optional: where not permitted use inexact idle alarms, not an ungranted permission. */
internal object RecordingScheduler {
    /**
     * Refuse overlapping future scheduled recordings. Only one stream can be
     * captured and each existing slot reserves its full configured duration.
     */
    fun schedule(context: Context, item: ScheduledRecording): Boolean {
        val store = LocalRecoveryStore(context)
        if (item.startAt <= System.currentTimeMillis() || item.durationMs !in 1_000L..6 * RecordingService.HOUR)
            return false
        val intersects = store.schedules().any { existing ->
            existing.id != item.id && recordingSlotsOverlap(
                item.startAt, item.durationMs, existing.startAt, existing.durationMs)
        }
        if (intersects) return false
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = pending(context, item.id)
        if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.startAt, alarm)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.startAt, alarm)
        }
        store.schedule(item)
        return true
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        LocalRecoveryStore(context).removeSchedule(id)
    }

    fun restore(context: Context) {
        val store = LocalRecoveryStore(context)
        store.schedules().forEach { item ->
            if (item.startAt > System.currentTimeMillis()) {
                runCatching { schedule(context, item) }
            } else {
                store.removeSchedule(item.id)
            }
        }
    }

    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, RecordingAlarmReceiver::class.java)
            .setData(Uri.parse("tvnext-recovery://schedule/$id"))
            .putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val store = LocalRecoveryStore(context)
        val item = store.schedules().find { it.id == id } ?: return
        store.removeSchedule(id)
        if (System.currentTimeMillis() - item.startAt > 20 * 60 * 1000) return
        // Android 12+ background-start restrictions may prevent starting a FGS on some TVs.
        runCatching { RecordingService.start(context,item.streamUrl,item.title,item.profileId,item.durationMs,true) }
    }
}

class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RecordingScheduler.restore(context)
            WakeLaunchSettings.ensureMonitoring(context, afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED)
        }
    }
}
