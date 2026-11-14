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
    suspend fun isEnabled(): Boolean = true
    suspend fun setEnabled(enabled:Boolean) = Unit
    suspend fun getKey(): String
    suspend fun setKey(value: String)
}

class SqlDelightKinopoiskKeyStore(private val meta: AppMetaRepository,
    private val defaultKey: String = "") : KinopoiskKeyStore {
    override suspend fun isEnabled(): Boolean = meta.getValue("settings.kinopoisk_enabled")?.toBooleanStrictOrNull() ?: true
    override suspend fun setEnabled(enabled:Boolean) {meta.setValue("settings.kinopoisk_enabled",enabled.toString())}
    override suspend fun getKey(): String = meta.getValue("settings.kinopoisk_unofficial_key")
        ?.trim()?.takeIf { it.isNotEmpty() } ?: defaultKey.trim()
    override suspend fun setKey(value: String) { meta.setValue("settings.kinopoisk_unofficial_key", value.trim()) }
}

private const val KINOPOISK_API = "https://kinopoiskapiunofficial.tech"

data class KinopoiskConnectionResult(val kind: Kind, val httpStatus: Int? = null) {
    enum class Kind { SUCCESS, KEY_MISSING, INVALID_KEY, LIMIT_REACHED, NO_CONNECTION, SERVER_ERROR, INVALID_RESPONSE }
    val message: String get() = when (kind) {
        Kind.SUCCESS -> "Кинопоиск: соединение установлено"
        Kind.KEY_MISSING -> "Кинопоиск: ключ не задан"
        Kind.INVALID_KEY -> "Кинопоиск: ключ отклонён (HTTP $httpStatus)"
        Kind.LIMIT_REACHED -> "Кинопоиск: лимит запросов исчерпан (HTTP $httpStatus)"
        Kind.NO_CONNECTION -> "Кинопоиск: нет ответа от сервера — проверьте подключение"
        Kind.SERVER_ERROR -> "Кинопоиск: ошибка сервера (HTTP $httpStatus)"
        Kind.INVALID_RESPONSE -> "Кинопоиск: некорректный ответ сервера"
    }
}

/** Uses the configured key store; credential values are never logged. */
class KinopoiskProgrammeLookup(private val client: HttpClient, private val keys: KinopoiskKeyStore) {
    // A channel grid can request many distinct films simultaneously. Limit
    // competing API requests to avoid exhausting the service or TV sockets.
    private val permits = Semaphore(2)
    @Volatile private var backoffUntil = 0L
    @Volatile private var temporaryFailureAt = 0L
    private data class MatchedCard(val id: Int, val card: ProgrammeMetadata, val token: String, val time: Long)
    private val matchedGuard = Any()
    private val matched = LinkedHashMap<String, MatchedCard>()
    fun resetAfterKeyChange() {
        backoffUntil = 0L; temporaryFailureAt = 0L
        synchronized(matchedGuard) { matched.clear() }
    }
    fun temporaryFailureRecently(): Boolean =
        currentTimeMillis() - temporaryFailureAt in 0L..60_000L
    suspend fun isEnabled(): Boolean = keys.isEnabled()
    suspend fun hasKey(): Boolean = keys.getKey().isNotBlank()

    suspend fun checkConnection(): KinopoiskConnectionResult {
        val token = keys.getKey().trim()
        if (token.isEmpty()) return KinopoiskConnectionResult(KinopoiskConnectionResult.Kind.KEY_MISSING)
        return withTimeoutOrNull(5_000L) {
            try {
                val response = client.get("$KINOPOISK_API/api/v2.2/films/301") {
                    header("X-API-KEY", token)
                    timeout { requestTimeoutMillis = 4_000; connectTimeoutMillis = 2_000; socketTimeoutMillis = 4_000 }
                }
                val code = response.status.value
                val kind = when {
                    code == 401 || code == 403 -> KinopoiskConnectionResult.Kind.INVALID_KEY
                    code == 402 || code == 429 -> KinopoiskConnectionResult.Kind.LIMIT_REACHED
                    !response.status.isSuccess() -> KinopoiskConnectionResult.Kind.SERVER_ERROR
                    else -> {
                        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                        if (body?.get("kinopoiskId")?.jsonPrimitive?.content?.toIntOrNull() != null &&
                            listOf("nameRu", "nameEn", "nameOriginal").any { key -> body[key]?.jsonPrimitive?.content?.let { it.isNotBlank() && it != "null" } == true }) {
                            backoffUntil = 0L; temporaryFailureAt = 0L
                            KinopoiskConnectionResult.Kind.SUCCESS
                        } else KinopoiskConnectionResult.Kind.INVALID_RESPONSE
                    }
                }
                KinopoiskConnectionResult(kind, code)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { KinopoiskConnectionResult(KinopoiskConnectionResult.Kind.NO_CONNECTION) }
        } ?: KinopoiskConnectionResult(KinopoiskConnectionResult.Kind.NO_CONNECTION)
    }

    suspend fun testConnection(): Boolean = checkConnection().kind == KinopoiskConnectionResult.Kind.SUCCESS

    suspend fun lookup(name: String, onUpdate: ((ProgrammeMetadata) -> Unit)? = null): ProgrammeMetadata? = lookup(name, onUpdate, ProgrammeKind.CATALOG)

    suspend fun lookup(name: String, onUpdate: ((ProgrammeMetadata) -> Unit)?, kind: ProgrammeKind, request: ProgrammeMetadataRequest? = null): ProgrammeMetadata? {
        if (!keys.isEnabled()) return null
        val query = cleanProgrammeTitle(name)
        val token = keys.getKey().trim()
        if (query.isBlank() || token.isEmpty() || currentTimeMillis() < backoffUntil) return null
        return permits.withPermit {
            if (currentTimeMillis() < backoffUntil) return@withPermit null
            val matchKey = metadataCacheKey(name,kind,request)
            val matchingName = if (programmeReleaseYear(name)==null && request?.releaseYear!=null) "$name (${request.releaseYear})" else name
            val known = synchronized(matchedGuard) { matched[matchKey]?.takeIf {
                it.token == token && currentTimeMillis() - it.time in 0 until 5 * 60_000L
            } }
            var serviceResponded = known != null
            var best: ProgrammeMetadata? = null
            val result = withTimeoutOrNull(16_000L) {
                try {
                    // Retry one transient server error; never retry a bad key
                    // or a rate-limited account in a tight loop.
                    var body: String? = null
                    if (known == null) for (attempt in 0..1) {
                        val response = client.get("$KINOPOISK_API/api/v2.1/films/search-by-keyword") {
                            header("X-API-KEY", token)
                            parameter("page", 1)
                            parameter("keyword", query)
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
                        if (code == 401 || code == 403 || code == 402 || code == 429) {
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
                    val card = known?.card ?: body?.let { parseKinopoiskSearch(it, matchingName, kind) }
                        ?: return@withTimeoutOrNull null
                    val movieId = known?.id ?: body?.let { kinopoiskMatchedId(it, matchingName, kind) }
                    if (movieId != null && (known != null || kind == ProgrammeKind.CATALOG)) synchronized(matchedGuard) {
                        if (matched.size >= 128 && matchKey !in matched) matched.remove(matched.keys.first())
                        matched[matchKey] = MatchedCard(movieId, card, token, currentTimeMillis())
                    }
                    if (known != null || kind == ProgrammeKind.CATALOG) { best = card; onUpdate?.invoke(card) }
                    if ((known != null || kind == ProgrammeKind.CATALOG) && !card.overview.isNullOrBlank() && card.overview.length >= 120 &&
                        card.frameUrl != null && card.posterUrl != null)
                        return@withTimeoutOrNull card
                    val details = movieId?.let { id ->
                        try {
                            var detail: ProgrammeMetadata? = null
                            for (attempt in 0..1) {
                                val response = try {
                                    client.get("$KINOPOISK_API/api/v2.2/films/$id") {
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
                                    val text = response.bodyAsText()
                                    val full = Json.parseToJsonElement(text).jsonObject
                                    val type = full["type"]?.jsonPrimitive?.content?.takeUnless { it == "null" }
                                    val fullYear = full["year"]?.jsonPrimitive?.content?.toIntOrNull()
                                    if ((!type.isNullOrBlank() && !kind.accepts(type)) ||
                                        (kind != ProgrammeKind.CATALOG && (type.isNullOrBlank() || !kind.accepts(type))) ||
                                        (programmeReleaseYear(matchingName) != null && fullYear != null && programmeReleaseYear(matchingName) != fullYear)) {
                                        synchronized(matchedGuard) { matched.remove(matchKey) }
                                        best = null
                                        return@withTimeoutOrNull null
                                    }
                                    if (kind == ProgrammeKind.DISCOVERY && !listOf("nameRu","nameEn","nameOriginal").any {
                                        full[it]?.jsonPrimitive?.content?.let { title -> similarProgrammeTitle(title,matchingName) } == true
                                    }) { best=null; return@withTimeoutOrNull null }
                                    val genres = (full["genres"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
                                        (it as? JsonObject)?.get("genre")?.jsonPrimitive?.content
                                    }
                                    val runtime = full["filmLength"]?.jsonPrimitive?.content?.toIntOrNull()
                                    val synopsis = full["description"]?.jsonPrimitive?.content
                                        ?: full["shortDescription"]?.jsonPrimitive?.content
                                    if (request != null && !request.acceptsCard(runtime,fullYear,genres,synopsis,type in setOf("TV_SERIES","MINI_SERIES"))) {
                                        best = null
                                        synchronized(matchedGuard) { matched.remove(matchKey) }
                                        return@withTimeoutOrNull null
                                    }
                                    detail = parseKinopoiskDetails(text)
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
                    if (known == null && kind != ProgrammeKind.CATALOG && details == null) return@withTimeoutOrNull null
                    val combined = card.copy(
                        overview = details?.overview?.takeIf(::hasProgrammeSynopsis)
                            ?: card.overview?.takeIf(::hasProgrammeSynopsis),
                        frameUrl = details?.frameUrl ?: card.frameUrl,
                        posterUrl = details?.posterUrl ?: card.posterUrl,
                    )
                    best = combined
                    if (movieId != null) synchronized(matchedGuard) {
                        if (matched.size >= 128 && matchKey !in matched) matched.remove(matched.keys.first())
                        matched[matchKey] = MatchedCard(movieId, combined, token, currentTimeMillis())
                    }
                    onUpdate?.invoke(combined)
                    if (movieId != null && hasProgrammeSynopsis(combined.overview) && (combined.frameUrl == null || combined.posterUrl == null)) {
                        try {
                            val images = client.get("$KINOPOISK_API/api/v2.2/films/$movieId/images") {
                                header("X-API-KEY", token)
                                parameter("type", "STILL")
                                parameter("page", 1)
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
private fun matchedKinopoiskMovie(body: String, name: String, kind: ProgrammeKind = ProgrammeKind.CATALOG): JsonObject? {
    val root = Json.parseToJsonElement(body).jsonObject
    val docs = (root["films"] ?: root["items"] ?: root["docs"])?.jsonArray ?: return null
    val year = programmeReleaseYear(name)
    val ranked = docs.mapNotNull { it as? JsonObject }.mapNotNull { entry ->
        val type = entry["type"]?.jsonPrimitive?.content
        val candidateYear = entry["year"]?.jsonPrimitive?.content?.toIntOrNull()
        if ((kind != ProgrammeKind.DISCOVERY && !type.isNullOrBlank() && !kind.accepts(type)) || (year != null && candidateYear != null && candidateYear != year)) return@mapNotNull null
        val titles = listOf("nameRu", "nameEn", "nameOriginal", "name", "alternativeName", "enName").mapNotNull { entry[it]?.jsonPrimitive?.content } +
            (entry["names"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.content
            }
        val exact = titles.any { similarProgrammeTitle(it, name) }
        // Numbered sequels are often broadcast without their colon subtitle.
        val shortSequel = normalizedProgrammeTitle(name).substringAfterLast(' ').all(Char::isDigit) &&
            titles.any { ':' in it && similarProgrammeTitle(it.substringBefore(':'), name) }
        if (!exact && !shortSequel) null
        else entry to ((if (exact) 100 else 50) +
            if (year != null && entry["year"]?.jsonPrimitive?.content?.toIntOrNull() == year) 10 else 0)
    }
    val best = ranked.maxByOrNull { it.second } ?: return null
    if (kind != ProgrammeKind.CATALOG && ranked.count { it.second == best.second } > 1) return null
    // The keyword endpoint often omits type; only the full card is authoritative.
    val type=best.first["type"]?.jsonPrimitive?.content?.takeUnless {it=="null"}
    if (!type.isNullOrBlank() && !kind.accepts(type)) return null
    return best.first
}

internal fun kinopoiskMatchedId(body: String, name: String, kind: ProgrammeKind = ProgrammeKind.CATALOG): Int? {
    val movie = matchedKinopoiskMovie(body, name, kind) ?: return null
    return listOf("kinopoiskId", "filmId", "id").firstNotNullOfOrNull { movie[it]?.jsonPrimitive?.content?.toIntOrNull() }
}

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
        frameUrl = field("coverUrl")?.takeIf { it.startsWith("https://") } ?: image("backdrop"),
        posterUrl = listOf("posterUrlPreview", "posterUrl").firstNotNullOfOrNull { field(it)?.takeIf { url -> url.startsWith("https://") } } ?: image("poster"),
    )
}

/** The image endpoint includes stills even when the film has no backdrop field. */
internal fun parseKinopoiskImages(body: String): ProgrammeMetadata {
    val root = Json.parseToJsonElement(body).jsonObject
    val stills = root["items"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    val docs = root["docs"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    fun url(item: JsonObject): String? = listOf("previewUrl", "imageUrl", "url").firstNotNullOfOrNull { key ->
        item[key]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    }
    fun type(item: JsonObject) = item["type"]?.jsonPrimitive?.content?.lowercase().orEmpty()
    val frame = docs.firstOrNull { type(it) in setOf("still", "stills", "frame", "screenshot", "backdrop", "backdrops") && url(it) != null }
    val poster = docs.firstOrNull { type(it) in setOf("poster", "posters", "cover") && url(it) != null }
    return ProgrammeMetadata(frameUrl = stills.firstNotNullOfOrNull(::url) ?: frame?.let(::url), posterUrl = poster?.let(::url))
}

/** Kept separate from the network call for deterministic fixture tests. */
internal fun parseKinopoiskSearch(body: String, name: String, kind: ProgrammeKind = ProgrammeKind.CATALOG): ProgrammeMetadata? {
    val match = matchedKinopoiskMovie(body, name, kind) ?: return null
    fun text(key: String): String? = match[key]?.jsonPrimitive?.content
        ?.takeIf { it != "null" && it.isNotBlank() }
    fun imageUrl(key: String): String? = (match[key] as? JsonObject)?.get("url")
        ?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    return ProgrammeMetadata(
        overview = kinopoiskOverview(text("description"), text("shortDescription")),
        frameUrl = imageUrl("backdrop"),
        posterUrl = listOf("posterUrlPreview", "posterUrl").firstNotNullOfOrNull { text(it)?.takeIf { url -> url.startsWith("https://") } } ?: imageUrl("poster"),
    )
}

/** One cache per application; partial descriptions are published before artwork completes. */
class ProgrammeMetadataRepository(
    private val tmdb: TmdbRepository,
    private val kinopoisk: KinopoiskProgrammeLookup,
    private val tmdbSettings: TmdbSettingsStore,
) {
    private data class Cached(val result: ProgrammeMetadata?, val time: Long, val complete: Boolean, val providers:Int)
    private val cache = LinkedHashMap<String, Cached>()
    private val cacheGuard = Any()
    private var cacheRevision = 0L
    private val mutex = Mutex()
    private class Lookup(val lock: Mutex = Mutex(), var readers: Int = 0,
        val updates: MutableStateFlow<ProgrammeMetadata?> = MutableStateFlow(null))
    private val lookups = hashMapOf<String, Lookup>()
    private val networkSlots = Semaphore(2)

    suspend fun clearCache() { synchronized(cacheGuard) { cache.clear(); cacheRevision++ } }

    fun cached(request: ProgrammeMetadataRequest): ProgrammeMetadata? =
        if (request.canEnrich) cached(request.title, request.kind, request) else null
    fun observe(request: ProgrammeMetadataRequest): Flow<ProgrammeMetadata?> =
        if (request.canEnrich) observe(request.title, request.kind, request) else flowOf(null)
    suspend fun find(request: ProgrammeMetadataRequest, onUpdate: ((ProgrammeMetadata) -> Unit)? = null): ProgrammeMetadata? =
        if (request.canEnrich) find(request.title, onUpdate, request.kind, request) else null
    fun forget(request: ProgrammeMetadataRequest): Unit = synchronized(cacheGuard) {
        cache.remove(metadataCacheKey(request.title, request.kind, request))
        Unit
    }

    /** A synchronous, title/year-specific snapshot for first-frame rendering; refresh stays asynchronous. */
    fun cached(title: String, kind: ProgrammeKind = ProgrammeKind.CATALOG, request: ProgrammeMetadataRequest? = null): ProgrammeMetadata? {
        val key = metadataCacheKey(title,kind,request)
        return synchronized(cacheGuard) {
            cache[key]?.takeIf { currentTimeMillis() - it.time in 0 until 5 * 60_000L }?.result
        }
    }

    fun observe(title: String, kind: ProgrammeKind = ProgrammeKind.CATALOG, request: ProgrammeMetadataRequest? = null): Flow<ProgrammeMetadata?> = channelFlow {
        var result = find(title, { trySend(it) }, kind, request)
        trySend(result)
        // A visible poster-only card must get another chance without leaving the screen.
        repeat(2) {
            if (result == null || hasProgrammeSynopsis(result?.overview)) return@channelFlow
            delay(2_100)
            result = find(title, { trySend(it) }, kind, request)
            trySend(result)
        }
    }

    suspend fun find(title: String, onUpdate: ((ProgrammeMetadata) -> Unit)? = null): ProgrammeMetadata? = find(title, onUpdate, ProgrammeKind.CATALOG)

    private suspend fun find(title: String, onUpdate: ((ProgrammeMetadata) -> Unit)?, kind: ProgrammeKind, request: ProgrammeMetadataRequest? = null): ProgrammeMetadata? {
        val raw = title.trim().take(180)
        val query = cleanProgrammeTitle(raw)
        if (query.length < 3) return null
        val year = (request?.releaseYear ?: programmeReleaseYear(raw))?.toString()
        val key = metadataCacheKey(title,kind,request)
        val flightKey=key+"|"+tmdbSettings.isEnabled()+"|"+kinopoisk.isEnabled()
        val flight = mutex.withLock { lookups.getOrPut(flightKey) { Lookup() }.also { it.readers++ } }
        return try {
            coroutineScope {
                val updates = onUpdate?.let { callback -> launch(start = CoroutineStart.UNDISPATCHED) {
                    flight.updates.filterNotNull().collect { callback(it) }
                } }
                try {
                    val result=flight.lock.withLock { networkSlots.withPermit {
                        withContext(Dispatchers.IO) { lookup(raw, query, year, key, kind, request) { flight.updates.value = it } }
                    } }
                    // A fast cached/network completion can beat the collector's
                    // dispatch. Deliver the final verified card before closing it.
                    result?.let {onUpdate?.invoke(it)}
                    result
                } finally { updates?.cancelAndJoin() }
            }
        } finally { withContext(NonCancellable) {
            mutex.withLock { if (--flight.readers == 0 && lookups[flightKey] === flight) lookups.remove(flightKey) }
        } }
    }

    private suspend fun lookup(raw: String, query: String, year: String?, key: String, kind: ProgrammeKind, request: ProgrammeMetadataRequest?,
        publish: (ProgrammeMetadata) -> Unit): ProgrammeMetadata? {
        val providers=(if(tmdbSettings.isEnabled())1 else 0) or (if(kinopoisk.isEnabled())2 else 0)
        val now = currentTimeMillis()
        var retained: ProgrammeMetadata? = null
        val revision = synchronized(cacheGuard) {
            retained = cache[key]?.takeIf {it.providers==providers}?.result
            cache[key]?.takeIf { it.providers==providers && (it.complete || hasProgrammeSynopsis(it.result?.overview)) && now - it.time < when {
                it.result == null -> 20_000L
                !hasProgrammeSynopsis(it.result.overview) -> 2_000L
                it.result.frameUrl == null || it.result.posterUrl == null -> 30_000L
                else -> 5 * 60_000L
            } }?.let { return it.result }
            cacheRevision
        }
        retained?.let(publish)
        val progressGuard = Any()
        var tmdbCard: ProgrammeMetadata? = null
        var kinoCard: ProgrammeMetadata? = null
        fun combine(): ProgrammeMetadata? {
            if (tmdbCard == null && kinoCard == null) return retained
            if (kind != ProgrammeKind.CATALOG) {
                // Never combine one provider's story with another identity's frames.
                return kinoCard?.takeIf { hasProgrammeSynopsis(it.overview) }
                    ?: tmdbCard?.takeIf { hasProgrammeSynopsis(it.overview) } ?: kinoCard ?: tmdbCard ?: retained
            }
            return ProgrammeMetadata(
                overview = kinoCard?.overview?.takeIf(::hasProgrammeSynopsis)
                    ?: tmdbCard?.overview?.takeIf(::hasProgrammeSynopsis) ?: retained?.overview?.takeIf(::hasProgrammeSynopsis),
                frameUrl = tmdbCard?.frameUrl ?: kinoCard?.frameUrl ?: retained?.frameUrl,
                posterUrl = tmdbCard?.posterUrl ?: kinoCard?.posterUrl ?: retained?.posterUrl,
            ).takeIf { it.overview != null || it.frameUrl != null || it.posterUrl != null }
        }
        fun cacheResult(result: ProgrammeMetadata?, complete: Boolean = false) = synchronized(cacheGuard) {
            if (revision == cacheRevision) {
                if (cache.size >= 256 && key !in cache) cache.remove(cache.keys.first())
                cache[key] = Cached(result, currentTimeMillis(), complete, providers)
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
                    if (providers and 1 != 0) {
                        val card = withTimeoutOrNull(12_000L) { lookupTmdb(raw, query, year, kind, request) { progress(false, it) } }
                        progress(false, card)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The other provider can still supply a real card. */ }
            }
            launch {
                try { if(providers and 2 != 0) progress(true, kinopoisk.lookup(raw, { progress(true, it) }, kind, request)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep already published metadata. */ }
            }
        }
        val result = synchronized(progressGuard) { combine() }
        if (result != null || !kinopoisk.temporaryFailureRecently()) cacheResult(result, complete = true)
        return result
    }

    private suspend fun lookupTmdb(clean: String, query: String, year: String?, kind: ProgrammeKind, request: ProgrammeMetadataRequest?,
        publish: (ProgrammeMetadata) -> Unit): ProgrammeMetadata? {
        var searchCard: ProgrammeMetadata? = null
        return try {
            fun matches(hit: TmdbSearchResult, tv: Boolean) =
                listOf(hit.title,hit.name,hit.original_title,hit.original_name).any { similarProgrammeTitle(it,clean) } &&
                    (year == null || (if(tv) hit.first_air_date else hit.release_date)?.startsWith(year) == true)
            val results = if(kind == ProgrammeKind.SERIES) emptyList() else tmdb.searchMovie(query).filter {matches(it,false)}
            val series = if(kind == ProgrammeKind.SERIES || kind == ProgrammeKind.FICTION || kind == ProgrammeKind.DISCOVERY ||
                (kind == ProgrammeKind.CATALOG && results.isEmpty()))
                tmdb.searchTv(query).filter {matches(it,true)} else emptyList()
            if (kind != ProgrammeKind.CATALOG && results.size + series.size != 1) return null
            val movie = results.firstOrNull()
            if (kind == ProgrammeKind.DISCOVERY) {
                val hit = movie ?: series.firstOrNull() ?: return null
                val tv = movie == null
                // No artwork from an unverified search result. Inference needs
                // the complete card, its running time, genres and EPG context.
                val full = if (tv) tmdb.getTvDetails(hit.id) else tmdb.getMovieDetails(hit.id)
                if (full.id != hit.id || !listOf(full.title,full.name,full.original_title,full.original_name).any { similarProgrammeTitle(it,clean) }) return null
                if (tv && (full.type?.lowercase() in setOf("news","talk show","reality") ||
                    (full.type?.lowercase() !in setOf("scripted","miniseries") &&
                        full.genres.none { it.id in setOf(16,18,35,80,9648,10759,10765) }))) return null
                val runtime = if(tv) full.episode_run_time.firstOrNull() ?: full.runtime else full.runtime
                val release = (if(tv) full.first_air_date else full.release_date)?.take(4)?.toIntOrNull()
                if (request?.acceptsCard(runtime,release,full.genres.map {it.name},full.overview,tv) != true) return null
                return ProgrammeMetadata(full.overview,tmdbImageUrl(full.backdrop_path,"w780"),tmdbImageUrl(full.poster_path,"w342"))
            }
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
                val show = series.firstOrNull()
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
