package com.tvnext.app.feature.home.domain

import com.tvnext.app.core.iptv.xmltv.EpgProgramme

enum class HomeQuickAccess {
    ALL_CHANNELS,
    FAVORITES,
    SETTINGS,
    MEDIA_LIBRARY,
}

/**
 * A channel card on the Home screen, enriched with EPG (current/next programme)
 * and the logo url. Pure UI model — resolved from the real repositories.
 */
data class FeaturedChannel(
    val id: String,
    val name: String,
    val number: Int,
    val category: String,
    val logoUrl: String? = null,
    val currentProgramme: EpgProgramme? = null,
    val nextProgramme: EpgProgramme? = null,
)

/** A VOD item on the Home screen (continue watching / recent / favorites). */
data class FeaturedVod(
    val id: String,
    val name: String,
    val category: String,
    val logoUrl: String? = null,
)

/**
 * An upcoming/next programme shown on Home (with its channel, so the card can
 * offer a clear action).
 */
data class FeaturedProgramme(
    val channelId: String,
    val channelName: String,
    val logoUrl: String? = null,
    val programme: EpgProgramme,
)

data class HomeContent(
    /** The most recently watched channel (large «continue watching» card). */
    val continueWatching: FeaturedChannel? = null,
    /** Recently watched channels (excluding the last one). */
    val recent: List<FeaturedChannel> = emptyList(),
    val favorites: List<FeaturedChannel> = emptyList(),
    /** The most recently watched VOD item. */
    val continueWatchingVod: FeaturedVod? = null,
    /** Recently watched VOD items (excluding the continue-watching one). */
    val recentVod: List<FeaturedVod> = emptyList(),
    /** Channels with a currently-running programme («Сейчас идёт»). */
    val nowPlaying: List<FeaturedChannel> = emptyList(),
    /** Next programmes of the featured channels («Ближайшие передачи»). */
    val upcoming: List<FeaturedProgramme> = emptyList(),
    /** Favorite VOD items («Избранные фильмы и сериалы»). */
    val favoriteVod: List<FeaturedVod> = emptyList(),
)
