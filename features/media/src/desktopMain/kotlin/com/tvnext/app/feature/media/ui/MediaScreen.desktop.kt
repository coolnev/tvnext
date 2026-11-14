package com.tvnext.app.feature.media.ui

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.player.PlaybackState
import com.tvnext.app.core.ui.component.AutoScrollText
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvPlayerPanel
import com.tvnext.app.core.ui.component.TvPlayPauseIcon
import com.tvnext.app.core.ui.component.PlayerSeekBar
import com.tvnext.app.core.ui.component.TvSearchField
import com.tvnext.app.core.ui.component.BackArrowIcon
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.component.TvListState
import com.tvnext.app.core.ui.component.hoverEnterExit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.theme.TVNextDimens
import com.tvnext.app.feature.media.domain.FeaturedMediaItem
import com.tvnext.app.feature.media.domain.category
import com.tvnext.app.feature.media.domain.durationSeconds
import com.tvnext.app.feature.media.domain.isFavorite
import com.tvnext.app.feature.media.domain.logoUrl
import com.tvnext.app.feature.media.domain.name

@Composable
actual fun MediaScreen(
    component: MediaComponent,
    modifier: Modifier,
    clockPosition: ClockPosition,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onBackHome: (() -> Unit)?,
    showDiagnostics: Boolean,
    infoDurationSeconds: Int?,
    onToggleDiagnostics: () -> Unit,
) {
    val state by component.state.collectAsState()
    MediaResumeDialog(component,state.pendingResume)
    var searchFocused by remember { mutableStateOf(false) }
    var infoDialogVisible by remember { mutableStateOf(false) }
    val seekStepMs = component.seekStepMs.collectAsState().value
    val focusRequester = remember { FocusRequester() }

    // The source can hold thousands of VOD items. `filteredItems`/`categories`
    // walk the whole list, so they must be recomputed only when their inputs
    // change (NOT on every recomposition — e.g. the name-overlay timer or the
    // clock tick would otherwise re-scan all items each frame).
    val allItemsById = remember(state.content) { state.content.allItems.associateBy { it.id } }
    val selectedItem = state.selectedItemId?.let { allItemsById[it] }
    val filteredItems = remember(
        state.content,
        state.query,
        state.selectedCategory,
        state.selectedSourceId,
    ) { state.filteredItems }
    val categories = remember(
        state.content,
        state.selectedSourceId,
    ) { state.categories }

    LaunchedEffect(Unit) {
        // The component loads itself on creation (see MediaComponent.init) —
        // do NOT dispatch Load here: the screen is recreated on every
        // fullscreen F11 window switch and would re-run the VOD scans.
        runCatching { focusRequester.requestFocus() }
    }

    // Android TV: auto-enter the full-screen (immersive) surface when a movie
    // STARTS playing (the auto-open on tab entry / app start). Manual movie
    // switches inside the browse layout keep the user in browse mode — the
    // remote Up/Down + OK surfing flow must not yank the screen back to
    // full-screen after every selection.
    var wasWatchingItem by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedItemId) {
        if (isAndroidPlatform()) {
            val watching = state.selectedItemId != null
            if (watching && !wasWatchingItem && !immersive) {
                onImmersiveChange(true)
            }
            wasWatchingItem = watching
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // In fullscreen/immersive there is no app chrome — the video must
            // fill the whole window edge to edge (no white frame).
            .then(if (immersive) Modifier else Modifier.padding(TVNextDimens.ScreenPadding))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val playing = state.selectedItemId != null
                if (event.key == Key.Info) {
                    if (event.type == KeyEventType.KeyUp) {
                        val itemId = state.selectedItemId
                        if (itemId != null) {
                            component.dispatch(MediaIntent.ShowInfo(itemId))
                            infoDialogVisible = !infoDialogVisible
                        }
                    }
                    true
                } else if (event.key == Key.I) {
                    if (event.type == KeyEventType.KeyUp) {
                        val itemId = state.selectedItemId
                        if (itemId != null) {
                            component.dispatch(MediaIntent.ShowInfo(itemId))
                            infoDialogVisible = !infoDialogVisible
                        }
                    }
                    true
                } else if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (isImmersiveToggle(event) && !searchFocused) {
                    onImmersiveChange(!immersive)
                    true
                } else if (event.key == Key.Spacebar && playing) {
                    component.dispatch(MediaIntent.TogglePlayback)
                    true
                } else if ((event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                    playing && !(isAndroidPlatform() && immersive)
                ) {
                    val step = when {
                        event.isCtrlPressed -> 30 * 60_000L
                        event.isShiftPressed -> 5_000L
                        else -> seekStepMs
                    }
                    val delta = if (event.key == Key.DirectionRight) step else -step
                    component.playbackController.seekBy(delta)
                    true
                } else {
                    false
                }
            },
    ) {
        // Android TV: no screen header — the remote's Back button leaves the
        // screen and the space is used for the list/video (dense 4K layout).
        if (!immersive && !isAndroidPlatform()) {
            ScreenHeader(
                title = uiText("Медиатека"),
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


        if (selectedItem != null) {
            WatchingLayout(
                seekStepMs = seekStepMs,
                state = state,
                selectedItem = selectedItem,
                tmdbMetadata = state.tmdbMetadata,
                tmdbLoading = state.tmdbLoading,
                controller = component.playbackController,
                clockPosition = clockPosition,
                immersive = immersive,
                onToggleImmersive = { onImmersiveChange(!immersive) },
                onToggleFullscreen = onToggleFullscreen,
                onSearch = { component.dispatch(MediaIntent.SearchChanged(it)) },
                onSearchFocusChanged = { searchFocused = it },
                onSourceSelected = { component.dispatch(MediaIntent.SourceSelected(it)) },
                onCategorySelected = { component.dispatch(MediaIntent.CategorySelected(it)) },
                onSelectItem = { component.dispatch(MediaIntent.SelectItem(it)) },
                onToggleFavorite = { component.dispatch(MediaIntent.FavoriteToggled(it)) },
                onBackHome = onBackHome,
                onClosePlayer = { component.dispatch(MediaIntent.ClosePlayer) },
                onTogglePlayback = { component.dispatch(MediaIntent.TogglePlayback) },
                showDiagnostics = showDiagnostics,
                modifier = Modifier.weight(1f),
            )
        } else {
            RegularLayout(
                state = state,
                onSearch = { component.dispatch(MediaIntent.SearchChanged(it)) },
                onSearchFocusChanged = { searchFocused = it },
                onSourceSelected = { component.dispatch(MediaIntent.SourceSelected(it)) },
                onCategorySelected = { component.dispatch(MediaIntent.CategorySelected(it)) },
                onSelectItem = { component.dispatch(MediaIntent.SelectItem(it)) },
                onToggleFavorite = { component.dispatch(MediaIntent.FavoriteToggled(it)) },
                onRefresh = { component.dispatch(MediaIntent.Refresh) },
                modifier = Modifier.weight(1f),
            )
        }

        if (infoDialogVisible) {
            val infoItem = state.infoItemId?.let { id -> allItemsById[id] }
                ?: selectedItem
            if (infoItem != null) {
                AlertDialog(
                    onDismissRequest = { infoDialogVisible = false },
                    title = { Text(state.infoTmdbMetadata?.title ?: infoItem.name) },
                    text = {
                        val metadata = state.infoTmdbMetadata
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(metadata?.genres?.joinToString(" · ").orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                metadata?.overview?.takeIf { it.isNotBlank() }
                                    ?: if (state.infoTmdbLoading) uiText("Загрузка описания…") else uiText("Описание недоступно."),
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { infoDialogVisible = false }) { Text(uiText("Закрыть")) } },
                )
            } else {
                infoDialogVisible = false
            }
        }

        state.error?.let { error ->
            Text(
                text = error.toUserMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall),
            )
        }
    }
}

@Composable
private fun WatchingLayout(
    seekStepMs: Long,
    state: MediaState,
    selectedItem: MediaItem,
    tmdbMetadata: com.tvnext.app.core.tmdb.TmdbMetadata?,
    tmdbLoading: Boolean,
    controller: com.tvnext.app.core.player.PlaybackController,
    clockPosition: ClockPosition,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSourceSelected: (String?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onSelectItem: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onBackHome: (() -> Unit)? = null,
    onClosePlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    showDiagnostics: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var edgeHovered by remember { mutableStateOf(false) }
    var drawerHovered by remember { mutableStateOf(false) }
    // True while the video area holds focus (the OUTER watching container OR the
    // player's own video surface): used to decide whether OK/Enter toggles the
    // full-screen <-> browse modes on Android TV. When a list row is focused the
    // key is NOT intercepted — OK selects the movie instead.
    var videoFocused by remember { mutableStateOf(false) }
    // Full-screen movie info overlay (Android TV, RIGHT arrow): title,
    // description, category + an interactive full-width seek bar.
    var rightInfoVisible by remember { mutableStateOf(false) }
    val hoverScope = rememberCoroutineScope()
    var drawerCloseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val videoFocusRequester = remember { FocusRequester() }
    // Scroll state of the library list lives HERE (in the stable watching
    // surface) and is SHARED by the non-immersive side panel and the immersive
    // drawer: toggling immersive (L) must not reset the scroll position, so the
    // currently playing item stays visible in both views. Both live in
    // TvListState (process lifetime) so F11 / double-click fullscreen does not
    // reset them and the list does not jump back to the top.
    val mediaListState = if (isAndroidPlatform()) rememberLazyListState() else TvListState.mediaListState
    // The expanded category is shared the same way, so switching between the
    // panel and the drawer never rebuilds the list at a different category.
    val mediaListExpandedCategory = TvListState.mediaExpandedCategory.value

    fun openDrawer() {
        drawerCloseJob?.cancel()
        drawerOpen = true
    }

    fun scheduleDrawerCloseIfAway() {
        if (edgeHovered || drawerHovered) return
        drawerCloseJob?.cancel()
        drawerCloseJob = hoverScope.launch {
            delay(2000)
            if (!edgeHovered && !drawerHovered) drawerOpen = false
        }
    }

    // Android TV: when the surface becomes immersive (auto on movie select) the
    // library list/search are not composed — focus the video area so OK toggles
    // to the browse layout, LEFT slides the drawer in, RIGHT shows the info.
    // When it becomes NON-immersive (browse), focus the currently playing
    // movie's row in the library list (so a repeated OK returns to full-screen).
    val browseListFocusRequester = remember { FocusRequester() }
    LaunchedEffect(immersive) {
        if (isAndroidPlatform()) {
            if (immersive) {
                rightInfoVisible = false
                delay(60)
                runCatching { videoFocusRequester.requestFocus() }
            } else {
                runCatching { browseListFocusRequester.requestFocus() }
            }
        }
    }

    // If the video focus is dropped while immersive (surface recreation during
    // a movie switch, overlay redraw, …) re-claim it, so the remote works
    // without a preliminary OK press. The open drawer keeps its own focus.
    LaunchedEffect(videoFocused) {
        if (isAndroidPlatform() && immersive && !videoFocused && !drawerOpen) {
            delay(60)
            runCatching { videoFocusRequester.requestFocus() }
        }
    }

    // Keep the player in one stable composition branch. The immersive drawer is
    // an overlay on top of it, so opening/closing the drawer never recreates the
    // player. LEFT/RIGHT in full-screen mode are handled HERE (drawer / info),
    // so the screen-level seek handler must let them through while immersive.
    Row(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(videoFocusRequester)
            .then(if (isAndroidPlatform()) Modifier.onFocusChanged { videoFocused = it.isFocused }.focusable()
                else Modifier.focusable().onFocusChanged { videoFocused = it.isFocused })
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        // Desktop/Android: I opens the same VOD information overlay.
                        // It never changes playback or seeking behaviour.
                        videoFocused && !drawerOpen && event.key == Key.I -> {
                            rightInfoVisible = !rightInfoVisible
                            true
                        }
                        // Android TV full-screen (immersive):
                        //  - OK/Enter toggles to the "browse" layout (video
                        //    shrinks, library list on the left, info below);
                        //  - LEFT slides the library drawer in over the video;
                        //  - RIGHT toggles the movie info overlay;
                        //  - UP/DOWN browses to the previous/next movie.
                        // These fire ONLY while the video area holds focus: when
                        // a list row (drawer or browse list) is focused the key
                        // is left to the row's own OK handler.
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            rightInfoVisible = false
                            onToggleImmersive()
                            true
                        }
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            event.key == Key.DirectionLeft -> {
                            rightInfoVisible = false
                            openDrawer()
                            true
                        }
                        isAndroidPlatform() && immersive && !drawerOpen &&
                            event.key == Key.DirectionRight -> {
                            rightInfoVisible = !rightInfoVisible
                            true
                        }
                        // Android TV: RIGHT closes the open drawer back to the video.
                        isAndroidPlatform() && immersive && drawerOpen &&
                            event.key == Key.DirectionRight -> {
                            drawerCloseJob?.cancel()
                            drawerOpen = false
                            runCatching { videoFocusRequester.requestFocus() }
                            true
                        }
                        // Android TV "browse" layout (reduced): OK on the video
                        // returns to the full-screen video (a repeated OK).
                        isAndroidPlatform() && !immersive && videoFocused &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            onToggleImmersive()
                            true
                        }
                        // Android TV: UP/DOWN while the video holds focus browses
                        // to the previous/next movie in the library.
                        isAndroidPlatform() && videoFocused &&
                            (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                            val items = state.filteredItems
                            val index = items.indexOfFirst { it.id == selectedItem.id }
                            if (index >= 0) {
                                val target = if (event.key == Key.DirectionUp) index - 1 else index + 1
                                if (target in items.indices) onSelectItem(items[target].id)
                            }
                            true
                        }
                        // Space toggles play/pause while watching a VOD item.
                        event.key == Key.Spacebar -> {
                            onTogglePlayback()
                            true
                        }
                        else -> false
                    }
                } else false
            },
    ) {
        if (!immersive) {
            // Android TV browse layout: OK on the currently playing movie row
            // returns to the full-screen video (the "repeated OK"); OK on
            // another movie plays it.
            val onBrowseSelectItem: (String) -> Unit = { id ->
                if (isAndroidPlatform() && !immersive && id == selectedItem.id) {
                    onToggleImmersive()
                } else {
                    onSelectItem(id)
                }
            }
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight(),
            ) {
                // Android TV: no in-list search (dedicated Search screen).
                if (!isAndroidPlatform()) {
                    TvSearchField(
                        value = state.query,
                        onValueChange = onSearch,
                        onClear = { onSearch("") },
                        placeholder = uiText("Поиск в медиатеке"),
                        onFocusChanged = onSearchFocusChanged,
                    )
                }
                Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
                if (state.content.sources.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                        modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
                    ) {
                        item {
                            FilterChip(
                                label = uiText("Все источники"),
                                selected = state.selectedSourceId == null,
                                onClick = { onSourceSelected(null) },
                            )
                        }
                        items(state.content.sources, key = { it.id }) { source ->
                            FilterChip(
                                label = source.name,
                                selected = state.selectedSourceId == source.id,
                                onClick = { onSourceSelected(source.id) },
                            )
                        }
                    }
                }
                val visibleItems = remember(state.content, state.selectedSourceId) {
                    val sourceIds = state.visibleSources.map { it.id }.toSet()
                    state.content.allItems.filter { it.playlistId in sourceIds }
                }
                if (state.query.isBlank()) {
                    MediaLibraryList(
                        items = visibleItems,
                        categories = state.categories,
                        selectedItemId = selectedItem.id,
                        onSelectItem = onBrowseSelectItem,
                        onToggleFavorite = onToggleFavorite,
                        listState = mediaListState,
                        expandedCategory = mediaListExpandedCategory,
                        onCategoryToggle = { key ->
                            TvListState.mediaExpandedCategory.value =
                                if (TvListState.mediaExpandedCategory.value == key) null else key
                        },
                        focusItemId = if (isAndroidPlatform()) selectedItem.id else null,
                        firstItemFocusRequester = browseListFocusRequester,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val filteredItems = remember(
                        state.content, state.query, state.selectedCategory, state.selectedSourceId
                    ) { state.filteredItems }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            MediaRow(
                                item = item,
                                selected = item.id == selectedItem.id,
                                onClick = { onBrowseSelectItem(item.id) },
                                onToggleFavorite = { onToggleFavorite(item.id) },
                            )
                        }
                        if (filteredItems.isEmpty()) {
                            item {
                                Text(
                                    text = uiText("Ничего не найдено"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = TVNextDimens.SectionSpacing),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
        }

        // The video area itself remains stable while the immersive library slides
        // over it. This is the key to avoiding a player/surface recreation.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
            TvPlayerPanel(
                controller = controller,
                channelName = selectedItem.name,
                channelNameVisible = state.channelNameVisible,
                clockPosition = clockPosition,
                showControls = !immersive,
                showSeekBar = !immersive,
                seekStepMs = seekStepMs,
                showDiagnostics = showDiagnostics,
                rounded = !immersive,
                onVideoFocusedChange = { videoFocused = it },
                onRetry = { onSelectItem(selectedItem.id) },
                onClose = onClosePlayer,
                onTogglePlayback = onTogglePlayback,
                onToggleFullscreen = onToggleFullscreen,
                channelFavorite = selectedItem.isFavorite,
                onToggleFavorite = { onToggleFavorite(selectedItem.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            )

            if (immersive) {
                // Hover zone on the left edge: the library drawer slides in when
                // the mouse approaches the left side and hides ~2 s after the
                // pointer leaves it (no floating handle / close button).
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(28.dp)
                        .fillMaxHeight()
                        .zIndex(5f)
                        .hoverEnterExit(
                            enter = {
                                edgeHovered = true
                                openDrawer()
                            },
                            exit = {
                                edgeHovered = false
                                scheduleDrawerCloseIfAway()
                            },
                        ),
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = drawerOpen,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .zIndex(10f)
                        .hoverEnterExit(
                            enter = {
                                drawerHovered = true
                                openDrawer()
                            },
                            exit = {
                                drawerHovered = false
                                scheduleDrawerCloseIfAway()
                            },
                        ),
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                    ) + fadeIn(),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                    ) + fadeOut(),
                ) {
                    MediaImmersiveDrawer(
                        state = state,
                        selectedItemId = selectedItem.id,
                        listState = mediaListState,
                        expandedCategory = mediaListExpandedCategory,
                        onCategoryToggle = { key ->
                            TvListState.mediaExpandedCategory.value =
                                if (TvListState.mediaExpandedCategory.value == key) null else key
                        },
                        onSearch = onSearch,
                        onSearchFocusChanged = onSearchFocusChanged,
                        onSourceSelected = onSourceSelected,
                        onSelectItem = { id ->
                            drawerOpen = false
                            // The already-playing movie is not re-selected:
                            // clicking its row only closes the drawer.
                            if (id != selectedItem.id) onSelectItem(id)
                        },
                        onToggleFavorite = onToggleFavorite,
                        onBackHome = onBackHome,
                    )
                }

                // Full-screen movie info overlay (Android TV, RIGHT arrow):
                // poster area + title/description/category + an interactive
                // full-width seek bar over the whole video width.
                if (rightInfoVisible) {
                    MovieInfoOverlay(
                        item = selectedItem,
                        tmdbMetadata = tmdbMetadata,
                        controller = controller,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .zIndex(8f),
                    )
                }
            }
            }
            // Browse layout (reduced window + library list on the left): the TMDB
            // description strip sits UNDER the video and follows the selection —
            // flipping through the movies updates it via the per-item enrich.
            if (!immersive) {
                TmdbInfoPanel(
                    item = selectedItem,
                    tmdbMetadata = tmdbMetadata,
                    loading = tmdbLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MediaImmersiveDrawer(
    state: MediaState,
    selectedItemId: String,
    listState: LazyListState,
    expandedCategory: String?,
    onCategoryToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSourceSelected: (String?) -> Unit,
    onSelectItem: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onBackHome: (() -> Unit)? = null,
) {
    val visibleItems = remember(state.content, state.selectedSourceId) {
        val sourceIds = state.visibleSources.map { it.id }.toSet()
        state.content.allItems.filter { it.playlistId in sourceIds }
    }
    val filteredItems = remember(
        state.content, state.query, state.selectedCategory, state.selectedSourceId
    ) { state.filteredItems }

    // Android TV: when the drawer opens, focus the first library row — not the
    // search field (which would pop the on-screen keyboard over the video).
    val firstItemFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isAndroidPlatform() && (visibleItems.isNotEmpty() || filteredItems.isNotEmpty())) {
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .width(410.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
            )
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBackHome != null) {
                TvCardButton(
                    onClick = onBackHome,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BackArrowIcon(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("Медиатека"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = uiText("{0} фильмов и передач", visibleItems.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Android TV: no in-list search (dedicated Search screen).
        if (!isAndroidPlatform()) {
            TvSearchField(
                value = state.query,
                onValueChange = onSearch,
                onClear = { onSearch("") },
                placeholder = uiText("Поиск"),
                onFocusChanged = onSearchFocusChanged,
            )
        }
        if (state.content.sources.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        label = uiText("Все"),
                        selected = state.selectedSourceId == null,
                        onClick = { onSourceSelected(null) },
                    )
                }
                items(state.content.sources, key = { it.id }) { source ->
                    FilterChip(
                        label = source.name,
                        selected = state.selectedSourceId == source.id,
                        onClick = { onSourceSelected(source.id) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (state.query.isBlank()) {
                    MediaLibraryList(
                        items = visibleItems,
                        categories = state.categories,
                        selectedItemId = selectedItemId,
                        onSelectItem = onSelectItem,
                        onToggleFavorite = onToggleFavorite,
                        expandedCategory = expandedCategory,
                        onCategoryToggle = onCategoryToggle,
                        modifier = Modifier.fillMaxSize(),
                        firstItemFocusRequester = if (isAndroidPlatform()) {
                            firstItemFocusRequester
                        } else {
                            null
                        },
                        listState = listState,
                    )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(filteredItems, key = { _, item -> item.id }) { index, item ->
                        MediaRow(
                            item = item,
                            selected = item.id == selectedItemId,
                            onClick = { onSelectItem(item.id) },
                            onToggleFavorite = { onToggleFavorite(item.id) },
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegularLayout(
    state: MediaState,
    onSearch: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSourceSelected: (String?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onSelectItem: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cache the list scans: thousands of VOD items must not be re-filtered on
    // every recomposition (name-overlay timer, clock tick, ...).
    val filteredItems = remember(
        state.content,
        state.query,
        state.selectedCategory,
        state.selectedSourceId,
    ) { state.filteredItems }
    val categories = remember(
        state.content,
        state.selectedSourceId,
    ) { state.categories }

    // Collapsible category list: expanded category ("" = «Все» by default).
    var expandedCategory by remember { mutableStateOf<String?>(ALL_CATEGORY_KEY) }
    val visibleItems = remember(state.content, state.selectedSourceId) {
        val sourceIds = state.visibleSources.map { it.id }.toSet()
        state.content.allItems.filter { it.playlistId in sourceIds }
    }

    // Android TV: focus the FIRST content element (continue watching / recent /
    // favorites / first row) instead of the search field, which would pop the
    // on-screen keyboard the moment the library tab opens.
    val firstItemFocusRequester = remember { FocusRequester() }
    val firstFocusTarget = remember(
        state.content,
        state.query,
        state.selectedCategory,
        state.selectedSourceId,
    ) {
        if (state.query.isNotBlank()) {
            "item:${state.filteredItems.firstOrNull()?.id}"
        } else {
            when {
                state.content.continueWatching != null -> "continue"
                state.content.recent.isNotEmpty() -> "recent:${state.content.recent.first().item.id}"
                state.content.favorites.isNotEmpty() -> "favorites:${state.content.favorites.first().item.id}"
                else -> "list"
            }
        }
    }
    LaunchedEffect(firstFocusTarget) {
        if (isAndroidPlatform()) {
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    // Precomputed once per content/category change: the "list" fallback focus
    // target is the first media row of the main (query-blank) list. Must be
    // computed in @Composable scope — LazyListScope is not a composable context.
    val queryBlankEntries = remember(visibleItems, categories, expandedCategory) {
        buildMediaEntries(visibleItems, categories, expandedCategory)
    }
    val firstQueryBlankEntryIndex = remember(queryBlankEntries) {
        queryBlankEntries.indexOfFirst { it is MediaItemEntry }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            state.isLoading && state.content.allItems.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.content.allItems.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (state.error != null) {
                            state.error.toUserMessage()
                        } else {
                            uiText("Медиатека пуста. Добавьте M3U-источник VOD в настройках.")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
                    )
                    TvCardButton(
                        onClick = onRefresh,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(
                            horizontal = TVNextDimens.ItemSpacing,
                            vertical = TVNextDimens.ItemSpacingSmall,
                        ),
                    ) {
                        Text(
                            text = uiText("Обновить"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            else -> {
                // Virtualized list: large VOD sources (thousands of items) must
                // not be composed eagerly — that froze the UI on tab entry.
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "filters") {
                        MediaFilters(
                            state = state,
                            onSearch = onSearch,
                            onSearchFocusChanged = onSearchFocusChanged,
                            onSourceSelected = onSourceSelected,
                        )
                    }

                    state.content.continueWatching?.let { featured ->
                        item(key = "continue") {
                            ContinueWatchingMediaCard(
                                featured = featured,
                                onClick = { onSelectItem(featured.item.id) },
                                modifier = if (isAndroidPlatform() && firstFocusTarget == "continue") {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    if (state.content.recent.isNotEmpty()) {
                        item(key = "recent") {
                            MediaSection(title = uiText("Недавно просмотренные")) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacing)) {
                                    itemsIndexed(state.content.recent, key = { _, featured -> featured.item.id }) { index, featured ->
                                        MediaCard(
                                            featured = featured,
                                            onClick = { onSelectItem(featured.item.id) },
                                            onToggleFavorite = { onToggleFavorite(featured.item.id) },
                                            modifier = if (
                                                isAndroidPlatform() &&
                                                firstFocusTarget == "recent:${featured.item.id}"
                                            ) {
                                                Modifier.focusRequester(firstItemFocusRequester)
                                            } else {
                                                Modifier
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.content.favorites.isNotEmpty()) {
                        item(key = "favorites") {
                            MediaSection(title = uiText("Избранное")) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacing)) {
                                    itemsIndexed(state.content.favorites, key = { _, featured -> featured.item.id }) { index, featured ->
                                        MediaCard(
                                            featured = featured,
                                            onClick = { onSelectItem(featured.item.id) },
                                            onToggleFavorite = { onToggleFavorite(featured.item.id) },
                                            modifier = if (
                                                isAndroidPlatform() &&
                                                firstFocusTarget == "favorites:${featured.item.id}"
                                            ) {
                                                Modifier.focusRequester(firstItemFocusRequester)
                                            } else {
                                                Modifier
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.query.isBlank()) {
                        val entries = queryBlankEntries
                        itemsIndexed(
                            entries,
                            key = { _, entry ->
                                if (entry is MediaItemEntry) "mi:${entry.item.id}" else "cat:${(entry as MediaCategoryEntry).key}"
                            },
                        ) { index, entry ->
                            when (entry) {
                                is MediaCategoryEntry -> MediaCategoryRow(
                                    label = entry.title,
                                    count = entry.count,
                                    selected = expandedCategory == entry.key,
                                    onClick = {
                                        expandedCategory = if (expandedCategory == entry.key) null else entry.key
                                    },
                                )

                                is MediaItemEntry -> MediaRow(
                                    item = entry.item,
                                    selected = false,
                                    onClick = { onSelectItem(entry.item.id) },
                                    onToggleFavorite = { onToggleFavorite(entry.item.id) },
                                    modifier = if (
                                        isAndroidPlatform() &&
                                        firstFocusTarget == "list" &&
                                        index == firstQueryBlankEntryIndex
                                    ) {
                                        Modifier.focusRequester(firstItemFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    } else {
                        itemsIndexed(filteredItems, key = { _, item -> item.id }) { index, item ->
                            MediaRow(
                                item = item,
                                selected = false,
                                onClick = { onSelectItem(item.id) },
                                onToggleFavorite = { onToggleFavorite(item.id) },
                                modifier = if (
                                    isAndroidPlatform() &&
                                    firstFocusTarget == "item:${item.id}"
                                ) {
                                    Modifier.focusRequester(firstItemFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                        if (filteredItems.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = uiText("Ничего не найдено"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaFilters(
    state: MediaState,
    onSearch: (String) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSourceSelected: (String?) -> Unit,
) {
    // Android TV: no in-list search (dedicated Search screen).
    if (!isAndroidPlatform()) {
        TvSearchField(
            value = state.query,
            onValueChange = onSearch,
            onClear = { onSearch("") },
            placeholder = uiText("Поиск в медиатеке"),
            onFocusChanged = onSearchFocusChanged,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        )
    }
    if (state.content.sources.size > 1) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacingSmall),
        ) {
            item {
                FilterChip(
                    label = uiText("Все источники"),
                    selected = state.selectedSourceId == null,
                    onClick = { onSourceSelected(null) },
                )
            }
            items(state.content.sources, key = { it.id }) { source ->
                FilterChip(
                    label = source.name,
                    selected = state.selectedSourceId == source.id,
                    onClick = { onSourceSelected(source.id) },
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacing,
            vertical = TVNextDimens.ItemSpacingSmall,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}
/** Compact vertical category row (Media Library watching layout). */
@Composable
private fun MediaCategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        contentPadding = PaddingValues(
            horizontal = TVNextDimens.ItemSpacingSmall,
            vertical = 2.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selected) "▾" else "▸",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private sealed interface MediaListEntry
private class MediaCategoryEntry(val key: String, val title: String, val count: Int) : MediaListEntry
private class MediaItemEntry(val item: MediaItem) : MediaListEntry

private const val ALL_CATEGORY_KEY = ""
private const val FAVORITES_KEY = "__favorites"

private fun buildMediaEntries(
    items: List<MediaItem>,
    categories: List<String>,
    expandedCategory: String?,
): List<MediaListEntry> {
    val byCategory = items.groupBy { it.category }
    val favorites = items.filter { it.isFavorite }
    return buildList {
        // «Избранное» sits on top of every category, exactly like the
        // Channels tab — a collapsible header followed by the favorite rows.
        add(MediaCategoryEntry(FAVORITES_KEY, uiText("Избранное"), favorites.size))
        if (expandedCategory == FAVORITES_KEY) {
            favorites.forEach { add(MediaItemEntry(it)) }
        }
        add(MediaCategoryEntry(ALL_CATEGORY_KEY, uiText("Все"), items.size))
        if (expandedCategory == ALL_CATEGORY_KEY) {
            items.forEach { add(MediaItemEntry(it)) }
        }
        categories.forEach { category ->
            val categoryItems = byCategory[category].orEmpty()
            add(MediaCategoryEntry(category, category, categoryItems.size))
            if (expandedCategory == category) {
                categoryItems.forEach { add(MediaItemEntry(it)) }
            }
        }
    }
}

/**
 * The Media Library list with collapsible categories (like the Channels list):
 * clicking a category header expands/collapses its films IN PLACE, instead of
 * dumping the whole film list after all the categories.
 */
@Composable
private fun MediaLibraryList(
    items: List<MediaItem>,
    categories: List<String>,
    selectedItemId: String?,
    onSelectItem: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    expandedCategory: String?,
    onCategoryToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    listState: LazyListState? = null,
    /**
     * When set (Android TV browse layout), the row of this item is focused on
     * mount instead of the first row: a repeated OK on the currently playing
     * movie row returns to the full-screen video.
     */
    focusItemId: String? = null,
) {
    val entries = remember(items, categories, expandedCategory) {
        buildMediaEntries(items, categories, expandedCategory)
    }
    val focusRowIndex = remember(entries, focusItemId) {
        if (focusItemId != null) {
            entries.indexOfFirst { it is MediaItemEntry && it.item.id == focusItemId }
                .takeIf { it >= 0 }
                ?: entries.indexOfFirst { it is MediaItemEntry }
        } else {
            entries.indexOfFirst { it is MediaItemEntry }
        }
    }
    // A caller-provided state (the watching surface's drawer state) survives
    // panel open/close cycles, so the list stays where the user left it.
    val resolvedListState = listState ?: rememberLazyListState()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
        state = resolvedListState,
        modifier = modifier,
    ) {
        itemsIndexed(
            entries,
            key = { _, entry ->
                if (entry is MediaItemEntry) "mi:${entry.item.id}" else "cat:${(entry as MediaCategoryEntry).key}"
            },
        ) { index, entry ->
            when (entry) {
                is MediaCategoryEntry -> MediaCategoryRow(
                    label = entry.title,
                    count = entry.count,
                    selected = expandedCategory == entry.key,
                    onClick = {
                        // Toggle is resolved in the state owner (which reads the
                        // CURRENT value), so a stale composition snapshot can
                        // never make the same click a no-op.
                        onCategoryToggle(entry.key)
                    },
                )

                is MediaItemEntry -> MediaRow(
                    item = entry.item,
                    selected = entry.item.id == selectedItemId,
                    onClick = { onSelectItem(entry.item.id) },
                    onToggleFavorite = { onToggleFavorite(entry.item.id) },
                    modifier = if (firstItemFocusRequester != null && index == focusRowIndex) {
                        Modifier.focusRequester(firstItemFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/** Items in a category, taking the selected source into account. */
@Composable
private fun ContinueWatchingMediaCard(
    featured: FeaturedMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCardButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = TVNextDimens.SectionSpacing),
        contentPadding = PaddingValues(TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = featured.logoUrl,
                placeholderText = featured.name,
                modifier = Modifier.size(TVNextDimens.IconSizeLarge * 2),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("Продолжить просмотр"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = featured.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = featured.category + durationLabel(featured.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (featured.resumePositionMs > 0L) {
                    Text(
                        text = uiText("Позиция: {0}", formatDuration(featured.resumePositionMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = TVNextDimens.SectionSpacing)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
        )
        content()
    }
}

@Composable
private fun MediaCard(
    featured: FeaturedMediaItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvCardButton(
        onClick = onClick,
        modifier = modifier
            .width(TVNextDimens.HomeCardWidth)
            .height(TVNextDimens.HomeCardHeight + 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(TVNextDimens.ScreenPaddingCompact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster: large, on the left.
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ChannelLogo(
                    url = featured.logoUrl,
                    placeholderText = featured.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = featured.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (featured.isFavorite) {
                        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                        Text(
                            text = "★",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = featured.category + durationLabel(featured.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                seasonEpisodeLabel(featured.item)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvCardButton(
            onClick = onClick,
            selected = selected,
            // The requester belongs on the focusable card itself. Previously it
            // was attached to the wrapping Row, so Android TV could not move
            // focus to the requested movie row reliably.
            modifier = modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(
                    url = item.logoUrl,
                    placeholderText = item.name,
                    // Android TV: larger posters in the dense 4K list — there is
                    // room in the 72dp row and the text column is untouched.
                    modifier = Modifier.size(if (isAndroidPlatform()) 60.dp else 40.dp),
                )
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.category + durationLabel(item.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    seasonEpisodeLabel(item)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
        TvCardButton(
            onClick = onToggleFavorite,
            selected = item.isFavorite,
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
            shape = RoundedCornerShape(TVNextDimens.CardCornerRadius),
            contentPadding = PaddingValues(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (item.isFavorite) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun MediaItemInfo(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(top = TVNextDimens.ItemSpacingSmall)
            .clip(RoundedCornerShape(TVNextDimens.CardCornerRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.category + durationLabel(item.durationSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        seasonEpisodeLabel(item)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun seasonEpisodeLabel(item: MediaItem): String? {
    val season = item.season?.takeIf { it.isNotBlank() }
    val episode = item.episode?.takeIf { it.isNotBlank() }
    return when {
        season != null && episode != null -> uiText("Сезон {0} · Серия {1}", season, episode)
        season != null -> uiText("Сезон {0}", season)
        episode != null -> uiText("Серия {0}", episode)
        else -> null
    }
}

/**
 * Full-screen movie info overlay (Android TV, RIGHT arrow): the movie logo +
 * name/category/duration on the left and an interactive FULL-WIDTH seek bar at
 * the bottom (Android TV has no volume engine, so the bar spans the whole
 * video). Translucent, matching the channel drawer and info overlay styles.
 */
@Composable
private fun MovieInfoOverlay(
    item: MediaItem,
    tmdbMetadata: com.tvnext.app.core.tmdb.TmdbMetadata?,
    controller: com.tvnext.app.core.player.PlaybackController,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val playerState by controller.state.collectAsState()
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = shape,
            )
            .padding(TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Large TMDB poster: takes advantage of the free vertical space in
            // the overlay without affecting the text column to the right. A
            // missing poster renders as empty space (no grey placeholder box).
            ChannelLogo(
                url = tmdbMetadata?.posterUrl ?: item.logoUrl,
                placeholderText = tmdbMetadata?.title ?: item.name,
                // Large TMDB poster: takes advantage of the free vertical space
                // in the overlay without affecting the text column. A missing
                // poster renders as empty space (no grey placeholder box).
                modifier = Modifier.size(200.dp),
                showPlaceholder = false,
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tmdbMetadata?.title ?: item.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        tmdbMetadata?.releaseDate?.take(4)?.let { append(it) }
                        tmdbMetadata?.voteAverage?.takeIf { it > 0.0 }?.let {
                            if (isNotEmpty()) append(" · ")
                            append("★ ").append("%.1f".format(it))
                        }
                        tmdbMetadata?.genres?.takeIf { it.isNotEmpty() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it.joinToString(" · "))
                        }
                        if (isNotEmpty()) append(" · ")
                        append(item.category)
                        append(durationLabel(tmdbMetadata?.runtimeMinutes?.toLong()?.times(60) ?: item.durationSeconds))
                        if (!item.season.isNullOrBlank()) append(uiText(" · Сезон {0}", item.season))
                        if (!item.episode.isNullOrBlank()) append(uiText(" · Серия {0}", item.episode))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                tmdbMetadata?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Spacer(modifier = Modifier.height(4.dp))
                    // Long overviews auto-scroll (3 s delay, slow, stop at the
                    // last line) instead of being truncated.
                    AutoScrollText(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                    )
                }
                tmdbMetadata?.director?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = uiText("Режиссёр: {0}", it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                tmdbMetadata?.cast?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = uiText("В ролях: {0}", it.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Interactive full-width seek bar (the whole video width).
        if (playerState.durationMs > 0L && playerState.state != PlaybackState.IDLE) {
            Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacingSmall))
            PlayerSeekBar(
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                onSeekTo = { controller.seekTo(it) },
            )
        }
    }
}

/**
 * Compact TMDB info strip shown UNDER the video in the browse layout (reduced
 * window + library list on the left): poster, title, year, rating, genres and
 * the overview (with director/cast). It follows the current selection — every
 * list flip re-runs the TMDB enrich, so the description updates to match the
 * movie. Falls back to provider data while loading or when TMDB is off / has
 * no match.
 */
@Composable
private fun TmdbInfoPanel(
    item: MediaItem,
    tmdbMetadata: com.tvnext.app.core.tmdb.TmdbMetadata?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .padding(top = TVNextDimens.ItemSpacingSmall)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = shape,
            )
            .padding(TVNextDimens.ScreenPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            url = tmdbMetadata?.posterUrl ?: item.logoUrl,
            placeholderText = tmdbMetadata?.title ?: item.name,
            // Large TMDB poster (room below the video); text column unaffected.
            modifier = Modifier.size(180.dp),
            // No grey placeholder square when the poster is missing/loading.
            showPlaceholder = false,
        )
        Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
        Column(modifier = Modifier.weight(1f)) {
            // Title + gold star rating on the same top line (rating above the text).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tmdbMetadata?.title ?: item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                tmdbMetadata?.voteAverage?.takeIf { it > 0.0 }?.let { rating ->
                    Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                    Text(
                        text = "★ ${"%.1f".format(rating)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = GoldStar,
                    )
                }
                if (loading) {
                    Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            // Year · genres · runtime (second line).
            val metaLine = buildString {
                tmdbMetadata?.releaseDate?.take(4)?.let { append(it) }
                tmdbMetadata?.genres?.takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it.joinToString(" · "))
                }
                tmdbMetadata?.runtimeMinutes?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(formatDuration(it.toLong() * 60_000L))
                }
            }
            if (metaLine.isNotEmpty()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tmdbMetadata?.originalTitle
                ?.takeIf { it.isNotBlank() && it != tmdbMetadata.title }
                ?.let { original ->
                    Text(
                        text = uiText("Оригинал: {0}", original),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            tmdbMetadata?.originalLanguage
                ?.takeIf { it.isNotBlank() }
                ?.let { language ->
                    Text(
                        text = uiText("Язык: {0}", languageLabel(language)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            tmdbMetadata?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(modifier = Modifier.height(2.dp))
                // Long overviews auto-scroll (3 s delay, slow, stop at the last
                // line) instead of being truncated.
                AutoScrollText(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                )
            }
            tmdbMetadata?.director?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = uiText("Режиссёр: {0}", it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tmdbMetadata?.cast?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = uiText("В ролях: {0}", it.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tmdbMetadata == null && !loading) {
                Text(
                    text = uiText("TMDB: информация недоступна (включите TMDB в настройках и укажите API-ключ)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun isImmersiveToggle(event: KeyEvent): Boolean {
    // Physical L key, independent of the keyboard layout (also matches Cyrillic «д»).
    return event.key == Key.L || event.utf16CodePoint == '\u0434'.code
}

private fun durationLabel(durationSeconds: Long?): String =
    durationSeconds?.takeIf { it > 0 }?.let { " · ${formatDuration(it * 1000)}" }.orEmpty()

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        uiText("%d ч %02d мин").format(hours, minutes)
    } else {
        uiText("%d мин").format(minutes)
    }
}

/** Gold/yellow color for the TMDB star rating (readable on dark + light). */
private val GoldStar = androidx.compose.ui.graphics.Color(0xFFE6B800)

/** Human-readable name for a TMDB original-language code (falls back to code). */
private fun languageLabel(code: String): String = when (code.lowercase()) {
    "ru" -> uiText("Русский")
    "uk" -> uiText("Украинский")
    "en" -> uiText("Английский")
    "de" -> uiText("Немецкий")
    "fr" -> uiText("Французский")
    "es" -> uiText("Испанский")
    "it" -> uiText("Итальянский")
    "pt" -> uiText("Португальский")
    "pl" -> uiText("Польский")
    "tr" -> uiText("Турецкий")
    "ja" -> uiText("Японский")
    "ko" -> uiText("Корейский")
    "zh" -> uiText("Китайский")
    "hi" -> uiText("Хинди")
    "ar" -> uiText("Арабский")
    else -> code
}


