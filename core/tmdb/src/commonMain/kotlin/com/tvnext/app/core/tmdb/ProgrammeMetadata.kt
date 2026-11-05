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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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

class SqlDelightKinopoiskKeyStore(private val meta: AppMetaRepository) : KinopoiskKeyStore {
    override suspend fun getKey(): String = meta.getValue("settings.kinopoisk_backup_key").orEmpty()
    override suspend fun setKey(value: String) { meta.setValue("settings.kinopoisk_backup_key", value.trim()) }
}

/** Only activated when a user explicitly enters a key. No key is embedded or logged. */
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

    suspend fun lookup(name: String): ProgrammeMetadata? {
        val token = keys.getKey().trim()
        if (token.isEmpty() || currentTimeMillis() < backoffUntil) return null
        return permits.withPermit {
            if (currentTimeMillis() < backoffUntil) return@withPermit null
            var serviceResponded = false
            val result = withTimeoutOrNull(16_000L) {
                try {
                    // Retry one transient server error; never retry a bad key
                    // or a rate-limited account in a tight loop.
                    var body: String? = null
                    for (attempt in 0..1) {
                        val response = client.get("https://api.poiskkino.dev/v1.5/movie/search") {
                            header("X-API-KEY", token)
                            parameter("page", 1)
                            parameter("limit", 5)
                            parameter("query", name)
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
                    if (!card.overview.isNullOrBlank() && card.overview.length >= 120)
                        return@withTimeoutOrNull card
                    val movieId = kinopoiskMatchedId(responseBody, name)
                    val details = movieId?.let { id ->
                        try {
                            val detailResponse = client.get("https://api.poiskkino.dev/v1.5/movie/$id") {
                                header("X-API-KEY", token)
                                timeout { requestTimeoutMillis = 6_000; connectTimeoutMillis = 3_000 }
                            }
                            if (detailResponse.status.isSuccess())
                                parseKinopoiskDetails(detailResponse.bodyAsText()) else null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                    }
                    card.copy(
                        overview = details?.overview?.takeIf { it.isNotBlank() }
                            ?: card.overview?.takeIf { it.isNotBlank() },
                        frameUrl = details?.frameUrl ?: card.frameUrl,
                        posterUrl = details?.posterUrl ?: card.posterUrl,
                    )
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
            result
        }
    }
}

/** A search hit is not necessarily a full card; use its id for a synopsis fallback. */
internal fun kinopoiskMatchedId(body: String, name: String): Int? {
    val docs = Json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray ?: return null
    return docs.mapNotNull { it as? JsonObject }.firstOrNull { entry ->
        listOf("name", "alternativeName", "enName")
            .any { field -> similarProgrammeTitle(entry[field]?.jsonPrimitive?.content, name) }
    }?.get("id")?.jsonPrimitive?.content?.toIntOrNull()
}

internal fun parseKinopoiskDetails(body: String): ProgrammeMetadata {
    val item = Json.parseToJsonElement(body).jsonObject
    fun field(key: String) = item[key]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() && it != "null" }
    fun image(key: String) = (item[key] as? JsonObject)?.get("url")
        ?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    return ProgrammeMetadata(
        overview = field("description") ?: field("shortDescription"),
        frameUrl = image("backdrop"),
        posterUrl = image("poster"),
    )
}

/** Kept separate from the network call for deterministic fixture tests. */
internal fun parseKinopoiskSearch(body: String, name: String): ProgrammeMetadata? {
    val root = Json.parseToJsonElement(body).jsonObject
    val movies = root["docs"]?.jsonArray ?: return null
    val match = movies.mapNotNull { it as? JsonObject }.firstOrNull { entry ->
        listOf("name", "alternativeName", "enName")
            .any { field -> similarProgrammeTitle(entry[field]?.jsonPrimitive?.content, name) }
    } ?: return null
    fun text(key: String): String? = match[key]?.jsonPrimitive?.content
        ?.takeIf { it != "null" && it.isNotBlank() }
    fun imageUrl(key: String): String? = (match[key] as? JsonObject)?.get("url")
        ?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
    return ProgrammeMetadata(
        overview = text("description") ?: text("shortDescription"),
        frameUrl = imageUrl("backdrop"),
        posterUrl = imageUrl("poster"),
    )
}

/** Avoid an unrelated programme with an overlapping word being mistaken for a movie. */
internal fun cleanProgrammeTitle(raw: String): String = raw.trim()
    // XMLTV often prepends the content type to the actual film name.
    .replace(Regex("""^(?:х/ф|т/с|м/ф|д/ф|фильм|сериал)(?:\s+|[:.—-]\s*)""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(?(?:19|20)\d{2}\)?\s*$"""), "")
    // EPG programme titles often append season/episode numbers to a series.
    // Strip only known technical suffixes, never arbitrary subtitles.
    .replace(Regex("""(?:[\s,;:.—-]+)(?:(?:сезон|сез\.)\s*\d+\s*[,;:.—-]*\s*)?(?:серия|сер\.)\s*\d+\s*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+S\d{1,2}E\d{1,3}\s*$""", RegexOption.IGNORE_CASE), "")
    .trim(' ', '«', '»', '\"')

/** Do not substitute an unrelated film when a query matches only one word. */
internal fun similarProgrammeTitle(candidate: String?, requested: String): Boolean {
    if (candidate.isNullOrBlank()) return false
    fun norm(s: String) = cleanProgrammeTitle(s).lowercase().replace('ё', 'е')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    return norm(candidate) == norm(requested)
}

/** One shared cache for channel changes; no remote request is made when an EPG title is empty. */
class ProgrammeMetadataRepository(
    private val tmdb: TmdbRepository,
    private val kinopoisk: KinopoiskProgrammeLookup,
    private val tmdbSettings: TmdbSettingsStore,
) {
    private data class Cached(val result: ProgrammeMetadata?, val time: Long)
    private val cache = LinkedHashMap<String, Cached>()
    private val mutex = Mutex()

    suspend fun clearCache() = mutex.withLock { cache.clear() }

    suspend fun find(title: String): ProgrammeMetadata? {
        val clean = title.trim().take(180)
        if (clean.length < 3) return null
        val query = cleanProgrammeTitle(clean)
        if (query.length < 3) return null
        val year = Regex("(?:19|20)\\d{2}").find(clean)?.value
        val key = clean.lowercase().replace('ё', 'е')
        val now = currentTimeMillis()
        mutex.withLock {
            cache[key]?.takeIf { now - it.time < (when {
                it.result == null -> 20_000L
                it.result.overview.isNullOrBlank() -> 30_000L
                else -> 24 * 60 * 60_000L
            }) }
                ?.let { return it.result }
        }
        val tmdbResult = if (runCatching { tmdbSettings.isEnabled() }.getOrDefault(false)) withTimeoutOrNull(9_000L) {
            try {
                val results = tmdb.searchMovie(query)
                val movie = results.firstOrNull { similarProgrammeTitle(it.title ?: it.name, clean) &&
                    (year == null || (it.release_date ?: it.first_air_date)?.startsWith(year) == true) }
                    ?: results.firstOrNull { similarProgrammeTitle(it.title ?: it.name, clean) }
                    ?: results.firstOrNull { similarProgrammeTitle(it.original_title ?: it.original_name, clean) }
                val details: TmdbDetails?
                val englishDescription: String?
                if (movie != null) {
                    details = tmdb.getMovieDetails(movie.id)
                    englishDescription = if (details.overview.isNullOrBlank())
                        runCatching { tmdb.getMovieDetails(movie.id, "en-US").overview }.getOrNull() else null
                } else {
                    val series = tmdb.searchTv(query)
                    val show = series.firstOrNull { similarProgrammeTitle(it.name ?: it.title, clean) &&
                        (year == null || (it.first_air_date ?: it.release_date)?.startsWith(year) == true) }
                        ?: series.firstOrNull { similarProgrammeTitle(it.name ?: it.title, clean) }
                        ?: series.firstOrNull { similarProgrammeTitle(it.original_name, clean) }
                    details = show?.let { tmdb.getTvDetails(it.id) }
                    englishDescription = if (show != null && details?.overview.isNullOrBlank())
                        runCatching { tmdb.getTvDetails(show.id, "en-US").overview }.getOrNull() else null
                }
                details?.let {
                    ProgrammeMetadata(
                        overview = it.overview?.takeIf(String::isNotBlank)
                            ?: englishDescription?.takeIf(String::isNotBlank),
                        frameUrl = it.backdrop_path?.let { path -> "https://image.tmdb.org/t/p/w780$path" },
                        posterUrl = it.poster_path?.let { path -> "https://image.tmdb.org/t/p/w342$path" },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else null
        // Fill *each* missing field separately: having a TMDB poster must not suppress
        // the backup service when TMDB has no synopsis or backdrop.
        val backup = if (tmdbResult?.overview.isNullOrBlank() ||
            (tmdbResult?.frameUrl == null && tmdbResult?.posterUrl == null)) {
            kinopoisk.lookup(query)
        } else null
        val result = if (tmdbResult == null && backup == null) null else ProgrammeMetadata(
            overview = tmdbResult?.overview?.takeIf { it.isNotBlank() && it != "null" }
                ?: backup?.overview?.takeIf { it.isNotBlank() && it != "null" },
            frameUrl = tmdbResult?.frameUrl ?: backup?.frameUrl,
            posterUrl = tmdbResult?.posterUrl ?: backup?.posterUrl,
        )
        mutex.withLock {
            // Network and quota failures are temporary; do not cache them as
            // "this film has no description" when only TMDB was available.
            if (!kinopoisk.temporaryFailureRecently() || !result?.overview.isNullOrBlank()) {
                if (cache.size >= 256) cache.remove(cache.keys.first())
                cache[key] = Cached(result, now)
            }
        }
        return result
    }
}
