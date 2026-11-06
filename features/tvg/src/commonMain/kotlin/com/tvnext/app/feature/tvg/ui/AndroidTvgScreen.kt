package com.tvnext.app.feature.tvg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.common.input.OkInputGate
import com.tvnext.app.core.common.util.startOfLocalDayOffset
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatDateTime
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.ui.component.AutoScrollText
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val GUIDE_HOUR = 3_600_000L
private val GuideChannelWidth = 190.dp
private val GuideHourWidth = 300.dp

/** One focus target for the grid: the selected cell and its scroll never race native focus. */
@Composable
internal fun AndroidTvgScreen(component: TvgComponent, modifier: Modifier, onBack: (() -> Unit)?) {
    run {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            AndroidTvgContent(component, modifier, onBack)
        }
    }
}

@Composable
private fun AndroidTvgContent(component: TvgComponent, modifier: Modifier, onBack: (() -> Unit)?) {
    val state by component.state.collectAsState()
    val rows = state.filteredChannels
    val list = rememberLazyListState()
    val timeScroll = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gridFocus = remember { FocusRequester() }
    val toolbarFocus = remember { FocusRequester() }
    var toolbarFocused by remember { mutableStateOf(false) }
    var okHeld by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    var okJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { okJob?.cancel() } }
    var channelId by remember { mutableStateOf<String?>(null) }
    val entryTime = remember(component) { guidePlaybackTime(component.playbackController.state.value, currentTimeMillis()) }
    var cursorTime by remember { mutableLongStateOf(entryTime) }
    // When LEFT crosses midnight, keep the target in the preceding day rather
    // than resetting the cursor to 00:00 (which traps archive navigation).
    var pendingDayCursor by remember { mutableStateOf<Long?>(entryTime) }
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    var nowRequest by remember { mutableIntStateOf(0) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ProgrammeInfo?>(null) }
    val categoryMemory = remember { mutableMapOf<String?, String>() }
    val hourPx = with(density) { GuideHourWidth.toPx() }
    val selectedChannel = rows.firstOrNull { it.id == channelId }
    val selectedProgramme = channelId?.let { state.programmes[it] }.orEmpty().firstOrNull {
        it.start <= cursorTime && cursorTime < it.stop
    }
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    fun select(channel: Channel, time: Long) {
        channelId = channel.id
        categoryMemory[state.selectedCategory] = channel.id
        cursorTime = time
    }
    fun showDetails(channel: Channel, programme: EpgProgramme?) {
        if (programme == null) component.dispatch(TvgIntent.ChannelClicked(channel.id))
        else details = ProgrammeInfo(channel, programme)
    }
    LaunchedEffect(Unit) { while (true) { now = currentTimeMillis(); delay(30_000) } }
    LaunchedEffect(rows.map { it.id }, state.selectedCategory) {
        if (rows.isEmpty()) return@LaunchedEffect
        val saved = categoryMemory[state.selectedCategory] ?: channelId ?: component.playbackController.state.value.item?.id
        channelId = rows.firstOrNull { it.id == saved }?.id ?: rows.first().id
    }
    LaunchedEffect(state.from, state.to) {
        if (state.to > state.from) {
            val pending = pendingDayCursor
            // A newer absolute cursor may still be waiting for its own day.
            // An intermediate result must not reset it back to this grid's midnight.
            if (pending != null && pending !in state.from until state.to) return@LaunchedEffect
            cursorTime = pending ?: now.takeIf { it in state.from until state.to } ?: state.from
            pendingDayCursor = null
            withFrameNanos { }
            timeScroll.scrollTo(((cursorTime - state.from) / GUIDE_HOUR.toFloat() * hourPx - hourPx / 4).toInt().coerceAtLeast(0))
        }
    }
    LaunchedEffect(channelId, rows.map { it.id }) {
        val index = rows.indexOfFirst { it.id == channelId }
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    }
    LaunchedEffect(list, rows.map { it.id }, state.from, state.to) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
            .distinctUntilChanged().collect { ids ->
                if (ids.isNotEmpty()) component.dispatch(TvgIntent.VisibleChannelsChanged(ids))
            }
    }
    LaunchedEffect(categoriesOpen, details, rows.isNotEmpty()) {
        if (!categoriesOpen && details == null && rows.isNotEmpty()) {
            withFrameNanos { }
            gridFocus.requestFocus()
        }
    }
    Column(modifier.onPreviewKeyEvent { event ->
        if ((event.key == Key.Enter || event.key == Key.DirectionCenter) && okLong) {
            if (event.type == KeyEventType.KeyUp) { okHeld = false; okLong = false }
            true
        } else if (event.key == Key.Info && !categoriesOpen) {
            if (event.type == KeyEventType.KeyUp) {
                if (details != null) details = null
                else selectedChannel?.let { showDetails(it, selectedProgramme) }
            }
            true
        } else if (event.key == Key.Back && !categoriesOpen && details == null) {
            if (event.type == KeyEventType.KeyUp) toolbarFocus.requestFocus()
            true
        } else false
    }.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Row(modifier = Modifier.onFocusChanged { toolbarFocused = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown) gridFocus.requestFocus()
                    true
                } else false
            }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(modifier = Modifier.focusRequester(toolbarFocus), onClick = { categoriesOpen = true }) { Text(state.selectedCategory ?: "Все каналы") }
            Button(onClick = { pendingDayCursor = null; component.dispatch(TvgIntent.DayDelta(-1)) }) { Text("− День") }
            Button(onClick = { pendingDayCursor = null; component.dispatch(TvgIntent.DaySet(0)); cursorTime = now; nowRequest++ }) { Text("Сейчас") }
            Button(onClick = { pendingDayCursor = null; component.dispatch(TvgIntent.DayDelta(1)) }) { Text("+ День") }
            Button(onClick = { component.dispatch(TvgIntent.Refresh) }) { Text("Обновить") }
        }
        Text(formatDateTime(if (state.from > 0) state.from else now), modifier = Modifier.padding(vertical = 6.dp))
        if (state.isRefreshing || state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it.toUserMessage(), color = MaterialTheme.colorScheme.error) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val timelineWidth = (maxWidth - GuideChannelWidth).coerceAtLeast(1.dp)
            val viewportPx = with(density) { timelineWidth.toPx() }
            val offsetDp = with(density) { timeScroll.value.toDp() }
            val visibleStart = state.from + (timeScroll.value / hourPx * GUIDE_HOUR).toLong()
            val visibleEnd = visibleStart + (viewportPx / hourPx * GUIDE_HOUR).toLong()
            LaunchedEffect(cursorTime, state.from, viewportPx, nowRequest) {
                if (state.to <= state.from || cursorTime !in state.from until state.to) return@LaunchedEffect
                val x = (cursorTime - state.from) / GUIDE_HOUR.toFloat() * hourPx
                val left = timeScroll.value.toFloat()
                val target = when {
                    x < left -> x - hourPx / 4
                    x > left + viewportPx - 40 -> x - viewportPx + hourPx / 2
                    else -> left
                }
                timeScroll.scrollTo(target.toInt().coerceAtLeast(0))
            }
            Column {
                Row(Modifier.height(30.dp)) {
                    Text("Канал", Modifier.width(GuideChannelWidth))
                    Row(Modifier.width(timelineWidth).horizontalScroll(timeScroll)) {
                        val count = ((state.to - state.from) / GUIDE_HOUR).toInt().coerceIn(1, 25)
                        repeat(count * 2) { index ->
                            Text(formatTimeOfDay(state.from + index * GUIDE_HOUR / 2), Modifier.width(GuideHourWidth / 2))
                        }
                    }
                }
                // Keep exactly five channel rows visible. The description/help area
                // below is outside this clipped viewport, so a sixth row can never
                // slide underneath it. LazyColumn still scrolls normally to the
                // following rows as the user navigates.
                val fiveRowsHeight = 64.dp * 5 + 3.dp * 4
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .height(fiveRowsHeight)
                        .fillMaxWidth()
                        .clipToBounds()
                        .testTag("tvg-grid").focusRequester(gridFocus)
                        .onPreviewKeyEvent { event ->
                            val navigation = event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                            val ok = event.key == Key.Enter || event.key == Key.DirectionCenter
                            if (ok) {
                                if (event.type == KeyEventType.KeyDown && !okHeld) {
                                    okHeld = true
                                    okLong = false
                                    okJob = scope.launch {
                                        delay(500)
                                        okLong = true
                                        toolbarFocus.requestFocus()
                                    }
                                }
                                if (event.type == KeyEventType.KeyUp) {
                                    okJob?.cancel()
                                    val suppressRelease = OkInputGate.suppressNextKeyUp
                                    if (suppressRelease) OkInputGate.suppressNextKeyUp = false
                                    val click = okHeld && !okLong && !suppressRelease
                                    okHeld = false
                                    okLong = false
                                    if (click) selectedChannel?.let { showDetails(it, selectedProgramme) }
                                }
                                true
                            } else if (navigation && event.type == KeyEventType.KeyDown) {
                                val index = rows.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
                                when (event.key) {
                                    Key.DirectionUp -> if (index == 0) toolbarFocus.requestFocus()
                                        else select(rows[index - 1], cursorTime)
                                    Key.DirectionDown -> rows.getOrNull(index + 1)?.let { select(it, cursorTime) }
                                    else -> {
                                        val forward = event.key == Key.DirectionRight
                                        val programmes = if (cursorTime in state.from until state.to)
                                            channelId?.let { state.programmes[it] }.orEmpty() else emptyList()
                                        val target = guideProgrammeTarget(programmes, cursorTime, forward).coerceIn(
                                            startOfLocalDayOffset(now, state.minArchiveDayOffset.coerceIn(-365, 0)),
                                            startOfLocalDayOffset(now, 8) - 1)
                                        cursorTime = target
                                        pendingDayCursor = target.takeUnless { it in state.from until state.to }
                                        component.dispatch(TvgIntent.TimelineCursorMoved(target))
                                    }
                                }
                                true
                            } else navigation && event.type == KeyEventType.KeyUp
                        }.focusable()
                        .draggable(rememberDraggableState { delta -> scope.launch { timeScroll.scrollBy(-delta) } }, Orientation.Horizontal),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(rows, key = { _, channel -> channel.id }) { _, channel ->
                        val selected = channel.id == channelId
                        Row(Modifier.fillMaxWidth().height(64.dp)) {
                            Column(Modifier.width(GuideChannelWidth).fillMaxHeight()
                                .background(if (selected) accent.copy(alpha = .2f) else foreground.copy(alpha = .05f))
                                .guideTap { select(channel, cursorTime); component.dispatch(TvgIntent.ChannelClicked(channel.id)) }.padding(8.dp)) {
                                Text("${channel.number}  ${channel.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.width(timelineWidth).fillMaxHeight().clipToBounds()) {
                                val programmes = state.programmes[channel.id].orEmpty()
                                if (programmes.isEmpty()) Text("Нет программы", Modifier.padding(12.dp), color = foreground.copy(alpha = .5f))
                                programmes.filter { it.stop > visibleStart && it.start < visibleEnd }.forEach { programme ->
                                    val start = maxOf(programme.start, state.from, visibleStart)
                                    val stop = minOf(programme.stop, state.to, visibleEnd)
                                    if (stop > start) {
                                        val active = programme.start <= now && now < programme.stop
                                        val highlighted = selected && programme == selectedProgramme
                                        val x = GuideHourWidth * ((start - state.from).toFloat() / GUIDE_HOUR) - offsetDp
                                        val width = GuideHourWidth * ((stop - start).toFloat() / GUIDE_HOUR)
                                        Column(Modifier.testTag("tvg-programme").offset(x = x).width(width).fillMaxHeight().padding(end = 2.dp)
                                            .background(if (active) accent.copy(alpha = .24f) else foreground.copy(alpha = .08f))
                                            .border(if (highlighted) 2.dp else 0.dp, if (highlighted) accent else Color.Transparent)
                                            .guideTap { select(channel, programme.start); showDetails(channel, programme) }.padding(8.dp)) {
                                            Text(programme.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${formatTimeOfDay(programme.start)}–${formatTimeOfDay(programme.stop)}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                if (now in visibleStart..visibleEnd) Box(Modifier.offset(x = GuideHourWidth * ((now - state.from).toFloat() / GUIDE_HOUR) - offsetDp).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
                            }
                        }
                    }
                    if (rows.isEmpty()) item { Text(if (state.isLoading) "Загрузка каналов…" else "Нет каналов в этой категории. Добавьте плейлист или выберите другую категорию.") }
                }
            }
        }
        AutoScrollText(selectedProgramme?.let { "${selectedChannel?.name} • ${it.title}\n${it.description.orEmpty()}" }
            ?: "↑↓ Каналы   ←→ Передачи   OK Информация   Удержать OK / Назад: кнопки", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
    }
    if (categoriesOpen) AlertDialog(
        onDismissRequest = { categoriesOpen = false }, title = { Text("Категории") },
        text = {
            val categories = listOf<String?>(null) + state.channels.map { it.category }.distinct().sorted()
            val categoryList = rememberLazyListState(initialFirstVisibleItemIndex = categories.indexOf(state.selectedCategory).coerceAtLeast(0))
            val selectedFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { selectedFocus.requestFocus() } }
            LazyColumn(state = categoryList, modifier = Modifier.heightIn(max = 350.dp)) {
                items(categories) { category ->
                    TextButton(onClick = { component.dispatch(TvgIntent.CategorySelected(category)); categoriesOpen = false },
                        modifier = if (category == state.selectedCategory) Modifier.focusRequester(selectedFocus) else Modifier) { Text(category ?: "Все каналы") }
                }
            }
        }, confirmButton = { TextButton(onClick = { categoriesOpen = false }) { Text("Закрыть") } },
    )
    details?.let { info ->
        val p = info.programme
        AlertDialog(onDismissRequest = { details = null }, title = { Text(p.title) },
            text = { AutoScrollText("${info.channel.name} • ${formatDateTime(p.start)}–${formatTimeOfDay(p.stop)}\n${p.description.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 9) },
            confirmButton = {
                Row {
                    TextButton(onClick = { details = null; component.dispatch(TvgIntent.ChannelClicked(info.channel.id)) }) { Text("Прямой эфир") }
                    if (p.start > now && p.stop > p.start && p.stop - p.start <= 6 * 60 * 60 * 1000L &&
                        info.channel.streamUrl.isNotBlank() &&
                        com.tvnext.app.core.common.platform.isAndroidPlatform()) {
                        TextButton(onClick = {
                            com.tvnext.app.core.common.util.RecordingScheduleRequests.request(
                                com.tvnext.app.core.common.util.RecordingScheduleRequest(
                                    channelName = info.channel.name,
                                    programmeTitle = p.title,
                                    streamUrl = info.channel.streamUrl,
                                    startAt = p.start,
                                    stopAt = p.stop,
                                )
                            )
                            details = null
                        }) { Text("Запланировать запись") }
                    }
                    if (p.start > now) TextButton(onClick = { component.dispatch(TvgIntent.ScheduleToggled(info.channel.id, p.start)); details = null }) {
                        val scheduled = "${info.channel.playlistId}|${info.channel.id}|${p.start}" in state.scheduledKeys
                        Text(if (scheduled) "Убрать напоминание" else "Напомнить")
                    }
                    else if (info.channel.hasCatchupMechanism) TextButton(onClick = { component.dispatch(TvgIntent.ArchiveProgrammeClicked(info.channel.id, p.start)); details = null }) { Text("Смотреть с начала") }
                }
            }, dismissButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } })
    }
}

@Composable
private fun Modifier.guideTap(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) { detectTapGestures(onTap = { current() }) }
}
