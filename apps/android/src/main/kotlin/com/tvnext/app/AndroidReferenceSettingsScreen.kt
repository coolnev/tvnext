package com.tvnext.app

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.AndroidEpgRepository
import com.tvnext.app.core.iptv.data.AndroidFallbackEpgSettings
import com.tvnext.app.core.player.PlaybackController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.tvnext.app.core.tmdb.KinopoiskKeyStore
import com.tvnext.app.core.tmdb.KinopoiskProgrammeLookup
import com.tvnext.app.core.tmdb.ProgrammeMetadataRepository
import com.tvnext.app.core.tmdb.TmdbConnectionResult
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.player.displayName
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.feature.settings.domain.ThemePreference
import com.tvnext.app.feature.settings.domain.StartScreen
import com.tvnext.app.feature.settings.ui.SettingsComponent
import com.tvnext.app.feature.settings.ui.SettingsIntent
import com.tvnext.app.feature.settings.ui.SettingsState

/**
 * Android-TV-only settings layout, visually matched to the 0.5.21 settings
 * reference photographs. Desktop continues using its original SettingsScreen.
 * The two densest sections have fixed compact rows that fit at 1280x720.
 * On smaller displays, a fallback scroll preserves reachability.
 */
@Composable
internal fun AndroidReferenceSettingsScreen(
    component: SettingsComponent,
    onBackHome: (() -> Unit)?,
    onOpen: (String) -> Unit,
) {
    val state by component.state.collectAsState()
    val context = LocalContext.current
    val recording = remember(context) { LocalRecoveryStore(context) }
    var recRevision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    val rightScroll = rememberScrollState()
    LaunchedEffect(selected) { rightScroll.scrollTo(0) }
    var choice by remember { mutableStateOf<ReferenceChoice?>(null) }
    var showHomeBlocks by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showPlaylistAdd by remember { mutableStateOf(false) }
    var showTmdbKey by remember { mutableStateOf(false) }
    var showKinopoiskKey by remember { mutableStateOf(false) }
    var kinopoiskConfigured by remember { mutableStateOf(false) }
    var kinopoiskMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val kinopoiskStore = remember { GlobalContext.get().get<KinopoiskKeyStore>() }
    val kinopoiskLookup = remember { GlobalContext.get().get<KinopoiskProgrammeLookup>() }
    LaunchedEffect(Unit) { kinopoiskConfigured = kinopoiskStore.getKey().isNotBlank() }
    // A changed/disabled TMDB credential must invalidate in-memory programme
    // misses as well as successes. Otherwise cards can stay blank for an hour.
    LaunchedEffect(state.tmdbApiKey, state.tmdbEnabled) {
        GlobalContext.get().get<ProgrammeMetadataRepository>().clearCache()
    }
    var showReset by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showPinChange by remember { mutableStateOf(false) }
    var showPinDisable by remember { mutableStateOf(false) }
    var parentalRevision by remember { mutableIntStateOf(0) }
    val parental = remember(context) { ParentalProtection(context) }
    val epgSource = remember { GlobalContext.get().get<EpgRepository>() }
    val epgLive by epgSource.observeState().collectAsState()
    val epgBusy = epgLive.isLoading || state.epgRefreshing
    var epgMessage by remember { mutableStateOf("") }
    val fallbackEpg = remember(context) { AndroidFallbackEpgSettings(context) }
    var fallbackEnabled by remember { mutableStateOf(fallbackEpg.enabled()) }
    var fallbackUrl by remember { mutableStateOf(fallbackEpg.url()) }
    var showFallbackUrlDialog by remember { mutableStateOf(false) }
    var fallbackUrlError by remember { mutableStateOf(false) }
    fun fallbackChanged() {
        (epgSource as? AndroidEpgRepository)?.fallbackSettingsChanged()
    }
    val sectionNames = remember { listOf(
        "Общие", "Оформление", "Плейлисты и EPG", "Воспроизведение",
        "Медиатека", "Запись", "Родительский контроль", "Обслуживание", "О приложении",
    ) }
    LaunchedEffect(Unit) { component.dispatch(SettingsIntent.Load) }

    fun select(title: String, options: List<Pair<String, () -> Unit>>) {
        choice = ReferenceChoice(title, options)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val condensed = maxHeight < 670.dp
        val railHeight = if (condensed) 40.dp else 54.dp
        // Capture BoxWithConstraints measurements before entering a ColumnScope.
        // `this` inside Column refers to ColumnScope, not BoxWithConstraintsScope.
        // Casting it caused a ClassCastException whenever settings were opened.
        val railWidth = maxWidth * 0.255f
        val entryHeight = if (condensed) 38.dp else 49.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(38.dp)) {
                Text("Настройки", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    modifier = Modifier.width(railWidth).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sectionNames.forEachIndexed { index, section ->
                        TvCardButton(
                            onClick = { selected = index },
                            onFocus = { selected = index },
                            selected = selected == index,
                            modifier = Modifier.fillMaxWidth().height(railHeight),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 3.dp),
                        ) {
                            Text(section, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rightScroll)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(39.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvCardButton(onClick = { onBackHome?.invoke() },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)) {
                            Text("‹ Назад", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    val s = state.settings
                    when (selected) {
                        0 -> {
                            ReferenceRow("Профили", height = entryHeight, onClick = { onOpen("profiles") })
                            ReferenceRow("Стартовый экран", when (s.startScreen) {
                                StartScreen.HOME -> "Главная"
                                StartScreen.CHANNELS -> "Каналы"
                                StartScreen.TV_GUIDE -> "Телепрограмма"
                                StartScreen.MEDIA_LIBRARY -> "Медиатека"
                            }, height = entryHeight, onClick = {
                                select("Стартовый экран", listOf(
                                    "Главная" to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.HOME)) },
                                    "Каналы" to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.CHANNELS)) },
                                    "Телепрограмма" to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.TV_GUIDE)) },
                                    "Медиатека" to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.MEDIA_LIBRARY)) },
                                ))
                            })
                            ReferenceRow("Блоки главной страницы", height = entryHeight,
                                onClick = { showHomeBlocks = true })
                            ReferenceToggle("Номера каналов", s.showChannelNumbers, entryHeight) {
                                component.dispatch(SettingsIntent.ShowChannelNumbersChanged(it))
                            }
                            ReferenceToggle("Возобновлять последний канал", s.openLastChannel, entryHeight) {
                                component.dispatch(SettingsIntent.OpenLastChannelChanged(it))
                            }
                            ReferenceToggle("Запускать на весь экран", s.startFullscreen, entryHeight) {
                                component.dispatch(SettingsIntent.StartFullscreenChanged(it))
                            }
                        }
                        1 -> {
                            ReferenceRow("Язык интерфейса", "Русский", height = entryHeight, enabled = false)
                            ReferenceRow("Тема", when (s.accentColor) {
                                AccentColor.GREEN -> "Классическая"
                                AccentColor.BLUE -> "Морская"
                                AccentColor.ORANGE -> "Тёплая"
                                AccentColor.PURPLE -> "Фиолетовая"
                            }, height = entryHeight, onClick = {
                                select("Тема", listOf(
                                    "Классическая" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.GREEN)) },
                                    "Морская" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.BLUE)) },
                                    "Тёплая" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.ORANGE)) },
                                    "Фиолетовая" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.PURPLE)) },
                                ))
                            })
                            ReferenceRow("Описание передач", if (s.programmeDescriptionRight)
                                "Справа" else "Снизу во всю ширину",
                                height = entryHeight, onClick = {
                                    select("Описание передач", listOf(
                                        "Снизу во всю ширину" to {
                                            component.dispatch(SettingsIntent.ProgrammeDescriptionPlacementChanged(false))
                                        },
                                        "Справа" to {
                                            component.dispatch(SettingsIntent.ProgrammeDescriptionPlacementChanged(true))
                                        },
                                    ))
                                })
                            ReferenceRow("Цветовой режим", when(s.theme) {
                                ThemePreference.DARK -> "Тёмная тема"
                                ThemePreference.LIGHT -> "Светлая тема"
                                ThemePreference.SYSTEM -> "Как в системе"
                            }, height = entryHeight, onClick = {
                                select("Цветовой режим", listOf(
                                    "Тёмная тема" to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.DARK)) },
                                    "Светлая тема" to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.LIGHT)) },
                                    "Как в системе" to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.SYSTEM)) },
                                ))
                            })
                            ReferenceRow("Акцентный цвет", when(s.accentColor) {
                                AccentColor.GREEN -> "Зелёный"
                                AccentColor.BLUE -> "Синий"
                                AccentColor.ORANGE -> "Оранжевый"
                                AccentColor.PURPLE -> "Фиолетовый"
                            }, height = entryHeight, onClick = {
                                select("Акцентный цвет", listOf(
                                    "Зелёный" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.GREEN)) },
                                    "Синий" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.BLUE)) },
                                    "Оранжевый" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.ORANGE)) },
                                    "Фиолетовый" to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.PURPLE)) },
                                ))
                            })
                            ReferenceRow("Прозрачность панелей", "${s.panelTransparencyPercent}%",
                                height = entryHeight, onClick = {
                                    select("Прозрачность панелей", listOf(0, 10, 20, 30, 40, 50, 60, 70, 80).map { percent ->
                                        "$percent%" to {
                                            component.dispatch(SettingsIntent.PanelTransparencyChanged(percent))
                                        }
                                    })
                                })
                            ReferenceRow("Часы на экране", when(s.clockPosition) {
                                ClockPosition.OFF -> "Выключены"
                                ClockPosition.TOP_LEFT -> "Верхний левый угол"
                                ClockPosition.TOP_RIGHT -> "Верхний правый угол"
                            }, height = entryHeight, onClick = {
                                select("Часы на экране", listOf(
                                    "Выключены" to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.OFF)) },
                                    "Верхний левый угол" to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.TOP_LEFT)) },
                                    "Верхний правый угол" to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.TOP_RIGHT)) },
                                ))
                            })
                            ReferenceToggle("Часы без фона", s.clockWithoutBackground, entryHeight) {
                                component.dispatch(SettingsIntent.ClockWithoutBackgroundChanged(it))
                            }
                            ReferenceRow("Информация о передаче", s.epgInfoDurationSeconds?.let {
                                if (it == 0) "Всегда" else "$it секунды"
                            } ?: "Выключено", height = entryHeight, onClick = {
                                select("Информация о передаче",
                                    (listOf(null to "Выключено", 0 to "Всегда") +
                                        (1..7).map { it to "$it сек." }).map { (seconds, text) ->
                                        text to { component.dispatch(SettingsIntent.EpgInfoDurationChanged(seconds)) }
                                    })
                            })
                        }
                        2 -> {
                            ReferenceRow("Редактор каналов", height = entryHeight,
                                onClick = { onOpen("editor") })
                            ReferenceRow("Мои плейлисты", "${state.playlists.size}", height = entryHeight,
                                onClick = { showPlaylistDialog = true })
                            ReferenceRow("Добавить источник", height = entryHeight,
                                onClick = { showPlaylistAdd = true })
                            ReferenceToggle("Телепрограмма", s.epgEnabled, entryHeight) {
                                component.dispatch(SettingsIntent.EpgEnabledChanged(it))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    ReferenceRow(
                                        if (epgBusy) "Загрузка EPG…" else "Обновить EPG",
                                        height = entryHeight,
                                        enabled = !epgBusy,
                                        onClick = {
                                            epgMessage = ""
                                            component.dispatch(SettingsIntent.EpgRefresh)
                                        },
                                    )
                                }
                                if (epgBusy) CircularProgressIndicator(
                                    modifier = Modifier.padding(start = 8.dp).size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            if (epgBusy || epgLive.programmeCount > 0) {
                                ReferenceNote("Каналов с EPG: ${epgLive.channelCount} · передач: ${epgLive.programmeCount}" +
                                    if (epgBusy) " · обновляется" else "")
                            }
                            epgLive.lastError?.let { ReferenceNote("EPG: $it") }
                            epgLive.notice?.let { ReferenceNote(it) }
                            ReferenceToggle("Искать EPG в интернете", fallbackEnabled, entryHeight) { value ->
                                fallbackEpg.setEnabled(value)
                                fallbackEnabled = value
                                fallbackChanged()
                            }
                            if (fallbackEnabled) {
                                ReferenceRow("Резервный источник XMLTV", "Настроить", height = entryHeight,
                                    onClick = { fallbackUrl = fallbackEpg.url(); showFallbackUrlDialog = true })
                            }
                        }
                        3 -> {
                            ReferenceRow("Индивидуальные настройки каналов", height = entryHeight,
                                onClick = { onOpen("editor") })
                            ReferenceRow("Соотношение сторон", height = entryHeight,
                                onClick = { onOpen("video_aspect") })
                            ReferenceRow("Декодирование видео", s.videoDecodeMode.displayName,
                                height = entryHeight, onClick = {
                                    select("Декодирование видео", VideoDecodeMode.entries.map { mode ->
                                        mode.displayName to { component.dispatch(SettingsIntent.VideoDecodeModeChanged(mode)) }
                                    })
                                })
                            ReferenceRow("Буфер сети", "${s.networkCachingMs / 1000}.0 сек.",
                                height = entryHeight, onClick = {
                                    select("Буфер сети", listOf(1000,3000,5000,8000).map { ms ->
                                        "${ms / 1000} сек." to { component.dispatch(SettingsIntent.NetworkCachingChanged(ms)) }
                                    })
                                })
                            ReferenceRow("Глубина архива",
                                if (s.archiveLimitDays == 0) "Автоматически" else "${s.archiveLimitDays} дн.",
                                height = entryHeight, onClick = {
                                select("Глубина архива", listOf(0,1,2,3,7,14,30).map { days ->
                                    (if (days == 0) "Автоматически" else "$days дн.") to {
                                        component.dispatch(SettingsIntent.ArchiveLimitChanged(days))
                                    }
                                })
                            })
                            ReferenceRow("Шаг перемотки", "${s.archiveSeekStepMs / 60000} мин.",
                                height = entryHeight, onClick = {
                                    select("Шаг перемотки", listOf(1L,5L,10L,30L).map { minutes ->
                                        "$minutes мин." to {
                                            component.dispatch(SettingsIntent.ArchiveSeekStepChanged(minutes * 60000L))
                                        }
                                    })
                                })
                        }
                        4 -> {
                            ReferenceToggle("Описания и постеры из TMDB", state.tmdbEnabled, entryHeight) {
                                component.dispatch(SettingsIntent.TmdbEnabledChanged(it))
                            }
                            ReferenceRow("Проверить соединение", height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbTestConnection) })
                            ReferenceRow("Собственный API-ключ", "••••••••", height = entryHeight,
                                onClick = { showTmdbKey = true })
                            ReferenceRow("Очистить кэш TMDB", height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbClearCache) })
                            ReferenceRow("Kinopoisk.dev · резерв",
                                if (kinopoiskConfigured) "Ключ задан" else "Добавить ключ",
                                height = entryHeight, onClick = { showKinopoiskKey = true })
                            ReferenceRow("Проверить Kinopoisk.dev", height = entryHeight,
                                onClick = {
                                    kinopoiskMessage = "Проверка соединения…"
                                    scope.launch {
                                        kinopoiskMessage = if (kinopoiskLookup.testConnection())
                                            "Kinopoisk.dev: соединение установлено" else
                                            "Kinopoisk.dev: недоступен или ключ неверен"
                                    }
                                })
                            if (kinopoiskMessage.isNotEmpty()) {
                                Text(kinopoiskMessage, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            state.tmdbConnectionResult?.let { result ->
                                val message = when (result.kind) {
                                    TmdbConnectionResult.Kind.SUCCESS -> "TMDB: соединение установлено"
                                    TmdbConnectionResult.Kind.INVALID_KEY -> "TMDB: ключ отклонён (HTTP 401/403)"
                                    TmdbConnectionResult.Kind.NO_CONNECTION -> "TMDB: нет ответа от сервера — проверьте сеть, DNS и доступность API"
                                    TmdbConnectionResult.Kind.ERROR -> "TMDB: сервер вернул ошибку"
                                }
                                Text(message, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        5 -> {
                            // All entries remain on one screen at 720p.
                            ReferenceNote("Запись продолжается при свёрнутом приложении.")
                            ReferenceRow("Папка записи",
                                recording.selectedRecordingLocation()?.title ?: "Недоступна",
                                height = entryHeight, onClick = { onOpen("recording_settings") })
                            ReferenceRow("Выбрать папку", height = entryHeight,
                                onClick = { onOpen("recording_settings") })
                            ReferenceRow("Выбрать без проводника", height = entryHeight,
                                onClick = { onOpen("recording_settings") })
                            ReferenceRow("Лимит всех записей", "${recording.limitGiB()} ГБ",
                                height = entryHeight, onClick = {
                                    select("Лимит всех записей", listOf(1,2,4,8,16,32,64,128).map { gb ->
                                        "$gb ГБ" to { recording.setLimitGiB(gb); recRevision++ }
                                    })
                                })
                            ReferenceRow("До начала", "${recording.paddingBeforeMinutes()} мин",
                                height = entryHeight, onClick = {
                                    select("До начала", listOf(0,1,2,5,10,15,30,60).map { minutes ->
                                        "$minutes мин" to {
                                            recording.setRecordingPadding(minutes, recording.paddingAfterMinutes())
                                            recRevision++
                                        }
                                    })
                                })
                            ReferenceRow("После окончания", "${recording.paddingAfterMinutes()} мин",
                                height = entryHeight, onClick = {
                                    select("После окончания", listOf(0,1,2,5,10,15,30,60).map { minutes ->
                                        "$minutes мин" to {
                                            recording.setRecordingPadding(recording.paddingBeforeMinutes(), minutes)
                                            recRevision++
                                        }
                                    })
                                })
                            val notifyEnabled = remember(recRevision) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                        .areNotificationsEnabled()
                                } else true
                            }
                            ReferenceRow("Уведомления записи", if(notifyEnabled) "Включены" else "Выключены",
                                height = entryHeight, onClick = { onOpen("recording_settings") })
                        }
                        6 -> {
                            val enabled = remember(parentalRevision) { parental.enabled }
                            ReferenceNote("Категории XXX, порно и эротика закрыты PIN-кодом. Доступ действует до выхода из программы.")
                            ReferenceRow("Родительский контроль", if (enabled) "Включён" else "Выключен",
                                height = entryHeight, onClick = {
                                    if (enabled) showPinDisable = true
                                    else { parental.enabled = true; parentalRevision++ }
                                })
                            ReferenceRow("Изменить PIN-код", height = entryHeight,
                                onClick = { showPinChange = true })
                            ReferenceRow("Хранение PIN", "Только на устройстве", height = entryHeight, enabled = false)
                        }
                        7 -> {
                            ReferenceRow("Резервная копия профилей", height = entryHeight,
                                onClick = { onOpen("backup") })
                            ReferenceRow("Мои записи", height = entryHeight, onClick = { onOpen("recordings") })
                            ReferenceRow("Очистить кэш TMDB", height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbClearCache) })
                            ReferenceRow("Диагностика", height = entryHeight, onClick = { showDiagnosticsDialog = true })
                            ReferenceRow("Сбросить данные приложения", height = entryHeight,
                                onClick = { showReset = true })
                        }
                        else -> {
                            val info = remember(context) {
                                context.packageManager.getPackageInfo(context.packageName, 0)
                            }
                            ReferenceRow("Название", "TVNext", height = entryHeight, enabled = false)
                            ReferenceRow("Версия", "${info.versionName} · восстановление",
                                height = entryHeight, enabled = false)
                            ReferenceRow("Лицензия", "TVNext Free", height = entryHeight, enabled = false)
                            ReferenceNote("Приложение использует API TMDB, но не одобрено и не сертифицировано TMDB.")
                        }
                    }
                }
            }
        }
    }

    if (showFallbackUrlDialog) {
        AlertDialog(
            onDismissRequest = { showFallbackUrlDialog = false },
            title = { Text("Резервная телепрограмма") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Если в EPG провайдера нет программы для канала, TVNext попробует " +
                        "дополнить её из резервных XMLTV. Основной источник имеет приоритет.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = fallbackUrl, onValueChange = { fallbackUrl = it; fallbackUrlError = false },
                        label = { Text("URL XMLTV (по одному в строке, до 3)") },
                        singleLine = false, minLines = 2, maxLines = 4,
                        isError = fallbackUrlError, modifier = Modifier.fillMaxWidth(),
                    )
                    if (fallbackUrlError) Text("До восьми адресов XMLTV (по одному на строку, http:// или https://)",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("Пустое поле отключает резерв. По умолчанию: российский EPG " +
                        "и дополнительный источник с каналами BCU. Совпадение не гарантируется.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (fallbackEpg.setUrl(fallbackUrl)) {
                        fallbackChanged()
                        showFallbackUrlDialog = false
                    } else fallbackUrlError = true
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { fallbackUrl = AndroidFallbackEpgSettings.DEFAULT_URLS }) {
                    Text("По умолчанию")
                }
                TextButton(onClick = { showFallbackUrlDialog = false }) { Text("Отмена") }
            },
        )
    }
    if (showPinChange) ParentalPinSettingsDialog(parental, onClose = { showPinChange = false },
        onChanged = { parentalRevision++ })
    if (showPinDisable) ParentalPinEntryDialog(
        title = "Отключить родительский контроль",
        onCancel = { showPinDisable = false },
        onSubmit = { input ->
            if (parental.verify(input)) {
                parental.enabled = false; parentalRevision++; showPinDisable = false; true
            } else false
        },
    )
    choice?.let { dialog ->
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(dialog.title) },
            text = {
                Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                    dialog.options.forEach { (label, callback) ->
                        TvCardButton(onClick = { callback(); choice = null },
                            modifier = Modifier.fillMaxWidth().height(47.dp)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choice = null }) { Text("Закрыть") } },
        )
    }
    if (showHomeBlocks) {
        AlertDialog(
            onDismissRequest = { showHomeBlocks = false },
            title = { Text("Блоки главной страницы") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val s = state.settings
                    listOf(
                        Triple("Сейчас идёт", s.showHomeNowPlaying, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeNowPlayingChanged(x)) }),
                        Triple("Ближайшие передачи", s.showHomeUpcoming, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeUpcomingChanged(x)) }),
                        Triple("Недавно просмотренные", s.showHomeRecent, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeRecentChanged(x)) }),
                        Triple("Избранное", s.showHomeFavorites, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeFavoritesChanged(x)) }),
                        Triple("Недавние фильмы", s.showHomeRecentVod, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeRecentVodChanged(x)) }),
                        Triple("Избранные фильмы", s.showHomeFavoriteVod, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeFavoriteVodChanged(x)) }),
                    ).forEach { (title, checked, cb) -> ReferenceToggle(title, checked, 43.dp, cb) }
                }
            },
            confirmButton = { TextButton(onClick = { showHomeBlocks = false }) { Text("Готово") } },
        )
    }
    if (showPlaylistDialog) {
        AlertDialog(onDismissRequest = { showPlaylistDialog = false },
            title = { Text("Мои плейлисты") }, text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    state.playlists.forEach { playlist ->
                        Text(playlist.name, color = MaterialTheme.colorScheme.onSurface)
                        Row {
                            TextButton(onClick = { component.dispatch(SettingsIntent.PlaylistRefresh(playlist.id)) }) { Text("Обновить") }
                            TextButton(onClick = { component.dispatch(SettingsIntent.PlaylistRemove(playlist.id)) }) { Text("Удалить") }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showPlaylistDialog = false }) { Text("Закрыть") } })
    }
    if (showPlaylistAdd) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showPlaylistAdd = false },
            title = { Text("Добавить источник M3U") }, text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("URL источника") }, singleLine = true)
                }
            }, confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    component.dispatch(SettingsIntent.PlaylistNameChanged(name))
                    component.dispatch(SettingsIntent.PlaylistUrlChanged(url))
                    component.dispatch(SettingsIntent.PlaylistAdd)
                    showPlaylistAdd = false
                }) { Text("Добавить") }
            }, dismissButton = { TextButton(onClick = { showPlaylistAdd = false }) { Text("Отмена") } })
    }
    if (showTmdbKey) {
        var key by remember { mutableStateOf(state.tmdbApiKey) }
        AlertDialog(onDismissRequest = { showTmdbKey = false },
            title = { Text("Собственный ключ TMDB") }, text = {
                OutlinedTextField(key, { key = it }, label = { Text("API-ключ") }, singleLine = true)
            }, confirmButton = { TextButton(onClick = {
                component.dispatch(SettingsIntent.TmdbApiKeyChanged(key))
                showTmdbKey = false
            }) { Text("Сохранить") } }, dismissButton = {
                TextButton(onClick = { showTmdbKey = false }) { Text("Отмена") }
            })
    }
    if (showKinopoiskKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showKinopoiskKey = false },
            title = { Text("Kinopoisk.dev — резервный API-ключ") },
            text = {
                Column {
                    Text("Можно задать другой API-ключ. Пустое поле использует ключ по умолчанию.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text("Полный API-ключ") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            }, confirmButton = { TextButton(onClick = {
                scope.launch {
                    kinopoiskStore.setKey(key)
                    kinopoiskLookup.resetAfterKeyChange()
                    GlobalContext.get().get<ProgrammeMetadataRepository>().clearCache()
                    kinopoiskConfigured = kinopoiskStore.getKey().isNotBlank()
                    kinopoiskMessage = ""
                }
                showKinopoiskKey = false
            }) { Text("Сохранить") } }, dismissButton = {
                TextButton(onClick = { showKinopoiskKey = false }) { Text("Отмена") }
            })
    }
    if (showDiagnosticsDialog) {
        val player = remember { GlobalContext.get().get<PlaybackController>() }
        val playback by player.state.collectAsState()
        val diagnostics = playback.diagnostics
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Диагностика") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Плеер: ${playback.state} · ${playback.item?.title ?: "нет воспроизведения"}")
                    Text("EPG: " + if (epgLive.isLoading) "обновляется" else "ожидание")
                    Text("Каналов с EPG: ${epgLive.channelCount} · передач: ${epgLive.programmeCount}")
                    epgLive.lastError?.let { Text("Ошибка EPG: $it", color = MaterialTheme.colorScheme.error) }
                    epgLive.notice?.let { Text(it) }
                    if (diagnostics != null) {
                        Text("Декодирование: ${diagnostics.actualMode} · ${diagnostics.decoder}")
                        Text("Видеовыход: ${diagnostics.videoOutput}")
                        Text("Разрешение: ${diagnostics.resolution}")
                        Text("Кодек: ${diagnostics.codec ?: "—"}, FPS: ${diagnostics.fps ?: "—"}")
                        Text("Битрейт: ${diagnostics.bitrate ?: "—"}")
                        Text("Состояние потока: ${diagnostics.transportState}")
                    } else Text("Параметры видеопотока появятся после запуска канала")
                }
            },
            confirmButton = { TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Закрыть") } },
        )
    }
    if (showReset) {
        AlertDialog(onDismissRequest = { showReset = false },
            title = { Text("Сбросить данные?") }, text = {
                Text("Все плейлисты, история и настройки будут удалены. Отменить это нельзя.")
            }, confirmButton = { TextButton(onClick = {
                component.dispatch(SettingsIntent.DataResetStart)
                component.dispatch(SettingsIntent.DataResetConfirm)
                showReset = false
            }) { Text("Сбросить") } }, dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Отмена") }
            })
    }
}

private data class ReferenceChoice(val title: String, val options: List<Pair<String, () -> Unit>>)

@Composable
private fun ReferenceNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 7.dp))
}

@Composable
private fun ReferenceRow(
    label: String,
    value: String = "",
    height: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    TvCardButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(height),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp),
        containerAlpha = 0.12f,
        shape = RoundedCornerShape(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) {
                Spacer(Modifier.width(10.dp))
                Text("›", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ReferenceToggle(label: String, checked: Boolean, height: androidx.compose.ui.unit.Dp,
                            onChange: (Boolean) -> Unit) {
    TvCardButton(onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth().height(height),
        containerAlpha = 0.12f,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = checked, onCheckedChange = null,
                modifier = Modifier.height(28.dp))
        }
    }
}
