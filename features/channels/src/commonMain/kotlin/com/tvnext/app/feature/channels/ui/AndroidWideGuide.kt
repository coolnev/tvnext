package com.tvnext.app.feature.channels.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.Constraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.tvnext.app.core.common.util.formatDate
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.common.util.startOfDay
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.tmdb.ProgrammeMetadata
import com.tvnext.app.core.tmdb.hasProgrammeSynopsis
import com.tvnext.app.core.tmdb.ProgrammeMetadataRepository
import com.tvnext.app.core.ui.component.AutoScrollText
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.image.LocalLogoImageLoader
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

private enum class GuideFocusColumn { CHANNELS, PROGRAMMES, DAYS, CATEGORIES }

/**
 * TV-only wide EPG overlay; the artwork/description strip is outside the three columns.
 * In bottom-description mode the grid fills the ENTIRE width instead of leaving a blank
 * reserved video column. Channels and the current programme start at the same Y position.
 * No live channel switch occurs when the user merely moves D-pad focus.
 */
@Composable
internal fun AndroidWideGuide(
    channels: List<Channel>,
    playingChannelId: String,
    focusedChannelId: String?,
    timeline: ChannelTimelineState,
    nowPlaying: Map<String, EpgProgramme>,
    now: Long,
    showNumbers: Boolean,
    showCategories: Boolean,
    descriptionOnRight: Boolean,
    categories: List<String>,
    selectedCategory: String?,
    onToggleCategories: () -> Unit,
    onChooseCategory: (String?) -> Unit,
    onChannelFocus: (String) -> Unit,
    onVisibleChannelsChanged: (Set<String>) -> Unit,
    onPlayChannel: (String) -> Unit,
    onPlayArchive: (String, EpgProgramme) -> Unit,
    onClose: () -> Unit,
    initialProgrammeTimeMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val parentalAccess by com.tvnext.app.core.iptv.domain.ParentalChannelGate.session.collectAsState()
    val activeId = focusedChannelId?.takeIf { id -> channels.any { it.id == id } }
        // Reopening may reset a category asynchronously. Hold playback focus
        // while the filtered list catches up, for both live TV and archive.
        ?: playingChannelId.takeIf { focusedChannelId == playingChannelId }
        ?: channels.firstOrNull { it.id == playingChannelId }?.id
        ?: channels.firstOrNull()?.id ?: playingChannelId
    val programmeList = remember(timeline.channelId, timeline.programmes, activeId) {
        if (timeline.channelId == activeId) guideProgrammeTimeline(timeline.programmes) else emptyList()
    }
    val days = remember(programmeList) {
        programmeList.map { startOfDay(it.start) }.distinct()
    }
    // An unconfirmed EPG cursor never changes the real playback anchor.
    // Archive time belongs only to the playing channel; other channels open at live time.
    val playbackTime = if (activeId == playingChannelId) initialProgrammeTimeMs ?: now else now
    var selectedStartMs by remember(activeId) { mutableStateOf<Long?>(null) }
    val highlightedIndex = guideProgrammeIndex(programmeList, selectedStartMs, playbackTime)
    val highlighted = programmeList.getOrNull(highlightedIndex)
    val displayedDay = highlighted?.let { startOfDay(it.start) }
    // A centred, bounded row window replaces LazyColumn.scrollToItem entirely.
    // The Stage 15 negative scroll offset moved the selected row towards the
    // bottom and competed with focus/EPG re-composition on every D-pad press.
    val rowHeight = 62.dp
    val rowSpacing = 2.dp
    // Two programme rows (including their gap) occupy one channel-row stride.
    val programmeRowHeight = (rowHeight - rowSpacing) / 2
    val categoryScroll = rememberLazyListState()
    val channelFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    var categoryCursor by remember(selectedCategory, categories) {
        mutableIntStateOf(
            when (selectedCategory) {
                null -> 0
                FAVORITES_CATEGORY -> 1
                else -> (categories.indexOf(selectedCategory) + 2).coerceAtLeast(0)
            }
        )
    }
    val programmeFocus = remember { FocusRequester() }
    val dayFocus = remember { FocusRequester() }
    var activeColumn by remember { mutableStateOf(GuideFocusColumn.CHANNELS) }
    var initialFocusApplied by remember { mutableStateOf(false) }

    LaunchedEffect(showCategories, playingChannelId) {
        if (!showCategories && focusedChannelId == null && channels.isNotEmpty()) {
            onChannelFocus(activeId)
        }
    }
    LaunchedEffect(showCategories, categoryCursor) {
        if (showCategories) categoryScroll.scrollToItem(categoryCursor)
    }

    // Consume both DOWN and UP so Compose does not also try to transfer focus
    // to another, potentially virtualized, row in the same LazyColumn.
    fun selectAdjacentChannel(delta: Int) {
        val next = guideAdjacentIndex(channels.size, channels.indexOfFirst { it.id == activeId }, delta)
        if (next != null && channels[next].id != activeId) onChannelFocus(channels[next].id)
    }
    fun selectAdjacentProgramme(delta: Int) {
        val next = guideAdjacentIndex(programmeList.size, highlightedIndex, delta)
        if (next != null) {
            selectedStartMs = programmeList[next].start
        }
    }
    fun selectDay(day: Long) {
        selectedStartMs = guideProgrammeOnDay(programmeList, day, now)?.start
    }
    fun selectAdjacentDay(delta: Int) {
        val next = guideAdjacentIndex(days.size, displayedDay?.let(days::indexOf) ?: -1, delta)
        if (next != null && days[next] != displayedDay) {
            selectDay(days[next])
        }
    }

    val finder = remember { GlobalContext.get().getOrNull<ProgrammeMetadataRepository>() }
    var remoteInfo by remember { mutableStateOf<ProgrammeMetadata?>(null) }
    LaunchedEffect(highlighted?.title, finder) {
        remoteInfo = null
        val title = highlighted?.title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (finder == null) return@LaunchedEffect
        // Cancel stale searches on rapid D-pad input. Cached cards appear without network.
        delay(280)
        try {
            finder.observe(title).collect { remoteInfo = it }
        } catch (cancelled: CancellationException) {
            throw cancelled // Preserve structured cancellation when focus changes quickly.
        } catch (_: Exception) {
            remoteInfo = null // Network/API errors must not terminate the UI coroutine.
        }
    }
    val grid: @Composable (Modifier) -> Unit = { gridModifier ->
        BoxWithConstraints(gridModifier) {
            // Focus never follows recycled row children. The focused element is the
            // stable column container, even as channel/EPG data arrives asynchronously.
            LaunchedEffect(showCategories) {
                withFrameNanos { }
                runCatching {
                    if (showCategories) {
                        activeColumn = GuideFocusColumn.CATEGORIES
                        categoryFocus.requestFocus()
                    } else {
                        val restoreArchive = !initialFocusApplied && initialProgrammeTimeMs != null && activeId == playingChannelId
                        activeColumn = if (restoreArchive) GuideFocusColumn.PROGRAMMES else GuideFocusColumn.CHANNELS
                        if (restoreArchive) programmeFocus.requestFocus() else channelFocus.requestFocus()
                        initialFocusApplied = true
                    }
                }
            }
            // Channels/dates retain their row count; programmes use twice as many
            // compact slots with their live/archive anchor beside the selected channel.
            val availableRowsHeight = (maxHeight - 35.dp - 12.dp).coerceAtLeast(rowHeight)
            val viewportCapacity = guideOddCapacity(
                ((availableRowsHeight + rowSpacing) / (rowHeight + rowSpacing)).toInt()
            )
            val channelIndex = channels.indexOfFirst { it.id == activeId }
            val channelAnchorSlot = guideChannelAnchorSlot(channels.size, channelIndex, viewportCapacity)
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            // Categories slide IN FROM THE LEFT and occupy an additional rail.
            // Never replace the channel list: channel/programme/day columns remain
            // mounted and shift to the right together, preserving their selection.
            AnimatedVisibility(
                visible = showCategories,
                enter = slideInHorizontally(initialOffsetX = { -it }) +
                    expandHorizontally(expandFrom = Alignment.Start),
                exit = slideOutHorizontally(targetOffsetX = { -it }) +
                    shrinkHorizontally(shrinkTowards = Alignment.Start),
            ) {
                Column(Modifier.width(190.dp).fillMaxHeight()) {
                    Text("Категории", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.height(35.dp).padding(start = 10.dp, bottom = 4.dp))
                    val categoryValues = listOf<String?>(null, FAVORITES_CATEGORY) + categories
                    LazyColumn(
                        state = categoryScroll,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .focusRequester(categoryFocus)
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionUp, Key.DirectionDown -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val step = if (event.key == Key.DirectionUp) -1 else 1
                                            categoryCursor = guideAdjacentIndex(categoryValues.size, categoryCursor, step)
                                                ?: categoryCursor
                                        }
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (event.type == KeyEventType.KeyUp) {
                                            onChooseCategory(categoryValues.getOrNull(categoryCursor))
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .onFocusChanged { if (it.isFocused) activeColumn = GuideFocusColumn.CATEGORIES }
                            .focusable(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        item { GuideRow("Все каналы", activeColumn == GuideFocusColumn.CATEGORIES && categoryCursor == 0, onClick = { onChooseCategory(null) }) }
                        item { GuideRow("Избранное", activeColumn == GuideFocusColumn.CATEGORIES && categoryCursor == 1,
                            onClick = { onChooseCategory(FAVORITES_CATEGORY) }) }
                        items(categories) { category -> GuideRow(category,
                            activeColumn == GuideFocusColumn.CATEGORIES && categoryCursor == categories.indexOf(category) + 2,
                            onClick = { onChooseCategory(category) }) }
                    }
                }
            }
            Column(Modifier.weight(0.40f).fillMaxHeight()) {
                Text("Каналы", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.height(35.dp).padding(start = 10.dp, bottom = 4.dp))
                    CentredGuideRows(
                        values = channels,
                        selectedIndex = channels.indexOfFirst { it.id == activeId },
                        rowHeight = rowHeight,
                        rowSpacing = rowSpacing,
                        viewportCapacity = viewportCapacity,
                        clampChannelEdges = true,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .focusRequester(channelFocus)
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (event.type == KeyEventType.KeyDown) selectAdjacentChannel(-1)
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (event.type == KeyEventType.KeyDown) selectAdjacentChannel(1)
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (event.type == KeyEventType.KeyDown) runCatching { programmeFocus.requestFocus() }
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        if (event.type == KeyEventType.KeyDown) onToggleCategories()
                                        true
                                    }
                                    // The host owns D-pad focus. Rows are deliberately
                                    // not focusable, so OK must select the channel HERE
                                    // on its first press, not rely on a second button tap.
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (event.type == KeyEventType.KeyUp) onPlayChannel(activeId)
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .onFocusChanged { if (it.isFocused) {
                                selectedStartMs = null
                                activeColumn = GuideFocusColumn.CHANNELS
                            } }
                            .focusable(),
                        // Debounce EPG refresh while a user holds DOWN/UP.
                        onVisibleChanged = { visible ->
                            onVisibleChannelsChanged(visible.mapTo(mutableSetOf()) { it.id })
                        },
                    ) { channel ->
                            val current = channel.id == activeId
                            val programme = nowPlaying[channel.id]
                                ?: if (timeline.channelId == channel.id) {
                                    timeline.programmes.firstOrNull { it.start <= now && now < it.stop }
                                } else null
                            val duration = programme?.let { (it.stop - it.start).coerceAtLeast(1L) } ?: 1L
                            val progress = programme?.let {
                                ((now - it.start).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            } ?: 0f
                            TvCardButton(
                                onClick = { onPlayChannel(channel.id) },
                                selected = current && activeColumn == GuideFocusColumn.CHANNELS,
                                // Only the stable list host takes D-pad focus; rows remain touch-clickable.
                                modifier = Modifier.testTag("guide-channel-row").fillMaxWidth().height(rowHeight)
                                    .focusProperties { canFocus = false },
                                containerAlpha = if (current) 0.24f else 0.08f,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(channel.logoUrl, modifier = Modifier.size(31.dp),
                                        placeholderText = channel.name)
                                    if (showNumbers) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(channel.number.toString(), color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp, maxLines = 1)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        val adult = parentalAccess.restrictionsActive && com.tvnext.app.core.iptv.domain.ParentalChannelGate.isRestricted(channel)
                                        Text((if (adult) "🔒 " else "") + channel.name,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (adult) Color(0xFFFF6767) else Color.White,
                                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            lineHeight = 15.sp)
                                        Text(programme?.title ?: "Нет данных EPG", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, color = Color.White,
                                            fontSize = 11.sp, lineHeight = 13.sp)
                                        if (programme != null) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(formatTimeOfDay(programme.start), color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 10.sp, lineHeight = 11.sp)
                                                Spacer(Modifier.width(5.dp))
                                                Box(Modifier.weight(1f).height(2.dp)
                                                    .background(Color.White.copy(alpha = 0.28f))) {
                                                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.primary))
                                                }
                                                Spacer(Modifier.width(5.dp))
                                                Text(formatTimeOfDay(programme.stop), color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 10.sp, lineHeight = 11.sp)
                                            }
                                        }
                                    }
                                    if (channel.hasCatchupMechanism) {
                                        Text("◷", color = Color.White, fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 2.dp))
                                    }
                                }
                            }
                    }
            }
            Column(Modifier.weight(0.48f).fillMaxHeight()) {
                Text(channels.firstOrNull { it.id == activeId }?.name ?: "Программа передач",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.height(35.dp).padding(start = 9.dp, bottom = 4.dp))
                CentredGuideRows(
                    values = programmeList,
                    selectedIndex = highlightedIndex,
                    rowHeight = programmeRowHeight,
                    rowSpacing = rowSpacing,
                    viewportCapacity = viewportCapacity * 2,
                    anchorSlot = channelAnchorSlot * 2,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .focusRequester(programmeFocus)
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (event.type == KeyEventType.KeyDown) selectAdjacentProgramme(-1)
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) selectAdjacentProgramme(1)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (event.type == KeyEventType.KeyDown) runCatching { channelFocus.requestFocus() }
                                    true
                                }
                                Key.DirectionRight -> {
                                    if (event.type == KeyEventType.KeyDown) runCatching { dayFocus.requestFocus() }
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    if (event.type == KeyEventType.KeyUp) {
                                        highlighted?.let { programme ->
                                            if (programme.start <= now &&
                                                channels.firstOrNull { it.id == activeId }?.hasCatchupMechanism == true
                                            ) onPlayArchive(activeId, programme)
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) activeColumn = GuideFocusColumn.PROGRAMMES }
                        .focusable(),
                    emptyLabel = if (timeline.loading) "Загрузка телепрограммы…" else "Нет телепрограммы",
                ) { programme ->
                        val selected = programme == highlighted && activeColumn == GuideFocusColumn.PROGRAMMES
                        GuideRow(
                            "${formatTimeOfDay(programme.start)}  ${programme.title}", selected,
                            currentBroadcast = programme.start <= now && now < programme.stop,
                            onClick = {
                                selectedStartMs = programme.start
                                if (programme.start <= now && channels.firstOrNull { it.id == activeId }?.hasCatchupMechanism == true) {
                                    onPlayArchive(activeId, programme)
                                }
                            },
                            height = programmeRowHeight,
                            modifier = Modifier.testTag("guide-programme-row"),
                            maxLines = 1,
                        )
                }
            }
            Column(Modifier.weight(0.12f).fillMaxHeight()) {
                Text("Дни", color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().height(35.dp).padding(bottom = 4.dp))
                CentredGuideRows(
                    values = days,
                    selectedIndex = displayedDay?.let(days::indexOf) ?: -1,
                    rowHeight = rowHeight,
                    rowSpacing = rowSpacing,
                    viewportCapacity = viewportCapacity,
                    anchorSlot = channelAnchorSlot,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .focusRequester(dayFocus)
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.DirectionUp -> {
                                    if (event.type == KeyEventType.KeyDown) selectAdjacentDay(-1)
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) selectAdjacentDay(1)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (event.type == KeyEventType.KeyDown) runCatching { programmeFocus.requestFocus() }
                                    true
                                }
                                Key.DirectionRight -> true
                                // Changing the day with UP/DOWN applies immediately;
                                // consume OK so it cannot fall through to the player.
                                Key.DirectionCenter, Key.Enter -> true
                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) activeColumn = GuideFocusColumn.DAYS }
                        .focusable(),
                    emptyLabel = "Нет дат EPG",
                ) { day ->
                        val dateTitle = "${guideWeekday(day)}\n${formatDate(day)}"
                        GuideRow(dateTitle, day == displayedDay && activeColumn == GuideFocusColumn.DAYS,
                            onClick = { selectDay(day) },
                            height = rowHeight, centerContent = true,
                            underline = day == displayedDay && activeColumn != GuideFocusColumn.DAYS)
                }
            }
            }
        }
    }
    // Artwork is only allocated after a decoded bitmap exists; absent images leave zero gap.
    val description = highlighted?.description?.takeIf(::hasProgrammeSynopsis)
            ?: remoteInfo?.overview?.takeIf(::hasProgrammeSynopsis)
            ?: "Описание передачи отсутствует"
    val info: @Composable (Modifier) -> Unit = { infoModifier ->
        if (descriptionOnRight) Column(
            modifier = infoModifier.background(Color.Black.copy(alpha = 0.50f))
                .padding(start = 15.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            GuideArtwork(remoteInfo?.frameUrl, remoteInfo?.posterUrl, descriptionOnRight)
            Column(Modifier.fillMaxWidth()) {
                GuideDescription(highlighted, description)
            }
        } else Row(
            modifier = infoModifier.background(Color.Black.copy(alpha = 0.50f))
                .padding(start = 15.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GuideArtwork(remoteInfo?.frameUrl, remoteInfo?.posterUrl, descriptionOnRight)
            Column(Modifier.weight(1f)) {
                GuideDescription(highlighted, description)
            }
        }
    }
    if (descriptionOnRight) {
        Row(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f))) {
            grid(Modifier.weight(0.80f).fillMaxHeight())
            info(Modifier.weight(0.20f).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f))) {
            grid(Modifier.fillMaxWidth().weight(1f))
            info(Modifier.fillMaxWidth().height(146.dp))
        }
    }
}

/** The same stable, continuous EPG cursor in the windowed programme pane. */
@Composable
internal fun AndroidWindowedProgrammeList(
    channel: Channel,
    programmes: List<EpgProgramme>,
    loading: Boolean,
    now: Long,
    initialProgrammeTimeMs: Long?,
    focusRequester: FocusRequester?,
    requestInitialFocus: Boolean,
    onProgrammeHighlighted: (EpgProgramme) -> Unit,
    onPlayProgramme: (EpgProgramme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = remember(programmes) { guideProgrammeTimeline(programmes) }
    val playbackTime = remember(channel.id) { initialProgrammeTimeMs ?: now }
    var selectedStartMs by remember(channel.id) { mutableStateOf<Long?>(null) }
    val index = guideProgrammeIndex(values, selectedStartMs, playbackTime)
    val highlighted = values.getOrNull(index)
    val hostFocus = focusRequester ?: remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(channel.id, requestInitialFocus) {
        if (requestInitialFocus) {
            withFrameNanos { }
            hostFocus.requestFocus()
        }
    }
    LaunchedEffect(highlighted) { highlighted?.let(onProgrammeHighlighted) }
    Column(modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().testTag("windowed-programmes").focusRequester(hostFocus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    guideAdjacentIndex(values.size, index, if (event.key == Key.DirectionUp) -1 else 1)
                                        ?.let { selectedStartMs = values[it].start }
                                }
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                if (event.type == KeyEventType.KeyUp) highlighted?.let(onPlayProgramme)
                                true
                            }
                            else -> false
                        }
                    }.focusable()
        ) {
            val capacity = ((maxHeight + 4.dp) / 33.dp).toInt().coerceAtLeast(1)
            CentredGuideRows(
                values = values, selectedIndex = index, rowHeight = 29.dp, rowSpacing = 4.dp,
                viewportCapacity = capacity, fillViewport = true,
                modifier = Modifier.fillMaxSize(),
                emptyLabel = if (loading) "Загрузка телепрограммы…" else "Программа передач недоступна",
            ) { programme ->
                GuideRow("${formatTimeOfDay(programme.start)}  ${programme.title}",
                    selected = programme == highlighted && focused,
                    currentBroadcast = programme.start <= now && now < programme.stop,
                    height = 29.dp,
                    maxLines = 1,
                    modifier = Modifier.testTag("windowed-programme-row"),
                    onClick = { selectedStartMs = programme.start; onPlayProgramme(programme) })
            }
        }
    }
}

/**
 * Fixed-slot TV guide viewport with no scroll mutations or recycled focus.
 * Channels clamp to the top and bottom. EPG/date rows instead use the channel's
 * selected slot so the three selected rows always share one horizontal line.
 */
@Composable
private fun <T> CentredGuideRows(
    values: List<T>,
    selectedIndex: Int,
    rowHeight: Dp,
    rowSpacing: Dp,
    modifier: Modifier = Modifier,
    viewportCapacity: Int? = null,
    clampChannelEdges: Boolean = false,
    fillViewport: Boolean = false,
    anchorSlot: Int? = null,
    emptyLabel: String = "Нет элементов",
    onVisibleChanged: ((List<T>) -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        if (values.isEmpty()) {
            Text(emptyLabel, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center).padding(8.dp))
        } else {
            // Explicit capacities may be even: dense EPG has two slots per channel.
            // Standalone/windowed lists retain their centred odd-row layout.
            val capacity = viewportCapacity?.coerceAtLeast(1) ?: guideOddCapacity(
                ((maxHeight + rowSpacing) / (rowHeight + rowSpacing)).toInt())
            val window = when {
                fillViewport -> guideFilledWindow(values.size, selectedIndex, capacity)
                clampChannelEdges -> guideClampedChannelWindow(values.size, selectedIndex, capacity)
                anchorSlot != null -> guideAlignedWindow(values.size, selectedIndex, capacity, anchorSlot)
                else -> guideCentredIndexWindow(values.size, selectedIndex, capacity)
            }
            val visible = window.mapNotNull { values.getOrNull(it) }
            if (onVisibleChanged != null) {
                LaunchedEffect(visible) {
                    // Coalesce fast remote key repeats before refreshing EPG.
                    delay(130)
                    onVisibleChanged(visible)
                }
            }
            Column(
                modifier = Modifier.align(if (fillViewport) Alignment.TopCenter else Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(rowSpacing),
            ) {
                for (index in window) {
                    val item = values.getOrNull(index)
                    if (item == null) Spacer(Modifier.fillMaxWidth().height(rowHeight))
                    else row(item)
                }
            }
        }
    }
}

@Composable
private fun GuideDescription(programme: EpgProgramme?, description: String) {
    Text(programme?.title ?: "Телепрограмма", maxLines = 2,
        style = MaterialTheme.typography.titleMedium, color = Color.White)
    if (programme != null) {
        Text("${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)} · ${formatDate(programme.start)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium)
    }
    Text(description, style = MaterialTheme.typography.bodyMedium,
        color = Color.White, maxLines = 7, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun GuideArtwork(frameUrl: String?, posterUrl: String?, rightColumn: Boolean) {
    val loader = LocalLogoImageLoader.current
    var bitmap by remember(frameUrl, posterUrl, loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frameUrl, posterUrl, loader) {
        bitmap = null
        if (loader != null) {
            // An unavailable TMDB backdrop must not prevent the poster fallback.
            // Keep the two fetches independent: one malformed image is not fatal.
            suspend fun safeLoad(url: String?): ImageBitmap? {
                if (url.isNullOrBlank()) return null
                return try {
                    loader.load(url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            bitmap = safeLoad(frameUrl) ?: safeLoad(posterUrl)
        }
    }
    bitmap?.let { picture ->
        // Reserve only the width ACTUALLY occupied by the decoded image.
        // A portrait poster fitted inside a wide 173dp backdrop slot used to
        // leave a huge invisible gap before the description text.
        val maxWidth = if (rightColumn) 180.dp else 173.dp
        val maxHeight = if (rightColumn) 115.dp else 126.dp
        val aspect = picture.width.toFloat() / picture.height.coerceAtLeast(1).toFloat()
        val imageWidth = minOf(maxWidth, maxHeight * aspect)
        val imageHeight = imageWidth / aspect.coerceAtLeast(0.01f)
        Row(verticalAlignment = Alignment.Top) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.TopStart,
                modifier = Modifier.size(imageWidth, imageHeight)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
private fun GuideRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp = 53.dp,
    centerContent: Boolean = false,
    underline: Boolean = false,
    currentBroadcast: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    TvCardButton(onClick = onClick, selected = selected,
        modifier = modifier.fillMaxWidth().height(height)
            .background(if (currentBroadcast) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
            .focusProperties { canFocus = false },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (maxLines == 1) 2.dp else 5.dp),
        containerAlpha = if (selected) 0.23f else if (currentBroadcast) 0.16f else 0.05f,
        shape = RoundedCornerShape(6.dp),
        contentAlignment = if (centerContent) Alignment.Center else Alignment.CenterStart) {
        Text(label, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
            textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
            textAlign = if (centerContent) TextAlign.Center else TextAlign.Start,
            modifier = if (centerContent) Modifier.fillMaxWidth() else Modifier,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Russian abbreviated weekday for programme dates, based on the provider's local day. */
internal fun guideWeekday(epochMillis: Long): String {
    val noonMillis = startOfDay(epochMillis) + 12L * 60 * 60 * 1000
    val day = noonMillis / (24L * 60 * 60 * 1000)
    val index = ((day + 3L) % 7L + 7L).toInt() % 7
    return arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[index]
}

/** Reusable detailed programme view; avoids a text-only dialog when artwork is cached. */
@Composable
internal fun ProgrammeRichDetails(programme: EpgProgramme?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val finder = remember { GlobalContext.get().getOrNull<ProgrammeMetadataRepository>() }
    var remote by remember(programme?.title) { mutableStateOf<ProgrammeMetadata?>(null) }
    LaunchedEffect(programme?.title, finder) {
        remote = null
        val title = programme?.title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        delay(150)
        try {
            finder?.observe(title)?.collect { remote = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            remote = null
        }
    }
    val description = programme?.description?.takeIf(::hasProgrammeSynopsis)
        ?: remote?.overview?.takeIf(::hasProgrammeSynopsis)
        ?: "Описание передачи недоступно"
    val loader = LocalLogoImageLoader.current
    var picture by remember(remote?.frameUrl, remote?.posterUrl, loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(remote?.frameUrl, remote?.posterUrl, loader) {
        picture = null
        if (loader != null) {
            suspend fun load(url: String?): ImageBitmap? {
                if (url.isNullOrBlank()) return null
                return try { loader.load(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            picture = load(remote?.frameUrl) ?: load(remote?.posterUrl)
        }
    }
    val style = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None),
    )
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().padding(start = if (compact) 0.dp else 12.dp,
        end = if (compact) 0.dp else 10.dp, top = 4.dp)) {
        val maxTextHeight = if (compact) maxHeight.coerceAtMost(430.dp) else (maxHeight * 0.64f).coerceIn(190.dp, 430.dp)
        if (picture == null) {
            ExpandableGuideDescription(description, Modifier.fillMaxWidth(), maxTextHeight, style)
        } else {
            val art = picture!!
            val aspect = art.width.toFloat() / art.height.coerceAtLeast(1)
            val pictureLimit = if (compact) minOf(80.dp, maxHeight * 0.55f) else 142.dp
            val pictureWidth = minOf(if (compact) 92.dp else 190.dp, pictureLimit * aspect)
            val pictureHeight = pictureWidth / aspect.coerceAtLeast(0.01f)
            val textWidth = (maxWidth - pictureWidth - 10.dp).coerceAtLeast(80.dp)
            val linePixels = with(density) {
                if (style.lineHeight.isSpecified) style.lineHeight.toPx()
                else if (style.fontSize.isSpecified) style.fontSize.toPx() * 1.25f
                else 19.dp.toPx()
            }
            // Round UP to a full line box: the next full-width line then follows
            // the last narrow line without waiting for the taller image + a gap.
            val lines = kotlin.math.ceil(with(density) { pictureHeight.toPx() } / linePixels).toInt().coerceAtLeast(1)
            // Measure actual line breaks at the width beside the image. Once the
            // image ends, the remainder continues across the full panel width.
            val layout = measure.measure(AnnotatedString(description), style = style,
                maxLines = lines, overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = with(density) { textWidth.roundToPx() }))
            val split = if (layout.lineCount > 0)
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true).coerceIn(0, description.length)
                else 0
            val topText = description.take(split).trimEnd()
            val remainingText = description.drop(split).trimStart()
            val leadingHeight = maxOf(pictureHeight, with(density) { layout.size.height.toDp() })
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Image(bitmap = art, contentDescription = null,
                        contentScale = ContentScale.Fit, alignment = Alignment.TopStart,
                        modifier = Modifier.size(pictureWidth, pictureHeight).clip(RoundedCornerShape(7.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(topText, modifier = Modifier.weight(1f).testTag("programme-description-beside"), style = style,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                if (remainingText.isNotEmpty()) {
                    ExpandableGuideDescription(remainingText, Modifier.fillMaxWidth(),
                        (maxTextHeight - leadingHeight).coerceAtLeast(if (compact) 1.dp else 65.dp), style)
                }
            }
        }
    }
}

/** Content grows with text until a TV-safe maximum; overflow scrolls back/forth. */
@Composable
private fun ExpandableGuideDescription(text: String, modifier: Modifier, maximum: Dp,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Column(modifier.heightIn(max = maximum).verticalScroll(scroll)) {
        Text(text, modifier = Modifier.testTag("programme-description-text"), color = MaterialTheme.colorScheme.onSurface, style = style)
    }
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(0)
        if (scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            delay(3000)
            val speed = with(density) { 18.dp.toPx() }
            val duration = (scroll.maxValue / speed * 1000).toInt().coerceIn(1500, 90_000)
            while (true) {
                scroll.animateScrollTo(scroll.maxValue, tween(duration, easing = LinearEasing))
                delay(3000)
                scroll.animateScrollTo(0, tween(duration, easing = LinearEasing))
                delay(3000)
            }
        }
    }
}
