package com.tvnext.app.feature.search.ui

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.result.toUserMessage
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.data.ProgrammeSearchHit
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvSearchField
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.theme.TVNextDimens

/** One flat search result entry across all five sections. */
private sealed interface SearchResult {
    data class Channel(val channel: com.tvnext.app.core.iptv.domain.Channel) : SearchResult
    data class Media(val item: com.tvnext.app.core.iptv.domain.MediaItem) : SearchResult
    data class Current(val hit: ProgrammeSearchHit) : SearchResult
    data class Archive(val hit: ProgrammeSearchHit) : SearchResult
    data class Future(val hit: ProgrammeSearchHit) : SearchResult
}

@Composable
fun SearchScreen(
    component: SearchComponent,
    modifier: Modifier = Modifier,
    onBackHome: (() -> Unit)? = null,
) {
    val state by component.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    val allResults = remember(
        state.channels,
        state.currentProgrammes,
        state.archiveProgrammes,
        state.mediaItems,
        state.futureProgrammes,
    ) {
        buildList {
            state.channels.forEach { add(SearchResult.Channel(it)) }
            state.currentProgrammes.forEach { add(SearchResult.Current(it)) }
            state.archiveProgrammes.forEach { add(SearchResult.Archive(it)) }
            state.mediaItems.forEach { add(SearchResult.Media(it)) }
            state.futureProgrammes.forEach { add(SearchResult.Future(it)) }
        }
    }
    var selectedIndex by remember { mutableStateOf(-1) }
    val resultListState = rememberLazyListState()
    // True while the search field itself holds focus. The remote's OK must open
    // the selected result only when the field is NOT focused (otherwise OK would
    // re-enter text-editing mode / be swallowed by the field).
    var searchFieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(selectedIndex, allResults.size) {
        if (selectedIndex in allResults.indices) {
            resultListState.scrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(allResults) {
        selectedIndex = if (allResults.isNotEmpty()) 0 else -1
    }

    fun openResult(index: Int) {
        when (val result = allResults.getOrNull(index)) {
            is SearchResult.Channel -> component.dispatch(SearchIntent.ChannelClicked(result.channel))
            is SearchResult.Media -> component.dispatch(SearchIntent.MediaItemClicked(result.item))
            is SearchResult.Current -> component.dispatch(SearchIntent.ProgrammeClicked(result.hit))
            is SearchResult.Archive -> component.dispatch(SearchIntent.ArchiveClicked(result.hit))
            is SearchResult.Future -> component.dispatch(SearchIntent.FutureClicked(result.hit))
            null -> Unit
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TVNextDimens.ScreenPadding)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        event.key == Key.DirectionDown && allResults.isNotEmpty() -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(allResults.size - 1)
                            // Leave the search field once the user moves into the
                            // results: OK must then act on the result, not on the
                            // text field.
                            if (searchFieldFocused && selectedIndex > 0) {
                                runCatching { focusRequester.requestFocus() }
                            }
                            true
                        }

                        event.key == Key.DirectionUp && allResults.isNotEmpty() -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            if (searchFieldFocused && selectedIndex > 0) {
                                runCatching { focusRequester.requestFocus() }
                            }
                            true
                        }

                        (event.key == Key.Enter || event.key == Key.DirectionCenter) &&
                            !searchFieldFocused && allResults.isNotEmpty() -> {
                            val index = if (selectedIndex in allResults.indices) selectedIndex else 0
                            openResult(index)
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        // Android TV: no screen header — the remote's Back button leaves the
        // screen (dense 4K layout).
        if (!isAndroidPlatform()) {
            ScreenHeader(
                title = uiText("Поиск"),
                onBack = onBackHome,
            )
        }

        TvSearchField(
            value = state.query,
            onValueChange = { component.dispatch(SearchIntent.SearchChanged(it)) },
            onClear = { component.dispatch(SearchIntent.SearchChanged("")) },
            placeholder = uiText("Каналы, фильмы, передачи…"),
            onFocusChanged = { searchFieldFocused = it },
        )

        Spacer(modifier = Modifier.height(TVNextDimens.ItemSpacing))

        when {
            state.query.isBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = uiText("Начните вводить запрос.\nИщутся каналы, текущие и будущие передачи, ") +
                            uiText("архив и медиатека."),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.isSearching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                state.error?.let { error ->
                    Text(
                        text = error.toUserMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = TVNextDimens.ItemSpacing),
                    )
                }

                LazyColumn(
                    state = resultListState,
                    verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(
                        allResults,
                        key = { index, result ->
                            when (result) {
                                is SearchResult.Channel -> "ch:${result.channel.id}"
                                is SearchResult.Media -> "mi:${result.item.id}"
                                is SearchResult.Current -> "cur:${result.hit.channelId}:${result.hit.programme.start}"
                                is SearchResult.Archive -> "arc:${result.hit.channelId}:${result.hit.programme.start}"
                                is SearchResult.Future -> "fut:${result.hit.channelId}:${result.hit.programme.start}"
                            }
                        },
                    ) { index, result ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacingSmall),
                        ) {
                            val title = when (result) {
                                is SearchResult.Channel -> uiText("Каналы")
                                is SearchResult.Current -> uiText("Сейчас")
                                is SearchResult.Archive -> uiText("Архив")
                                is SearchResult.Media -> uiText("Медиатека")
                                is SearchResult.Future -> uiText("Будущее")
                            }
                            val previousTitle = allResults.getOrNull(index - 1)?.let {
                                when (it) {
                                    is SearchResult.Channel -> uiText("Каналы")
                                    is SearchResult.Current -> uiText("Сейчас")
                                    is SearchResult.Archive -> uiText("Архив")
                                    is SearchResult.Media -> uiText("Медиатека")
                                    is SearchResult.Future -> uiText("Будущее")
                                }
                            }
                            if (title != previousTitle) {
                                SearchSectionTitle(title)
                            }
                            when (result) {
                                is SearchResult.Channel -> SearchChannelRow(
                                    channel = result.channel,
                                    selected = index == selectedIndex,
                                    onClick = {
                                        selectedIndex = index
                                        openResult(index)
                                    },
                                )
                                is SearchResult.Current -> SearchProgrammeRow(
                                    prefix = "🔴",
                                    hit = result.hit,
                                    enabled = result.hit.channelId != null,
                                    selected = index == selectedIndex,
                                    action = { uiText("Смотреть") },
                                    onClick = {
                                        selectedIndex = index
                                        openResult(index)
                                    },
                                )
                                is SearchResult.Archive -> SearchProgrammeRow(
                                    prefix = "▶",
                                    hit = result.hit,
                                    enabled = result.hit.channelId != null,
                                    selected = index == selectedIndex,
                                    action = { uiText("Смотреть архив") },
                                    onClick = {
                                        selectedIndex = index
                                        openResult(index)
                                    },
                                )
                                is SearchResult.Media -> SearchMediaRow(
                                    item = result.item,
                                    selected = index == selectedIndex,
                                    onClick = {
                                        selectedIndex = index
                                        openResult(index)
                                    },
                                )
                                is SearchResult.Future -> SearchProgrammeRow(
                                    prefix = "⏰",
                                    hit = result.hit,
                                    enabled = result.hit.channelId != null,
                                    selected = index == selectedIndex,
                                    action = { uiText("Запланировать") },
                                    onClick = {
                                        selectedIndex = index
                                        openResult(index)
                                    },
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
private fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = TVNextDimens.ItemSpacingSmall, bottom = TVNextDimens.ItemSpacingSmall),
    )
}

@Composable
private fun SearchChannelRow(
    channel: Channel,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = PaddingValues(horizontal = TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = channel.logoUrl,
                placeholderText = channel.name,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchMediaRow(
    item: MediaItem,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = PaddingValues(horizontal = TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = item.logoUrl,
                placeholderText = item.name,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = uiText("Открыть"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SearchProgrammeRow(
    prefix: String,
    hit: ProgrammeSearchHit,
    enabled: Boolean,
    selected: Boolean = false,
    action: () -> String,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = PaddingValues(horizontal = TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$prefix ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.programme.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${hit.channelName} · ${formatTimeOfDay(hit.programme.start)} — ${formatTimeOfDay(hit.programme.stop)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (enabled) {
                Text(
                    text = action(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

