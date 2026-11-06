package com.tvnext.app.feature.tvg.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatDate
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.archive.ArchiveStatus
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.player.PlayerSurface
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.theme.TVNextDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce

private const val HOUR_MS = 3_600_000L
private val HourWidth = 170.dp
private val ChannelColumnWidth = 220.dp
private val RowHeight = 64.dp

/** «28 августа, 16:34» — текущие дата и время для шапки ТВ-гида. */
private fun currentDateTimeText(): String {
    val now = currentTimeMillis()
    return "${formatDate(now)}, ${formatTimeOfDay(now)}"
}

@Composable
private fun Modifier.dragDayHorizontal(
    scrollState: ScrollState,
): Modifier {
    val scope = rememberCoroutineScope()
    return this.draggable(
        state = rememberDraggableState { delta ->
            scope.launch { scrollState.scrollBy(-delta) }
        },
        orientation = Orientation.Horizontal,
    )
}

@Composable
private fun Modifier.mouseWheelHorizontal(
    scrollState: ScrollState,
): Modifier {
    val scope = rememberCoroutineScope()
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                val delta = change.scrollDelta.y + change.scrollDelta.x
                if (delta != 0f) {
                    change.consume()
                    scope.launch { scrollState.scrollBy(delta * 40f) }
                }
            }
        }
    }
}

@Composable
fun TvgScreen(
    component: TvgComponent,
    modifier: Modifier = Modifier,
    onBackHome: (() -> Unit)? = null,
) {
    if (isAndroidPlatform()) {
        LaunchedEffect(component) { component.dispatch(TvgIntent.Activated) }
        AndroidTvgScreen(component, modifier, onBackHome)
        return
    }
    val state by component.state.collectAsState()
    val scrollState = rememberScrollState()
    val channelListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val channelFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var focusedChannelIndex by remember { mutableIntStateOf(0) }
    var focusedChannelId by remember { mutableStateOf<String?>(null) }
    var focusedProgrammeStart by remember { mutableStateOf<Long?>(null) }
    var focusedInfo by remember { mutableStateOf<ProgrammeInfo?>(null) }
    var initialProgrammeFocusDone by remember { mutableStateOf(false) }
    var channelScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmeScrollJob by remember { mutableStateOf<Job?>(null) }

    // Категории, вычисленные из всех каналов.
    val categories = remember(state.channels) {
        state.channels.map { it.category }.distinct().sorted()
    }

    // Двухпанельный режим: true = список категорий + список каналов (программы
    // и описание скрыты), false = сетка программ + описание. Открывается на
    // категориях; Right переводит к программам, Left — обратно к категориям.
    var showCategories by remember { mutableStateOf(true) }
    var categoryListFocused by remember { mutableStateOf(true) }
    val categoryListState = rememberLazyListState()
    val categoryRows = remember(categories) {
        listOf(null) + categories
    }
    var focusedCategoryIndex by remember { mutableIntStateOf(0) }
    // Выравниваем индекс на текущую выбранную категорию при изменении выбора.
    LaunchedEffect(state.selectedCategory) {
        val index = categoryRows.indexOf(state.selectedCategory)
        if (index >= 0) focusedCategoryIndex = index
    }

    LaunchedEffect(channelListState, state.from, state.to, state.filteredChannels.size) {
        // Report only the viewport, not the entire channel list. The component
        // uses this to fetch EPG rows lazily for the channels actually on screen.
        snapshotFlow {
            channelListState.layoutInfo.visibleItemsInfo
                .mapNotNull { it.key as? String }
                .toSet()
        }
            .distinctUntilChanged()
            .debounce(90)
            .collect { ids ->
                if (ids.isNotEmpty()) {
                    component.dispatch(TvgIntent.VisibleChannelsChanged(ids))
                }
            }
    }

    LaunchedEffect(state.from, state.to, state.selectedCategory, state.selectedDayOffset) {
        initialProgrammeFocusDone = false
        focusedChannelId = null
        focusedProgrammeStart = null
        focusedInfo = null
    }

    // Initial focus: open on the CURRENTLY WATCHED channel, centred vertically,
    // with its current (or next) programme selected.
    LaunchedEffect(state.filteredChannels, state.currentProgrammes, state.selectedDayOffset) {
        if (initialProgrammeFocusDone || state.filteredChannels.isEmpty()) return@LaunchedEffect
        val playingId = component.playbackController.state.value.item?.id
        val index = state.filteredChannels.indexOfFirst { it.id == playingId }
            .takeIf { it >= 0 } ?: 0
        focusedChannelIndex = index
        val channel = state.filteredChannels[index]
        val programme = if (state.selectedDayOffset == 0) state.currentProgrammes[channel.id] else null
        focusedChannelId = channel.id
        focusedProgrammeStart = programme?.start
        focusedInfo = programme?.let { ProgrammeInfo(channel, it) }
        // Make the target row visible, CENTRED vertically in the viewport.
        if (!channelListState.layoutInfo.visibleItemsInfo.any { it.index == index }) {
            val rowsInViewport = with(density) {
                (channelListState.layoutInfo.viewportSize.height / RowHeight.toPx()).toInt()
            }.coerceAtLeast(1)
            val targetScroll = (index - rowsInViewport / 2).coerceAtLeast(0)
            runCatching { channelListState.scrollToItem(targetScroll) }
        }
        withFrameNanos { }
        channelFocusRequesters[channel.id]?.let { runCatching { it.requestFocus() } }
        initialProgrammeFocusDone = true
    }

    fun moveChannel(delta: Int) {
        val channels = state.filteredChannels
        if (channels.isEmpty()) return
        // Навигация по каналам с остановкой на границах (без wrap-around)
        val target = (focusedChannelIndex + delta).coerceIn(0, channels.lastIndex)
        if (target == focusedChannelIndex) return
        focusedChannelIndex = target
        val channel = channels[target]
        focusedChannelId = channel.id
        val programme = if (state.selectedDayOffset == 0) state.currentProgrammes[channel.id]
            ?: state.programmes[channel.id].orEmpty().firstOrNull {
                val now = currentTimeMillis()
                it.start <= now && now < it.stop
            }
        else null
        focusedProgrammeStart = programme?.start
        focusedInfo = programme?.let { ProgrammeInfo(channel, it) }
        channelScrollJob?.cancel()
        channelScrollJob = scope.launch {
            val visible = channelListState.layoutInfo.visibleItemsInfo.any { it.index == target }
            if (!visible) {
                runCatching { channelListState.animateScrollToItem(target) }
            }
            withFrameNanos { }
            channelFocusRequesters[channel.id]?.let { runCatching { it.requestFocus() } }
        }
    }

    fun moveProgramme(delta: Int) {
        val channel = state.filteredChannels.getOrNull(focusedChannelIndex) ?: return
        val programmes = state.programmes[channel.id].orEmpty()
        if (programmes.isEmpty()) return
        val currentIndex = focusedProgrammeStart?.let { start -> programmes.indexOfFirst { it.start == start } }
            ?.takeIf { it >= 0 }
            ?: programmes.indexOfFirst { it.start <= currentTimeMillis() && currentTimeMillis() < it.stop }.takeIf { it >= 0 }
            ?: 0
        val target = (currentIndex + delta).coerceIn(0, programmes.lastIndex)
        val programme = programmes[target]
        focusedProgrammeStart = programme.start
        focusedInfo = ProgrammeInfo(channel, programme)
        val targetPx = (((programme.start - state.from).toFloat() / HOUR_MS) * HourWidth.value).toInt()
        val desired = (targetPx - 90).coerceIn(0, scrollState.maxValue)
        if (kotlin.math.abs(scrollState.value - desired) > 12) {
            programmeScrollJob?.cancel()
            programmeScrollJob = scope.launch { runCatching { scrollState.animateScrollTo(desired) } }
        }
    }

    fun selectCategory(index: Int) {
        val category = categoryRows.getOrNull(index)
        focusedCategoryIndex = index
        component.dispatch(TvgIntent.CategorySelected(category))
    }

    var clockText by remember { mutableStateOf(currentDateTimeText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = currentDateTimeText()
            delay(30_000)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Фон — ЖИВОЕ видео текущего канала, полупрозрачная сетка поверх.
        PlayerSurface(
            controller = component.playbackController,
            modifier = Modifier.fillMaxSize(),
        )
        // Затемняющий оверлей для читаемости.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TVNextDimens.ScreenPadding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (showCategories) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (categoryListFocused) {
                                    // Wrap-around навигация для категорий
                                    focusedCategoryIndex = if (focusedCategoryIndex > 0) {
                                        focusedCategoryIndex - 1
                                    } else {
                                        categoryRows.lastIndex  // Вернуться в конец списка
                                    }
                                    scope.launch { runCatching { categoryListState.animateScrollToItem(focusedCategoryIndex) } }
                                } else {
                                    moveChannel(-1)
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                if (categoryListFocused) {
                                    // Wrap-around навигация для категорий
                                    focusedCategoryIndex = if (focusedCategoryIndex < categoryRows.lastIndex) {
                                        focusedCategoryIndex + 1
                                    } else {
                                        0  // Вернуться в начало списка
                                    }
                                    scope.launch { runCatching { categoryListState.animateScrollToItem(focusedCategoryIndex) } }
                                } else {
                                    moveChannel(1)
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                if (!categoryListFocused) {
                                    // Влево из списка каналов = переход в категории
                                    categoryListFocused = true
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (categoryListFocused) {
                                    // Вправо из категорий = выбор и переход в каналы
                                    selectCategory(focusedCategoryIndex)
                                    categoryListFocused = false
                                } else {
                                    // Вправо из каналов = переход к программам (сетка)
                                    showCategories = false
                                }
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                if (categoryListFocused) {
                                    selectCategory(focusedCategoryIndex)
                                    categoryListFocused = false  // Переместить фокус на каналы
                                } else {
                                    state.filteredChannels.getOrNull(focusedChannelIndex)?.let {
                                        component.dispatch(TvgIntent.ChannelClicked(it.id))
                                    }
                                }
                                true
                            }
                            Key.Back -> {
                                onBackHome?.invoke()
                                true
                            }
                            else -> false
                        }
                    } else {
                        // Режим просмотра программ (сетка)
                        when (event.key) {
                            Key.DirectionLeft -> {
                                // Влево: вернуться к категориям и каналам
                                showCategories = true
                                categoryListFocused = false  // Фокус на каналы, а не на категории
                                true
                            }
                            Key.DirectionUp -> { 
                                moveChannel(-1)
                                true 
                            }
                            Key.DirectionDown -> { 
                                moveChannel(1)
                                true 
                            }
                            Key.DirectionRight -> { 
                                moveProgramme(1)
                                true 
                            }
                            Key.Back -> { 
                                onBackHome?.invoke()
                                true 
                            }
                            else -> false
                        }
                    }
                },
        ) {
            if (!isAndroidPlatform()) {
                ScreenHeader(
                    title = "ТВ-гид",
                    onBack = onBackHome,
                    trailing = {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
            }

            // Верхний левый угол: текущие дата и время.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = TVNextDimens.ItemSpacingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = clockText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
                )
            }

            when {
                state.isLoading && state.filteredChannels.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.filteredChannels.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.error?.toUserMessage()
                                ?: "Добавьте M3U-плейлист, чтобы открыть ТВ-гид.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    if (showCategories) {
                        CategoryChannelsPanel(
                            categories = categoryRows,
                            focusedCategoryIndex = focusedCategoryIndex,
                            categoryListFocused = categoryListFocused,
                            categoryListState = categoryListState,
                            channels = state.filteredChannels,
                            focusedChannelIndex = focusedChannelIndex,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val now = remember(state.from, state.to, state.programmes) { currentTimeMillis() }
                        val totalWidth = ChannelColumnWidth + HourWidth * hoursCount(state)
                        val hasAnyProgramme = remember(state.programmes) {
                            state.programmes.values.any { it.isNotEmpty() }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            // Time header: fixed "Канал" label + scrollable hour labels.
                            Row {
                                TimeHeaderCell(
                                    modifier = Modifier.width(ChannelColumnWidth),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(scrollState)
                                        .mouseWheelHorizontal(scrollState),
                                ) {
                                    Row(
                                        modifier = Modifier.width(totalWidth - ChannelColumnWidth),
                                    ) {
                                        hours(state).forEach { hour ->
                                            Text(
                                                text = hour,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .width(HourWidth)
                                                    .padding(vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            if (!hasAnyProgramme) {
                                Text(
                                    text = "Нет данных EPG для этого дня. Обновите EPG в настройках или подождите импорта телепрограммы.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = TVNextDimens.ScreenPaddingCompact,
                                        vertical = TVNextDimens.ItemSpacing,
                                    ),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(scrollState)
                                    .dragDayHorizontal(scrollState)
                                    .mouseWheelHorizontal(scrollState),
                            ) {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                                    state = channelListState,
                                    modifier = Modifier.width(totalWidth),
                                ) {
                                    itemsIndexed(state.filteredChannels, key = { _, it -> "${it.playlistId}|${it.id}" }) { index, channel ->
                                        val isFocusedRow = focusedChannelId == channel.id
                                        ChannelRow(
                                            channel = channel,
                                            programmes = state.programmes[channel.id].orEmpty(),
                                            currentProgramme = state.currentProgrammes[channel.id],
                                            from = state.from,
                                            to = state.to,
                                            now = now,
                                            totalWidth = totalWidth,
                                            scrollState = scrollState,
                                            archiveAvailable = state.archiveStatus[channel.id] == ArchiveStatus.AVAILABLE,
                                            focused = isFocusedRow,
                                            focusProgrammeStart = if (isFocusedRow) focusedProgrammeStart else null,
                                            focusRequester = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() },
                                            info = if (isFocusedRow) focusedInfo else null,
                                            providerText = buildString {
                                                state.playlistNames[channel.playlistId]?.let { append(it) }
                                                channel.category.takeIf { it.isNotBlank() }?.let { category ->
                                                    if (isNotEmpty()) append(" · ")
                                                    append(category)
                                                }
                                            },
                                            onClick = { component.dispatch(TvgIntent.ChannelClicked(channel.id)) },
                                            onProgramme = { programme ->
                                                component.dispatch(
                                                    TvgIntent.ProgrammeClicked(channel.id, programme.start),
                                                )
                                            },
                                            onFocused = {
                                                if (focusedChannelId != channel.id) {
                                                    focusedChannelId = channel.id
                                                    focusedChannelIndex = index
                                                    val p = if (state.selectedDayOffset == 0) state.currentProgrammes[channel.id] else null
                                                    focusedProgrammeStart = p?.start
                                                    focusedInfo = p?.let { ProgrammeInfo(channel, it) }
                                                }
                                            },
                                            onMoveChannel = ::moveChannel,
                                            onMoveProgramme = ::moveProgramme,
                                            onBack = { onBackHome?.invoke() },
                                            onArchive = { programme ->
                                                component.dispatch(
                                                    TvgIntent.ArchiveProgrammeClicked(channel.id, programme.start),
                                                )
                                            },
                                        )
                                    }
                                }

                                // Линия текущего времени — красная вертикальная линия.
                                if (state.from > 0L && state.selectedDayOffset == 0) {
                                    CurrentTimeIndicator(
                                        from = state.from,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Режим категорий ТВ-гида: слева вертикальный список категорий («Все» +
 * категории), справа — список каналов выбранной категории. Список программ
 * и описание скрыты; Right из каналов переводит в сетку программ.
 */
@Composable
private fun CategoryChannelsPanel(
    categories: List<String?>,
    focusedCategoryIndex: Int,
    categoryListFocused: Boolean,
    categoryListState: androidx.compose.foundation.lazy.LazyListState,
    channels: List<Channel>,
    focusedChannelIndex: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val categoryWidth = 320.dp
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacing),
    ) {
        LazyColumn(
            state = categoryListState,
            verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            modifier = Modifier
                .width(categoryWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(vertical = TVNextDimens.ItemSpacingSmall),
        ) {
            itemsIndexed(categories, key = { _, c -> c ?: "__all__" }) { index, category ->
                val selected = index == focusedCategoryIndex
                TvCardButton(
                    onClick = {},
                    selected = selected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .then(
                            if (selected && categoryListFocused) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            } else Modifier,
                        ),
                    contentPadding = PaddingValues(horizontal = TVNextDimens.ItemSpacing),
                ) {
                    Text(
                        text = category ?: "Все",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(vertical = TVNextDimens.ItemSpacingSmall),
        ) {
            itemsIndexed(channels, key = { _, c -> "${c.playlistId}|${c.id}" }) { index, channel ->
                val selected = index == focusedChannelIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else Color.Transparent,
                        )
                        .then(
                            if (selected && !categoryListFocused) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            } else Modifier,
                        )
                        .padding(horizontal = TVNextDimens.ItemSpacingSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(
                        url = channel.logoUrl,
                        placeholderText = channel.name,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Значок архива (часы) — только у каналов, у которых есть
                    // архив (та же логика, что в списке каналов «Каналов»).
                    if (channel.hasCatchupMechanism) {
                        Text(
                            text = "🕓",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = TVNextDimens.ItemSpacingSmall),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeHeaderCell(modifier: Modifier = Modifier) {
    Text(
        text = "Канал",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

private fun hours(state: TvgState): List<String> {
    val result = mutableListOf<String>()
    var hour = state.from
    while (hour < state.to) {
        result.add(formatTimeOfDay(hour))
        hour += HOUR_MS
    }
    return result
}

private fun hoursCount(state: TvgState): Int {
    if (state.to <= state.from) return 6
    return ((state.to - state.from) / HOUR_MS).toInt()
}

@Composable
private fun ChannelRow(
    channel: Channel,
    programmes: List<EpgProgramme>,
    currentProgramme: EpgProgramme?,
    from: Long,
    to: Long,
    now: Long,
    totalWidth: Dp,
    scrollState: ScrollState,
    archiveAvailable: Boolean,
    focused: Boolean,
    focusProgrammeStart: Long?,
    info: ProgrammeInfo?,
    providerText: String,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onMoveChannel: (Int) -> Unit,
    onMoveProgramme: (Int) -> Unit,
    onBack: () -> Unit,
    onClick: () -> Unit,
    onProgramme: (EpgProgramme) -> Unit,
    onArchive: (EpgProgramme) -> Unit,
) {
    val normalizedProgrammes = remember(programmes) { normalizeProgrammeTimeline(programmes) }
    val selectedProgramme = remember(normalizedProgrammes, focusProgrammeStart) {
        focusProgrammeStart?.let { start -> normalizedProgrammes.firstOrNull { it.start == start } }
            ?: currentProgramme
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !isAndroidPlatform()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onMoveChannel(-1); true }
                    Key.DirectionDown -> { onMoveChannel(1); true }
                    Key.DirectionLeft -> { onMoveProgramme(-1); true }
                    Key.DirectionRight -> { onMoveProgramme(1); true }
                    Key.Back -> { onBack(); true }
                    Key.DirectionCenter, Key.Enter -> {
                        val p = selectedProgramme
                        if (p == null) onClick()
                        else {
                            val t = currentTimeMillis()
                            when {
                                p.start <= t && t < p.stop -> onClick()
                                p.stop <= t && archiveAvailable -> onArchive(p)
                                else -> onProgramme(p)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier),
    ) {
        Row(modifier = Modifier.height(RowHeight).fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .width(ChannelColumnWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = TVNextDimens.ItemSpacingSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(url = channel.logoUrl, modifier = Modifier.size(28.dp), placeholderText = channel.name)
                Spacer(Modifier.width(TVNextDimens.ItemSpacingSmall))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    currentProgramme?.let {
                        Text(
                            text = it.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(TVNextDimens.ItemSpacingSmall))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (focused) 0.45f else 0.28f)),
            ) {
                Box(Modifier.width(totalWidth - ChannelColumnWidth - TVNextDimens.ItemSpacingSmall).fillMaxHeight()) {
                    normalizedProgrammes.forEach { programme ->
                        key(programme.start) {
                            ProgrammeCell(
                                programme = programme,
                                from = from,
                                to = to,
                                now = now,
                                archiveAvailable = archiveAvailable,
                                focused = focused && programme.start == focusProgrammeStart,
                                onClick = {
                                    val t = currentTimeMillis()
                                    when {
                                        programme.start <= t && t < programme.stop -> onClick()
                                        programme.stop <= t && archiveAvailable -> onArchive(programme)
                                        else -> onProgramme(programme)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (focused && info != null) {
            // Раскрывающаяся строка с описанием под рядом.
            ExpandedProgrammeInfo(
                info = info,
                providerText = providerText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .offset(y = RowHeight + 2.dp)
                    .zIndex(1f),
            )
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: EpgProgramme,
    from: Long,
    to: Long,
    now: Long,
    archiveAvailable: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val isCurrent = programme.start <= now && now < programme.stop
    val isPast = programme.stop <= now
    val startOffset = ((programme.start - from).toFloat() / HOUR_MS) * HourWidth.value
    val duration = programme.stop - programme.start
    val cellWidthDp = ((duration.toFloat() / HOUR_MS) * HourWidth.value).coerceAtLeast(48f)
    val background = when {
        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .offset(x = startOffset.dp)
            .width(cellWidthDp.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
            .pointerInput(programme.start) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = "${formatTimeOfDay(programme.start)} ${programme.title}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) Text("Сейчас", style = MaterialTheme.typography.labelSmall, color = Color.White)
            else if (isPast && archiveAvailable) Text("▶ Архив", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Prevents malformed/duplicate XMLTV intervals from visually overlapping. */
private fun normalizeProgrammeTimeline(programmes: List<EpgProgramme>): List<EpgProgramme> {
    val sorted = programmes.asSequence()
        .filter { it.stop > it.start }
        .sortedWith(compareBy<EpgProgramme> { it.start }.thenBy { it.stop })
        .toList()
    if (sorted.size < 2) return sorted
    val result = ArrayList<EpgProgramme>(sorted.size)
    var cursor = Long.MIN_VALUE
    for (programme in sorted) {
        val effectiveStart = maxOf(programme.start, cursor)
        if (programme.stop <= effectiveStart) continue
        if (effectiveStart == programme.start) {
            result += programme
        } else {
            result += programme.copy(start = effectiveStart)
        }
        cursor = programme.stop
    }
    return result
}

/** Раскрывающаяся строка с описанием выбранной передачи. */
@Composable
private fun ExpandedProgrammeInfo(
    info: ProgrammeInfo,
    providerText: String,
    modifier: Modifier = Modifier,
) {
    val programme = info.programme
    val durationMinutes = ((programme.stop - programme.start) / 60_000L).coerceAtLeast(0L)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = programme.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${info.channel.name} · ${formatTimeOfDay(programme.start)} — " +
                "${formatTimeOfDay(programme.stop)} · $durationMinutes мин",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        programme.category?.takeIf { it.isNotBlank() }?.let { category ->
            Text(
                text = "Жанр: $category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        providerText.takeIf { it.isNotBlank() }?.let { provider ->
            Text(
                text = "Источник: $provider",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        programme.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Тонкая красная вертикальная линия текущего времени. */
@Composable
private fun CurrentTimeIndicator(
    from: Long,
) {
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = currentTimeMillis()
        }
    }
    val density = LocalDensity.current
    val nowPx = ((now - from).toFloat() / HOUR_MS) * with(density) { HourWidth.toPx() }
    // Indicator lives INSIDE the horizontal scroller of the guide body, so its
    // position already accounts for the current scroll offset.
    val lineX = with(density) { ChannelColumnWidth.toPx() + TVNextDimens.ItemSpacingSmall.toPx() + nowPx }
    Box(
        modifier = Modifier
            .offset(x = lineX.dp)
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary),
    )
}
