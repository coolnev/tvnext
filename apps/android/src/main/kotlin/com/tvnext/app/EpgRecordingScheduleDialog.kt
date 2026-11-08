package com.tvnext.app

import com.tvnext.app.core.ui.component.TvActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.RecordingScheduleRequest
import java.text.DateFormat
import java.util.UUID

/** Schedule the selected EPG programme, with profile ownership and overlap checks. */
@Composable
internal fun EpgRecordingScheduleDialog(request: RecordingScheduleRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalRecoveryStore(context) }
    var before by remember(request) { mutableStateOf(store.paddingBeforeMinutes().toString()) }
    var after by remember(request) { mutableStateOf(store.paddingAfterMinutes().toString()) }
    var error by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запланировать запись") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${request.channelName} · ${request.programmeTitle}")
                Text(DateFormat.getDateTimeInstance().format(request.startAt) + " — " +
                    DateFormat.getTimeInstance().format(request.stopAt))
                OutlinedTextField(before, {before = it}, label={Text("Начать раньше, мин")},
                    singleLine=true, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(after, {after = it}, label={Text("Закончить позже, мин")},
                    singleLine=true, modifier=Modifier.fillMaxWidth())
                Text("Запись останется у текущего профиля; можно запланировать только одну запись на время.")
                if (error.isNotBlank()) Text(error)
            }
        },
        confirmButton = { TvActionButton(onClick = {
            val startPad = before.toIntOrNull()
            val stopPad = after.toIntOrNull()
            if (startPad == null || stopPad == null || startPad !in 0..60 || stopPad !in 0..60) {
                error = "Запас времени должен быть от 0 до 60 минут"
            } else {
                val start = request.startAt - startPad * 60_000L
                val duration = request.stopAt - request.startAt + (startPad + stopPad) * 60_000L
                if (start <= System.currentTimeMillis() || duration !in 1L..(6L * 60 * 60 * 1000)) {
                    error = "Невозможно назначить запись: время уже прошло или длительность больше 6 часов"
                } else {
                    val scheduled = runCatching {
                        RecordingScheduler.schedule(context, ScheduledRecording(
                            id=UUID.randomUUID().toString(), profileId=store.activeProfileId(),
                            title=request.programmeTitle, streamUrl=request.streamUrl,
                            startAt=start, durationMs=duration))
                    }.getOrDefault(false)
                    if (scheduled) {
                        store.setRecordingPadding(startPad, stopPad)
                        onDismiss()
                    } else error = "Не удалось назначить запись: проверьте разрешения и пересечения в расписании"
                }
            }
        }) { Text("Запланировать") } },
        dismissButton = { TvActionButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
