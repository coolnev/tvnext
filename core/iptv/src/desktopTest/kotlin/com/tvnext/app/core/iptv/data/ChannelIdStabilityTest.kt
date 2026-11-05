package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.common.result.AppResult
import com.tvnext.app.core.database.SqlDelightAppMetaRepository
import com.tvnext.app.core.iptv.archive.ArchiveStatus
import com.tvnext.app.core.iptv.domain.Channel
import com.tvnext.app.core.iptv.domain.Playlist
import com.tvnext.app.core.iptv.domain.PlaylistType
import com.tvnext.app.core.iptv.domain.WatchContentType
import com.tvnext.app.core.iptv.m3u.M3uParser
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stable channel identity (tvg-id → normalized name → URL as last resort) and
 * the one-time migration that rewrites OLD ids (which included the stream URL)
 * without losing favorites / history / archive / schedule / resume data.
 */
class ChannelIdStabilityTest {

    private class FakeM3uSource(
        var contents: MutableMap<String, String>,
    ) : M3uSource {
        override suspend fun fetch(url: String): AppResult<String> {
            val content = contents[url] ?: return AppResult.Failure(
                com.tvnext.app.core.common.result.AppError.Network(),
            )
            return AppResult.Success(content)
        }
    }

    private val PLAYLIST_A = "http://a.example/pl.m3u"

    private fun m3u(name: String, tvgId: String?, url: String): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXTINF:-1 tvg-id=\"${tvgId.orEmpty()}\" group-title=\"Новости\",$name")
        appendLine(url)
    }

    private fun createRepo(
        source: M3uSource,
        database: com.tvnext.app.core.database.TvNextDatabase = createTestDatabase(),
    ): IptvPlaylistRepository = IptvPlaylistRepository(
        source = source,
        parser = M3uParser(),
        store = SqlDelightChannelStore(database),
        mediaStore = SqlDelightMediaItemStore(database),
        epgStore = SqlDelightEpgStore(database),
        archiveCapabilityStore = com.tvnext.app.core.iptv.archive.SqlDelightArchiveCapabilityStore(database),
        scheduledRepository = SqlDelightScheduledRepository(database),
        metaRepository = SqlDelightAppMetaRepository(database),
        idMigration = ChannelIdMigration(database),
    )

    // ------------------------------------------------------------------ ids --

    @Test
    fun channelIdIgnoresStreamUrlWhenTvgIdPresent() {
        val withTvgId = IdGenerator.channelId("pl", "news1", "Новости", "http://old/stream.m3u8")
        val afterUrlChange = IdGenerator.channelId("pl", "news1", "Новости", "http://new-cdn/stream.m3u8?token=xyz")
        assertEquals(withTvgId, afterUrlChange, "URL must not participate when tvg-id exists")
    }

    @Test
    fun channelIdUsesNameWhenNoTvgId() {
        val before = IdGenerator.channelId("pl", null, "Новости", "http://old/stream.m3u8")
        val afterUrlChange = IdGenerator.channelId("pl", null, "Новости", "http://new/stream.m3u8")
        assertEquals(before, afterUrlChange, "URL must not participate when name exists")
        val afterNameChange = IdGenerator.channelId("pl", null, "Новости HD", "http://old/stream.m3u8")
        assertNotEquals(before, afterNameChange, "name change is a different channel when no tvg-id")
    }

    @Test
    fun channelIdFallsBackToUrlOnlyWhenNoIdentity() {
        val a = IdGenerator.channelId("pl", null, "  ", "http://host/one.m3u8")
        val b = IdGenerator.channelId("pl", null, "  ", "http://host/two.m3u8")
        assertNotEquals(a, b, "without name/tvg-id the URL is the only identity")
    }

    @Test
    fun nameIdentityIsCaseAndWhitespaceInsensitive() {
        val a = IdGenerator.channelId("pl", null, "Новости 24", "http://host/x.m3u8")
        val b = IdGenerator.channelId("pl", null, "новости24", "http://host/y.m3u8")
        assertEquals(a, b, "normalized name identity")
    }

    @Test
    fun sameChannelInDifferentPlaylistsNeverCollides() {
        val inA = IdGenerator.channelId("pl-a", "news1", "Новости", "http://a/x.m3u8")
        val inB = IdGenerator.channelId("pl-b", "news1", "Новости", "http://b/x.m3u8")
        assertNotEquals(inA, inB, "playlist is always part of the identity")
    }

    @Test
    fun mediaItemIdIgnoresUrlWhenTvgIdPresent() {
        val before = IdGenerator.mediaItemId("pl", "film-1", "Фильм 1", "http://old/film.m3u8")
        val after = IdGenerator.mediaItemId("pl", "film-1", "Фильм 1", "http://new/film.m3u8")
        assertEquals(before, after)
    }

    @Test
    fun mediaItemIdKeepsSeriesParts() {
        val s1e2 = IdGenerator.mediaItemId("pl", "s1", "Сериал", "http://h/s1e2.m3u8", "1", "2")
        val s1e3 = IdGenerator.mediaItemId("pl", "s1", "Сериал", "http://h/s1e3.m3u8", "1", "3")
        assertNotEquals(s1e2, s1e3, "season/episode separate episodes of one series")
    }

    // ------------------------------------------- integration: refresh keeps id --

    @Test
    fun refreshWithChangedUrlKeepsIdAndUserData() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(PLAYLIST_A to m3u("Новости", "news1", "http://host/old.m3u8")),
        )
        val database = createTestDatabase()
        val repo = createRepo(source, database)
        val store = SqlDelightChannelStore(database)
        val history = SqlDelightHistoryStore(database)

        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        val channel = store.getChannels().single()
        val idBefore = channel.id

        store.setFavorite(channel.id, true)
        history.add(channel.id, WatchContentType.LIVE, 1_700_000_000_000L)

        // The provider rotates the CDN URL ONLY — same tvg-id, same name.
        source.contents[PLAYLIST_A] = m3u("Новости", "news1", "http://cdn-new.example/stream.m3u8?token=abc")
        val result = repo.refreshPlaylist(IdGenerator.playlistId(PLAYLIST_A))
        assertTrue(result is AppResult.Success)

        val after = store.getChannels().single()
        assertEquals(idBefore, after.id, "URL change must keep the stable channel id")
        assertTrue(after.isFavorite, "favorite survives the refresh")
        assertEquals("news1", after.tvgId)
        assertEquals("http://cdn-new.example/stream.m3u8?token=abc", after.streamUrl)
        assertEquals(idBefore, history.getRecent(10).single().itemId, "history survives the refresh")
    }

    @Test
    fun refreshWithoutTvgIdKeepsIdWhenOnlyUrlChanges() = runBlocking {
        val source = FakeM3uSource(
            mutableMapOf(PLAYLIST_A to m3u("Новости", null, "http://host/old.m3u8")),
        )
        val database = createTestDatabase()
        val repo = createRepo(source, database)
        val store = SqlDelightChannelStore(database)

        repo.addPlaylist(PLAYLIST_A, PlaylistType.LIVE)
        val idBefore = store.getChannels().single().id

        source.contents[PLAYLIST_A] = m3u("Новости", null, "http://host/new.m3u8")
        repo.refreshPlaylist(IdGenerator.playlistId(PLAYLIST_A))

        assertEquals(idBefore, store.getChannels().single().id)
    }

    // ------------------------------------------------------ one-time migration --

    /** The OLD (pre-hardening) id formula: playlist + tvg-id + name + URL. */
    private fun legacyChannelId(playlistId: String, tvgId: String?, name: String, url: String): String =
        "ch-${IdGenerator.stableHash(listOf(playlistId, tvgId.orEmpty(), name, url).joinToString("|"))}"

    @Test
    fun migrationRewritesIdsAndCarriesUserData() = runBlocking {
        val database = createTestDatabase()
        val store = SqlDelightChannelStore(database)
        val history = SqlDelightHistoryStore(database)
        val archiveStore = com.tvnext.app.core.iptv.archive.SqlDelightArchiveCapabilityStore(database)
        val scheduledRepo = SqlDelightScheduledRepository(database)
        val meta = SqlDelightAppMetaRepository(database)

        // Seed the database with the LEGACY id (URL baked into the hash).
        val legacyId = legacyChannelId("pl1", "news1", "Новости", "http://old/stream.m3u8")
        store.savePlaylist(
            Playlist(
                id = "pl1",
                name = "Плейлист",
                url = PLAYLIST_A,
                type = PlaylistType.LIVE,
            ),
        )
        store.replacePlaylistChannels(
            "pl1",
            listOf(
                Channel(
                    id = legacyId,
                    name = "Новости",
                    number = 1,
                    category = "Новости",
                    streamUrl = "http://old/stream.m3u8",
                    tvgId = "news1",
                    playlistId = "pl1",
                ),
            ),
        )
        store.setFavorite(legacyId, true)
        history.add(legacyId, WatchContentType.LIVE, 1_700_000_000_000L)
        archiveStore.upsert(
            com.tvnext.app.core.iptv.archive.ArchiveCapability(
                channelId = legacyId,
                status = ArchiveStatus.AVAILABLE,
                declaredDays = 7,
            ),
        )
        scheduledRepo.schedule(
            channel = Channel(
                id = legacyId,
                name = "Новости",
                number = 1,
                category = "Новости",
                streamUrl = "http://old/stream.m3u8",
                tvgId = "news1",
                playlistId = "pl1",
            ),
            programme = EpgProgramme(
                channelId = "news1",
                start = 1_800_000_000_000L,
                stop = 1_800_003_600_000L,
                title = "Вечерние новости",
            ),
            now = 1_700_000_000_000L,
        )
        // VOD resume position is keyed by the (legacy) media-item id.
        val legacyMediaId = "mi-${IdGenerator.stableHash("pl1|film-1|Фильм 1|http://old/film.m3u8")}"
        val mediaStore = SqlDelightMediaItemStore(database)
        mediaStore.replacePlaylistItems(
            "pl1",
            listOf(
                com.tvnext.app.core.iptv.domain.MediaItem(
                    id = legacyMediaId,
                    playlistId = "pl1",
                    name = "Фильм 1",
                    number = 1,
                    category = "Фильмы",
                    streamUrl = "http://old/film.m3u8",
                    tvgId = "film-1",
                ),
            ),
        )
        meta.setValue("vod.position.$legacyMediaId", "42000")

        ChannelIdMigration(database).migrate()

        val expectedNewId = IdGenerator.channelId("pl1", "news1", "Новости", "http://old/stream.m3u8")
        val expectedNewMediaId = IdGenerator.mediaItemId("pl1", "film-1", "Фильм 1", "http://old/film.m3u8")
        val migrated = store.getChannels().single()
        assertEquals(expectedNewId, migrated.id, "legacy id must be rewritten to the stable id")
        assertTrue(migrated.isFavorite, "favorite travels to the new id")
        assertEquals(expectedNewId, history.getRecent(10).single().itemId, "history travels to the new id")
        assertEquals(
            expectedNewId,
            archiveStore.getByPlaylist("pl1").single().channelId,
            "archive capability travels to the new id",
        )
        val scheduled = scheduledRepo.getAll()
        assertEquals(expectedNewId, scheduled.single().channelId, "schedule travels to the new id")
        assertEquals(
            expectedNewMediaId,
            mediaStore.getItems("pl1").single().id,
            "media item id is rewritten to the stable id",
        )
        assertEquals(
            "42000",
            meta.getValue("vod.position.$expectedNewMediaId"),
            "VOD resume position is remapped to the new id",
        )
        assertEquals(null, meta.getValue("vod.position.$legacyMediaId"), "old resume key is removed")
    }

    @Test
    fun migrationIsIdempotent() = runBlocking {
        val database = createTestDatabase()
        val store = SqlDelightChannelStore(database)
        val legacyId = legacyChannelId("pl1", "news1", "Новости", "http://old/stream.m3u8")
        store.savePlaylist(
            Playlist(id = "pl1", name = "Плейлист", url = PLAYLIST_A, type = PlaylistType.LIVE),
        )
        store.replacePlaylistChannels(
            "pl1",
            listOf(
                Channel(
                    id = legacyId,
                    name = "Новости",
                    number = 1,
                    category = "Новости",
                    streamUrl = "http://old/stream.m3u8",
                    tvgId = "news1",
                    playlistId = "pl1",
                ),
            ),
        )

        val migration = ChannelIdMigration(database)
        migration.migrate()
        val afterFirst = store.getChannels().single().id
        migration.migrate()
        assertEquals(afterFirst, store.getChannels().single().id, "second run must be a no-op")
    }
}
