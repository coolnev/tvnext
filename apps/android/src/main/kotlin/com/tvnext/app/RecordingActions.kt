package com.tvnext.app

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for the service to finalize files before the library may remove them. */
internal suspend fun stopActiveRecording(context: Context, id: String): Boolean {
    if (RecordingRuntime.active.value?.id != id) return true
    return runCatching {
        RecordingService.stop(context, id)
        withTimeoutOrNull(10_000) {
            RecordingRuntime.active.first { it?.id != id }
            true
        } ?: false
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        false
    }
}

/** Deletion runs after finalization and cannot turn a storage failure into an Activity crash. */
internal suspend fun deleteRecordingAfterStop(context: Context, id: String, owner: String): Boolean {
    return try {
        if (!stopActiveRecording(context, id)) false
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LocalRecoveryStore(context).deleteRecording(id, owner)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
