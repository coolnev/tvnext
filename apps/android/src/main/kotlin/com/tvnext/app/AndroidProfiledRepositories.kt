package com.tvnext.app

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.iptv.data.ChannelStore
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.data.DefaultHistoryRepository
import com.tvnext.app.core.iptv.data.HistoryEntry
import com.tvnext.app.core.iptv.data.HistoryRepository
import com.tvnext.app.core.iptv.data.HistoryStore
import com.tvnext.app.core.iptv.data.IptvMediaLibraryRepository
import com.tvnext.app.core.iptv.data.MediaItemStore
import com.tvnext.app.core.iptv.data.MediaLibraryRepository
import com.tvnext.app.core.iptv.data.PlaylistRepository
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.feature.settings.data.SettingsRepository
import com.tvnext.app.feature.settings.data.SqlDelightSettingsRepository
import com.tvnext.app.core.database.AppMetaRepository
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.domain.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Profile-specific channel names, visibility, order and favorites without modifying provider M3U. */
internal class AndroidProfiledChannelsRepository(
    private val source: ChannelStore,
    private val playlists: PlaylistRepository,
    private val personal: ProfilePersonalData,
) : ChannelsRepository {
    override suspend fun getChannels(): List<Channel> = apply(source.getChannels())
    override suspend fun getChannelsByPlaylist(playlistId: String): List<Channel> =
        apply(source.getChannelsByPlaylist(playlistId))
    private suspend fun apply(raw: List<Channel>): List<Channel> =
        projectProfileChannels(raw, getFavoriteIds(), personal.channelEdits(),
            personal.channelOrder(), hideDuplicates = personal.hideDuplicateChannels())
    override suspend fun isConfigured(): Boolean = source.getEnabledPlaylists().isNotEmpty()
    override suspend fun refresh(): AppResult<Unit> = playlists.refreshAll()
    override suspend fun getFavoriteIds(): Set<String> =
        personal.channelFavorites(source.getFavoriteIds())
    override suspend fun setFavorite(channelId: String, favorite: Boolean) {
        check(personal.setChannelFavorite(channelId, favorite)) { "Unable to store channel favorite" }
    }
}

/** The same per-profile favorite view is used by Search, Home and Media Library. */
internal class AndroidProfiledMediaRepository(
    private val source: MediaItemStore,
    playlists: PlaylistRepository,
    private val personal: ProfilePersonalData,
) : MediaLibraryRepository by IptvMediaLibraryRepository(source, playlists) {
    private suspend fun favorites(): Set<String> = personal.mediaFavorites(source.getFavoriteIds())
    private fun mark(items: List<MediaItem>, favorites: Set<String>): List<MediaItem> =
        items.map { it.copy(isFavorite = it.id in favorites) }
    override suspend fun getItems(playlistId: String?): List<MediaItem> =
        mark(source.getItems(playlistId), favorites())
    override suspend fun getItemsByCategory(playlistId: String, category: String): List<MediaItem> =
        getItems(playlistId).filter { it.category == category }
    override suspend fun getItemById(id: String): MediaItem? =
        source.getItemById(id)?.let { it.copy(isFavorite = id in favorites()) }
    override suspend fun search(query: String): List<MediaItem> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return getItems(null).filter { it.name.contains(term, ignoreCase = true) ||
            it.category.contains(term, ignoreCase = true) }
    }
    override suspend fun getFavoriteIds(): Set<String> = favorites()
    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        check(personal.setMediaFavorite(itemId, favorite)) { "Unable to store media favorite" }
    }
}

/** Existing SQLite history is imported ONCE into the first (main) profile. */
internal class AndroidProfiledHistoryRepository(
    store: HistoryStore,
    private val personal: ProfilePersonalData,
) : HistoryRepository {
    private val legacy = DefaultHistoryRepository(store)
    private val changed = MutableStateFlow(0L)

    private suspend fun snapshot(): List<HistoryEntry> {
        personal.history()?.let { return it }
        val import = if (personal.activeId() == "main") legacy.getRecent() else emptyList()
        personal.saveHistory(import)
        return personal.history().orEmpty()
    }

    override suspend fun add(itemId: String, contentType: WatchContentType) {
        if (itemId.isBlank()) return
        val entry = HistoryEntry(itemId, contentType, System.currentTimeMillis())
        val previous = snapshot()
        check(personal.saveHistory((listOf(entry) + previous.filterNot {
            it.itemId == itemId && it.contentType == contentType
        }).take(HistoryRepository.MAX_HISTORY - 1))) { "Cannot persist profile history" }
        changed.value += 1
    }
    override suspend fun getRecent(limit: Int): List<HistoryEntry> = snapshot().take(limit.coerceAtLeast(0))
    override suspend fun getRecentByType(contentType: WatchContentType, limit: Int): List<HistoryEntry> =
        snapshot().filter { it.contentType == contentType }.take(limit.coerceAtLeast(0))
    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = changed
        .map { getRecent(limit) }.distinctUntilChanged()
    override suspend fun clear() {
        check(personal.saveHistory(emptyList())) { "Cannot clear profile history" }
        changed.value += 1
    }
}

/** App-wide network/playback settings stay shared, while theme/accent/last channel are personal. */
internal class AndroidProfiledSettingsRepository(
    meta: AppMetaRepository,
    private val personal: ProfilePersonalData,
) : SettingsRepository {
    private val shared = SqlDelightSettingsRepository(meta)
    private val appearanceRevision = MutableStateFlow(0L)

    private fun scoped(base: AppSettings): AppSettings {
        val default = if (personal.activeId() == "main") base else AppSettings()
        val theme = personal.style("theme")?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
            ?: default.theme
        val accent = personal.style("accent")?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
            ?: default.accentColor
        return base.copy(theme = theme, accentColor = accent)
    }
    override suspend fun load(): AppSettings = scoped(shared.load())
    override suspend fun save(settings: AppSettings) {
        // Keep the legacy DB appearance stable for future migration and for desktop.
        val current = shared.load()
        shared.save(settings.copy(theme = current.theme, accentColor = current.accentColor))
        check(personal.saveStyle(settings.theme.name, settings.accentColor.name)) {
            "Cannot save profile appearance"
        }
        // Shared SQLite may emit BEFORE the profile-specific appearance is
        // written to SharedPreferences. This revision forces the app-wide
        // MaterialTheme to observe the new theme/accent immediately.
        appearanceRevision.value = appearanceRevision.value + 1L
    }
    override fun observe(): Flow<AppSettings> =
        combine(shared.observe(), appearanceRevision) { base, _ -> scoped(base) }
            .distinctUntilChanged()
    override suspend fun getAppInfo() = shared.getAppInfo()
    override suspend fun getLastChannelId(): String? {
        if (personal.hasLastChannelId()) return personal.lastChannelId()?.takeIf { it.isNotBlank() }
        val old = if (personal.activeId() == "main") shared.getLastChannelId() else null
        personal.setLastChannelId(old)
        return old
    }
    override suspend fun setLastChannelId(channelId: String?) {
        check(personal.setLastChannelId(channelId)) { "Cannot store last channel" }
    }
    override suspend fun getLastCategoryId(): String? = shared.getLastCategoryId()
    override suspend fun setLastCategoryId(categoryId: String?) = shared.setLastCategoryId(categoryId)
}
