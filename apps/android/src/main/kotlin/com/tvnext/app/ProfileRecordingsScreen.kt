package com.tvnext.app

import com.tvnext.app.core.ui.i18n.uiText

import com.tvnext.app.core.ui.component.TvActionButton
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat

/** Full-screen library of recordings belonging to the active profile. */
@Composable
internal fun ProfileRecordingsScreen(
    onPlayRecording: (PlaybackItem) -> Unit,
    onExportWithPicker: (String, String) -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { LocalRecoveryStore(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var missingPicker by remember { mutableStateOf(false) }
    var pendingPickerId by remember { mutableStateOf<String?>(null) }
    val owner = remember(revision) { store.activeProfileId() }
    val profileName = remember(com.tvnext.app.core.ui.i18n.UiLocale.language, revision) {
        store.profiles().firstOrNull { it.id == owner }?.name ?: uiText("Основной")
    }
    val active by RecordingRuntime.active.collectAsState()
    val records = remember(revision, active) { store.recordings(owner) }
    var busyId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText("Мои записи"), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(uiText("Профиль: {0} · записей: {1}", profileName, records.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TvActionButton(onClick = { revision++ }) { Text(uiText("Обновить")) }
            Spacer(Modifier.width(10.dp))
            TvActionButton(onClick = onBack) { Text(uiText("Назад")) }
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text(uiText("Пока нет сохранённых записей этого профиля"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.id }) { record ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(record.title, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(record.statusLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Text(DateFormat.getDateTimeInstance().format(record.startedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.error.isNotBlank()) Text(uiText(record.error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            if (active?.id == record.id) TvActionButton(enabled = busyId == null, onClick = {
                                busyId = record.id
                                message = uiText("Останавливаем запись")
                                scope.launch {
                                    val stopped = stopActiveRecording(context, record.id)
                                    message = if (stopped) uiText("Запись остановлена") else uiText("Не удалось остановить запись")
                                    busyId = null
                                    revision++
                                }
                            }) { Text(uiText("Остановить")) }
                            TvActionButton(onClick = {
                                val item = record.playbackItem()
                                if (item == null) message = uiText("Файл записи не найден или пуст")
                                else onPlayRecording(item)
                            }) { Text(uiText("Смотреть")) }
                            TvActionButton(onClick = {
                                scope.launch {
                                    message = uiText("Экспорт...")
                                    message = runCatching {
                                        RecordingExport.toDownloads(context, record.id, owner)
                                    }.fold({ uiText("Сохранено: {0}", it) },
                                        { uiText("Экспорт не выполнен: {0}", it.javaClass.simpleName) })
                                }
                            }) { Text("ZIP") }
                            TvActionButton(onClick = {
                                if (!onExportWithPicker(record.id, owner)) {
                                    pendingPickerId = record.id
                                    missingPicker = true
                                }
                            }) { Text(uiText("Сохранить в файл")) }
                            TvActionButton(enabled = busyId == null, onClick = { confirmDelete = record.id }) {
                                Text(uiText("Удалить"))
                            }
                        }
                    }
                }
            }
        }
    }
    if (missingPicker) {
        AlertDialog(onDismissRequest = { missingPicker = false },
            title = { Text(uiText("Нет приложения для выбора файла")) },
            text = { Text(uiText("На этом Android TV нет системного проводника. Можно установить AnExplorer или сохранить ZIP в Загрузки.")) },
            confirmButton = { TvActionButton(onClick = {
                missingPicker = false
                val market = RecordingFileContracts.installExplorerIntent()
                if (runCatching { context.startActivity(market) }.isFailure) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=dev.dworks.apps.anexplorer")))
                    }
                }
            }) { Text(uiText("Установить AnExplorer")) } },
            dismissButton = { TvActionButton(onClick = {
                missingPicker = false
                pendingPickerId?.let { id ->
                    if (!onExportWithPicker(id, owner)) message = uiText("Проводник пока недоступен")
                }
            }) { Text(uiText("Повторить")) } })
    }
    if (confirmDelete != null) {
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text(uiText("Удалить запись?")) },
            text = { Text(if (active?.id == confirmDelete)
                uiText("Запись будет остановлена, затем её файлы будут удалены. Это действие необратимо.")
                else uiText("Удаление записи необратимо.")) },
            confirmButton = { TvActionButton(onClick = {
                val id = confirmDelete ?: return@TvActionButton
                confirmDelete = null
                busyId = id
                message = if (active?.id == id) uiText("Останавливаем запись") else uiText("Удаляем запись")
                scope.launch {
                    val deleted = deleteRecordingAfterStop(context, id, owner)
                    message = if (deleted) uiText("Запись удалена") else uiText("Запись не удалена")
                    busyId = null
                    revision++
                }
            }) { Text(if (active?.id == confirmDelete) uiText("Остановить и удалить") else uiText("Удалить")) } },
            dismissButton = { TvActionButton(onClick = { confirmDelete = null }) {
                Text(uiText("Отмена"))
            } })
    }
}
