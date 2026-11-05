package com.tvnext.app.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.VideoDecodeMode
import com.tvnext.app.core.player.displayName
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvRadioIndicator
import com.tvnext.app.core.ui.component.TvSearchField
import com.tvnext.app.core.ui.component.TvSwitchIndicator
import com.tvnext.app.core.tmdb.TmdbConnectionResult
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.core.ui.theme.TVNextDimens
import com.tvnext.app.feature.settings.domain.ThemePreference
import com.tvnext.app.feature.settings.domain.WindowResolution

@Composable
fun SettingsScreen(
    component: SettingsComponent,
    modifier: Modifier = Modifier,
    onBackHome: (() -> Unit)? = null,
) {
    val state by component.state.collectAsState()

    LaunchedEffect(Unit) {
        component.dispatch(SettingsIntent.Load)
    }

    if (isAndroidPlatform()) {
        AndroidSettingsLayout(state, component, modifier, onBackHome)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TVNextDimens.ScreenPadding),
    ) {
        // Android TV: no screen header — the remote's Back button leaves the
        // settings; the header is kept on desktop only.
        if (!isAndroidPlatform()) {
            ScreenHeader(
                title = "Настройки",
                onBack = onBackHome,
            )
        }

        PlaybackSection(state, component)
        ArchiveSection(state, component)
        InterfaceSection(state, component)
        PlaylistsSection(state, component)
        TmdbSection(state, component)
        EpgSection(state, component)
        DataSection(state, component)
        AboutSection(state)
    }
}

// ------------------------------------------------------------------ Archive --

@Composable
private fun ArchiveSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Архив каналов") {
        Text(
            text = "Максимальная глубина архива",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        ArchiveLimitOption.entries.forEach { option ->
            SettingsSelectRow(
                label = option.label,
                selected = option.days == state.settings.archiveLimitDays,
                onClick = { component.dispatch(SettingsIntent.ArchiveLimitChanged(option.days)) },
            )
        }
        Text(
            text = "Приложение показывает и индексирует только последние N дней. " +
                "Это ограничение TVNext — реальная глубина архива провайдера не меняется. " +
                "Если глубина провайдера неизвестна, она определяется автоматически.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** User-selectable archive depth limits (days); 0 = automatic / provider max. */
private enum class ArchiveLimitOption(val days: Int, val label: String) {
    AUTO(0, "Автоматически (максимум провайдера)"),
    ONE_DAY(1, "1 день"),
    TWO_DAYS(2, "2 дня"),
    THREE_DAYS(3, "3 дня"),
    SEVEN_DAYS(7, "7 дней"),
    FOURTEEN_DAYS(14, "14 дней"),
    THIRTY_DAYS(30, "30 дней"),
}

// ---------------------------------------------------------------- Playback --

@Composable
private fun PlaybackSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Воспроизведение", initiallyExpanded = true) {
        Text(
            text = "Декодирование видео",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        VideoDecodeMode.entries.forEach { mode ->
            SettingsSelectRow(
                label = mode.displayName,
                selected = mode == state.settings.videoDecodeMode,
                onClick = { component.dispatch(SettingsIntent.VideoDecodeModeChanged(mode)) },
            )
        }

        Text(
            text = "Буфер сети",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        NetworkCachingOption.entries.forEach { option ->
            SettingsSelectRow(
                label = option.label,
                selected = option.ms == state.settings.networkCachingMs,
                onClick = { component.dispatch(SettingsIntent.NetworkCachingChanged(option.ms)) },
            )
        }
        Text(
            text = "Меньшее значение снижает задержку live и ускоряет старт, " +
                "но может вызывать прерывания на слабых HLS-источниках.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Шаг перемотки",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        ArchiveSeekStepOption.entries.forEach { option ->
            SettingsSelectRow(
                label = option.label,
                selected = option.ms == state.settings.archiveSeekStepMs,
                onClick = { component.dispatch(SettingsIntent.ArchiveSeekStepChanged(option.ms)) },
            )
        }

    }
}

/** Selectable network-buffer values (ms). */
private enum class NetworkCachingOption(val ms: Int, val label: String) {
    ONE_SECOND(1000, "1 секунда"),
    THREE_SECONDS(3000, "3 секунды"),
    FIVE_SECONDS(5000, "5 секунд"),
    EIGHT_SECONDS(8000, "8 секунд"),
}

// --------------------------------------------------------------- Interface --

@Composable
private fun InterfaceSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Интерфейс") {
        Text(
            text = "Тема",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        ThemePreference.entries.forEach { preference ->
            SettingsSelectRow(
                label = preference.displayName,
                selected = preference == state.settings.theme,
                onClick = { component.dispatch(SettingsIntent.ThemeChanged(preference)) },
            )
        }

        Text(
            text = "Акцентный цвет",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        AccentColor.entries.forEach { accent ->
            SettingsSelectRow(
                label = accent.displayName,
                selected = accent == state.settings.accentColor,
                onClick = { component.dispatch(SettingsIntent.AccentColorChanged(accent)) },
            )
        }

        Text(
            text = "Часы на экране",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        ClockPosition.entries.forEach { position ->
            SettingsSelectRow(
                label = position.displayName,
                selected = position == state.settings.clockPosition,
                onClick = { component.dispatch(SettingsIntent.ClockPositionChanged(position)) },
            )
        }

        if (isAndroidPlatform()) {
            SettingsSelectRow(
                label = "Часы без фона",
                selected = state.settings.clockWithoutBackground,
                onClick = { component.dispatch(SettingsIntent.ClockWithoutBackgroundChanged(!state.settings.clockWithoutBackground)) },
            )
        }
        if (!isAndroidPlatform()) {
        Text(
            text = "Разрешение окна (Windows)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        WindowResolution.entries.forEach { resolution ->
            SettingsSelectRow(
                label = resolution.displayName,
                selected = resolution == state.settings.windowResolution,
                onClick = { component.dispatch(SettingsIntent.WindowResolutionChanged(resolution)) },
            )
        }

        }

        Text(
            text = "Блок «Телепрограмма» на полном экране",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        EpgInfoDurationOption.entries.forEach { option ->
            SettingsSelectRow(
                label = option.label,
                selected = option.seconds == state.settings.epgInfoDurationSeconds,
                onClick = { component.dispatch(SettingsIntent.EpgInfoDurationChanged(option.seconds)) },
            )
        }

        SettingsToggleRow(
            label = "Запускать приложение на весь экран",
            checked = state.settings.startFullscreen,
            onToggle = { component.dispatch(SettingsIntent.StartFullscreenChanged(it)) },
        )
        SettingsToggleRow(
            label = "Показывать номера каналов",
            checked = state.settings.showChannelNumbers,
            onToggle = { component.dispatch(SettingsIntent.ShowChannelNumbersChanged(it)) },
        )
        SettingsToggleRow(
            label = "Автоматически открывать последний канал",
            checked = state.settings.openLastChannel,
            onToggle = { component.dispatch(SettingsIntent.OpenLastChannelChanged(it)) },
        )
        if (!isAndroidPlatform()) {
            SettingsToggleRow(
                label = "Запускать при загрузке Windows",
                checked = state.settings.launchOnWindowsStartup,
                onToggle = { component.dispatch(SettingsIntent.LaunchOnWindowsStartupChanged(it)) },
            )
        }

        Text(
            text = "Главный экран",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacing, bottom = TVNextDimens.ItemSpacingSmall),
        )
        Text(
            text = "Карточек в секции",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeCardLimitValues.forEach { limit ->
                SettingsSelectRowInline(
                    label = limit.toString(),
                    selected = limit == state.settings.homeCardLimit,
                    onClick = { component.dispatch(SettingsIntent.HomeCardLimitChanged(limit)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SettingsToggleRow(
            label = "Сейчас идёт",
            checked = state.settings.showHomeNowPlaying,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeNowPlayingChanged(it)) },
        )
        SettingsToggleRow(
            label = "Ближайшие передачи",
            checked = state.settings.showHomeUpcoming,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeUpcomingChanged(it)) },
        )
        SettingsToggleRow(
            label = "Недавно просмотренные",
            checked = state.settings.showHomeRecent,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeRecentChanged(it)) },
        )
        SettingsToggleRow(
            label = "Избранное",
            checked = state.settings.showHomeFavorites,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeFavoritesChanged(it)) },
        )
        SettingsToggleRow(
            label = "Недавно просмотренные фильмы",
            checked = state.settings.showHomeRecentVod,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeRecentVodChanged(it)) },
        )
        SettingsToggleRow(
            label = "Избранные фильмы и сериалы",
            checked = state.settings.showHomeFavoriteVod,
            onToggle = { component.dispatch(SettingsIntent.ShowHomeFavoriteVodChanged(it)) },
        )
    }
}

/** Remote seek step used while watching archived programmes. */
private enum class ArchiveSeekStepOption(val ms: Long, val label: String) {
    ONE_MINUTE(60_000L, "1 минута"),
    FIVE_MINUTES(5 * 60_000L, "5 минут"),
    TEN_MINUTES(10 * 60_000L, "10 минут"),
    THIRTY_MINUTES(30 * 60_000L, "30 минут"),
}

/** Selectable Home section card limits. */
private val HomeCardLimitValues = listOf(4, 5, 6, 7, 8, 10, 12)

/**
 * Full-screen «Телепрограмма» block visibility: disabled (`null`), always
 * visible (`0`) or a 1–7 second auto-hide timeout (default 3 s).
 */
private enum class EpgInfoDurationOption(val seconds: Int?, val label: String) {
    DISABLED(null, "Выключено"),
    ALWAYS(0, "Всегда показывать"),
    ONE_SECOND(1, "1 секунда"),
    TWO_SECONDS(2, "2 секунды"),
    THREE_SECONDS(3, "3 секунды"),
    FOUR_SECONDS(4, "4 секунды"),
    FIVE_SECONDS(5, "5 секунд"),
    SIX_SECONDS(6, "6 секунд"),
    SEVEN_SECONDS(7, "7 секунд"),
}

// --------------------------------------------------------------- Playlists --

@Composable
private fun PlaylistsSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Плейлисты") {
        Text(
            text = "Добавить M3U/M3U8 источник",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        TvSearchField(
            value = state.playlistUrl,
            onValueChange = { component.dispatch(SettingsIntent.PlaylistUrlChanged(it)) },
            onClear = { component.dispatch(SettingsIntent.PlaylistUrlChanged("")) },
            placeholder = "https://example.com/playlist.m3u",
        )
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        TvSearchField(
            value = state.playlistName,
            onValueChange = { component.dispatch(SettingsIntent.PlaylistNameChanged(it)) },
            onClear = { component.dispatch(SettingsIntent.PlaylistNameChanged("")) },
            placeholder = "Название (необязательно)",
        )
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistType.entries.forEach { type ->
                SettingsSelectRowInline(
                    label = if (type == PlaylistType.LIVE) "Телеканалы" else "Медиатека / VOD",
                    selected = type == state.playlistType,
                    onClick = { component.dispatch(SettingsIntent.PlaylistTypeChanged(type)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvCardButton(
                onClick = { component.dispatch(SettingsIntent.PlaylistAdd) },
                enabled = !state.playlistLoading,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(
                    horizontal = TVNextDimens.ItemSpacing,
                    vertical = TVNextDimens.ItemSpacingSmall,
                ),
            ) {
                Text(
                    text = "Добавить",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.playlistLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (state.playlistError != null) {
            Text(
                text = state.playlistError?.toUserMessage() ?: "Ошибка",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }

        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacing))

        if (state.playlists.isEmpty()) {
            Text(
                text = "Плейлисты не добавлены.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.playlists.forEach { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    renaming = state.renamingPlaylistId == playlist.id,
                    onRefresh = { component.dispatch(SettingsIntent.PlaylistRefresh(playlist.id)) },
                    onRemove = { component.dispatch(SettingsIntent.PlaylistRemove(playlist.id)) },
                    onSetDefault = {
                        component.dispatch(SettingsIntent.PlaylistSetDefault(if (playlist.isDefault) null else playlist.id))
                    },
                    onRenameStart = { component.dispatch(SettingsIntent.PlaylistRenameStart(playlist.id)) },
                    onRenameApply = { name ->
                        component.dispatch(SettingsIntent.PlaylistRenameApply(playlist.id, name))
                    },
                    onRenameCancel = { component.dispatch(SettingsIntent.PlaylistRenameCancel) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    renaming: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onSetDefault: () -> Unit,
    onRenameStart: () -> Unit,
    onRenameApply: (String) -> Unit,
    onRenameCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = TVNextDimens.ItemSpacing),
    ) {
        if (renaming) {
            RenamePlaylistField(
                initialName = playlist.name,
                onApply = onRenameApply,
                onCancel = onRenameCancel,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (playlist.isDefault) {
                    Text(
                        text = "по умолчанию",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = if (playlist.type == PlaylistType.LIVE) " · каналы" else " · медиатека",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "${playlist.channelCount} записей · обновлён: " +
                (playlist.lastUpdated?.let { formatDateTime(it) } ?: "никогда"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        playlist.epgUrl?.let { epg ->
            Text(
                text = "EPG: $epg",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
        ) {
            SmallActionButton(label = "Обновить", onClick = onRefresh)
            SmallActionButton(label = "Переименовать", onClick = onRenameStart)
            SmallActionButton(
                label = if (playlist.isDefault) "Снять default" else "По умолчанию",
                onClick = onSetDefault,
            )
            SmallActionButton(label = "Удалить", onClick = onRemove)
        }
    }
}

@Composable
private fun RenamePlaylistField(
    initialName: String,
    onApply: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    Column {
        TvSearchField(
            value = name,
            onValueChange = { name = it },
            onClear = { name = "" },
            placeholder = "Новое название",
        )
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Row(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall)) {
            SmallActionButton(label = "Применить", onClick = { onApply(name) })
            SmallActionButton(label = "Отмена", onClick = onCancel)
        }
    }
}

@Composable
private fun SmallActionButton(
    label: String,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = TVNextDimens.ItemSpacing, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// --------------------------------------------------------------------- TMDB --

@Composable
private fun TmdbSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Медиатека · TMDB") {
        SettingsToggleRow(
            label = "Использовать TMDB",
            checked = state.tmdbEnabled,
            onToggle = { component.dispatch(SettingsIntent.TmdbEnabledChanged(it)) },
        )
        Text(
            text = "API-ключ The Movie Database",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        var showKey by remember { mutableStateOf(false) }
        TextField(
            value = state.tmdbApiKey,
            onValueChange = { component.dispatch(SettingsIntent.TmdbApiKeyChanged(it)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            placeholder = { Text("Введите ключ TMDB") },
            trailingIcon = {
                TvCardButton(
                    onClick = { showKey = !showKey },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(
                        horizontal = TVNextDimens.ItemSpacingSmall,
                        vertical = 2.dp,
                    ),
                ) {
                    Text(
                        text = if (showKey) "Скрыть" else "Показать",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvCardButton(
                onClick = { component.dispatch(SettingsIntent.TmdbTestConnection) },
                enabled = !state.tmdbTesting && state.tmdbApiKey.isNotBlank(),
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(
                    horizontal = TVNextDimens.ItemSpacing,
                    vertical = TVNextDimens.ItemSpacingSmall,
                ),
            ) {
                Text(
                    text = "Проверить соединение",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TvCardButton(
                onClick = { component.dispatch(SettingsIntent.TmdbClearCache) },
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(
                    horizontal = TVNextDimens.ItemSpacing,
                    vertical = TVNextDimens.ItemSpacingSmall,
                ),
            ) {
                Text(
                    text = "Очистить кэш",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.tmdbTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        state.tmdbConnectionResult?.let { result ->
            Text(
                text = result.toUserMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }
        Text(
            text = "Постеры, рейтинг и описание берутся из TMDB, когда ключ настроен; " +
                "данные IPTV-провайдера остаются основными.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun TmdbConnectionResult.toUserMessage(): String = when (kind) {
    TmdbConnectionResult.Kind.SUCCESS -> "Соединение успешно"
    TmdbConnectionResult.Kind.INVALID_KEY -> "Неверный API-ключ"
    TmdbConnectionResult.Kind.NO_CONNECTION -> "Нет соединения с TMDB"
    TmdbConnectionResult.Kind.ERROR -> "Ошибка соединения"
}

// --------------------------------------------------------------------- EPG --

@Composable
private fun EpgSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Телепрограмма (EPG)") {
        SettingsToggleRow(
            label = "Включить EPG",
            checked = state.epgState.enabled,
            onToggle = { component.dispatch(SettingsIntent.EpgEnabledChanged(it)) },
        )
        Text(
            text = "XMLTV URL (глобальный, переопределяет url-tvg каждого плейлиста)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
        TvSearchField(
            value = state.epgUrlField,
            onValueChange = { component.dispatch(SettingsIntent.EpgUrlChanged(it)) },
            onClear = { component.dispatch(SettingsIntent.EpgUrlChanged("")) },
            placeholder = "https://example.com/epg.xml.gz",
        )
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvCardButton(
                onClick = { component.dispatch(SettingsIntent.EpgRefresh) },
                enabled = !state.epgRefreshing && state.epgState.enabled,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(
                    horizontal = TVNextDimens.ItemSpacing,
                    vertical = TVNextDimens.ItemSpacingSmall,
                ),
            ) {
                Text(
                    text = "Обновить EPG",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.epgRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        val epg = state.epgState
        SettingsInfoRow(
            label = "Плейлист",
            value = epg.playlistName ?: "—",
        )
        SettingsInfoRow(
            label = "Из плейлиста",
            value = epg.detectedUrl ?: "—",
        )
        SettingsInfoRow(
            label = "Активный источник",
            value = epg.effectiveUrl ?: "—",
        )
        SettingsInfoRow(
            label = "Обновлено",
            value = epg.lastUpdated?.let { formatDateTime(it) } ?: "никогда",
        )
        SettingsInfoRow(
            label = "Кэш",
            value = "${epg.channelCount} каналов · ${epg.programmeCount} передач",
        )
        epg.diagnostics?.let { diagnostics ->
            SettingsInfoRow(
                label = "Сопоставление",
                value = "M3U: ${diagnostics.m3uChannelCount} · XMLTV: ${diagnostics.epgChannelCount} · " +
                    "по tvg-id: ${diagnostics.matchedByTvgId} · по имени: ${diagnostics.matchedByName} · " +
                    "не сопоставлено: ${diagnostics.unmatchedM3u}",
            )
        }
        val lastEpgError = epg.lastError ?: state.epgStateError
        if (lastEpgError != null) {
            Text(
                text = lastEpgError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }
        val epgNotice = epg.notice
        if (epgNotice != null && lastEpgError == null) {
            Text(
                text = epgNotice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }
    }
}

// ------------------------------------------------------------------- Data --

@Composable
private fun DataSection(
    state: SettingsState,
    component: SettingsComponent,
) {
    SettingsSection(title = "Данные") {
        if (isAndroidPlatform()) {
            var report by remember { mutableStateOf("") }
            TvCardButton(onClick = {
                report = com.tvnext.app.core.common.log.AndroidPerformanceCounters.recentReport()
                    .ifBlank { "Показатели появятся через 30 секунд работы приложения." }
            }) { Text("Диагностика производительности", color = MaterialTheme.colorScheme.onSurface) }
            if (report.isNotBlank()) {
                Text("Последние замеры: память, кадры, кеш логотипов и EPG. Повторное нажатие обновит данные.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.tvnext.app.core.ui.component.AutoScrollText(report, MaterialTheme.typography.bodySmall,
                    MaterialTheme.colorScheme.onSurface, 8)
            }
        }

        if (state.confirmDataReset) {
            Text(
                text = "Будут удалены все плейлисты, история просмотров, прогресс " +
                    "фильмов, настройки и кэш. Действие необратимо. Продолжить?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
            Row(
                horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvCardButton(
                    onClick = { component.dispatch(SettingsIntent.DataResetConfirm) },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(
                        horizontal = TVNextDimens.ItemSpacing,
                        vertical = TVNextDimens.ItemSpacingSmall,
                    ),
                ) {
                    Text(
                        text = "Да, сбросить",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TvCardButton(
                    onClick = { component.dispatch(SettingsIntent.DataResetCancel) },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(
                        horizontal = TVNextDimens.ItemSpacing,
                        vertical = TVNextDimens.ItemSpacingSmall,
                    ),
                ) {
                    Text(
                        text = "Отмена",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            TvCardButton(
                onClick = { component.dispatch(SettingsIntent.DataResetStart) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentPadding = PaddingValues(
                    horizontal = TVNextDimens.ItemSpacing,
                    vertical = TVNextDimens.ItemSpacingSmall,
                ),
            ) {
                Text(
                    text = "Сбросить все данные",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "Удаляет плейлисты, историю просмотров, прогресс фильмов, " +
                    "настройки и кэш логотипов, после чего приложение перезапустится.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------------------- About --

@Composable
private fun AboutSection(state: SettingsState) {
    SettingsSection(title = "О приложении") {
        SettingsInfoRow(label = "Название", value = state.appInfo.name)
        SettingsInfoRow(label = "Версия", value = state.appInfo.version)
        SettingsInfoRow(label = "Лицензия", value = "TVNext Free")
        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
        Text(
            text = "TMDB\nThis product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------- Components --

@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (isAndroidPlatform()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = if (isAndroidPlatform()) MaterialTheme.colorScheme.onSurface else Color.White)
            content()
        }
        return
    }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier.padding(bottom = TVNextDimens.SectionSpacing),
    ) {
        // Section header doubles as the collapse/expand toggle, so the
        // settings screen is not one long scrollable sheet.
        TvCardButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .height(TVNextDimens.NavItemHeight),
            shape = RoundedCornerShape(10.dp),
            contentPadding = TvRowPadding,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSelectRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .height(TVNextDimens.ListItemHeight),
        contentPadding = TvRowPadding,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            TvRadioIndicator(selected = selected)
        }
    }
}

@Composable
private fun SettingsSelectRowInline(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = modifier.height(TVNextDimens.ListItemHeight),
        contentPadding = TvRowPadding,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            TvRadioIndicator(selected = selected)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    TvCardButton(
        onClick = { onToggle(!checked) },
        selected = checked,
        modifier = Modifier
            .fillMaxWidth()
            .height(TVNextDimens.ListItemHeight),
        contentPadding = TvRowPadding,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TvSwitchIndicator(checked = checked)
        }
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TVNextDimens.ListItemHeight)
            .padding(TvRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val ThemePreference.displayName: String
    get() = when (this) {
        ThemePreference.DARK -> "Тёмная тема"
        ThemePreference.LIGHT -> "Светлая тема"
        ThemePreference.SYSTEM -> "Следить за системой"
    }

private val AccentColor.displayName: String
    get() = when (this) {
        AccentColor.GREEN -> "Зелёный"
        AccentColor.BLUE -> "Синий"
        AccentColor.ORANGE -> "Оранжевый"
        AccentColor.PURPLE -> "Фиолетовый"
    }

private val ClockPosition.displayName: String    get() = when (this) {
        ClockPosition.OFF -> "Выключены"
        ClockPosition.TOP_LEFT -> "Верхний левый угол"
        ClockPosition.TOP_RIGHT -> "Верхний правый угол"
    }

private val WindowResolution.displayName: String    get() = when (this) {
        WindowResolution.AUTO -> "Авто (по разрешению монитора)"
        WindowResolution.HD -> "HD (1280×720)"
        WindowResolution.FULL_HD -> "FullHD (1920×1080)"
        WindowResolution.UHD_4K -> "4K UHD (3840×2160)"
    }

private val TvRowPadding = PaddingValues(
    horizontal = TVNextDimens.ScreenPaddingCompact,
    vertical = TVNextDimens.ItemSpacingSmall,
)

/** TV settings: stable section rail and one independently scrollable settings pane. */
@Composable
private fun AndroidSettingsLayout(state: SettingsState, component: SettingsComponent, modifier: Modifier, onBack: (() -> Unit)?) {
    val titles = listOf("Воспроизведение", "Архив", "Интерфейс", "Плейлисты", "Фильмы / TMDB", "Телепрограмма", "Данные", "О приложении")
    var selected by remember { mutableStateOf(0) }
    var inDetails by remember { mutableStateOf(false) }
    val railRequests = remember { List(titles.size) { FocusRequester() } }
    val detailRequest = remember { FocusRequester() }
    val railState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withFrameNanos { }; railRequests[selected].requestFocus() }
    run {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) {
                            if (inDetails) railRequests[selected].requestFocus() else onBack?.invoke()
                        }; true
                    } else false
                }) {
                Text("Настройки", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("↑↓ разделы   OK / → открыть   Назад к разделам", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyColumn(state = railState, modifier = Modifier.width(170.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(titles) { index, title ->
                            TvCardButton(onClick = { selected = index; scope.launch { withFrameNanos { }; detailRequest.requestFocus() } }, selected = selected == index,
                                modifier = Modifier.fillMaxWidth().height(40.dp).focusRequester(railRequests[index])
                                    .onFocusChanged { if (it.isFocused) { selected = index; inDetails = false } }
                                    .onPreviewKeyEvent { event ->
                                        if (event.key == Key.DirectionRight) {
                                            if (event.type == KeyEventType.KeyDown) detailRequest.requestFocus()
                                            true
                                        } else false
                                    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    key(selected) {
                        val scroll = rememberScrollState()
                        Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                            .focusRequester(detailRequest).onFocusChanged { inDetails = it.hasFocus }.focusGroup()
                            .onKeyEvent { event ->
                                if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
                                    railRequests[selected].requestFocus(); true
                                } else false
                            }.verticalScroll(scroll).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // A real focusable entry also serves sections containing only informational text.
                            TvCardButton(onClick = { railRequests[selected].requestFocus() }, modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("← Разделы", color = MaterialTheme.colorScheme.onSurface) }
                            when (selected) {
                                0 -> PlaybackSection(state, component)
                                1 -> ArchiveSection(state, component)
                                2 -> InterfaceSection(state, component)
                                3 -> PlaylistsSection(state, component)
                                4 -> TmdbSection(state, component)
                                5 -> EpgSection(state, component)
                                6 -> DataSection(state, component)
                                else -> AboutSection(state)
                            }
                        }
                    }
                }
            }
        }
    }
}
