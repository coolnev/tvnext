package com.tvnext.app.core.tmdb

import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.util.runPlatformCatching as runCatching
import kotlinx.coroutines.sync.withPermit
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.database.AppMetaRepository
import com.tvnext.app.core.iptv.domain.MediaItem
import com.tvnext.app.core.common.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

interface TmdbSettingsStore {
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun getApiKey(): String
    suspend fun setApiKey(apiKey: String)
}

class SqlDelightTmdbSettingsStore(private val meta: AppMetaRepository) : TmdbSettingsStore {
    companion object {
        const val ENABLED = "settings.tmdb_enabled"
        const val API_KEY = "settings.tmdb_api_key"
        /**
         * Built-in API key used when the user has not entered their own. This
         * keeps the media library working out of the box (no manual setup).
         * A user-entered key always takes precedence.
         */
        const val DEFAULT_API_KEY = "698bff9ae128f80d8e6bff80ae075549"
    }

    override suspend fun isEnabled() = meta.getValue(ENABLED)?.toBooleanStrictOrNull() ?: true
    override suspend fun setEnabled(enabled: Boolean) { meta.setValue(ENABLED, enabled.toString()) }
    override suspend fun getApiKey() =
        meta.getValue(API_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_API_KEY
    override suspend fun setApiKey(apiKey: String) { meta.setValue(API_KEY, apiKey.trim()) }
}

data class TmdbConnectionResult(val ok: Boolean, val kind: Kind) {
    enum class Kind { SUCCESS, INVALID_KEY, NO_CONNECTION, ERROR }
}

interface TmdbRepository {
    suspend fun searchMovie(query: String, year: Int? = null): List<TmdbSearchResult>
    suspend fun searchTv(query: String, firstAirYear: Int? = null): List<TmdbSearchResult>
    suspend fun getMovieDetails(id: Int, language: String = "ru-RU"): TmdbDetails
    suspend fun getTvDetails(id: Int, language: String = "ru-RU"): TmdbDetails
    suspend fun enrich(item: MediaItem, forceRefresh: Boolean = false): TmdbLookupResult
    suspend fun enrichAll(items: List<MediaItem>)
    suspend fun testConnection(apiKey: String? = null): TmdbConnectionResult
    suspend fun clearCache()
}

private class TmdbHttpFailure(val code: Int, val retryAfterMs: Long) : IllegalStateException("TMDB HTTP $code")

class SqlDelightTmdbRepository(
    private val client: HttpClient,
    private val database: TvNextDatabase,
    private val settings: TmdbSettingsStore,
) : TmdbRepository {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<TmdbLookupResult>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ttlMs = 7L * 24 * 60 * 60 * 1000
    private val concurrency = kotlinx.coroutines.sync.Semaphore(4)

    private companion object {
        /** TMDB request/read timeout — Cloudflare edges are slower than local APIs. */
        const val TMDB_REQUEST_TIMEOUT_MS = 30_000L
        const val TMDB_CONNECT_TIMEOUT_MS = 20_000L
        /** Transient network failures (Cloudflare TLS drops) are retried. */
        const val TMDB_MAX_RETRIES = 3
        const val TMDB_RETRY_DELAY_MS = 400L
    }

    /** Retry transient transport, 408/429/5xx failures. Authentication failures,
     * malformed JSON and cancellation fail promptly without extra requests. */
    private suspend fun <T> tmdbRequest(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val isCancellation = e is kotlinx.coroutines.CancellationException
                val http = e as? TmdbHttpFailure
                val permanent = http != null && http.code !in setOf(408, 429, 500, 502, 503, 504)
                if (isCancellation || permanent || e is kotlinx.serialization.SerializationException || attempt >= TMDB_MAX_RETRIES) throw e
                attempt++
                delay(maxOf(TMDB_RETRY_DELAY_MS * attempt, http?.retryAfterMs ?: 0L).coerceAtMost(5_000L))
            }
        }
    }

    override suspend fun searchMovie(query: String, year: Int?): List<TmdbSearchResult> =
        search("movie", query, year)

    override suspend fun searchTv(query: String, firstAirYear: Int?): List<TmdbSearchResult> =
        search("tv", query, firstAirYear)

    private suspend fun search(type: String, query: String, year: Int?): List<TmdbSearchResult> {
        val key = settings.getApiKey().trim()
        require(key.isNotBlank()) { "TMDB API key is empty" }
        suspend fun request(language: String): List<TmdbSearchResult> =
            tmdbRequest {
                client.get("https://api.themoviedb.org/3/search/$type") {
                    parameter("api_key", key)
                    parameter("language", language)
                    parameter("query", query)
                    parameter("include_adult", false)
                    // TMDB is behind Cloudflare: the app's default 15s request
                    // timeout is too tight for its edges, so TMDB gets its own.
                    timeout {
                        requestTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = TMDB_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                    }
                    // NOTE: the year extracted from the title is deliberately NOT
                    // sent as the TMDB `year`/`first_air_date_year` filter. Titles
                    // often contain a number that is not the release year ("Blade
                    // Runner 2049" is from 2017, "2012" from 2009): a hard filter
                    // would return nothing and the film would be cached NOT_FOUND.
                    // The year is still used as a soft score inside chooseBest.
                }.let { response ->
                    ensureSuccess(response)
                    response.body<TmdbSearchPage>().results
                }
            }
        val ru = request("ru-RU")
        return if (ru.isNotEmpty()) ru else request("en-US")
    }

    override suspend fun getMovieDetails(id: Int, language: String): TmdbDetails = details("movie", id, language)
    override suspend fun getTvDetails(id: Int, language: String): TmdbDetails = details("tv", id, language)

    private suspend fun details(type: String, id: Int, language: String): TmdbDetails {
        val key = settings.getApiKey().trim()
        require(key.isNotBlank()) { "TMDB API key is empty" }
        return tmdbRequest {
            val response = client.get("https://api.themoviedb.org/3/$type/$id") {
                parameter("api_key", key)
                parameter("language", language)
                parameter("append_to_response", "credits")
                timeout {
                    requestTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = TMDB_CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                }
            }
            ensureSuccess(response)
            response.body<TmdbDetails>()
        }
    }

    override suspend fun enrich(item: MediaItem, forceRefresh: Boolean): TmdbLookupResult {
        // The settings reads are synchronous SQLDelight queries: offload them
        // so the caller (usually the main thread) is not blocked.
        val settingsOk = withContext(Dispatchers.Default) {
            settings.isEnabled() && settings.getApiKey().isNotBlank()
        }
        if (!settingsOk) return TmdbLookupResult(null, TmdbCacheStatus.ERROR)
        val cached = withContext(Dispatchers.Default) { readCache(item.id) }
        val cacheTtl = when {
            cached?.status == "ERROR" -> 20_000L
            cached?.status == "NOT_FOUND" -> 60L * 60_000L
            cached?.overview.isNullOrBlank() || cached?.poster_path.isNullOrBlank() || cached?.backdrop_path.isNullOrBlank() -> 2 * 60_000L
            else -> ttlMs
        }
        if (!forceRefresh && cached != null && currentTimeMillis() - cached.updated_at in 0 until cacheTtl) {
            return TmdbLookupResult(cached.takeIf { it.tmdb_id != null }?.toMetadata(), TmdbCacheStatus.valueOf(cached.status))
        }
        if (isAndroidPlatform()) return enrichAndroid(item)
        val key = item.id
        val deferred: Deferred<TmdbLookupResult> = mutex.withLock {
            inFlight[key]?.let { return@withLock it }
            val task = scope.async(Dispatchers.Default) {
                concurrency.acquire()
                try {
                    fetchAndCache(item)
                } finally {
                    concurrency.release()
                }
            }
            inFlight[key] = task
            task
        }
        return try {
            deferred.await()
        } finally {
            // Remove ONLY the task that was actually awaited. A caller that
            // merely reused another coroutine's in-flight task must not delete
            // a NEWER task that was inserted for the same key afterwards.
            mutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
        }
    }

    private data class AndroidLookup(val task: Deferred<TmdbLookupResult>, var waiters: Int = 0)
    private val androidLookups = mutableMapOf<String, AndroidLookup>()

    private suspend fun enrichAndroid(item: MediaItem): TmdbLookupResult {
        val lookup = mutex.withLock {
            androidLookups.getOrPut(item.id) {
                AndroidLookup(scope.async {
                    concurrency.withPermit { fetchAndCache(item) }
                })
            }.also { it.waiters++ }
        }
        return try {
            lookup.task.await()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    lookup.waiters--
                    if (lookup.waiters == 0) {
                        if (androidLookups[item.id] === lookup) androidLookups.remove(item.id)
                        lookup.task.cancel()
                    }
                }
            }
        }
    }

    private suspend fun fetchAndCache(item: MediaItem): TmdbLookupResult {
        val normalized = TmdbTitleNormalizer.normalize(item)
        return runCatching {
            val results = if (normalized.isTv) searchTv(normalized.query, normalized.year) else searchMovie(normalized.query, normalized.year)
            val best = chooseBest(results, normalized.query, normalized.year) ?: run {
                saveCache(item.id, null, "NOT_FOUND")
                return TmdbLookupResult(null, TmdbCacheStatus.NOT_FOUND)
            }
            val details = try {
                if (normalized.isTv) getTvDetails(best.id, "ru-RU") else getMovieDetails(best.id, "ru-RU")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A details outage must not throw away a valid search card.
                TmdbDetails(best.id, title = best.title, name = best.name, original_title = best.original_title,
                    overview = best.overview, poster_path = best.poster_path, backdrop_path = best.backdrop_path,
                    release_date = best.release_date, first_air_date = best.first_air_date,
                    original_language = best.original_language)
            }
            val metadata = details.toMetadata(if (normalized.isTv) "tv" else "movie")
            val finalMetadata = if (metadata.title.isNullOrBlank() && metadata.originalTitle.isNullOrBlank()) {
                val en = if (normalized.isTv) getTvDetails(best.id, "en-US") else getMovieDetails(best.id, "en-US")
                en.toMetadata(if (normalized.isTv) "tv" else "movie")
            } else if (metadata.overview.isNullOrBlank()) {
                val original = metadata.originalLanguage
                    ?.takeIf { it.isNotBlank() && !it.equals("ru", ignoreCase = true) }
                    ?.let { language ->
                        runCatching {
                            if (normalized.isTv) getTvDetails(best.id, language) else getMovieDetails(best.id, language)
                        }.getOrNull()
                    }
                val en = runCatching { if (normalized.isTv) getTvDetails(best.id, "en-US") else getMovieDetails(best.id, "en-US") }.getOrNull()
                metadata.copy(overview = original?.overview ?: en?.overview ?: metadata.overview)
            } else metadata
            saveCache(item.id, finalMetadata, "FOUND")
            TmdbLookupResult(finalMetadata, TmdbCacheStatus.FOUND)
        }.getOrElse {
            val previous = withContext(Dispatchers.Default) { readCache(item.id) }
            if (previous?.tmdb_id != null) return TmdbLookupResult(previous.toMetadata(), TmdbCacheStatus.FOUND)
            saveCache(item.id, null, "ERROR")
            TmdbLookupResult(null, TmdbCacheStatus.ERROR)
        }
    }

    private fun chooseBest(results: List<TmdbSearchResult>, query: String, year: Int?): TmdbSearchResult? {
        fun norm(value: String?) = value.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val q = norm(query)
        return results.asSequence().take(8).map { result ->
            val title = norm(result.title ?: result.name)
            val original = norm(result.original_title ?: result.original_name)
            val exact = if (title == q || original == q) 1000 else 0
            val starts = if (title.startsWith(q) || original.startsWith(q)) 150 else 0
            val contains = if (title.contains(q) || original.contains(q)) 40 else 0
            val yearScore = year?.let { requested ->
                val date = (result.release_date ?: result.first_air_date).orEmpty().take(4).toIntOrNull()
                when { date == requested -> 100; date != null && kotlin.math.abs(date - requested) == 1 -> 20; else -> 0 }
            } ?: 0
            result to (exact + starts + contains + yearScore)
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

        private suspend fun saveCache(itemId: String, metadata: TmdbMetadata?, status: String) = withContext(Dispatchers.Default) {
        database.tmdbCacheQueries.upsert(
            media_item_id = itemId,
            tmdb_id = metadata?.tmdbId?.toLong(),
            media_type = metadata?.mediaType ?: "",
            title = metadata?.title,
            original_title = metadata?.originalTitle,
            overview = metadata?.overview,
            poster_path = metadata?.posterPath,
            backdrop_path = metadata?.backdropPath,
            release_date = metadata?.releaseDate,
            vote_average = metadata?.voteAverage,
            genres = metadata?.genres?.joinToString("|").orEmpty(),
            runtime_minutes = metadata?.runtimeMinutes?.toLong(),
            director = metadata?.director,
            cast = metadata?.cast?.joinToString("|").orEmpty(),
            original_language = metadata?.originalLanguage,
            updated_at = currentTimeMillis(),
            status = status,
        )
        if (isAndroidPlatform()) runCatching {
            com.tvnext.app.core.database.executeRawSql("DELETE FROM tmdb_cache WHERE updated_at<${currentTimeMillis()-5*60_000L} OR media_item_id NOT IN (SELECT media_item_id FROM tmdb_cache ORDER BY updated_at DESC LIMIT 128)")
        }
    }

    private suspend fun readCache(itemId: String) = database.tmdbCacheQueries.selectByMediaItemId(itemId).executeAsOneOrNull()

    override suspend fun enrichAll(items: List<MediaItem>) = coroutineScope {
        if (!settings.isEnabled() || settings.getApiKey().isBlank()) return@coroutineScope
        items.map { item -> async { enrich(item) } }.awaitAll()
    }

    override suspend fun testConnection(apiKey: String?): TmdbConnectionResult = runCatching {
        val key = (apiKey ?: settings.getApiKey()).trim()
        if (key.isBlank()) return TmdbConnectionResult(false, TmdbConnectionResult.Kind.INVALID_KEY)
        // A connectivity check must never monopolize the settings screen with
        // three 30-second retries. Only Android uses the short single-request probe;
        // desktop keeps its existing request/retry configuration.
        val response = if (isAndroidPlatform()) {
            withTimeoutOrNull(7_000L) {
                client.get("https://api.themoviedb.org/3/configuration") {
                    parameter("api_key", key)
                    timeout {
                        requestTimeoutMillis = 6_000L
                        connectTimeoutMillis = 4_000L
                        socketTimeoutMillis = 6_000L
                    }
                }
            } ?: return TmdbConnectionResult(false, TmdbConnectionResult.Kind.NO_CONNECTION)
        } else {
            tmdbRequest {
                client.get("https://api.themoviedb.org/3/configuration") {
                    parameter("api_key", key)
                    timeout {
                        requestTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = TMDB_CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
                    }
                }
            }
        }
        when (response.status.value) {
            401, 403 -> TmdbConnectionResult(false, TmdbConnectionResult.Kind.INVALID_KEY)
            in 200..299 -> TmdbConnectionResult(true, TmdbConnectionResult.Kind.SUCCESS)
            else -> TmdbConnectionResult(false, TmdbConnectionResult.Kind.ERROR)
        }
    }.getOrElse { TmdbConnectionResult(false, TmdbConnectionResult.Kind.NO_CONNECTION) }

    override suspend fun clearCache() {
        withContext(Dispatchers.Default) {
            database.tmdbCacheQueries.deleteAll()
        }
    }

    private fun ensureSuccess(response: HttpResponse) {
        if (response.status.value !in 200..299) throw TmdbHttpFailure(response.status.value,
            response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(0, 5)?.times(1_000L) ?: 0L)
    }

    private fun TmdbDetails.toMetadata(type: String): TmdbMetadata = TmdbMetadata(
        tmdbId = id,
        mediaType = type,
        title = title ?: name ?: original_title ?: original_name,
        originalTitle = original_title ?: original_name,
        overview = overview,
        posterPath = poster_path,
        backdropPath = backdrop_path,
        releaseDate = release_date ?: first_air_date,
        voteAverage = vote_average,
        genres = genres.map { it.name },
        runtimeMinutes = runtime ?: episode_run_time.firstOrNull(),
        director = credits?.crew?.firstOrNull { it.job.equals("Director", true) }?.name,
        cast = credits?.cast?.sortedBy { it.order ?: Int.MAX_VALUE }?.take(8)?.map { it.name }.orEmpty(),
        originalLanguage = original_language,
        updatedAt = currentTimeMillis(),
    )

    private fun com.tvnext.app.core.database.Tmdb_cache.toMetadata() = TmdbMetadata(
        tmdbId = tmdb_id?.toInt() ?: 0,
        mediaType = media_type,
        title = title,
        originalTitle = original_title,
        overview = overview,
        posterPath = poster_path,
        backdropPath = backdrop_path,
        releaseDate = release_date,
        voteAverage = vote_average,
        genres = genres.split('|').filter { it.isNotBlank() },
        runtimeMinutes = runtime_minutes?.toInt(),
        director = director,
        cast = cast.split('|').filter { it.isNotBlank() },
        originalLanguage = original_language,
        updatedAt = updated_at,
    )
}
