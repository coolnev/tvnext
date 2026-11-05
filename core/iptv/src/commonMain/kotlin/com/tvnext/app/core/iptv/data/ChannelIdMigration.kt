package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.database.TvNextDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time migration of channel / media-item ids to the stable identity
 * scheme ([IdGenerator]: `tvg-id`, then normalized name, URL only as the last
 * resort).
 *
 * Older app versions computed ids from `playlist + tvg-id + name + streamUrl`.
 * A provider rotating its CDN/URL would have silently "lost" the channel (new
 * id → favorites/history/archive/schedule no longer matched). This migration
 * rewrites every existing id ONCE and carries the user data with it:
 *
 *  - `channel.id` (favorites live on the row) and any duplicate rows with the
 *    same new identity are merged;
 *  - `history.item_id` (LIVE channels and VOD items);
 *  - `archive_capability.channel_id`;
 *  - `scheduled_programme.channel_id`;
 *  - `app_meta` VOD resume keys (`vod.position.<id>`) and
 *    `settings.last_channel_id`.
 *
 * The whole pass runs inside a single transaction (atomic), is idempotent
 * (channels already on the new scheme are skipped) and is invoked only when
 * the import version is bumped — never on a hot path.
 */
class ChannelIdMigration(
    private val database: TvNextDatabase,
) {

    /** Rewrites all ids; throws on failure so the caller can retry later. */
    suspend fun migrate() = withContext(Dispatchers.Default) {
        database.transaction {
            val channels = database.channelQueries.selectAllChannels().executeAsList()
            val mediaItems = database.mediaItemQueries.selectAllMediaItems().executeAsList()

            val channelNewIdByOld = HashMap<String, String>(channels.size)
            channels.forEach { row ->
                val newId = IdGenerator.channelId(row.playlist_id, row.tvg_id, row.name, row.stream_url)
                channelNewIdByOld[row.id] = newId
                if (newId != row.id) migrateChannel(row.id, newId)
            }

            val mediaNewIdByOld = HashMap<String, String>(mediaItems.size)
            mediaItems.forEach { row ->
                val newId = IdGenerator.mediaItemId(
                    row.playlist_id,
                    row.tvg_id,
                    row.name,
                    row.stream_url,
                    row.season,
                    row.episode,
                )
                mediaNewIdByOld[row.id] = newId
                if (newId != row.id) migrateMediaItem(row.id, newId)
            }

            remapMetaKeys(channelNewIdByOld, mediaNewIdByOld)
        }
    }

    private fun migrateChannel(oldId: String, newId: String) {
        val channelQueries = database.channelQueries
        val existing = channelQueries.selectChannelById(newId).executeAsOneOrNull()
        if (existing != null) {
            // Two rows collapsed onto one identity (e.g. a channel duplicated
            // in one playlist with two URLs). Keep the favorite if ANY copy had it.
            val oldFavorite = channelQueries.selectChannelById(oldId).executeAsOneOrNull()?.is_favorite == 1L
            if (existing.is_favorite != 1L && oldFavorite) {
                channelQueries.setFavorite(1L, newId)
            }
            channelQueries.deleteChannelById(oldId)
        } else {
            channelQueries.updateChannelId(newId, oldId)
        }

        // Watch history (LIVE). Drop a conflicting entry at the new id first
        // (the row is the same channel, keeping one entry is correct).
        database.historyQueries.deleteHistoryByItem(newId, "LIVE")
        database.historyQueries.updateHistoryItemId(newId, oldId, "LIVE")

        // Archive capability (PK = channel_id → drop the new-id row first).
        database.archiveCapabilityQueries.deleteArchiveCapability(newId)
        database.archiveCapabilityQueries.updateArchiveCapabilityChannelId(newId, oldId)

        // Scheduled programmes: the unique key (playlist, channel, start) must
        // not collide — remove a conflicting entry before rewriting.
        database.scheduledQueries.selectAllScheduled().executeAsList()
            .filter { it.channel_id == oldId }
            .forEach { scheduled ->
                database.scheduledQueries.deleteScheduled(scheduled.playlist_id, newId, scheduled.start_time)
            }
        database.scheduledQueries.updateScheduledChannelId(newId, oldId)
    }

    private fun migrateMediaItem(oldId: String, newId: String) {
        val mediaQueries = database.mediaItemQueries
        val existing = mediaQueries.selectMediaItemById(newId).executeAsOneOrNull()
        if (existing != null) {
            val oldFavorite = mediaQueries.selectMediaItemById(oldId).executeAsOneOrNull()?.is_favorite == 1L
            if (existing.is_favorite != 1L && oldFavorite) {
                mediaQueries.setMediaFavorite(1L, newId)
            }
            mediaQueries.deleteMediaItemById(oldId)
        } else {
            mediaQueries.updateMediaItemId(newId, oldId)
        }

        database.historyQueries.deleteHistoryByItem(newId, "VOD")
        database.historyQueries.updateHistoryItemId(newId, oldId, "VOD")
    }

    private fun remapMetaKeys(
        channelNewIdByOld: Map<String, String>,
        mediaNewIdByOld: Map<String, String>,
    ) {
        val metaQueries = database.appMetaQueries
        metaQueries.selectAll().executeAsList().forEach { row ->
            val key = row.key
            when {
                key.startsWith(VOD_POSITION_PREFIX) -> {
                    val oldId = key.removePrefix(VOD_POSITION_PREFIX)
                    val newId = mediaNewIdByOld[oldId] ?: return@forEach
                    if (newId != oldId) {
                        metaQueries.upsert(VOD_POSITION_PREFIX + newId, row.value_)
                        metaQueries.deleteByKey(key)
                    }
                }

                key == LAST_CHANNEL_KEY -> {
                    val newId = channelNewIdByOld[row.value_] ?: return@forEach
                    if (newId != row.value_) {
                        metaQueries.upsert(key, newId)
                    }
                }
            }
        }
    }

    private companion object {
        const val VOD_POSITION_PREFIX = "vod.position."
        const val LAST_CHANNEL_KEY = "settings.last_channel_id"
    }
}
