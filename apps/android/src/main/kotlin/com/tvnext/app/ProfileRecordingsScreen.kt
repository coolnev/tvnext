package com.tvnext.app

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
    val profileName = remember(revision) {
        store.profiles().firstOrNull { it.id == owner }?.name ?: "Основной"
    }
    val records = remember(revision) { store.recordings(owner) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Мои записи", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Профиль: $profileName · записей: ${records.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TvActionButton(onClick = { revision++ }) { Text("Обновить") }
            Spacer(Modifier.width(10.dp))
            TvActionButton(onClick = onBack) { Text("Назад") }
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text("Пока нет сохранённых записей этого профиля",
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
                            Text(record.status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Text(DateFormat.getDateTimeInstance().format(record.startedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.error.isNotBlank()) Text(record.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            TvActionButton(onClick = {
                                val item = record.playbackItem()
                                if (item == null) message = "Файл записи не найден или пуст"
                                else onPlayRecording(item)
                            }) { Text("Смотреть") }
                            TvActionButton(onClick = {
                                scope.launch {
                                    message = "Экспорт..."
                                    message = runCatching {
                                        RecordingExport.toDownloads(context, record.id, owner)
                                    }.fold({ "Сохранено: $it" },
                                        { "Экспорт не выполнен: ${it.javaClass.simpleName}" })
                                }
                            }) { Text("ZIP") }
                            TvActionButton(onClick = {
                                if (!onExportWithPicker(record.id, owner)) {
                                    pendingPickerId = record.id
                                    missingPicker = true
                                }
                            }) { Text("Сохранить в файл") }
                            TvActionButton(onClick = { confirmDelete = record.id }) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
    if (missingPicker) {
        AlertDialog(onDismissRequest = { missingPicker = false },
            title = { Text("Нет приложения для выбора файла") },
            text = { Text("На этом Android TV нет системного проводника. Можно установить AnExplorer или сохранить ZIP в Загрузки.") },
            confirmButton = { TvActionButton(onClick = {
                missingPicker = false
                val market = RecordingFileContracts.installExplorerIntent()
                if (runCatching { context.startActivity(market) }.isFailure) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=dev.dworks.apps.anexplorer")))
                    }
                }
            }) { Text("Установить AnExplorer") } },
            dismissButton = { TvActionButton(onClick = {
                missingPicker = false
                pendingPickerId?.let { id ->
                    if (!onExportWithPicker(id, owner)) message = "Проводник пока недоступен"
                }
            }) { Text("Повторить") } })
    }
    if (confirmDelete != null) {
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("Удаление записи необратимо.") },
            confirmButton = { TvActionButton(onClick = {
                confirmDelete?.let { id ->
                    message = if (store.deleteRecording(id, owner)) "Запись удалена"
                        else "Запись не удалена"
                }
                confirmDelete = null
                revision++
            }) { Text("Удалить") } },
            dismissButton = { TvActionButton(onClick = { confirmDelete = null }) {
                Text("Отмена")
            } })
    }
}
