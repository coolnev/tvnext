package com.tvnext.app

import com.tvnext.app.core.ui.i18n.uiText

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
import androidx.compose.runtime.collectAsState
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
    val recordingActive by RecordingRuntime.active.collectAsState()
    val records = remember(revision) { store.recordings(owner) }
    val scheduled = remember(revision) { store.schedules().filter { it.profileId == owner } }
    val storageLocations = remember(revision) { store.availableRecordingLocations() }
    val selectedStorage = remember(revision) { store.selectedRecordingLocation() }
    val active = playback.state.value.item
    val usable = active?.takeIf { it.kind == PlaybackContentKind.LIVE && it.url.startsWith("http") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when(section) {
            RecoverySection.PROFILES -> uiText("Профили")
            RecoverySection.RECORDINGS -> uiText("Мои записи")
            RecoverySection.RECORDING_SETTINGS -> uiText("Запись и расписание")
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 570.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (section == RecoverySection.PROFILES) {
                Text(uiText("Текущий профиль: {0}", profiles.firstOrNull { it.id == owner }?.name ?: uiText("Основной")))
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
                            } else message = uiText("Не удалось переключить профиль")
                        }
                    }) { Text((if (p.id == owner) "✓  " else "   ") + p.name) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newProfile, onValueChange = { newProfile = it },
                        label = { Text(uiText("Новый профиль")) }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TvActionButton(onClick = {
                        val result = store.addProfile(newProfile)
                        message = if (result != null) uiText("Профиль добавлен") else uiText("Максимум 8 профилей или пустое имя")
                        if (result != null) { newProfile = ""; revision++ }
                    }) { Text(uiText("Добавить")) }
                }
                Text(uiText("Переименовать текущий профиль"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = renamedProfile, onValueChange = { renamedProfile = it },
                        label = { Text(uiText("Новое имя")) }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TvActionButton(onClick = {
                        message = if (store.renameProfile(owner, renamedProfile)) {
                            renamedProfile = ""
                            revision++
                            uiText("Имя профиля сохранено")
                        } else uiText("Укажи непустое имя профиля")
                    }) { Text(uiText("Переименовать")) }
                }
                // Deletion is refused when a profile owns recordings or schedules.
                profiles.filter { it.id != owner }.forEach { p ->
                    TvActionButton(onClick = {
                        message = if (store.deleteEmptyProfile(p.id)) {
                            ProfilePersonalData(context, store).purgeProfile(p.id)
                            revision++
                            uiText("Пустой профиль удалён")
                        } else uiText("Нельзя удалить профиль с записями или расписанием")
                    }) { Text(uiText("Удалить пустой профиль «{0}»", p.name)) }
                }
                } // PROFILES
                if (section == RecoverySection.RECORDING_SETTINGS) {
                TvActionButton(onClick={ revision++ }, modifier=Modifier.focusRequester(firstFocus)) {
                    Text(uiText("Обновить настройки записи"))
                }
                Text(uiText("Запас времени при записи из телепрограммы"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(padBefore, {padBefore = it}, label={Text(uiText("До начала, мин"))},
                        modifier=Modifier.weight(1f), singleLine=true)
                    OutlinedTextField(padAfter, {padAfter = it}, label={Text(uiText("После окончания, мин"))},
                        modifier=Modifier.weight(1f), singleLine=true)
                }
                TvActionButton(onClick={
                    message = if (store.setRecordingPadding(padBefore.toIntOrNull() ?: -1,
                            padAfter.toIntOrNull() ?: -1)) uiText("Запас времени сохранён")
                        else uiText("Укажите от 0 до 60 минут")
                }) { Text(uiText("Сохранить запас времени")) }
                Text(uiText("Папка для новых записей"))
                Text(uiText("Выбрано: {0}", selectedStorage?.title ?: uiText("Накопитель недоступен — выберите другую папку")))
                storageLocations.forEach { location ->
                    TvActionButton(onClick = {
                        message = if (store.selectRecordingLocation(location.token)) {
                            revision++
                            uiText("Новые записи: {0}", location.title)
                        } else uiText("Не удалось открыть хранилище для записи")
                    }) {
                        Text((if (selectedStorage?.token == location.token) "✓  " else "   ") + location.title)
                    }
                }
                Text(uiText("Внутренние папки принадлежат TVNext и удаляются при удалении приложения. ") +
                    uiText("Для независимой копии используйте экспорт в ZIP. Старые записи не переносятся."))
                Text(uiText("Текущий эфир: {0}", usable?.title ?: uiText("прямой эфир не выбран")))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(enabled = usable != null && selectedStorage != null && recordingActive == null, onClick = {
                        val item = usable ?: return@TvActionButton
                        runCatching {
                            RecordingService.start(context,item.url,item.title,owner,channelId = item.id)
                            message = uiText("Запись запущена. Для остановки нажмите «Стоп».")
                        }.onFailure { message = uiText("Не удалось запустить запись: {0}", it.javaClass.simpleName) }
                        revision++
                    }) { Text(uiText("Записать эфир")) }
                    TvActionButton(onClick = { RecordingService.stop(context); message = uiText("Запись останавливается") }) {
                        Text(uiText("Стоп"))
                    }
                }
                Text(uiText("Запланировать текущий эфир"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(afterMinutes,{afterMinutes=it},label={Text(uiText("Через, мин"))},
                        singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(durationMinutes,{durationMinutes=it},label={Text(uiText("На, мин"))},
                        singleLine=true,modifier=Modifier.weight(1f))
                }
                TvActionButton(enabled=usable != null,onClick={
                    val item=usable ?: return@TvActionButton
                    val delay=afterMinutes.toLongOrNull()
                    val length=durationMinutes.toLongOrNull()
                    if (delay == null || delay !in 1..10080 || length == null || length !in 1..360) {
                        message = uiText("Укажи 1–10080 минут ожидания и 1–360 минут записи")
                    } else {
                        runCatching {
                            val added = RecordingScheduler.schedule(context, ScheduledRecording(
                                UUID.randomUUID().toString(), owner, item.title, item.url,
                                System.currentTimeMillis() + delay * 60000, length * 60000, channelId = item.id))
                            message = if (added) uiText("Расписание добавлено (возможны задержки Android)")
                                else uiText("Время пересекается с другой записью или уже прошло")
                            if (added) revision++
                        }.onFailure { message=uiText("Невозможно запланировать запись") }
                    }
                }) { Text(uiText("Добавить в расписание")) }
                scheduled.forEach { task ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${task.title} · ${java.text.DateFormat.getDateTimeInstance().format(task.startAt)}",
                            modifier=Modifier.weight(1f))
                        TvActionButton(onClick={RecordingScheduler.cancel(context,task.id);revision++}){Text(uiText("Отмена"))}
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(storageLimit, { storageLimit = it },
                        label = { Text(uiText("Лимит записей, ГиБ (1–128)")) },
                        modifier = Modifier.weight(1f), singleLine = true)
                    TvActionButton(onClick = {
                        message = if (store.setLimitGiB(storageLimit.toIntOrNull() ?: -1))
                            uiText("Лимит записей сохранён") else uiText("Выбери значение от 1 до 128")
                    }) { Text(uiText("Сохранить")) }
                }
                } // RECORDING_SETTINGS
                if (section == RecoverySection.RECORDINGS) {
                TvActionButton(onClick={revision++}, modifier=Modifier.focusRequester(firstFocus)) { Text(uiText("Обновить список")) }
                if (records.isEmpty()) Text(uiText("Пока нет сохранённых записей этого профиля"))
                records.forEach { record ->
                    Column {
                        Text("${record.title} · ${record.statusLabel()}")
                        if (record.error.isNotBlank()) Text(record.error)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TvActionButton(onClick={
                                val item = record.playbackItem()
                                if (item == null) message=uiText("Файл записи не найден или пуст")
                                else onPlayRecording(item)
                            }){Text(uiText("Смотреть"))}
                            TvActionButton(onClick={
                                scope.launch {
                                    message=uiText("Экспорт...")
                                    message = runCatching { RecordingExport.toDownloads(context,record.id,owner) }
                                        .fold({uiText("Сохранено: {0}", it)},{uiText("Экспорт не выполнен: {0}", it.javaClass.simpleName)})
                                }
                            }){Text("ZIP")}
                            TvActionButton(onClick={
                                if (!onExportWithPicker(record.id,owner)) {
                                    pendingPickerId = record.id
                                    missingPicker = true
                                }
                            }) { Text(uiText("Сохранить в файл")) }
                            TvActionButton(onClick={confirmDelete=record.id}){Text(uiText("Удалить"))}
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
                Text(uiText("Уведомления приложения: {0}", if (notificationState) uiText("включены") else uiText("отключены")))
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    TvActionButton(onClick={
                        (context as? Activity)?.requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),521)
                    }) { Text(uiText("Разрешить уведомления о записи")) }
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    TvActionButton(onClick={
                        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                        runCatching { context.startActivity(settings) }
                    }) { Text(uiText("Настроить уведомления записи")) }
                }
                if (message.isNotBlank()) Text(message)
                } // RECORDING_SETTINGS notifications
            }
        },
        confirmButton = { TvActionButton(onClick = onDismiss) { Text(uiText("Закрыть")) } },
    )

    if (missingPicker) {
        AlertDialog(onDismissRequest = { missingPicker = false },
            title = { Text(uiText("Нет приложения для выбора файла")) },
            text = { Text(uiText("На этом Android TV нет совместимого системного проводника. Можно установить AnExplorer (бесплатный, с рекламой), повторить поиск или сохранить ZIP в Загрузки через кнопку ZIP.")) },
            confirmButton = { TvActionButton(onClick = {
                missingPicker = false
                val market = RecordingFileContracts.installExplorerIntent()
                if (runCatching { context.startActivity(market) }.isFailure) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=dev.dworks.apps.anexplorer"))) }
                }
            }) { Text(uiText("Установить AnExplorer")) } },
            dismissButton = { TvActionButton(onClick = {
                missingPicker = false
                pendingPickerId?.let { id ->
                    if (!onExportWithPicker(id,owner)) message=uiText("Проводник пока недоступен")
                }
            }) { Text(uiText("Повторить")) } },
        )
    }
    if (confirmDelete != null) {
        AlertDialog(onDismissRequest={confirmDelete=null},title={Text(uiText("Удалить запись?"))},
            text={Text(uiText("Удаление записи необратимо."))},
            confirmButton={TvActionButton(onClick={
                val id=confirmDelete
                if(id != null) message=if(store.deleteRecording(id,owner)) uiText("Запись удалена") else uiText("Запись не удалена")
                confirmDelete=null
                revision++
            }){Text(uiText("Удалить"))}},
            dismissButton={TvActionButton(onClick={confirmDelete=null}){Text(uiText("Отмена"))}})
    }
}
