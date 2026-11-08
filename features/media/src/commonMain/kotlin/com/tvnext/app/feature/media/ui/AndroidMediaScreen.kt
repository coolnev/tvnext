package com.tvnext.app.feature.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.player.PlaybackState
import kotlinx.coroutines.delay
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.ui.component.AndroidPlaybackControls
import com.tvnext.app.core.ui.component.TvPlayPauseIcon
import com.tvnext.app.core.ui.component.AutoScrollText
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvPlayerPanel
import com.tvnext.app.core.ui.image.ChannelLogo

private enum class MediaPane { CATEGORIES, FILMS, VIDEO, INFO }

/** Android-only navigation; the video stays at the same composition location. */
@Composable
internal fun AndroidMediaScreen(
    component: MediaComponent,
    modifier: Modifier,
    clockPosition: ClockPosition,
    onImmersiveChange: (Boolean) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onBackHome: (() -> Unit)?,
    showDiagnostics: Boolean,
    infoDurationSeconds: Int?,
) {
    val state by component.state.collectAsState()
    val playback by component.playbackController.state.collectAsState()
    var controlsVisible by remember { mutableStateOf(false) }
    // RIGHT shows a timed passive strip; OK opens an interactive transport bar.
    var controlsFromOk by remember { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }
    var pauseFeedback by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf(0L) }
    var pane by remember { mutableStateOf(MediaPane.CATEGORIES) }
    var categoryIndex by remember { mutableStateOf(0) }
    var filmIndex by remember { mutableStateOf(0) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var infoFromList by remember { mutableStateOf(false) }
    val selected = remember(state.content, state.selectedItemId) {
        state.content.allItems.firstOrNull { it.id == state.selectedItemId }
    }
    val infoItem = state.infoItemId?.let { id -> state.content.allItems.firstOrNull { it.id == id } } ?: selected
    val infoMetadata = if (infoItem?.id == selected?.id) state.tmdbMetadata else state.infoTmdbMetadata
    val infoLoading = if (infoItem?.id == selected?.id) state.tmdbLoading else state.infoTmdbLoading
    val seekStepMs = component.seekStepMs.collectAsState().value
    val focusManager = LocalFocusManager.current
    val videoFocus = remember { FocusRequester() }
    val categoryScroll = rememberLazyGridState()
    val filmScroll = rememberLazyGridState()
    val cardFocus = remember { FocusRequester() }
    val categories = remember(state.content, state.selectedSourceId) { listOf("Все", "Избранное") + state.categories }
    val films = remember(state.content, state.selectedSourceId, state.selectedCategory, state.query, favoritesOnly) {
        state.filteredItems.filter { !favoritesOnly || it.isFavorite }
    }
    val browsing = pane == MediaPane.CATEGORIES || pane == MediaPane.FILMS || (pane == MediaPane.INFO && infoFromList)
    LaunchedEffect(pane) {
        if (pane != MediaPane.VIDEO) { controlsVisible = false; controlsFocused = false }
        onImmersiveChange(!browsing)
        if (!browsing) {
            withFrameNanos { }
            videoFocus.requestFocus()
        }
    }
    LaunchedEffect(pane, categories, films) {
        if (browsing) {
            val count = if (pane == MediaPane.CATEGORIES) categories.size else films.size
            if (count > 0) {
                val index = (if (pane == MediaPane.CATEGORIES) categoryIndex else filmIndex).coerceIn(0, count - 1)
                if (pane == MediaPane.CATEGORIES) categoryIndex = index else filmIndex = index
                (if (pane == MediaPane.CATEGORIES) categoryScroll else filmScroll).scrollToItem(index)
                withFrameNanos { }
                runCatching { cardFocus.requestFocus() }
            }
        }
    }
    LaunchedEffect(controlsVisible, controlsFromOk, infoDurationSeconds, pane) {
        if (pane == MediaPane.VIDEO && controlsVisible && !controlsFromOk) {
            val seconds = infoDurationSeconds
            if (seconds == null) controlsVisible = false
            else if (seconds > 0) {
                delay(seconds * 1000L)
                controlsVisible = false
            }
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != 0L) { delay(2500); seekFeedback = 0L }
    }
    fun play(id: String) {
        filmIndex = films.indexOfFirst { it.id == id }.coerceAtLeast(0)
        if (id != state.selectedItemId) component.dispatch(MediaIntent.SelectItem(id))
        pane = MediaPane.VIDEO
    }
    fun back() {
        if (controlsVisible) {
            controlsVisible = false; controlsFromOk = false
            controlsFocused = false
            videoFocus.requestFocus()
            return
        }
        pane = when (pane) {
            MediaPane.INFO -> {
                val target = if (infoFromList) MediaPane.FILMS else MediaPane.VIDEO
                infoFromList = false
                target
            }
            MediaPane.VIDEO -> MediaPane.FILMS
            MediaPane.FILMS -> MediaPane.CATEGORIES
            MediaPane.CATEGORIES -> {
                onBackHome?.invoke()
                MediaPane.CATEGORIES
            }
        }
    }
    val backAction by rememberUpdatedState { back(); Unit }
    DisposableEffect(component) {
        component.setBackHandler { backAction(); true }
        onDispose { component.setBackHandler { false } }
    }
    LaunchedEffect(state.openPlayer, state.selectedItemId, state.isLoading) {
        if (state.openPlayer && !state.isLoading) {
            pane = if (state.selectedItemId != null) MediaPane.VIDEO else MediaPane.CATEGORIES
            component.dispatch(MediaIntent.PlayerShown)
        }
    }
    LaunchedEffect(pauseFeedback) { if (pauseFeedback) { delay(2500); pauseFeedback = false } }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.fillMaxSize().background(com.tvnext.app.core.player.videoSurfaceBackground(MaterialTheme.colorScheme.background))
            .onPreviewKeyEvent { event ->
                if (controlsFocused) return@onPreviewKeyEvent false
                val infoKey = event.key == Key.Info
                if (infoKey) {
                    if (event.type == KeyEventType.KeyUp) {
                        if (browsing && pane == MediaPane.FILMS) {
                            val item = films.getOrNull(filmIndex)
                            if (item != null) {
                                infoFromList = true
                                component.dispatch(MediaIntent.ShowInfo(item.id))
                                pane = MediaPane.INFO
                            }
                        } else if (pane == MediaPane.INFO) {
                            pane = if (infoFromList) MediaPane.FILMS else MediaPane.VIDEO
                            infoFromList = false
                        } else if (pane == MediaPane.VIDEO && selected != null) {
                            infoFromList = false
                            component.dispatch(MediaIntent.ShowInfo(selected.id))
                            pane = MediaPane.INFO
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                val backKey = event.key == Key.Back || event.key == Key.Escape
                val videoKey = !browsing && event.key in listOf(Key.DirectionUp, Key.DirectionDown,
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)
                if (backKey || videoKey) {
                    // Consume both phases, but toggle only on release (held OK must not flap).
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.Back, Key.Escape -> back()
                            Key.DirectionLeft -> pane = MediaPane.FILMS
                            Key.DirectionRight -> {
                                if (pane == MediaPane.VIDEO && !controlsVisible) {
                                    controlsFromOk = false
                                    controlsVisible = infoDurationSeconds != null
                                } else if (pane == MediaPane.VIDEO) {
                                    controlsVisible = false
                                    controlsFromOk = false
                                    pane = MediaPane.INFO
                                    infoFromList = false
                                    selected?.let { component.dispatch(MediaIntent.ShowInfo(it.id)) }
                                } else pane = MediaPane.VIDEO
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                                // Short OK opens the focused transport buttons;
                                // the next OK activates the highlighted button.
                                if (pane == MediaPane.VIDEO) {
                                    controlsFromOk = true
                                    controlsVisible = true
                                }
                            }
                            else -> Unit
                        }
                    } else if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                component.playbackController.seekBy(if (event.key == Key.DirectionUp) 60_000L else -60_000L)
                                seekFeedback = com.tvnext.app.core.common.util.currentTimeMillis()
                            }
                            else -> Unit
                        }
                    }
                    true
                } else false
            }) {
            // Never move the player into a conditional browse/video branch.
            Box(Modifier.fillMaxSize().focusRequester(videoFocus).focusable(enabled = !browsing)) {
                if (selected != null) TvPlayerPanel(
                    controller = component.playbackController,
                    channelName = selected.name,
                    channelNameVisible = state.channelNameVisible,
                    clockPosition = clockPosition,
                    showControls = false,
                    videoFocusable = !browsing,
                    showSeekBar = false,
                    showDiagnostics = showDiagnostics,
                    rounded = false,
                    onRetry = { component.dispatch(MediaIntent.SelectItem(selected.id)) },
                    onClose = { component.dispatch(MediaIntent.ClosePlayer); pane = MediaPane.CATEGORIES },
                    onTogglePlayback = { component.dispatch(MediaIntent.TogglePlayback) },
                    onToggleFullscreen = onToggleFullscreen,
                    onVideoTap = { pane = MediaPane.VIDEO; controlsVisible = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (browsing) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (pane == MediaPane.CATEGORIES) "Медиатека · Категории"
                        else if (favoritesOnly) "Избранное" else state.selectedCategory ?: "Все фильмы",
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (selected != null) TvCardButton(onClick = { pane = MediaPane.VIDEO }) { Text("К просмотру", color = MaterialTheme.colorScheme.onSurface) }
                    TvCardButton(onClick = { component.dispatch(MediaIntent.Refresh) }) { Text("Обновить", color = MaterialTheme.colorScheme.onSurface) }
                }
                if (state.content.sources.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TvCardButton(onClick = {
                        component.dispatch(MediaIntent.SourceSelected(null)); categoryIndex = 0; filmIndex = 0; pane = MediaPane.CATEGORIES
                    }) { Text("Все источники", color = MaterialTheme.colorScheme.onSurface) } }
                    items(state.content.sources, key = { it.id }) { source ->
                        TvCardButton(onClick = {
                            component.dispatch(MediaIntent.SourceSelected(source.id)); categoryIndex = 0; filmIndex = 0; pane = MediaPane.CATEGORIES
                        }) { Text(source.name, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                Text(if (pane == MediaPane.CATEGORIES) "OK — открыть категорию" else "OK — смотреть   ·   Назад — категории   ·   Удерживать OK — избранное",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                if (state.isLoading || state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pane == MediaPane.CATEGORIES) {
                    LazyVerticalGrid(GridCells.Adaptive(220.dp), state = categoryScroll,
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(categories) { index, category ->
                            TvCardButton(onClick = {
                                categoryIndex = index
                                favoritesOnly = index == 1
                                val target = if (index < 2) null else category
                                // The shared reducer toggles categories; do not re-dispatch an unchanged one.
                                if (target != state.selectedCategory) component.dispatch(MediaIntent.CategorySelected(target))
                                filmIndex = 0
                                pane = MediaPane.FILMS
                            }, modifier = Modifier.fillMaxWidth().height(96.dp)
                                .then(if (index == categoryIndex) Modifier.focusRequester(cardFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) categoryIndex = index }) {
                                Text(category, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                } else {
                    if (films.isEmpty()) Text("В этой категории пока нет фильмов", color = MaterialTheme.colorScheme.onSurface)
                    LazyVerticalGrid(GridCells.Adaptive(160.dp), state = filmScroll,
                        modifier = Modifier.weight(1f).focusGroup().onPreviewKeyEvent { event ->
                            val direction = when (event.key) {
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionRight -> FocusDirection.Right
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                else -> null
                            }
                            if (direction != null) {
                                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                                true
                            } else false
                        }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(films, key = { _, item -> item.id }) { index, item ->
                            var okStarted by remember(item.id) { mutableStateOf(0L) }
                            var favoriteHandled by remember(item.id) { mutableStateOf(false) }
                            TvCardButton(onClick = { play(item.id) }, contentPadding = PaddingValues(8.dp),
                                modifier = Modifier.fillMaxWidth().height(280.dp)
                                    .then(if (index == filmIndex) Modifier.focusRequester(cardFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) filmIndex = index }
                                    .onPreviewKeyEvent { event ->
                                        if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                            val now = com.tvnext.app.core.common.util.currentTimeMillis()
                                            if (event.type == KeyEventType.KeyDown && okStarted == 0L) { okStarted = now; favoriteHandled = false }
                                            if (event.type == KeyEventType.KeyUp) {
                                                if (okStarted != 0L && now - okStarted >= 650L) {
                                                    component.dispatch(MediaIntent.FavoriteToggled(item.id)); favoriteHandled = true
                                                }
                                                okStarted = 0L
                                                if (!favoriteHandled) play(item.id)
                                            }
                                            true
                                        } else false
                                    }) {
                                Column(Modifier.fillMaxSize()) {
                                    ChannelLogo(url = if (item.id == selected?.id) state.tmdbMetadata?.posterUrl ?: item.logoUrl else item.logoUrl,
                                        placeholderText = item.name, modifier = Modifier.fillMaxWidth().weight(1f))
                                    Text((if (item.isFavorite) "★ " else "") + item.name, color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
                state.error?.let { Text(it.toUserMessage(), color = MaterialTheme.colorScheme.error) }
            }
            if (pane == MediaPane.VIDEO && (controlsVisible || seekFeedback != 0L)) {
                AndroidPlaybackControls(
                    paused = playback.state == PlaybackState.PAUSED,
                    positionMs = playback.positionMs, durationMs = playback.durationMs,
                    diagnostics = playback.diagnostics,
                    onTogglePlayback = { component.dispatch(MediaIntent.TogglePlayback); pauseFeedback = true },
                    onSeekBy = { delta -> component.playbackController.seekBy(delta) },
                    seekStepMs = seekStepMs,
                    interactive = controlsFromOk,
                    onDetails = {
                        controlsVisible = false; controlsFromOk = false
                        controlsFocused = false; pane = MediaPane.INFO
                        selected?.let { component.dispatch(MediaIntent.ShowInfo(it.id)) }
                    },
                    onClose = {
                        controlsVisible = false; controlsFromOk = false
                        controlsFocused = false; videoFocus.requestFocus()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)).padding(20.dp)
                        .onFocusChanged { controlsFocused = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.Back, Key.Escape -> {
                                    if (event.type == KeyEventType.KeyUp) back()
                                    true
                                }
                                Key.DirectionUp -> {
                                    val wasActive = controlsFromOk
                                    if (event.type == KeyEventType.KeyDown && wasActive) {
                                        controlsVisible = false; controlsFromOk = false
                                        controlsFocused = false
                                        videoFocus.requestFocus()
                                    }
                                    wasActive
                                }
                                else -> false
                            }
                        },
                )
            }
            if (pane == MediaPane.INFO && infoItem != null) Row(Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)).padding(24.dp)) {
                ChannelLogo(url = infoMetadata?.posterUrl ?: infoItem.logoUrl,
                    placeholderText = infoItem.name, modifier = Modifier.width(180.dp).height(270.dp))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                Text(infoMetadata?.title ?: infoItem.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(infoMetadata?.releaseDate, infoMetadata?.genres?.joinToString(" · ")?.takeIf { it.isNotBlank() },
                    infoMetadata?.runtimeMinutes?.let { "$it мин" }).joinToString("   ·   "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                AutoScrollText(text = infoMetadata?.overview?.takeIf { it.isNotBlank() }
                    ?: if (infoLoading) "Загрузка описания…" else "Описание недоступно",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 7,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text("Info / → — закрыть описание   ·   Назад — назад", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
