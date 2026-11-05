package com.tvnext.app.feature.home.data

import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.EpgRepository
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.feature.home.domain.FeaturedChannel
import com.tvnext.app.feature.home.domain.FeaturedProgramme
import com.tvnext.app.feature.home.domain.FeaturedVod
import com.tvnext.app.feature.home.domain.HomeContent

interface HomeRepository {
    suspend fun getContent(): HomeContent
}

/**
 * Real Home content:
 *  - continue watching = most recent history entry (LIVE channel and VOD item);
 *  - recent = remaining history entries (resolved against current data —
 *    removed content is simply skipped, never crashes the UI);
 *  - favorites = favorite channels (existing storage);
 *  - media categories = categories actually present in the VOD sources.
 *
 * EPG enrichment uses a single bulk query per playlist ([EpgRepository.getNowNextForChannels])
 * instead of per-channel queries — for a 1000+ channel playlist this is the
 * difference between a handful of SQL queries and thousands.
 */
class DefaultHomeRepository(
    private val channelsRepository: ChannelsRepository,
    private val historyRepository: HistoryRepository,
    private val epgRepository: EpgRepository,
    private val mediaLibrary: MediaLibraryRepository,
) : HomeRepository {

    override suspend fun getContent(): HomeContent {
        val channels = channelsRepository.getChannels()
        val byId = channels.associateBy { it.id }
        val now = currentTimeMillis()

        val history = historyRepository.getRecent(HistoryRepository.MAX_HISTORY)
        val liveHistory = history.filter { it.contentType == WatchContentType.LIVE }
        val vodHistory = history.filter { it.contentType == WatchContentType.VOD }
        val favoriteVodIds = mediaLibrary.getFavoriteIds()
        // Home needs only a few IDs, not a full 20k-film profile projection.
        val allVod = if (com.tvnext.app.core.common.platform.isAndroidPlatform()) {
            val ids = (vodHistory.map { it.itemId } + favoriteVodIds.take(SECTION_LIMIT) +
                listOfNotNull(com.tvnext.app.core.player.AndroidPlaybackSession.ids.value.vodId)).distinct()
            ids.mapNotNull { mediaLibrary.getItemById(it) }
        } else mediaLibrary.getItems(null)
        val vodById = allVod.associateBy { it.id }

        val favoriteIds = channelsRepository.getFavoriteIds()
        val session = if (com.tvnext.app.core.common.platform.isAndroidPlatform())
            com.tvnext.app.core.player.AndroidPlaybackSession.ids.value else null
        val lastChannelId = session?.channelId?.takeIf { it in byId } ?: liveHistory.firstOrNull()?.itemId
        val continueWatching = lastChannelId?.let { byId[it] }
        val recent = liveHistory.drop(1).mapNotNull { byId[it.itemId] }
        val favorites = favoriteIds.mapNotNull { byId[it] }

        // EPG "current/next" is resolved ONLY for a small surface: the channels
        // actually shown on Home (continue watching + recent + favorites), or —
        // when the user has no history/favorites yet — the first few channels,
        // so «Сейчас идёт» / «Ближайшие передачи» still work for a new user.
        // Resolving it for all ~2000+ channels would cost a DISTINCT scan over
        // the whole EPG (seconds on a large XMLTV).
        val featuredChannels = buildList {
            if (continueWatching != null) add(continueWatching)
            addAll(recent)
            addAll(favorites)
        }
        val epgSurface = if (featuredChannels.isEmpty()) {
            channels.take(SECTION_LIMIT)
        } else {
            featuredChannels
        }
        val nowNext = epgRepository.getNowNextForChannels(epgSurface, now)
        val featured = epgSurface.map { it.toFeatured(now, nowNext) }

        val continueWatchingVod = (session?.vodId?.takeIf { it in vodById } ?: vodHistory.firstOrNull()?.itemId)?.let { vodById[it] }?.let { it.toFeaturedVod() }
        val recentVod = vodHistory.drop(1)
            .mapNotNull { vodById[it.itemId] }
            .map { it.toFeaturedVod() }

        // «Сейчас идёт»: featured channels whose current programme is running.
        val nowPlaying = featured.filter { it.currentProgramme != null }
            .take(SECTION_LIMIT)
        // «Ближайшие передачи»: next programmes of the featured channels.
        val upcoming = featured
            .mapNotNull { ch ->
                ch.nextProgramme?.let { programme ->
                    FeaturedProgramme(
                        channelId = ch.id,
                        channelName = ch.name,
                        logoUrl = ch.logoUrl,
                        programme = programme,
                    )
                }
            }
            .take(SECTION_LIMIT)
        // «Избранные фильмы и сериалы»: favorite VOD items.
        val favoriteVod = favoriteVodIds
            .mapNotNull { vodById[it] }
            .map { it.toFeaturedVod() }
            .take(SECTION_LIMIT)

        return HomeContent(
            continueWatching = continueWatching?.toFeatured(now, nowNext),
            recent = recent.map { it.toFeatured(now, nowNext) },
            favorites = favorites.map { it.toFeatured(now, nowNext) },
            continueWatchingVod = continueWatchingVod,
            recentVod = recentVod,
            nowPlaying = nowPlaying,
            upcoming = upcoming,
            favoriteVod = favoriteVod,
        )
    }

    private fun Channel.toFeatured(
        now: Long,
        nowNext: Map<String, com.tvnext.app.core.iptv.data.NowNext>,
    ): FeaturedChannel {
        val currentNext = nowNext[id]
        return FeaturedChannel(
            id = id,
            name = name,
            number = number,
            category = category,
            logoUrl = logoUrl,
            currentProgramme = currentNext?.current,
            nextProgramme = currentNext?.next,
        )
    }

    private fun com.tvnext.app.core.iptv.domain.MediaItem.toFeaturedVod(): FeaturedVod = FeaturedVod(
        id = id,
        name = name,
        category = category,
        logoUrl = logoUrl,
    )

    private companion object {
        /** Per-section card limit (avoids rendering hundreds of rows). */
        const val SECTION_LIMIT = 12
    }
}
