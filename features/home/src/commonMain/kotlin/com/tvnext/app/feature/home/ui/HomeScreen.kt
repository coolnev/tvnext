package com.tvnext.app.feature.home.ui

import com.tvnext.app.core.ui.i18n.uiText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.tvnext.app.core.player.PlaybackController
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlayerSurface
import com.tvnext.app.core.player.videoSurfaceBackground
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.util.formatTimeOfDay
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.ui.component.ScreenHeader
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.component.TvProgressBar
import com.tvnext.app.core.ui.image.ChannelLogo
import com.tvnext.app.core.ui.theme.TVNextDimens
import com.tvnext.app.feature.home.domain.FeaturedChannel
import com.tvnext.app.feature.home.domain.FeaturedProgramme
import com.tvnext.app.feature.home.domain.FeaturedVod
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    component: HomeComponent,
    modifier: Modifier = Modifier,
    homeCardLimit: Int = 7,
    showHomeUpcoming: Boolean = true,
    showHomeRecent: Boolean = true,
    showHomeFavorites: Boolean = true,
    showHomeRecentVod: Boolean = true,
    showHomeFavoriteVod: Boolean = true,
    playbackController: PlaybackController? = null,
    onOpenPlayingVideo: () -> Unit = {},
) {
    val state by component.state.collectAsState()
    val now = rememberTicker()
    val playing by remember(playbackController) {
        playbackController?.state?.map {it.item}?.distinctUntilChanged() ?: flowOf(null)
    }.collectAsState(initial=playbackController?.state?.value?.item)

    // The component loads itself on creation (see HomeComponent.init) — do NOT
    // dispatch Load from here: the screen is recreated on every fullscreen F11
    // window switch, and re-running the heavy EPG scans would stall it.

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TVNextDimens.ScreenPadding),
    ) {
        ScreenHeader(
            title = uiText("Главная"),
            onBack = null,
        )

        val anyLive = state.content.continueWatching != null ||
            (showHomeUpcoming && state.content.upcoming.isNotEmpty()) ||
            (showHomeRecent && state.content.recent.isNotEmpty()) ||
            (showHomeFavorites && state.content.favorites.isNotEmpty())
        val anyVod = state.content.continueWatchingVod != null ||
            (showHomeRecentVod && state.content.recentVod.isNotEmpty()) ||
            (showHomeFavoriteVod && state.content.favoriteVod.isNotEmpty())

        if (anyLive || playing!=null) {
            HomeGroupTitle(title = if(playing?.kind==PlaybackContentKind.VOD) uiText("Сейчас смотрите") else uiText("Каналы"))
        }

        val continuingChannel=state.content.continueWatching
        if(playbackController!=null && playing!=null) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val previewWidth=(maxWidth*.35f).coerceIn(176.dp,288.dp)
            val showChannelDetails = maxWidth >= 560.dp
            TvCardButton(
                onClick = onOpenPlayingVideo,
                modifier = Modifier.fillMaxWidth().testTag("home-current-block"),
                transparentContainer = true,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (showChannelDetails && playing?.kind != PlaybackContentKind.VOD && continuingChannel != null && continuingChannel.id == playing?.id) {
                            ContinueWatchingChannelContent(continuingChannel, now)
                        } else {
                            Text(uiText("Сейчас смотрите"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(playing?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.width(previewWidth).aspectRatio(16f / 9).testTag("home-current-video")
                        .background(videoSurfaceBackground(Color.Black))) {
                        PlayerSurface(playbackController, Modifier.fillMaxSize())
                    }
                }
            }
        } else continuingChannel?.let { channel ->
            ContinueWatchingCard(
                channel = channel,
                now = now,
                onClick = { component.dispatch(if (com.tvnext.app.core.common.platform.isAndroidPlatform()) HomeIntent.ContinueChannel else HomeIntent.ChannelClicked(channel.id)) },
            )
        }

        if (showHomeUpcoming && state.content.upcoming.isNotEmpty()) {
            HomeSection(title = uiText("Ближайшие передачи")) {
                HomeCardRow(
                    items = state.content.upcoming.take(homeCardLimit),
                    content = { upcoming ->
                        UpcomingCard(
                            programme = upcoming,
                            now = now,
                            onClick = { component.dispatch(HomeIntent.ChannelClicked(upcoming.channelId)) },
                        )
                    },
                )
            }
        }

        if (showHomeRecent && state.content.recent.isNotEmpty()) {
            HomeSection(title = uiText("Недавно просмотренные")) {
                HomeCardRow(
                    items = state.content.recent.take(homeCardLimit),
                    content = { channel ->
                        ChannelCard(
                            channel = channel,
                            now = now,
                            onClick = { component.dispatch(HomeIntent.ChannelClicked(channel.id)) },
                        )
                    },
                )
            }
        }

        if (showHomeFavorites && state.content.favorites.isNotEmpty()) {
            HomeSection(title = uiText("Избранное")) {
                HomeCardRow(
                    items = state.content.favorites.take(homeCardLimit),
                    content = { channel ->
                        ChannelCard(
                            channel = channel,
                            now = now,
                            onClick = { component.dispatch(HomeIntent.ChannelClicked(channel.id)) },
                        )
                    },
                )
            }
        }

        if (anyVod) {
            HomeGroupTitle(title = uiText("Медиатека"))
        }

        state.content.continueWatchingVod?.let { vod ->
            ContinueWatchingVodCard(
                vod = vod,
                onClick = { component.dispatch(if (com.tvnext.app.core.common.platform.isAndroidPlatform()) HomeIntent.ContinueVod else HomeIntent.VodClicked(vod.id)) },
            )
        }

        if (showHomeRecentVod && state.content.recentVod.isNotEmpty()) {
            HomeSection(title = uiText("Недавно просмотренные фильмы")) {
                HomeCardRow(
                    items = state.content.recentVod.take(homeCardLimit),
                    content = { vod ->
                        VodCard(
                            vod = vod,
                            onClick = { component.dispatch(HomeIntent.VodClicked(vod.id)) },
                        )
                    },
                )
            }
        }

        if (showHomeFavoriteVod && state.content.favoriteVod.isNotEmpty()) {
            HomeSection(title = uiText("Избранные фильмы и сериалы")) {
                HomeCardRow(
                    items = state.content.favoriteVod.take(homeCardLimit),
                    content = { vod ->
                        VodCard(
                            vod = vod,
                            onClick = { component.dispatch(HomeIntent.VodClicked(vod.id)) },
                        )
                    },
                )
            }
        }

    }
}

/** Group heading on Home («Каналы» / «Медиатека»). */
@Composable
private fun HomeGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            top = TVNextDimens.ItemSpacingSmall,
            bottom = TVNextDimens.ItemSpacingSmall,
        ),
    )
}

/** Upcoming-programme card: start time + title + channel + countdown. */
@Composable
private fun UpcomingCard(
    programme: FeaturedProgramme,
    now: Long,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier
            .width(TVNextDimens.HomeCardWidth)
            .height(TVNextDimens.HomeCardHeight + 8.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    url = programme.logoUrl,
                    placeholderText = programme.channelName,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                Text(
                    text = programme.channelName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column {
                Text(
                    text = programme.programme.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatTimeOfDay(programme.programme.start)} — " +
                        formatTimeOfDay(programme.programme.stop),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = startsInText(programme.programme.start, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** «Категория» chip: opens the media library (categories are filterable there). */
@Composable
private fun MediaCategoryChip(
    category: String,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = TVNextDimens.ItemSpacing),
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun startsInText(start: Long, now: Long): String {
    val minutes = ((start - now) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes <= 0L -> uiText("начнётся сейчас")
        minutes < 60L -> uiText("через {0} мин", minutes)
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) uiText("через {0} ч", hours) else uiText("через {0} ч {1} мин", hours, rest)
        }
    }
}

/** «Продолжить просмотр» — the most recently watched VOD item. */
@Composable
private fun ContinueWatchingVodCard(
    vod: FeaturedVod,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = TVNextDimens.SectionSpacing),
        contentPadding = PaddingValues(TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = vod.logoUrl,
                placeholderText = vod.name,
                modifier = Modifier.size(TVNextDimens.IconSizeLarge * 2),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiText("Продолжить просмотр VOD"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = vod.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = vod.category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VodCard(
    vod: FeaturedVod,
    onClick: () -> Unit,
) {
    // Card size stays unchanged; inside: a LARGE poster on the left (fills the
    // card height, roughly half the width) and the title + category to its
    // right — instead of a tiny icon with text underneath.
    TvCardButton(
        onClick = onClick,
        modifier = Modifier
            .width(TVNextDimens.HomeCardWidth)
            .height(TVNextDimens.HomeCardHeight + 8.dp),
        contentPadding = PaddingValues(TVNextDimens.ScreenPaddingCompact),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = vod.logoUrl,
                placeholderText = vod.name,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = vod.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = vod.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** «Продолжить просмотр» — the most recently watched channel. */
@Composable
private fun ContinueWatchingCard(
    channel: FeaturedChannel,
    now: Long,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = TVNextDimens.SectionSpacing),
        contentPadding = PaddingValues(TVNextDimens.ScreenPaddingCompact),
    ) {
        ContinueWatchingChannelContent(channel, now)
    }
}

@Composable
private fun ContinueWatchingChannelContent(channel: FeaturedChannel, now: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            url = channel.logoUrl,
            placeholderText = channel.name,
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
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "№ ${channel.number} · ${channel.category}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel.currentProgramme?.let { programme ->
                CurrentProgramme(programme = programme, now = now)
            }
            channel.nextProgramme?.let { programme ->
                Text(
                    text = uiText("Далее: {0} — {1} · {2}", formatTimeOfDay(programme.start), formatTimeOfDay(programme.stop), programme.title),
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
private fun CurrentProgramme(
    programme: EpgProgramme,
    now: Long,
) {
    Column {
        Text(
            text = programme.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TvProgressBar(
            progress = { programmeProgress(programme, now) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(4.dp),
        )
    }
}

/**
 * A non-scrolling row of fixed-width cards. Only cards that fit WHOLLY into
 * the available width are shown — a partially visible "sticking out" card is
 * hidden instead of being clipped. [items] should already be limited by the
 * user's Home card limit.
 */
@Composable
private fun <T> HomeCardRow(
    items: List<T>,
    content: @Composable (T) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val density = LocalDensity.current
        val spacingPx = with(density) { TVNextDimens.ItemSpacing.toPx() }
        val cardPx = with(density) { TVNextDimens.HomeCardWidth.toPx() }
        val availablePx = with(density) { maxWidth.toPx() }
        val visibleCount = ((availablePx + spacingPx) / (cardPx + spacingPx))
            .toInt()
            .coerceIn(1, items.size)
        Row(
            horizontalArrangement = Arrangement.spacedBy(TVNextDimens.ItemSpacing),
        ) {
            items.take(visibleCount).forEach { content(it) }
        }
    }
}

@Composable
private fun HomeSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = TVNextDimens.SectionSpacing),
    ) {
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
private fun ChannelCard(
    channel: FeaturedChannel,
    now: Long,
    onClick: () -> Unit,
) {
    TvCardButton(
        onClick = onClick,
        modifier = Modifier
            .width(TVNextDimens.HomeCardWidth)
            .height(TVNextDimens.HomeCardHeight + 8.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    url = channel.logoUrl,
                    placeholderText = channel.name,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(TVNextDimens.ItemSpacingSmall))
                Text(
                    text = "№ ${channel.number}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
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
                channel.currentProgramme?.let { programme ->
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatTimeOfDay(programme.start)} — ${formatTimeOfDay(programme.stop)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun programmeProgress(programme: EpgProgramme, now: Long): Float {
    val duration = programme.stop - programme.start
    if (duration <= 0L) return 1f
    return ((now - programme.start).toFloat() / duration).coerceIn(0f, 1f)
}

/** Ticks every 30 s so the EPG progress bar stays live without re-querying. */
@Composable
private fun rememberTicker(intervalMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now = currentTimeMillis()
        }
    }
    return now
}

