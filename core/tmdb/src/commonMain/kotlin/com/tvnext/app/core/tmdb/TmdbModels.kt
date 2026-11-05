package com.tvnext.app.core.tmdb

import com.tvnext.app.core.iptv.domain.MediaItem
import kotlinx.serialization.Serializable

@Serializable
data class TmdbSearchPage(
    val results: List<TmdbSearchResult> = emptyList(),
)

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val original_language: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
)

@Serializable
data class TmdbDetails(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val runtime: Int? = null,
    val episode_run_time: List<Int> = emptyList(),
    val original_language: String? = null,
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList(),
)

@Serializable
data class TmdbCast(
    val name: String,
    val order: Int? = null,
)

@Serializable
data class TmdbCrew(
    val name: String,
    val job: String? = null,
)

data class TmdbMetadata(
    val tmdbId: Int,
    val mediaType: String,
    val title: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: Double? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val updatedAt: Long = 0L,
) {
    val posterUrl: String? get() = tmdbImageUrl(posterPath, "w500")
    val backdropUrl: String? get() = tmdbImageUrl(backdropPath, "w1280")
}

internal fun tmdbImageUrl(path: String?, size: String): String? = path?.trim()
    ?.takeIf { it.startsWith('/') && it.length > 1 }
    ?.let { "https://image.tmdb.org/t/p/$size$it" }

enum class TmdbCacheStatus { FOUND, NOT_FOUND, ERROR }

data class TmdbLookupResult(
    val metadata: TmdbMetadata?,
    val status: TmdbCacheStatus,
)

object TmdbTitleNormalizer {
    // Matches "S02", "S02E05", "S2E5", "сезон 2", "серия 3". The episode part
    // is optional: many playlists only carry the season ("След / S02").
    private val seasonEpisode = Regex("\\b[Ss]\\d{1,2}(?:\\s*[Ee]\\d{1,3})?\\b|\\b\\d{1,2}\\s*[сС]езон\\b|\\b\\d{1,3}\\s*[сС]ерия\\b")
    private val year = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
    private val bracketed = Regex("[\\[\\]{}()]")
    private val separators = Regex("[|:/\\\\]+")
    private val repeatedWhitespace = Regex("\\s+")

    data class Normalized(val query: String, val year: Int?, val isTv: Boolean)

    fun normalize(item: MediaItem): Normalized {
        val raw = listOfNotNull(item.name.takeIf { it.isNotBlank() }, item.tvgName, item.tvgId)
            .firstOrNull() ?: item.name
        val detectedYear = year.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isTv = !item.season.isNullOrBlank() || !item.episode.isNullOrBlank() || seasonEpisode.containsMatchIn(raw)
        val cleaned = raw
            .replace(seasonEpisode, " ")
            .replace(year, " ")
            .replace(bracketed, " ")
            .replace(separators, " ")
            .replace(Regex("\\b(?:4k|2160p|1080p|720p|web-dl|bluray|hdtv|hevc|x264|x265)\\b", RegexOption.IGNORE_CASE), " ")
            .let { repeatedWhitespace.replace(it, " ") }
            .trim(' ', '-', '_', '.')
        return Normalized(cleaned.ifBlank { item.name.trim() }, detectedYear, isTv)
    }
}
