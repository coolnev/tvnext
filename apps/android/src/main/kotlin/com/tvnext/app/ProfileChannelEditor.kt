package com.tvnext.app

import com.tvnext.app.core.ui.component.TvActionButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.iptv.data.ChannelStore
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.player.VideoAspectMode
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.player.displayName
import com.tvnext.app.core.ui.component.TvCardButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Full-screen Android TV editor; IPTV sources and channel IDs are never mutated. */
@Composable
internal fun ProfileChannelEditor(onClose: (Boolean) -> Unit) {
    val profiles = remember { GlobalContext.get().get<LocalRecoveryStore>() }
    val personal = remember { GlobalContext.get().get<ProfilePersonalData>() }
    val source = remember { GlobalContext.get().get<ChannelStore>() }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var preferredAudio by remember { mutableStateOf("") }
    var preferredText by remember { mutableStateOf("") }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var resize by remember { mutableStateOf("inherit") }
    var aspectPicker by remember { mutableStateOf(false) }
    var decodePicker by remember { mutableStateOf(false) }
    var decodeMode by remember { mutableStateOf("inherit") }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    val firstChannelFocus = remember { FocusRequester() }
    val resetProviderFocus = remember { FocusRequester() }
    var returnChannelFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val channelList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val close = { onClose(changed) }

    LaunchedEffect(source) {
        runCatching { withContext(Dispatchers.IO) { source.getChannels() } }
            .onSuccess { channels = it }
            .onFailure { message = "Не удалось загрузить список каналов" }
    }
    val edits = remember(revision) { personal.channelEdits() }
    val favorites = remember(revision) { personal.channelFavorites() }
    val hideDuplicates = remember(revision) { personal.hideDuplicateChannels() }
    val effective = remember(channels, revision) {
        projectProfileChannels(channels, favorites, edits, personal.channelOrder(), includeHidden = true)
    }
    val filtered = remember(effective, query, showHidden, edits) {
        effective.filter { channel ->
            (showHidden || edits[channel.id]?.hidden != true) &&
                (query.isBlank() || channel.name.contains(query.trim(), ignoreCase = true) ||
                    channel.category.contains(query.trim(), ignoreCase = true))
        }
    }
    LaunchedEffect(filtered.firstOrNull()?.id) {
        if (filtered.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstChannelFocus.requestFocus() }
        }
    }

    // Full screen, not AlertDialog: navigation and editor occupy the video/home area.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Редактор каналов", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Профиль: ${profiles.profiles().firstOrNull { it.id == profiles.activeProfileId() }?.name ?: "Основной"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TvActionButton(onClick = close) { Text("Назад") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.46f).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("Поиск по каналам и категориям") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TvActionButton(onClick = {
                        if (personal.setHideDuplicateChannels(!hideDuplicates)) {
                            revision++; changed = true
                        }
                    }) { Text(if (hideDuplicates) "✓ Дубликаты" else "Дубликаты") }
                    TvActionButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "Скрыть скрытые" else "Показать скрытые")
                    }
                }
                Text("Каналы: ${filtered.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionRight) {
                        if (event.type == KeyEventType.KeyDown) resetProviderFocus.requestFocus()
                        true
                    } else false
                }, state = channelList,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(filtered, key = { _, channel -> channel.id }) { index, channel ->
                        val channelFocus = remember(channel.id) { FocusRequester() }
                        TvCardButton(
                            onClick = {
                                selectedId = channel.id
                                newName = edits[channel.id]?.name ?: channel.name
                                newCategory = edits[channel.id]?.category ?: channel.category
                                val prefs = personal.channelPlaybackSettings(channel.id)
                                preferredAudio = prefs.audioLanguage
                                preferredText = prefs.subtitleLanguage
                                subtitlesEnabled = prefs.subtitlesEnabled
                                resize = prefs.resize
                                decodeMode = prefs.decodeMode
                            },
                            selected = channel.id == selectedId,
                            modifier = Modifier.fillMaxWidth().height(43.dp)
                                .focusRequester(channelFocus)
                                .onFocusChanged { if (it.hasFocus) returnChannelFocus = channelFocus }
                                .then(if (index == 0) Modifier.focusRequester(firstChannelFocus) else Modifier),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 3.dp),
                        ) {
                            Text("${if (channel.id in favorites) "★ " else ""}${if (edits[channel.id]?.hidden == true) "[скрыт] " else ""}${channel.name} · ${channel.category}",
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.54f).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                TvActionButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.End)
                    .focusRequester(resetProviderFocus).onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (event.type == KeyEventType.KeyDown &&
                                    runCatching { returnChannelFocus?.requestFocus() }.getOrNull() != true) {
                                    scope.launch {
                                        channelList.scrollToItem(0)
                                        withFrameNanos { }
                                        runCatching { firstChannelFocus.requestFocus() }
                                    }
                                }
                                true
                            }
                            Key.DirectionRight -> true
                            else -> false
                        }
                    }) { Text("Вернуть список провайдера") }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                val selected = channels.firstOrNull { it.id == selectedId }
                if (selected == null) {
                    Text("Выбери канал слева", style = MaterialTheme.typography.titleMedium)
                    Text("Здесь можно изменить название, категорию, порядок, избранное и параметры воспроизведения.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val current = edits[selected.id] ?: ChannelCustomization()
                    Text("Настройка канала", style = MaterialTheme.typography.titleMedium)
                    Text("№${selected.number} · ${selected.name}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(newName, { newName = it }, label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(newCategory, { newCategory = it }, label = { Text("Категория") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionButton(onClick = {
                            if (personal.setChannelFavorite(selected.id, selected.id !in favorites)) {
                                revision++; changed = true
                            }
                        }) { Text(if (selected.id in favorites) "Убрать ★" else "В избранное") }
                        TvActionButton(onClick = {
                            if (personal.editChannel(selected.id, current.copy(hidden = !current.hidden))) {
                                revision++; changed = true
                            }
                        }) { Text(if (current.hidden) "Показать" else "Скрыть") }
                        TvActionButton(onClick = {
                            if (personal.moveChannel(selected.id, -1, effective.map { it.id })) {
                                revision++; changed = true
                            }
                        }) { Text("↑") }
                        TvActionButton(onClick = {
                            if (personal.moveChannel(selected.id, 1, effective.map { it.id })) {
                                revision++; changed = true
                            }
                        }) { Text("↓") }
                    }
                    TvActionButton(onClick = { decodePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Декодирование: " + if (decodeMode == "inherit") "из общих настроек"
                            else VideoDecodeMode.entries.firstOrNull { it.name == decodeMode }?.displayName.orEmpty())
                    }
                    TvActionButton(onClick = { aspectPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Формат видео: " + (VideoAspectMode.fromToken(resize) ?: VideoAspectMode.INHERIT).displayName)
                    }
                    OutlinedTextField(preferredAudio, { preferredAudio = it.take(30) },
                        label = { Text("Язык аудио (ru, en; пусто — авто)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(preferredText, { preferredText = it.take(30) },
                        label = { Text("Язык субтитров (пусто — авто)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TvActionButton(onClick = { subtitlesEnabled = !subtitlesEnabled }) {
                        Text(if (subtitlesEnabled) "Субтитры: разрешены" else "Субтитры: отключены")
                    }
                    TvActionButton(onClick = {
                        val name = newName.trim().take(120)
                        val category = newCategory.trim().take(120)
                        if (name.isEmpty() || category.isEmpty()) {
                            message = "Название и категория не должны быть пустыми"
                        } else if (personal.editChannel(selected.id, current.copy(
                            name = name.takeUnless { it == selected.name },
                            category = category.takeUnless { it == selected.category },
                        )) && personal.setChannelPlaybackSettings(selected.id, ChannelPlaybackPreferences(
                            resize = resize, audioLanguage = preferredAudio.trim(),
                            subtitleLanguage = preferredText.trim(), subtitlesEnabled = subtitlesEnabled,
                            decodeMode = decodeMode,
                        ))) {
                            revision++
                            changed = true
                            message = "Изменения канала сохранены"
                        } else message = "Не удалось сохранить канал"
                    }) { Text("Сохранить настройки канала") }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (decodePicker) {
        AlertDialog(onDismissRequest = { decodePicker = false },
            title = { Text("Декодирование канала") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("inherit") + VideoDecodeMode.entries.map { it.name }).forEach { mode ->
                    TvActionButton(onClick = { decodeMode = mode; decodePicker = false },
                        modifier = Modifier.fillMaxWidth()) {
                        Text((if (decodeMode == mode) "✓ " else "") +
                            (if (mode == "inherit") "Из общих настроек"
                            else VideoDecodeMode.valueOf(mode).displayName))
                    }
                }
            } }, confirmButton = { TvActionButton(onClick = { decodePicker = false }) { Text("Отмена") } })
    }
    if (aspectPicker) {
        AlertDialog(onDismissRequest = { aspectPicker = false },
            title = { Text("Соотношение сторон канала") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoAspectMode.entries.forEach { option ->
                    TvActionButton(onClick = { resize = option.token; aspectPicker = false },
                        modifier = Modifier.fillMaxWidth()) {
                        Text((if (resize == option.token) "✓ " else "") + option.displayName)
                    }
                }
            } }, confirmButton = { TvActionButton(onClick = { aspectPicker = false }) { Text("Отмена") } })
    }
    if (confirmReset) {
        AlertDialog(onDismissRequest = { confirmReset = false },
            title = { Text("Вернуть список провайдера?") },
            text = { Text("Восстановятся названия, категории, порядок и видимость. Избранное сохранится.") },
            confirmButton = { TvActionButton(onClick = {
                if (personal.resetChannelEditor()) {
                    revision++; changed = true; message = "Список восстановлен"
                }
                confirmReset = false
            }) { Text("Восстановить") } },
            dismissButton = { TvActionButton(onClick = { confirmReset = false }) { Text("Отмена") } })
    }
    }
}
