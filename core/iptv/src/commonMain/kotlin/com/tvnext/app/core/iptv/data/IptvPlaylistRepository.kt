package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.util.rethrowAndroidCancellation
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import com.tvnext.app.core.common.result.AppError
import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.common.result.AppResult.Failure
import com.tvnext.app.core.common.result.AppResult.Success
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.m3u.M3uChannel
import com.tvnext.app.core.iptv.m3u.M3uParseResult
import com.tvnext.app.core.iptv.m3u.M3uParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Multi-playlist repository. Every source is an independent [Playlist]:
 * adding one never touches the others, a refresh only rewrites the channels of
 * the refreshed playlist, and removing a playlist cleans up its channels,
 * media items and EPG cache.
 */
class IptvPlaylistRepository(
    private val source: M3uSource,
    private val parser: M3uParser,
    private val store: ChannelStore,
    private val mediaStore: MediaItemStore,
    private val epgStore: EpgStore,
    private val archiveCapabilityStore: com.tvnext.app.core.iptv.archive.ArchiveCapabilityStore,
    private val scheduledRepository: ScheduledRepository,
    private val metaRepository: com.tvnext.app.core.database.AppMetaRepository,
    private val idMigration: ChannelIdMigration,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> = store.observePlaylists()

    override suspend fun getPlaylists(): List<Playlist> = store.getPlaylists()

    override suspend fun getPlaylist(id: String): Playlist? = store.getPlaylistById(id)

    override suspend fun getDefaultPlaylist(): Playlist? = store.getDefaultPlaylist()

    override suspend fun addPlaylist(
        url: String,
        type: PlaylistType,
        name: String?,
    ): AppResult<Playlist> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return Failure(AppError.InvalidPlaylist("URL плейлиста не может быть пустым"))
        }
        val playlistId = IdGenerator.playlistId(trimmed)
        val existing = store.getPlaylistById(playlistId)
        // Re-adding the same source refreshes it in place (no duplicate).
        if (existing != null && existing.type != type) {
            store.updatePlaylistType(playlistId, type)
        }
        return when (val fetch = source.fetch(trimmed)) {
            is Failure -> fetch
            is Success -> {
                when (val parsed = parser.parse(fetch.data)) {
                    is M3uParseResult.Failure ->
                        Failure(AppError.InvalidPlaylist(parsed.reason))
                    is M3uParseResult.Success -> {
                        val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.name
                            ?: deriveName(trimmed)
                        if (parsed.playlist.channels.isEmpty()) {
                            Failure(AppError.EmptyPlaylist("Плейлист не содержит записей"))
                        } else {
                            saveParsed(
                                playlistId = playlistId,
                                name = resolvedName,
                                url = trimmed,
                                type = type,
                                entries = parsed.playlist.channels,
                                urlTvg = parsed.playlist.urlTvg,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun removePlaylist(id: String): AppResult<Unit> {
        val existing = store.getPlaylistById(id)
            ?: return Failure(AppError.InvalidData("Плейлист не найден"))
        if (existing.isDefault) {
            // A default playlist can be removed; the default simply moves on.
            store.setDefaultPlaylist(null)
        }
        return try {
            // Order matters: archive capabilities are resolved through the
            // channel table, so they must be cleared before the channels are
            // deleted by store.deletePlaylist.
            archiveCapabilityStore.deleteByPlaylist(id)
            scheduledRepository.deleteByPlaylist(id)
            store.deletePlaylist(id)
            mediaStore.deleteByPlaylist(id)
            epgStore.deleteByPlaylist(id)
            Success(Unit)
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            Failure(AppError.Database(e))
        }
    }

    override suspend fun renamePlaylist(id: String, name: String): AppResult<Unit> {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return Failure(AppError.InvalidData("Имя не может быть пустым"))
        return try {
            store.renamePlaylist(id, trimmed)
            Success(Unit)
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            Failure(AppError.Database(e))
        }
    }

    override suspend fun refreshPlaylist(playlistId: String): AppResult<Playlist> {
        val existing = store.getPlaylistById(playlistId)
            ?: return Failure(AppError.InvalidData("Плейлист не найден"))
        return when (val fetch = source.fetch(existing.url)) {
            is Failure -> fetch
            is Success -> {
                when (val parsed = parser.parse(fetch.data)) {
                    is M3uParseResult.Failure ->
                        Failure(AppError.InvalidPlaylist(parsed.reason))
                    is M3uParseResult.Success -> {
                        if (parsed.playlist.channels.isEmpty()) {
                            Failure(AppError.EmptyPlaylist("Плейлист не содержит записей"))
                        } else {
                            saveParsed(
                                playlistId = existing.id,
                                name = existing.name,
                                url = existing.url,
                                type = existing.type,
                                entries = parsed.playlist.channels,
                                urlTvg = parsed.playlist.urlTvg,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun refreshAll(): AppResult<Unit> {
        val playlists = store.getEnabledPlaylists()
        if (playlists.isEmpty()) return Success(Unit)
        var firstError: AppError? = null
        for (playlist in playlists) {
            when (val result = refreshPlaylist(playlist.id)) {
                is Success -> Unit
                is Failure -> if (firstError == null) firstError = result.error
            }
        }
        // The parser-import version is bumped on any attempt: a transient
        // failure self-heals via the next refresh, while a permanently broken
        // playlist must not cause a re-import on every app start.
        runCatching {
            metaRepository.setValue(KEY_IMPORT_VERSION, CHANNEL_IMPORT_VERSION.toString())
        }
        return if (firstError == null) Success(Unit) else Failure(firstError)
    }

    override suspend fun refreshIfParserUpgraded(): AppResult<Unit> {
        val stored = runCatching { metaRepository.getValue(KEY_IMPORT_VERSION) }
            .getOrNull()
            ?.toIntOrNull()
            ?: LEGACY_IMPORT_VERSION
        if (stored >= CHANNEL_IMPORT_VERSION) return Success(Unit)
        // Upgrade path: FIRST rewrite the channel/media ids to the stable
        // scheme (favorites/history/archive/schedule travel with the rows), and
        // only then re-import the playlists with the current parser. If the
        // migration fails, the import version is NOT bumped and the next launch
        // retries — user data can never be silently dropped.
        return try {
            idMigration.migrate()
            // Old database imported by a previous app version: re-read every
            // playlist so the current parser's attributes land in the tables.
            refreshAll()
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            Failure(AppError.Database(e))
        }
    }

    override suspend fun setDefaultPlaylist(id: String?): AppResult<Unit> = try {
        store.setDefaultPlaylist(id)
        Success(Unit)
    } catch (e: Exception) {
            e.rethrowAndroidCancellation()
        Failure(AppError.Database(e))
    }

    private suspend fun saveParsed(
        playlistId: String,
        name: String,
        url: String,
        type: PlaylistType,
        entries: List<M3uChannel>,
        urlTvg: String?,
    ): AppResult<Playlist> {
        val baseUrl = baseUrlOf(url)
        val resolvedUrlTvg = urlTvg?.trim()?.takeIf { it.isNotEmpty() }
        val playlist = Playlist(
            id = playlistId,
            name = name,
            url = url,
            lastUpdated = currentTimeMillis(),
            enabled = true,
            type = type,
            channelCount = entries.size,
            epgUrl = resolvedUrlTvg,
        )
        return try {
            // Favorites are preserved by stable ids inside the store's
            // replace operation; no global wipe happens anymore.
            when (type) {
                PlaylistType.LIVE -> {
                    val channels = ArrayList<Channel>(entries.size)
                    val usedIds = HashSet<String>(entries.size)
                    entries.forEachIndexed { index, entry ->
                        channels.add(
                            entry.toDomainChannelUnique(
                                playlistId = playlistId,
                                number = index + 1,
                                baseUrl = baseUrl,
                                usedIds = usedIds,
                            ),
                        )
                    }
                    store.savePlaylist(playlist)
                    store.replacePlaylistChannels(playlistId, channels)
                }
                PlaylistType.VOD -> {
                    val items = ArrayList<MediaItem>(entries.size)
                    val usedIds = HashSet<String>(entries.size)
                    entries.forEachIndexed { index, entry ->
                        items.add(
                            entry.toDomainMediaItemUnique(
                                playlistId = playlistId,
                                number = index + 1,
                                baseUrl = baseUrl,
                                usedIds = usedIds,
                            ),
                        )
                    }
                    store.savePlaylist(playlist.copy(channelCount = items.size))
                    mediaStore.replacePlaylistItems(playlistId, items)
                }
            }
            Success(playlist)
        } catch (e: Exception) {
            e.rethrowAndroidCancellation()
            Failure(AppError.Database(e))
        }
    }

    private fun deriveName(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        val host = withoutScheme.substringBefore("/", withoutScheme)
        return host.ifBlank { url }
    }

    private fun baseUrlOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val hostStart = schemeEnd + 3
        val hostEnd = url.indexOf('/', hostStart)
        return if (hostEnd < 0) url else url.substring(0, hostEnd + 1)
    }

    private fun M3uChannel.toDomainChannel(playlistId: String, number: Int, baseUrl: String): Channel {
        val resolvedUrl = resolveUrl(streamUrl, baseUrl)
        return Channel(
            id = IdGenerator.channelId(playlistId, tvgId, name, resolvedUrl),
            name = name.ifBlank { "Канал $number" },
            number = number,
            category = groupTitle?.trim().takeIf { !it.isNullOrEmpty() } ?: DEFAULT_CATEGORY,
            streamUrl = resolvedUrl,
            logoUrl = tvgLogo?.trim()?.takeIf { it.isNotEmpty() },
            tvgId = tvgId?.trim()?.takeIf { it.isNotEmpty() },
            tvgName = tvgName?.trim()?.takeIf { it.isNotEmpty() },
            playlistId = playlistId,
            catchup = catchup?.trim()?.takeIf { it.isNotEmpty() },
            catchupDays = catchupDays?.takeIf { it > 0L },
            catchupSource = catchupSource?.trim()?.takeIf { it.isNotEmpty() },
            catchupType = catchupType?.trim()?.takeIf { it.isNotEmpty() },
            timeshift = timeshift?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun M3uChannel.toDomainMediaItem(playlistId: String, number: Int, baseUrl: String): MediaItem {
        val resolvedUrl = resolveUrl(streamUrl, baseUrl)
        return MediaItem(
            id = IdGenerator.mediaItemId(playlistId, tvgId, name, resolvedUrl, season, episode),
            playlistId = playlistId,
            name = name.ifBlank { "Элемент $number" },
            number = number,
            category = groupTitle?.trim().takeIf { !it.isNullOrEmpty() } ?: DEFAULT_CATEGORY,
            streamUrl = resolvedUrl,
            logoUrl = tvgLogo?.trim()?.takeIf { it.isNotEmpty() },
            tvgId = tvgId?.trim()?.takeIf { it.isNotEmpty() },
            tvgName = tvgName?.trim()?.takeIf { it.isNotEmpty() },
            durationSeconds = durationSeconds,
            season = season?.trim()?.takeIf { it.isNotEmpty() },
            episode = episode?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Like [toDomainChannel] but guarantees a UNIQUE id within the playlist.
     * Real sources (e.g. vivamax) reuse the same `tvg-id` for several channels;
     * the channel table's PRIMARY KEY (playlist_id, id) would silently drop all
     * but the last one. Conflicts fall back to a deterministic URL-based id,
     * then to a numbered suffix (fully identical rows).
     */
    private fun M3uChannel.toDomainChannelUnique(
        playlistId: String,
        number: Int,
        baseUrl: String,
        usedIds: MutableSet<String>,
    ): Channel {
        val base = toDomainChannel(playlistId, number, baseUrl)
        if (usedIds.add(base.id)) return base
        var id = "ch-${IdGenerator.stableHash("channel|$playlistId|url|${base.streamUrl.trim()}")}"
        var suffix = 2
        while (!usedIds.add(id)) {
            id = "ch-${IdGenerator.stableHash("channel|$playlistId|url|${base.streamUrl.trim()}|#${suffix++}")}"
        }
        return base.copy(id = id)
    }

    /**
     * Like [toDomainMediaItem] but guarantees a UNIQUE id within the playlist
     * (same duplicate-`tvg-id` problem as channels).
     */
    private fun M3uChannel.toDomainMediaItemUnique(
        playlistId: String,
        number: Int,
        baseUrl: String,
        usedIds: MutableSet<String>,
    ): MediaItem {
        val base = toDomainMediaItem(playlistId, number, baseUrl)
        if (usedIds.add(base.id)) return base
        var id = "mi-${IdGenerator.stableHash("media|$playlistId|url|${base.streamUrl.trim()}")}"
        var suffix = 2
        while (!usedIds.add(id)) {
            id = "mi-${IdGenerator.stableHash("media|$playlistId|url|${base.streamUrl.trim()}|#${suffix++}")}"
        }
        return base.copy(id = id)
    }

    private fun resolveUrl(streamUrl: String, baseUrl: String): String {
        val trimmed = streamUrl.trim()
        val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (hasScheme || baseUrl.isEmpty()) trimmed else baseUrl + trimmed
    }

    private companion object {
        const val DEFAULT_CATEGORY = "Другое"

        /**
         * Bumped every time the M3U parser / id scheme changes in a way that
         * existing databases lack. Version 1 = pre-catch-up; version 2 added the
         * catch-up attributes; version 3 introduced the STABLE channel/media
         * ids (tvg-id → name → URL) and rewrites old ids on upgrade.
         */
        const val CHANNEL_IMPORT_VERSION = 3
        const val KEY_IMPORT_VERSION = "channel.import_version"
        const val LEGACY_IMPORT_VERSION = 1
    }
}
