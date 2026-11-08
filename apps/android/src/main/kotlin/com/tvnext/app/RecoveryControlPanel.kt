package com.tvnext.app

import com.tvnext.app.core.ui.component.TvActionButton
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackItem
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Recovery controls are opened with MENU on Android TV; existing TV screens
 * remain untouched. This is a provisional UI, not a recreation of 0.5.21.
 */
@Composable
internal fun RecoveryControlPanel(
    playback: PlaybackController,
    onPlayRecording: (PlaybackItem) -> Unit,
    onExportWithPicker: (String, String) -> Boolean,
    onDismiss: () -> Unit,
    section: RecoverySection,
) {
    val context = LocalContext.current
    val store = remember(context) { LocalRecoveryStore(context) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(firstFocus, section) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    var revision by remember { mutableStateOf(0) }
    var newProfile by remember { mutableStateOf("") }
    var renamedProfile by remember { mutableStateOf("") }
    var afterMinutes by remember { mutableStateOf("10") }
    var durationMinutes by remember { mutableStateOf("60") }
    var storageLimit by remember { mutableStateOf(store.limitGiB().toString()) }
    var padBefore by remember { mutableStateOf(store.paddingBeforeMinutes().toString()) }
    var padAfter by remember { mutableStateOf(store.paddingAfterMinutes().toString()) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var missingPicker by remember { mutableStateOf(false) }
    var pendingPickerId by remember { mutableStateOf<String?>(null) }
    val profiles = remember(revision) { store.profiles() }
    val owner = remember(revision) { store.activeProfileId() }
    val records = remember(revision) { store.recordings(owner) }
    val scheduled = remember(revision) { store.schedules().filter { it.profileId == owner } }
    val storageLocations = remember(revision) { store.availableRecordingLocations() }
    val selectedStorage = remember(revision) { store.selectedRecordingLocation() }
    val active = playback.state.value.item
    val usable = active?.takeIf { it.kind == PlaybackContentKind.LIVE && it.url.startsWith("http") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when(section) {
            RecoverySection.PROFILES -> "Профили"
            RecoverySection.RECORDINGS -> "Мои записи"
            RecoverySection.RECORDING_SETTINGS -> "Запись и расписание"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 570.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (section == RecoverySection.PROFILES) {
                Text("Текущий профиль: ${profiles.firstOrNull { it.id == owner }?.name ?: "Основной"}")
                profiles.forEachIndexed { index, p ->
                    TvActionButton(modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = {
                        if (p.id != owner) {
                            // 0.5.20 behaviour: scheduled captures belong to their
                            // original profile even if the user switches profile.
                            if (store.recordings(owner).any { it.status == "recording" && !it.scheduled })
                                RecordingService.stop(context)
                            playback.stop()
                            if (store.switchProfile(p.id)) {
                                com.tvnext.app.core.common.util.PreviousChannelHistory.clear()
                                // Rebuild all screen components so Home/Channels/Media
                                // obtain the newly selected profile's repository view.
                                com.tvnext.app.core.player.AndroidPlaybackSession.clearPersonalSession()
                                onDismiss()
                                (context as? Activity)?.recreate()
                            } else message = "Не удалось переключить профиль"
                        }
                    }) { Text((if (p.id == owner) "✓  " else "   ") + p.name) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newProfile, onValueChange = { newProfile = it },
                        label = { Text("Новый профиль") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TvActionButton(onClick = {
                        val result = store.addProfile(newProfile)
                        message = if (result != null) "Профиль добавлен" else "Максимум 8 профилей или пустое имя"
                        if (result != null) { newProfile = ""; revision++ }
                    }) { Text("Добавить") }
                }
                Text("Переименовать текущий профиль")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = renamedProfile, onValueChange = { renamedProfile = it },
                        label = { Text("Новое имя") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TvActionButton(onClick = {
                        message = if (store.renameProfile(owner, renamedProfile)) {
                            renamedProfile = ""
                            revision++
                            "Имя профиля сохранено"
                        } else "Укажи непустое имя профиля"
                    }) { Text("Переименовать") }
                }
                // Deletion is refused when a profile owns recordings or schedules.
                profiles.filter { it.id != owner }.forEach { p ->
                    TvActionButton(onClick = {
                        message = if (store.deleteEmptyProfile(p.id)) {
                            ProfilePersonalData(context, store).purgeProfile(p.id)
                            revision++
                            "Пустой профиль удалён"
                        } else "Нельзя удалить профиль с записями или расписанием"
                    }) { Text("Удалить пустой профиль «${p.name}»") }
                }
                } // PROFILES
                if (section == RecoverySection.RECORDING_SETTINGS) {
                TvActionButton(onClick={ revision++ }, modifier=Modifier.focusRequester(firstFocus)) {
                    Text("Обновить настройки записи")
                }
                Text("Запас времени при записи из телепрограммы")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(padBefore, {padBefore = it}, label={Text("До начала, мин")},
                        modifier=Modifier.weight(1f), singleLine=true)
                    OutlinedTextField(padAfter, {padAfter = it}, label={Text("После окончания, мин")},
                        modifier=Modifier.weight(1f), singleLine=true)
                }
                TvActionButton(onClick={
                    message = if (store.setRecordingPadding(padBefore.toIntOrNull() ?: -1,
                            padAfter.toIntOrNull() ?: -1)) "Запас времени сохранён"
                        else "Укажите от 0 до 60 минут"
                }) { Text("Сохранить запас времени") }
                Text("Папка для новых записей")
                Text("Выбрано: ${selectedStorage?.title ?: "Накопитель недоступен — выберите другую папку"}")
                storageLocations.forEach { location ->
                    TvActionButton(onClick = {
                        message = if (store.selectRecordingLocation(location.token)) {
                            revision++
                            "Новые записи: ${location.title}"
                        } else "Не удалось открыть хранилище для записи"
                    }) {
                        Text((if (selectedStorage?.token == location.token) "✓  " else "   ") + location.title)
                    }
                }
                Text("Внутренние папки принадлежат TVNext и удаляются при удалении приложения. " +
                    "Для независимой копии используйте экспорт в ZIP. Старые записи не переносятся.")
                Text("Текущий эфир: ${usable?.title ?: "прямой эфир не выбран"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(enabled = usable != null && selectedStorage != null, onClick = {
                        val item = usable ?: return@TvActionButton
                        runCatching {
                            RecordingService.start(context,item.url,item.title,owner)
                            message = "Запись запущена. Для остановки нажмите «Стоп»."
                        }.onFailure { message = "Не удалось запустить запись: ${it.javaClass.simpleName}" }
                        revision++
                    }) { Text("Записать эфир") }
                    TvActionButton(onClick = { RecordingService.stop(context); message = "Запись останавливается" }) {
                        Text("Стоп")
                    }
                }
                Text("Запланировать текущий эфир")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(afterMinutes,{afterMinutes=it},label={Text("Через, мин")},
                        singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(durationMinutes,{durationMinutes=it},label={Text("На, мин")},
                        singleLine=true,modifier=Modifier.weight(1f))
                }
                TvActionButton(enabled=usable != null,onClick={
                    val item=usable ?: return@TvActionButton
                    val delay=afterMinutes.toLongOrNull()
                    val length=durationMinutes.toLongOrNull()
                    if (delay == null || delay !in 1..10080 || length == null || length !in 1..360) {
                        message = "Укажи 1–10080 минут ожидания и 1–360 минут записи"
                    } else {
                        runCatching {
                            val added = RecordingScheduler.schedule(context, ScheduledRecording(
                                UUID.randomUUID().toString(), owner, item.title, item.url,
                                System.currentTimeMillis() + delay * 60000, length * 60000))
                            message = if (added) "Расписание добавлено (возможны задержки Android)"
                                else "Время пересекается с другой записью или уже прошло"
                            if (added) revision++
                        }.onFailure { message="Невозможно запланировать запись" }
                    }
                }) { Text("Добавить в расписание") }
                scheduled.forEach { task ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${task.title} · ${java.text.DateFormat.getDateTimeInstance().format(task.startAt)}",
                            modifier=Modifier.weight(1f))
                        TvActionButton(onClick={RecordingScheduler.cancel(context,task.id);revision++}){Text("Отмена")}
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(storageLimit, { storageLimit = it },
                        label = { Text("Лимит записей, ГиБ (1–128)") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    TvActionButton(onClick = {
                        message = if (store.setLimitGiB(storageLimit.toIntOrNull() ?: -1))
                            "Лимит записей сохранён" else "Выбери значение от 1 до 128"
                    }) { Text("Сохранить") }
                }
                } // RECORDING_SETTINGS
                if (section == RecoverySection.RECORDINGS) {
                TvActionButton(onClick={revision++}, modifier=Modifier.focusRequester(firstFocus)) { Text("Обновить список") }
                if (records.isEmpty()) Text("Пока нет сохранённых записей этого профиля")
                records.forEach { record ->
                    Column {
                        Text("${record.title} · ${record.status}")
                        if (record.error.isNotBlank()) Text(record.error)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TvActionButton(onClick={
                                val item = record.playbackItem()
                                if (item == null) message="Файл записи не найден или пуст"
                                else onPlayRecording(item)
                            }){Text("Смотреть")}
                            TvActionButton(onClick={
                                scope.launch {
                                    message="Экспорт..."
                                    message = runCatching { RecordingExport.toDownloads(context,record.id,owner) }
                                        .fold({"Сохранено: $it"},{"Экспорт не выполнен: ${it.javaClass.simpleName}"})
                                }
                            }){Text("ZIP")}
                            TvActionButton(onClick={
                                if (!onExportWithPicker(record.id,owner)) {
                                    pendingPickerId = record.id
                                    missingPicker = true
                                }
                            }) { Text("Сохранить в файл") }
                            TvActionButton(onClick={confirmDelete=record.id}){Text("Удалить")}
                        }
                    }
                }
                } // RECORDINGS
                if (section == RecoverySection.RECORDING_SETTINGS) {
                val notificationState = runCatching {
                    if (Build.VERSION.SDK_INT >= 24) {
                        (context.getSystemService(android.app.NotificationManager::class.java))
                            .areNotificationsEnabled()
                    } else true
                }.getOrDefault(true)
                Text("Уведомления приложения: ${if (notificationState) "включены" else "отключены"}")
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    TvActionButton(onClick={
                        (context as? Activity)?.requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),521)
                    }) { Text("Разрешить уведомления о записи") }
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    TvActionButton(onClick={
                        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                        runCatching { context.startActivity(settings) }
                    }) { Text("Настроить уведомления записи") }
                }
                if (message.isNotBlank()) Text(message)
                } // RECORDING_SETTINGS notifications
            }
        },
        confirmButton = { TvActionButton(onClick = onDismiss) { Text("Закрыть") } },
    )

    if (missingPicker) {
        AlertDialog(onDismissRequest = { missingPicker = false },
            title = { Text("Нет приложения для выбора файла") },
            text = { Text("На этом Android TV нет совместимого системного проводника. Можно установить AnExplorer (бесплатный, с рекламой), повторить поиск или сохранить ZIP в Загрузки через кнопку ZIP.") },
            confirmButton = { TvActionButton(onClick = {
                missingPicker = false
                val market = RecordingFileContracts.installExplorerIntent()
                if (runCatching { context.startActivity(market) }.isFailure) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=dev.dworks.apps.anexplorer"))) }
                }
            }) { Text("Установить AnExplorer") } },
            dismissButton = { TvActionButton(onClick = {
                missingPicker = false
                pendingPickerId?.let { id ->
                    if (!onExportWithPicker(id,owner)) message="Проводник пока недоступен"
                }
            }) { Text("Повторить") } },
        )
    }
    if (confirmDelete != null) {
        AlertDialog(onDismissRequest={confirmDelete=null},title={Text("Удалить запись?")},
            text={Text("Удаление записи необратимо.")},
            confirmButton={TvActionButton(onClick={
                val id=confirmDelete
                if(id != null) message=if(store.deleteRecording(id,owner)) "Запись удалена" else "Запись не удалена"
                confirmDelete=null
                revision++
            }){Text("Удалить")}},
            dismissButton={TvActionButton(onClick={confirmDelete=null}){Text("Отмена")}})
    }
}
