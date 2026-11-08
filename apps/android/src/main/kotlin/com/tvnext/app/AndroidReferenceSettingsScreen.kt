package com.tvnext.app

import com.tvnext.app.core.ui.i18n.uiText

import com.tvnext.app.core.ui.component.TvActionButton
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var wakeEnabled by remember(context) { mutableStateOf(WakeLaunchSettings.enabled(context)) }
    var wakeMessage by remember(context) { mutableStateOf(WakeLaunchSettings.failure(context)) }
    var videoAnimation by remember(context) { mutableStateOf(AndroidAnimationSettings.enabled(context)) }
    val wakePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        wakeEnabled = WakeLaunchSettings.enabled(context)
        wakeMessage = if (WakeLaunchSettings.canLaunch(context)) WakeLaunchSettings.failure(context)
            else uiText("Для запуска после сна разрешите TVNext показ поверх других приложений.")
    }
    DisposableEffect(context) {
        val prefs = WakeLaunchSettings.preferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            wakeEnabled = WakeLaunchSettings.enabled(context)
            wakeMessage = WakeLaunchSettings.failure(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
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
    var kinopoiskTesting by remember { mutableStateOf(false) }
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
    val sectionNames = remember(com.tvnext.app.core.ui.i18n.UiLocale.language) { listOf(
        uiText("Общие"), uiText("Оформление"), uiText("Плейлисты и EPG"), uiText("Воспроизведение"),
        uiText("Медиатека"), uiText("Запись"), uiText("Родительский контроль"), uiText("Обслуживание"), uiText("О приложении"),
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
                Text(uiText("Настройки"), style = MaterialTheme.typography.titleLarge,
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
                            Text(uiText("‹ Назад"), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    val s = state.settings
                    when (selected) {
                        0 -> {
                            ReferenceRow(uiText("Профили"), height = entryHeight, onClick = { onOpen("profiles") })
                            ReferenceToggle(uiText("Запускать TVNext при включении"), wakeEnabled, entryHeight) { enabled ->
                                wakeMessage = ""
                                if (!enabled) WakeLaunchSettings.setEnabled(context, false)
                                else if (WakeLaunchSettings.canLaunch(context)) {
                                    if (!WakeLaunchSettings.setEnabled(context, true)) wakeMessage = uiText("Не удалось включить запуск после сна.")
                                } else {
                                    WakeLaunchSettings.requestPermission(context)
                                    runCatching { wakePermission.launch(WakeLaunchSettings.permissionIntent(context)) }
                                        .onFailure {
                                            WakeLaunchSettings.setEnabled(context, false)
                                            wakeMessage = uiText("В настройках Android разрешите TVNext показ поверх других приложений.")
                                        }
                                }
                            }
                            Text(wakeMessage.ifBlank { uiText("Открывать TVNext после выхода телевизора из спящего режима.") },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (wakeMessage.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            ReferenceRow(uiText("Стартовый экран"), when (s.startScreen) {
                                StartScreen.HOME -> uiText("Главная")
                                StartScreen.CHANNELS -> uiText("Каналы")
                                StartScreen.TV_GUIDE -> uiText("Телепрограмма")
                                StartScreen.MEDIA_LIBRARY -> uiText("Медиатека")
                            }, height = entryHeight, onClick = {
                                select(uiText("Стартовый экран"), listOf(
                                    uiText("Главная") to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.HOME)) },
                                    uiText("Каналы") to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.CHANNELS)) },
                                    uiText("Телепрограмма") to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.TV_GUIDE)) },
                                    uiText("Медиатека") to { component.dispatch(SettingsIntent.StartScreenChanged(StartScreen.MEDIA_LIBRARY)) },
                                ))
                            })
                            ReferenceRow(uiText("Блоки главной страницы"), height = entryHeight,
                                onClick = { showHomeBlocks = true })
                            ReferenceToggle(uiText("Номера каналов"), s.showChannelNumbers, entryHeight) {
                                component.dispatch(SettingsIntent.ShowChannelNumbersChanged(it))
                            }
                            ReferenceToggle(uiText("Возобновлять последний канал"), s.openLastChannel, entryHeight) {
                                component.dispatch(SettingsIntent.OpenLastChannelChanged(it))
                            }
                        }
                        1 -> {
                            ReferenceToggle(uiText("Анимация"), videoAnimation, entryHeight) { enabled ->
                                if (AndroidAnimationSettings.setEnabled(context, enabled)) videoAnimation = enabled
                            }
                            ReferenceRow(uiText("Язык интерфейса"), s.language.nativeName, height = entryHeight, onClick = {
                                select(uiText("Язык интерфейса"), com.tvnext.app.core.common.UiLanguage.entries.map { language ->
                                    language.nativeName to { component.dispatch(SettingsIntent.LanguageChanged(language)) }
                                })
                            })
                            ReferenceRow(uiText("Тема"), when (s.accentColor) {
                                AccentColor.GREEN -> uiText("Классическая")
                                AccentColor.BLUE -> uiText("Морская")
                                AccentColor.ORANGE -> uiText("Тёплая")
                                AccentColor.PURPLE -> uiText("Фиолетовая")
                            }, height = entryHeight, onClick = {
                                select(uiText("Тема"), listOf(
                                    uiText("Классическая") to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.GREEN)) },
                                    uiText("Морская") to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.BLUE)) },
                                    uiText("Тёплая") to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.ORANGE)) },
                                    uiText("Фиолетовая") to { component.dispatch(SettingsIntent.AccentColorChanged(AccentColor.PURPLE)) },
                                ))
                            })
                            ReferenceRow(uiText("Описание передач"), if (s.programmeDescriptionRight)
                                uiText("Справа") else uiText("Снизу во всю ширину"),
                                height = entryHeight, onClick = {
                                    select(uiText("Описание передач"), listOf(
                                        uiText("Снизу во всю ширину") to {
                                            component.dispatch(SettingsIntent.ProgrammeDescriptionPlacementChanged(false))
                                        },
                                        uiText("Справа") to {
                                            component.dispatch(SettingsIntent.ProgrammeDescriptionPlacementChanged(true))
                                        },
                                    ))
                                })
                            ReferenceRow(uiText("Цветовой режим"), when(s.theme) {
                                ThemePreference.DARK -> uiText("Тёмная тема")
                                ThemePreference.LIGHT -> uiText("Светлая тема")
                                ThemePreference.SYSTEM -> uiText("Как в системе")
                            }, height = entryHeight, onClick = {
                                select(uiText("Цветовой режим"), listOf(
                                    uiText("Тёмная тема") to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.DARK)) },
                                    uiText("Светлая тема") to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.LIGHT)) },
                                    uiText("Как в системе") to { component.dispatch(SettingsIntent.ThemeChanged(ThemePreference.SYSTEM)) },
                                ))
                            })
                            ReferenceRow(uiText("Прозрачность панелей"), "${s.panelTransparencyPercent}%",
                                height = entryHeight, onClick = {
                                    select(uiText("Прозрачность панелей"), listOf(0, 10, 20, 30, 40, 50, 60, 70, 80).map { percent ->
                                        "$percent%" to {
                                            component.dispatch(SettingsIntent.PanelTransparencyChanged(percent))
                                        }
                                    })
                                })
                            ReferenceRow(uiText("Часы на экране"), when(s.clockPosition) {
                                ClockPosition.OFF -> uiText("Выключены")
                                ClockPosition.TOP_LEFT -> uiText("Верхний левый угол")
                                ClockPosition.TOP_RIGHT -> uiText("Верхний правый угол")
                            }, height = entryHeight, onClick = {
                                select(uiText("Часы на экране"), listOf(
                                    uiText("Выключены") to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.OFF)) },
                                    uiText("Верхний левый угол") to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.TOP_LEFT)) },
                                    uiText("Верхний правый угол") to { component.dispatch(SettingsIntent.ClockPositionChanged(ClockPosition.TOP_RIGHT)) },
                                ))
                            })
                            ReferenceToggle(uiText("Часы без фона"), s.clockWithoutBackground, entryHeight) {
                                component.dispatch(SettingsIntent.ClockWithoutBackgroundChanged(it))
                            }
                            ReferenceRow(uiText("Информация о передаче"), s.epgInfoDurationSeconds?.let {
                                if (it == 0) uiText("Всегда") else uiText("{0} секунды", it)
                            } ?: uiText("Выключено"), height = entryHeight, onClick = {
                                select(uiText("Информация о передаче"),
                                    (listOf(null to uiText("Выключено"), 0 to uiText("Всегда")) +
                                        (1..7).map { it to uiText("{0} сек.", it) }).map { (seconds, text) ->
                                        text to { component.dispatch(SettingsIntent.EpgInfoDurationChanged(seconds)) }
                                    })
                            })
                        }
                        2 -> {
                            ReferenceRow(uiText("Редактор каналов"), height = entryHeight,
                                onClick = { onOpen("editor") })
                            ReferenceRow(uiText("Мои плейлисты"), "${state.playlists.size}", height = entryHeight,
                                onClick = { showPlaylistDialog = true })
                            ReferenceRow(uiText("Добавить источник"), height = entryHeight,
                                onClick = { showPlaylistAdd = true })
                            ReferenceToggle(uiText("Телепрограмма"), s.epgEnabled, entryHeight) {
                                component.dispatch(SettingsIntent.EpgEnabledChanged(it))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    ReferenceRow(
                                        if (epgBusy) uiText("Загрузка EPG…") else uiText("Обновить EPG"),
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
                                ReferenceNote(uiText("Каналов с EPG: {0} · передач: {1}", epgLive.channelCount, epgLive.programmeCount) +
                                    if (epgBusy) uiText(" · обновляется") else "")
                            }
                            epgLive.lastError?.let { ReferenceNote("EPG: $it") }
                            epgLive.notice?.let { ReferenceNote(it) }
                            ReferenceToggle(uiText("Искать EPG в интернете"), fallbackEnabled, entryHeight) { value ->
                                fallbackEpg.setEnabled(value)
                                fallbackEnabled = value
                                fallbackChanged()
                            }
                            if (fallbackEnabled) {
                                ReferenceRow(uiText("Резервный источник XMLTV"), uiText("Настроить"), height = entryHeight,
                                    onClick = { fallbackUrl = fallbackEpg.url(); showFallbackUrlDialog = true })
                            }
                        }
                        3 -> {
                            ReferenceRow(uiText("Индивидуальные настройки каналов"), height = entryHeight,
                                onClick = { onOpen("editor") })
                            ReferenceRow(uiText("Соотношение сторон"), height = entryHeight,
                                onClick = { onOpen("video_aspect") })
                            ReferenceRow(uiText("Декодирование видео"), uiText(s.videoDecodeMode.displayName),
                                height = entryHeight, onClick = {
                                    select(uiText("Декодирование видео"), VideoDecodeMode.entries.map { mode ->
                                        uiText(mode.displayName) to { component.dispatch(SettingsIntent.VideoDecodeModeChanged(mode)) }
                                    })
                                })
                            ReferenceRow(uiText("Буфер сети"), uiText("{0}.0 сек.", s.networkCachingMs / 1000),
                                height = entryHeight, onClick = {
                                    select(uiText("Буфер сети"), listOf(1000,3000,5000,8000).map { ms ->
                                        uiText("{0} сек.", ms / 1000) to { component.dispatch(SettingsIntent.NetworkCachingChanged(ms)) }
                                    })
                                })
                            ReferenceRow(uiText("Глубина архива"),
                                if (s.archiveLimitDays == 0) uiText("Автоматически") else uiText("{0} дн.", s.archiveLimitDays),
                                height = entryHeight, onClick = {
                                select(uiText("Глубина архива"), listOf(0,1,2,3,7,14,30).map { days ->
                                    (if (days == 0) uiText("Автоматически") else uiText("{0} дн.", days)) to {
                                        component.dispatch(SettingsIntent.ArchiveLimitChanged(days))
                                    }
                                })
                            })
                            ReferenceRow(uiText("Шаг перемотки"), uiText("{0} мин.", s.archiveSeekStepMs / 60000),
                                height = entryHeight, onClick = {
                                    select(uiText("Шаг перемотки"), listOf(1L,5L,10L,30L).map { minutes ->
                                        uiText("{0} мин.", minutes) to {
                                            component.dispatch(SettingsIntent.ArchiveSeekStepChanged(minutes * 60000L))
                                        }
                                    })
                                })
                        }
                        4 -> {
                            ReferenceToggle(uiText("Описания и постеры из TMDB"), state.tmdbEnabled, entryHeight) {
                                component.dispatch(SettingsIntent.TmdbEnabledChanged(it))
                            }
                            ReferenceRow(uiText("Проверить соединение"), height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbTestConnection) })
                            ReferenceRow(uiText("Собственный API-ключ"), "••••••••", height = entryHeight,
                                onClick = { showTmdbKey = true })
                            ReferenceRow(uiText("Очистить кэш TMDB"), height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbClearCache) })
                            ReferenceRow(uiText("Кинопоиск · Unofficial"),
                                if (kinopoiskConfigured) uiText("Ключ задан") else uiText("Добавить ключ"),
                                height = entryHeight, onClick = { showKinopoiskKey = true })
                            ReferenceRow(uiText("Проверить Кинопоиск"), height = entryHeight,
                                onClick = {
                                    if (!kinopoiskTesting) {
                                        kinopoiskTesting = true
                                        kinopoiskMessage = uiText("Проверка соединения…")
                                        scope.launch {
                                            try { kinopoiskMessage = kinopoiskLookup.checkConnection().message }
                                            finally { kinopoiskTesting = false }
                                        }
                                    }
                                })
                            if (kinopoiskMessage.isNotEmpty()) {
                                Text(kinopoiskMessage, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            state.tmdbConnectionResult?.let { result ->
                                val message = when (result.kind) {
                                    TmdbConnectionResult.Kind.SUCCESS -> uiText("TMDB: соединение установлено")
                                    TmdbConnectionResult.Kind.INVALID_KEY -> uiText("TMDB: ключ отклонён (HTTP 401/403)")
                                    TmdbConnectionResult.Kind.NO_CONNECTION -> uiText("TMDB: нет ответа от сервера — проверьте сеть, DNS и доступность API")
                                    TmdbConnectionResult.Kind.ERROR -> uiText("TMDB: сервер вернул ошибку")
                                }
                                Text(message, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        5 -> {
                            // All entries remain on one screen at 720p.
                            ReferenceNote(uiText("Запись продолжается при свёрнутом приложении."))
                            ReferenceRow(uiText("Папка записи"),
                                recording.selectedRecordingLocation()?.title ?: uiText("Недоступна"),
                                height = entryHeight, onClick = { onOpen("recording_settings") })
                            ReferenceRow(uiText("Выбрать папку"), height = entryHeight,
                                onClick = { onOpen("recording_settings") })
                            ReferenceRow(uiText("Выбрать без проводника"), height = entryHeight,
                                onClick = { onOpen("recording_settings") })
                            ReferenceRow(uiText("Лимит всех записей"), uiText("{0} ГБ", recording.limitGiB()),
                                height = entryHeight, onClick = {
                                    select(uiText("Лимит всех записей"), listOf(1,2,4,8,16,32,64,128).map { gb ->
                                        uiText("{0} ГБ", gb) to { recording.setLimitGiB(gb); recRevision++ }
                                    })
                                })
                            ReferenceRow(uiText("До начала"), uiText("{0} мин", recording.paddingBeforeMinutes()),
                                height = entryHeight, onClick = {
                                    select(uiText("До начала"), listOf(0,1,2,5,10,15,30,60).map { minutes ->
                                        uiText("{0} мин", minutes) to {
                                            recording.setRecordingPadding(minutes, recording.paddingAfterMinutes())
                                            recRevision++
                                        }
                                    })
                                })
                            ReferenceRow(uiText("После окончания"), uiText("{0} мин", recording.paddingAfterMinutes()),
                                height = entryHeight, onClick = {
                                    select(uiText("После окончания"), listOf(0,1,2,5,10,15,30,60).map { minutes ->
                                        uiText("{0} мин", minutes) to {
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
                            ReferenceRow(uiText("Уведомления записи"), if(notifyEnabled) uiText("Включены") else uiText("Выключены"),
                                height = entryHeight, onClick = { onOpen("recording_settings") })
                        }
                        6 -> {
                            val enabled = remember(parentalRevision) { parental.enabled }
                            ReferenceNote(uiText("Категории XXX, порно и эротика закрыты PIN-кодом. Доступ действует до выхода из программы."))
                            ReferenceRow(uiText("Родительский контроль"), if (enabled) uiText("Включён") else uiText("Выключен"),
                                height = entryHeight, onClick = {
                                    if (enabled) showPinDisable = true
                                    else { parental.enabled = true; parentalRevision++ }
                                })
                            ReferenceRow(uiText("Изменить PIN-код"), height = entryHeight,
                                onClick = { showPinChange = true })
                            ReferenceRow(uiText("Хранение PIN"), uiText("Только на устройстве"), height = entryHeight, enabled = false)
                        }
                        7 -> {
                            ReferenceRow(uiText("Резервная копия профилей"), height = entryHeight,
                                onClick = { onOpen("backup") })
                            ReferenceRow(uiText("Очистить кэш TMDB"), height = entryHeight,
                                onClick = { component.dispatch(SettingsIntent.TmdbClearCache) })
                            ReferenceRow(uiText("Диагностика"), height = entryHeight, onClick = { showDiagnosticsDialog = true })
                            ReferenceRow(uiText("Сбросить данные приложения"), height = entryHeight,
                                onClick = { showReset = true })
                        }
                        else -> {
                            val info = remember(context) {
                                context.packageManager.getPackageInfo(context.packageName, 0)
                            }
                            ReferenceRow(uiText("Название"), "TVNext", height = entryHeight, enabled = false)
                            ReferenceRow(uiText("Версия"), info.versionName.orEmpty(),
                                height = entryHeight, enabled = false)
                            ReferenceRow(uiText("Лицензия"), "TVNext Free", height = entryHeight, enabled = false)
                            ReferenceNote(uiText("Приложение использует API TMDB, но не одобрено и не сертифицировано TMDB."))
                        }
                    }
                }
            }
        }
    }

    if (showFallbackUrlDialog) {
        AlertDialog(
            onDismissRequest = { showFallbackUrlDialog = false },
            title = { Text(uiText("Резервная телепрограмма")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiText("Если в EPG провайдера нет программы для канала, TVNext попробует ") +
                        uiText("дополнить её из резервных XMLTV. Основной источник имеет приоритет."),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = fallbackUrl, onValueChange = { fallbackUrl = it; fallbackUrlError = false },
                        label = { Text(uiText("URL XMLTV (по одному в строке, до 3)")) },
                        singleLine = false, minLines = 2, maxLines = 4,
                        isError = fallbackUrlError, modifier = Modifier.fillMaxWidth(),
                    )
                    if (fallbackUrlError) Text(uiText("До восьми адресов XMLTV (по одному на строку, http:// или https://)"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(uiText("Пустое поле отключает резерв. По умолчанию: российский EPG ") +
                        uiText("и дополнительный источник с каналами BCU. Совпадение не гарантируется."),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TvActionButton(onClick = {
                    if (fallbackEpg.setUrl(fallbackUrl)) {
                        fallbackChanged()
                        showFallbackUrlDialog = false
                    } else fallbackUrlError = true
                }) { Text(uiText("Сохранить")) }
            },
            dismissButton = {
                TvActionButton(onClick = { fallbackUrl = AndroidFallbackEpgSettings.DEFAULT_URLS }) {
                    Text(uiText("По умолчанию"))
                }
                TvActionButton(onClick = { showFallbackUrlDialog = false }) { Text(uiText("Отмена")) }
            },
        )
    }
    if (showPinChange) ParentalPinSettingsDialog(parental, onClose = { showPinChange = false },
        onChanged = { parentalRevision++ })
    if (showPinDisable) ParentalPinEntryDialog(
        title = uiText("Отключить родительский контроль"),
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
            confirmButton = { TvActionButton(onClick = { choice = null }) { Text(uiText("Закрыть")) } },
        )
    }
    if (showHomeBlocks) {
        AlertDialog(
            onDismissRequest = { showHomeBlocks = false },
            title = { Text(uiText("Блоки главной страницы")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val s = state.settings
                    listOf(
                        Triple(uiText("Ближайшие передачи"), s.showHomeUpcoming, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeUpcomingChanged(x)) }),
                        Triple(uiText("Недавно просмотренные"), s.showHomeRecent, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeRecentChanged(x)) }),
                        Triple(uiText("Избранное"), s.showHomeFavorites, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeFavoritesChanged(x)) }),
                        Triple(uiText("Недавние фильмы"), s.showHomeRecentVod, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeRecentVodChanged(x)) }),
                        Triple(uiText("Избранные фильмы"), s.showHomeFavoriteVod, { x: Boolean -> component.dispatch(SettingsIntent.ShowHomeFavoriteVodChanged(x)) }),
                    ).forEach { (title, checked, cb) -> ReferenceToggle(title, checked, 43.dp, cb) }
                }
            },
            confirmButton = { TvActionButton(onClick = { showHomeBlocks = false }) { Text(uiText("Готово")) } },
        )
    }
    if (showPlaylistDialog) {
        AlertDialog(onDismissRequest = { showPlaylistDialog = false },
            title = { Text(uiText("Мои плейлисты")) }, text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    state.playlists.forEach { playlist ->
                        Text(playlist.name, color = MaterialTheme.colorScheme.onSurface)
                        Row {
                            TvActionButton(onClick = { component.dispatch(SettingsIntent.PlaylistRefresh(playlist.id)) }) { Text(uiText("Обновить")) }
                            TvActionButton(onClick = { component.dispatch(SettingsIntent.PlaylistRemove(playlist.id)) }) { Text(uiText("Удалить")) }
                        }
                    }
                }
            }, confirmButton = { TvActionButton(onClick = { showPlaylistDialog = false }) { Text(uiText("Закрыть")) } })
    }
    if (showPlaylistAdd) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showPlaylistAdd = false },
            title = { Text(uiText("Добавить источник M3U")) }, text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(uiText("Название")) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(uiText("URL источника")) }, singleLine = true)
                }
            }, confirmButton = {
                TvActionButton(enabled = url.isNotBlank(), onClick = {
                    component.dispatch(SettingsIntent.PlaylistNameChanged(name))
                    component.dispatch(SettingsIntent.PlaylistUrlChanged(url))
                    component.dispatch(SettingsIntent.PlaylistAdd)
                    showPlaylistAdd = false
                }) { Text(uiText("Добавить")) }
            }, dismissButton = { TvActionButton(onClick = { showPlaylistAdd = false }) { Text(uiText("Отмена")) } })
    }
    if (showTmdbKey) {
        var key by remember { mutableStateOf(state.tmdbApiKey) }
        AlertDialog(onDismissRequest = { showTmdbKey = false },
            title = { Text(uiText("Собственный ключ TMDB")) }, text = {
                OutlinedTextField(key, { key = it }, label = { Text(uiText("API-ключ")) }, singleLine = true)
            }, confirmButton = { TvActionButton(onClick = {
                component.dispatch(SettingsIntent.TmdbApiKeyChanged(key))
                showTmdbKey = false
            }) { Text(uiText("Сохранить")) } }, dismissButton = {
                TvActionButton(onClick = { showTmdbKey = false }) { Text(uiText("Отмена")) }
            })
    }
    if (showKinopoiskKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showKinopoiskKey = false },
            title = { Text(uiText("Кинопоиск · Unofficial — резервный API-ключ")) },
            text = {
                Column {
                    Text(uiText("Можно задать другой API-ключ. Пустое поле использует ключ по умолчанию."),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text(uiText("Полный API-ключ")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            }, confirmButton = { TvActionButton(onClick = {
                scope.launch {
                    kinopoiskStore.setKey(key)
                    kinopoiskLookup.resetAfterKeyChange()
                    GlobalContext.get().get<ProgrammeMetadataRepository>().clearCache()
                    kinopoiskConfigured = kinopoiskStore.getKey().isNotBlank()
                    kinopoiskMessage = ""
                }
                showKinopoiskKey = false
            }) { Text(uiText("Сохранить")) } }, dismissButton = {
                TvActionButton(onClick = { showKinopoiskKey = false }) { Text(uiText("Отмена")) }
            })
    }
    if (showDiagnosticsDialog) {
        val player = remember { GlobalContext.get().get<PlaybackController>() }
        val playback by player.state.collectAsState()
        val diagnostics = playback.diagnostics
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text(uiText("Диагностика")) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(uiText("Плеер: {0} · {1}", playback.state, playback.item?.title ?: uiText("нет воспроизведения")))
                    Text("EPG: " + if (epgLive.isLoading) uiText("обновляется") else uiText("ожидание"))
                    Text(uiText("Каналов с EPG: {0} · передач: {1}", epgLive.channelCount, epgLive.programmeCount))
                    epgLive.lastError?.let { Text(uiText("Ошибка EPG: {0}", it), color = MaterialTheme.colorScheme.error) }
                    epgLive.notice?.let { Text(it) }
                    if (diagnostics != null) {
                        Text(uiText("Декодирование: {0} · {1}", diagnostics.actualMode, diagnostics.decoder))
                        Text(uiText("Видеовыход: {0}", diagnostics.videoOutput))
                        Text(uiText("Разрешение: {0}", diagnostics.resolution))
                        Text(uiText("Кодек: {0}, FPS: {1}", diagnostics.codec ?: "—", diagnostics.fps ?: "—"))
                        Text(uiText("Битрейт: {0}", diagnostics.bitrate ?: "—"))
                        Text(uiText("Состояние потока: {0}", diagnostics.transportState))
                    } else Text(uiText("Параметры видеопотока появятся после запуска канала"))
                }
            },
            confirmButton = { TvActionButton(onClick = { showDiagnosticsDialog = false }) { Text(uiText("Закрыть")) } },
        )
    }
    if (showReset) {
        AlertDialog(onDismissRequest = { showReset = false },
            title = { Text(uiText("Сбросить данные?")) }, text = {
                Text(uiText("Все плейлисты, история и настройки будут удалены. Отменить это нельзя."))
            }, confirmButton = { TvActionButton(onClick = {
                component.dispatch(SettingsIntent.DataResetStart)
                component.dispatch(SettingsIntent.DataResetConfirm)
                showReset = false
            }) { Text(uiText("Сбросить")) } }, dismissButton = {
                TvActionButton(onClick = { showReset = false }) { Text(uiText("Отмена")) }
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
