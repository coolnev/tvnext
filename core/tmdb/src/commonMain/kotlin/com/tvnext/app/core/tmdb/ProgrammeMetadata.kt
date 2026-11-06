package com.tvnext.app.core.tmdb

import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.database.AppMetaRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A programme card has no fake image placeholder: if both URLs fail, use all the width for text. */
data class ProgrammeMetadata(
    val overview: String? = null,
    val frameUrl: String? = null,
    val posterUrl: String? = null,
)

interface KinopoiskKeyStore {
    suspend fun getKey(): String
    suspend fun setKey(value: String)
}

class SqlDelightKinopoiskKeyStore(private val meta: AppMetaRepository,
    private val defaultKey: String = "") : KinopoiskKeyStore {
    override suspend fun getKey(): String = meta.getValue("settings.kinopoisk_backup_key")
        ?.trim()?.takeIf { it.isNotEmpty() } ?: defaultKey.trim()
    override suspend fun setKey(value: String) { meta.setValue("settings.kinopoisk_backup_key", value.trim()) }
}

/** Uses the configured key store; credential values are never logged. */
class KinopoiskProgrammeLookup(private val client: HttpClient, private val keys: KinopoiskKeyStore) {
    // A channel grid can request many distinct films simultaneously. Limit
    // competing API requests to avoid exhausting the service or TV sockets.
    private val permits = Semaphore(2)
    @Volatile private var backoffUntil = 0L
    @Volatile private var temporaryFailureAt = 0L
    fun resetAfterKeyChange() { backoffUntil = 0L; temporaryFailureAt = 0L }
    fun temporaryFailureRecently(): Boolean =
        currentTimeMillis() - temporaryFailureAt in 0L..60_000L
    suspend fun hasKey(): Boolean = keys.getKey().isNotBlank()

    suspend fun testConnection(): Boolean {
        val token = keys.getKey().trim()
        if (token.isEmpty()) return false
        return withTimeoutOrNull(5_000L) {
            runCatching {
                client.get("https://api.poiskkino.dev/v1.5/movie") {
                    header("X-API-KEY", token)
                    parameter("limit", 1)
                    timeout { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000 }
                }.status.isSuccess()
            }.getOrDefault(false)
        } ?: false
    }

    suspend fun lookup(name: String, onUpdate: ((ProgrammeMetadata) -> Unit)? = null): ProgrammeMetadata? {
        val query = cleanProgrammeTitle(name)
        val token = keys.getKey().trim()
        if (query.isBlank() || token.isEmpty() || currentTimeMillis() < backoffUntil) return null
        return permits.withPermit {
            if (currentTimeMillis() < backoffUntil) return@withPermit null
            var serviceResponded = false
            var best: ProgrammeMetadata? = null
            val result = withTimeoutOrNull(16_000L) {
                try {
                    // Retry one transient server error; never retry a bad key
                    // or a rate-limited account in a tight loop.
                    var body: String? = null
                    for (attempt in 0..1) {
                        val response = client.get("https://api.poiskkino.dev/v1.5/movie/search") {
                            header("X-API-KEY", token)
                            parameter("page", 1)
                            parameter("limit", 20)
                            parameter("query", query)
                            timeout {
                                requestTimeoutMillis = 7_000
                                connectTimeoutMillis = 3_000
                                socketTimeoutMillis = 7_000
                            }
                        }
                        val code = response.status.value
                        if (code in 200..299) {
                            serviceResponded = true
                            body = response.bodyAsText()
                            break
                        }
                        if (code == 401 || code == 403 || code == 429) {
                            // 403 may mean daily quota exceeded (not just bad key).
                            backoffUntil = currentTimeMillis() + when (code) {
                                429 -> 60_000L
                                else -> 10L * 60_000L
                            }
                            temporaryFailureAt = currentTimeMillis()
                            return@withTimeoutOrNull null
                        }
                        if (code >= 500 && attempt == 0) {
                            delay(750)
                            continue
                        }
                        temporaryFailureAt = currentTimeMillis()
                        return@withTimeoutOrNull null
                    }
                    val responseBody = body ?: return@withTimeoutOrNull null
                    val card = parseKinopoiskSearch(responseBody, name)
                        ?: return@withTimeoutOrNull null
                    best = card
                    onUpdate?.invoke(card)
                    if (!card.overview.isNullOrBlank() && card.overview.length >= 120 &&
                        card.frameUrl != null && card.posterUrl != null)
                        return@withTimeoutOrNull card
                    val movieId = kinopoiskMatchedId(responseBody, name)
                    val details = movieId?.let { id ->
                        try {
                            var detail: ProgrammeMetadata? = null
                            for (attempt in 0..1) {
                                val response = try {
                                    client.get("https://api.poiskkino.dev/v1.5/movie/$id") {
                                        header("X-API-KEY", token)
                                        timeout { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 4_000 }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    if (attempt == 0) { delay(400); continue }
                                    temporaryFailureAt = currentTimeMillis()
                                    break
                                }
                                if (response.status.isSuccess()) {
                                    detail = parseKinopoiskDetails(response.bodyAsText())
                                    break
                                }
                                temporaryFailureAt = currentTimeMillis()
                                if (response.status.value >= 500 && attempt == 0) { delay(400); continue }
                                break
                            }
                            detail
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                    }
                    val combined = card.copy(
                        overview = details?.overview?.takeIf(::hasProgrammeSynopsis)
                            ?: card.overview?.takeIf(::hasProgrammeSynopsis),
                        frameUrl = details?.frameUrl ?: card.frameUrl,
                        posterUrl = details?.posterUrl ?: card.posterUrl,
                    )
                    best = combined
                    onUpdate?.invoke(combined)
                    if (movieId != null && (combined.frameUrl == null || combined.posterUrl == null)) {
                        try {
                            val images = client.get("https://api.poiskkino.dev/v1.5/image") {
                                header("X-API-KEY", token)
                                parameter("movieId", movieId)
                                parameter("limit", 12)
                                timeout { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000 }
                            }
                            if (images.status.isSuccess()) {
                                val artwork = parseKinopoiskImages(images.bodyAsText())
                                best = combined.copy(frameUrl = combined.frameUrl ?: artwork.frameUrl,
                                    posterUrl = combined.posterUrl ?: artwork.posterUrl)
                                best?.let { onUpdate?.invoke(it) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep the real search/details card. */ }
                    }
                    best
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    temporaryFailureAt = currentTimeMillis()
                    null
                }
            }
            if (result == null && !serviceResponded) {
                // A timeout differs from a confirmed empty search result.
                temporaryFailureAt = currentTimeMillis()
            }
            result ?: best
        }
    }
}

/** Search and detail requests must use the same ranked hit and movie id. */
private fun matchedKinopoiskMovie(body: String, name: String): JsonObject? {
    val docs = Json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray ?: return null
    val year = programmeReleaseYear(name)
    return docs.mapNotNull { it as? JsonObject }.mapNotNull { entry ->
        val titles = listOf("name", "alternativeName", "enName").mapNotNull { entry[it]?.jsonPrimitive?.content } +
            (entry["names"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.content
            }
        if (titles.none { similarProgrammeTitle(it, name) }) null
        else entry to (if (year != null && entry["year"]?.jsonPrimitive?.content?.toIntOrNull() == year) 10 else 0)
    }.maxByOrNull { it.second }?.first
}

internal fun kinopoiskMatchedId(body: String, name: String): Int? =
    matchedKinopoiskMovie(body, name)?.get("id")?.jsonPrimitive?.content?.toIntOrNull()

private fun kinopoiskOverview(full: String?, short: String?): String? =
    full?.takeIf(::hasProgrammeSynopsis) ?: short?.takeIf(::hasProgrammeSynopsis) ?: full ?: short

internal fun parseKinopoiskDetails(body: String): ProgrammeMetadata {
    val item = Json.parseToJsonElement(body).jsonObject
    fun field(key: String) = item[key]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() && it != "null" }
    fun image(key: String) = (item[key] as? JsonObject)?.get("url")
        ?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    return ProgrammeMetadata(
        overview = kinopoiskOverview(field("description"), field("shortDescription")),
        frameUrl = image("backdrop"),
        posterUrl = image("poster"),
    )
}

/** The image endpoint includes stills even when the film has no backdrop field. */
internal fun parseKinopoiskImages(body: String): ProgrammeMetadata {
    val docs = Json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray.orEmpty()
        .mapNotNull { it as? JsonObject }
    fun url(item: JsonObject): String? = listOf("previewUrl", "url").firstNotNullOfOrNull { key ->
        item[key]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    }
    fun type(item: JsonObject) = item["type"]?.jsonPrimitive?.content?.lowercase().orEmpty()
    val frame = docs.firstOrNull { type(it) in setOf("still", "stills", "frame", "screenshot", "backdrop", "backdrops") && url(it) != null }
    val poster = docs.firstOrNull { type(it) in setOf("poster", "posters", "cover") && url(it) != null }
    return ProgrammeMetadata(frameUrl = frame?.let(::url), posterUrl = poster?.let(::url))
}

/** Kept separate from the network call for deterministic fixture tests. */
internal fun parseKinopoiskSearch(body: String, name: String): ProgrammeMetadata? {
    val match = matchedKinopoiskMovie(body, name) ?: return null
    fun text(key: String): String? = match[key]?.jsonPrimitive?.content
        ?.takeIf { it != "null" && it.isNotBlank() }
    fun imageUrl(key: String): String? = (match[key] as? JsonObject)?.get("url")
        ?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    return ProgrammeMetadata(
        overview = kinopoiskOverview(text("description"), text("shortDescription")),
        frameUrl = imageUrl("backdrop"),
        posterUrl = imageUrl("poster"),
    )
}

/** One cache per application; partial descriptions are published before artwork completes. */
class ProgrammeMetadataRepository(
    private val tmdb: TmdbRepository,
    private val kinopoisk: KinopoiskProgrammeLookup,
    private val tmdbSettings: TmdbSettingsStore,
) {
    private data class Cached(val result: ProgrammeMetadata?, val time: Long)
    private val cache = LinkedHashMap<String, Cached>()
    private val cacheGuard = Any()
    private var cacheRevision = 0L
    private val mutex = Mutex()
    private class Lookup(val lock: Mutex = Mutex(), var readers: Int = 0,
        val updates: MutableStateFlow<ProgrammeMetadata?> = MutableStateFlow(null))
    private val lookups = hashMapOf<String, Lookup>()
    private val networkSlots = Semaphore(2)

    suspend fun clearCache() { synchronized(cacheGuard) { cache.clear(); cacheRevision++ } }

    fun observe(title: String): Flow<ProgrammeMetadata?> = channelFlow {
        val result = find(title) { trySend(it) }
        trySend(result)
    }

    suspend fun find(title: String, onUpdate: ((ProgrammeMetadata) -> Unit)? = null): ProgrammeMetadata? {
        val raw = title.trim().take(180)
        val query = cleanProgrammeTitle(raw)
        if (query.length < 3) return null
        val year = programmeReleaseYear(raw)?.toString()
        val key = normalizedProgrammeTitle(query) + "|" + year.orEmpty()
        val flight = mutex.withLock { lookups.getOrPut(key) { Lookup() }.also { it.readers++ } }
        return try {
            coroutineScope {
                val updates = onUpdate?.let { callback -> launch(start = CoroutineStart.UNDISPATCHED) {
                    flight.updates.filterNotNull().collect { callback(it) }
                } }
                try {
                    flight.lock.withLock { networkSlots.withPermit {
                        withContext(Dispatchers.IO) { lookup(raw, query, year, key) { flight.updates.value = it } }
                    } }
                } finally { updates?.cancelAndJoin() }
            }
        } finally { withContext(NonCancellable) {
            mutex.withLock { if (--flight.readers == 0 && lookups[key] === flight) lookups.remove(key) }
        } }
    }

    private suspend fun lookup(raw: String, query: String, year: String?, key: String,
        publish: (ProgrammeMetadata) -> Unit): ProgrammeMetadata? {
        val now = currentTimeMillis()
        val revision = synchronized(cacheGuard) {
            cache[key]?.takeIf { now - it.time < when {
                it.result == null -> 20_000L
                !hasProgrammeSynopsis(it.result.overview) || it.result.frameUrl == null || it.result.posterUrl == null -> 30_000L
                else -> 24 * 60 * 60_000L
            } }?.let { return it.result }
            cacheRevision
        }
        val progressGuard = Any()
        var tmdbCard: ProgrammeMetadata? = null
        var kinoCard: ProgrammeMetadata? = null
        fun combine(): ProgrammeMetadata? {
            if (tmdbCard == null && kinoCard == null) return null
            return ProgrammeMetadata(
                overview = kinoCard?.overview?.takeIf(::hasProgrammeSynopsis)
                    ?: tmdbCard?.overview?.takeIf(::hasProgrammeSynopsis),
                frameUrl = tmdbCard?.frameUrl ?: kinoCard?.frameUrl,
                posterUrl = tmdbCard?.posterUrl ?: kinoCard?.posterUrl,
            ).takeIf { it.overview != null || it.frameUrl != null || it.posterUrl != null }
        }
        fun cacheResult(result: ProgrammeMetadata?) = synchronized(cacheGuard) {
            if (revision == cacheRevision) {
                if (cache.size >= 256 && key !in cache) cache.remove(cache.keys.first())
                cache[key] = Cached(result, currentTimeMillis())
            }
        }
        fun progress(kino: Boolean, card: ProgrammeMetadata?) {
            if (card == null) return
            synchronized(progressGuard) {
                val previous = if (kino) kinoCard else tmdbCard
                val improved = card.copy(
                    overview = card.overview?.takeIf(::hasProgrammeSynopsis) ?: previous?.overview,
                    frameUrl = card.frameUrl ?: previous?.frameUrl,
                    posterUrl = card.posterUrl ?: previous?.posterUrl,
                )
                if (kino) kinoCard = improved else tmdbCard = improved
                combine()?.let { cacheResult(it); publish(it) }
            }
        }
        // A slow/unavailable TMDB must not delay a configured Kinopoisk source.
        coroutineScope {
            launch {
                try {
                    if (tmdbSettings.isEnabled()) {
                        val card = withTimeoutOrNull(12_000L) { lookupTmdb(raw, query, year) { progress(false, it) } }
                        progress(false, card)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The other provider can still supply a real card. */ }
            }
            launch {
                try { progress(true, kinopoisk.lookup(raw) { progress(true, it) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep already published metadata. */ }
            }
        }
        val result = synchronized(progressGuard) { combine() }
        if (result != null || !kinopoisk.temporaryFailureRecently()) cacheResult(result)
        return result
    }

    private suspend fun lookupTmdb(clean: String, query: String, year: String?,
        publish: (ProgrammeMetadata) -> Unit): ProgrammeMetadata? {
        var searchCard: ProgrammeMetadata? = null
        return try {
            val results = tmdb.searchMovie(query)
            val movie = results.firstOrNull { similarProgrammeTitle(it.title ?: it.name, clean) &&
                (year == null || (it.release_date ?: it.first_air_date)?.startsWith(year) == true) }
                ?: results.firstOrNull { similarProgrammeTitle(it.title ?: it.name, clean) }
                ?: results.firstOrNull { similarProgrammeTitle(it.original_title ?: it.original_name, clean) }
            val details: TmdbDetails?
            val englishDescription: String?
            if (movie != null) {
                searchCard = ProgrammeMetadata(movie.overview, tmdbImageUrl(movie.backdrop_path, "w780"), tmdbImageUrl(movie.poster_path, "w342"))
                publish(searchCard)
                details = safeDetails(movie, false)
                publish(ProgrammeMetadata(details.overview, tmdbImageUrl(details.backdrop_path, "w780"), tmdbImageUrl(details.poster_path, "w342")))
                englishDescription = if (!hasProgrammeSynopsis(details.overview))
                    runCatching { tmdb.getMovieDetails(movie.id, "en-US").overview }.getOrNull() else null
            } else {
                val series = tmdb.searchTv(query)
                val show = series.firstOrNull { similarProgrammeTitle(it.name ?: it.title, clean) &&
                    (year == null || (it.first_air_date ?: it.release_date)?.startsWith(year) == true) }
                    ?: series.firstOrNull { similarProgrammeTitle(it.name ?: it.title, clean) }
                    ?: series.firstOrNull { similarProgrammeTitle(it.original_name, clean) }
                if (show != null) {
                    searchCard = ProgrammeMetadata(show.overview, tmdbImageUrl(show.backdrop_path, "w780"), tmdbImageUrl(show.poster_path, "w342"))
                    publish(searchCard)
                }
                details = show?.let { safeDetails(it, true) }
                if (details != null) publish(ProgrammeMetadata(details.overview, tmdbImageUrl(details.backdrop_path, "w780"), tmdbImageUrl(details.poster_path, "w342")))
                englishDescription = if (show != null && !hasProgrammeSynopsis(details?.overview))
                    runCatching { tmdb.getTvDetails(show.id, "en-US").overview }.getOrNull() else null
            }
            details?.let {
                ProgrammeMetadata(it.overview?.takeIf(::hasProgrammeSynopsis) ?: englishDescription?.takeIf(::hasProgrammeSynopsis),
                    tmdbImageUrl(it.backdrop_path, "w780"), tmdbImageUrl(it.poster_path, "w342"))
            } ?: searchCard
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { searchCard }
    }

    private suspend fun safeDetails(hit: TmdbSearchResult, tv: Boolean): TmdbDetails {
        val details = try {
            if (tv) tmdb.getTvDetails(hit.id) else tmdb.getMovieDetails(hit.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { TmdbDetails(hit.id) }
        return details.copy(title = details.title ?: hit.title, name = details.name ?: hit.name,
            overview = details.overview?.takeIf(::hasProgrammeSynopsis) ?: hit.overview,
            poster_path = details.poster_path?.takeIf { it.isNotBlank() } ?: hit.poster_path,
            backdrop_path = details.backdrop_path?.takeIf { it.isNotBlank() } ?: hit.backdrop_path)
    }
}
