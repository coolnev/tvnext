package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.util.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-app reminder for scheduled programmes.
 *
 * When a scheduled programme is about to start (within [REMINDER_WINDOW_MS])
 * the service exposes it through [reminder] and the app shell renders a banner.
 * It never switches channels on its own — the user decides. Each programme is
 * reminded once ([notifiedKeys]); the banner is cleared with [dismiss] or when
 * the programme starts (the next loop clears it).
 *
 * Started by the applications (desktop Main / Android Application); a single
 * instance lives for the whole process.
 */
class ScheduledReminderService(
    private val repository: ScheduledRepository,
    mainContext: CoroutineContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private val _reminder = MutableStateFlow<ScheduledProgramme?>(null)
    val reminder: StateFlow<ScheduledProgramme?> = _reminder.asStateFlow()

    private var job: Job? = null
    private val notifiedKeys = mutableSetOf<String>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                runCatching { repository.deleteExpired(currentTimeMillis()) }
                val now = currentTimeMillis()
                val due = runCatching { repository.getAll() }.getOrDefault(emptyList())
                    .firstOrNull { scheduled ->
                        val upcoming = scheduled.startTime > now && scheduled.startTime <= now + REMINDER_WINDOW_MS
                        upcoming && scheduled.key !in notifiedKeys
                    }
                if (due != null) {
                    notifiedKeys += due.key
                    _reminder.value = due
                } else if (_reminder.value?.let { it.startTime <= now } == true) {
                    _reminder.value = null
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun dismiss() {
        _reminder.value = null
    }

    fun stop() {
        job?.cancel()
        job = null
        _reminder.value = null
        notifiedKeys.clear()
    }

    companion object {
        /** Remind when the programme starts within this window. */
        const val REMINDER_WINDOW_MS = 5L * 60 * 1000

        const val CHECK_INTERVAL_MS = 30_000L
    }
}
