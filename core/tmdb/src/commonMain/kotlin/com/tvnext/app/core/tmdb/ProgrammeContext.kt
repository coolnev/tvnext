package com.tvnext.app.core.tmdb

/** Unknown live titles use a verified catalogue probe, never an unrestricted search card. */
enum class ProgrammeKind { CATALOG, MOVIE, SERIES, FICTION, DISCOVERY, BROADCAST, UNKNOWN }

data class ProgrammeMetadataRequest(
    val title: String,
    val category: String? = null,
    val description: String? = null,
    val durationMinutes: Int? = null,
    val episodeNum: String? = null,
) {
    val releaseYear: Int? get() = programmeReleaseYear(title) ?: description?.take(350)?.let {
        Regex("(?:год(?: выпуска)?|year)\\s*[:=—-]\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
            .find(it)?.groupValues?.get(1)?.toIntOrNull()
    }
    val kind: ProgrammeKind get() {
        val genre = category.orEmpty().lowercase().replace('ё', 'е')
        val raw = title.lowercase().replace('ё', 'е')
        val text = description.orEmpty().take(1200).lowercase().replace('ё', 'е')
        if (broadcastGenre(genre) || BROADCAST_FORMAT.containsMatchIn(text)) return ProgrammeKind.BROADCAST
        if (SERIES.containsMatchIn(genre) || SERIES_TITLE.containsMatchIn(raw) || !episodeNum.isNullOrBlank()) return ProgrammeKind.SERIES
        if (MOVIE.containsMatchIn(genre) || MOVIE_TITLE.containsMatchIn(raw)) return ProgrammeKind.MOVIE
        if (FICTION.containsMatchIn(genre)) return ProgrammeKind.FICTION
        if (SERIES.containsMatchIn(text.take(120))) return ProgrammeKind.SERIES
        if (MOVIE_TITLE.containsMatchIn(text)) return ProgrammeKind.MOVIE
        if (durationMinutes?.let { it in 1..600 } == true || releaseYear != null || hasProgrammeSynopsis(description)) return ProgrammeKind.DISCOVERY
        return ProgrammeKind.UNKNOWN
    }
    val canEnrich: Boolean get() = kind in setOf(ProgrammeKind.MOVIE, ProgrammeKind.SERIES, ProgrammeKind.FICTION, ProgrammeKind.DISCOVERY)

    /** Context is part of the key: a two-hour film must not authorize a same-title news slot. */
    internal val cacheContext: String get() = "$kind|$releaseYear|$durationMinutes|${episodeNum.orEmpty()}|${category.orEmpty()}|${description.orEmpty().take(1200)}"

    internal fun acceptsCard(runtime: Int?, year: Int?, genres: List<String>, overview: String?, series: Boolean): Boolean {
        if (releaseYear != null && year != null && releaseYear != year) return false
        if (kind != ProgrammeKind.DISCOVERY) return true
        if (genres.any(::broadcastGenre)) return false
        val plotAgrees = synopsisAgrees(description, overview)
        val knownYear = releaseYear != null && releaseYear == year
        val scheduled = durationMinutes?.takeIf { it in 1..600 }
        val length = runtime?.takeIf { it in 1..600 }
        if (scheduled != null && length != null) {
            // Room for adverts and small provider timing errors, but a news
            // bulletin cannot acquire the synopsis of a feature-length film.
            if (scheduled < length * 0.80 || scheduled > length * 1.85 + 8) return false
            if (!series && length < 45 && !plotAgrees && !knownYear) return false
            return true
        }
        // A catalogue without running time needs independent EPG evidence.
        return knownYear || (plotAgrees && scheduled != null)
    }

    private companion object {
        val BROADCAST_FORMAT = Regex("(?:выпуск новостей|информационно[ -]аналитическая программа|информационная программа|общественно[ -]политическ|ток[ -]?шоу|телевикторина|телевизионная игра|капитал[ -]?шоу|ежедневная программа|прогноз погоды|прямой эфир|телепередача|гости студии|в студии обсуждают)")
        val SERIES = Regex("(?:сериал|телесериал|многосерийн|документальный цикл|^series$)")
        val SERIES_TITLE = Regex("""(?:^(?:т\s*[/\.]\s*с|сериал)(?!\p{L})|(?<!\p{L})(?:сезон|серия|эпизод)\s*\d+|(?<![\p{L}\p{N}])\d+(?:-?[аяй])?\s+серия(?!\p{L})|(?<![\p{L}\p{N}])s\d{1,2}e\d{1,3}(?![\p{L}\p{N}]))""")
        val MOVIE = Regex("(?:фильм|кино|^movie$|^film$)")
        val MOVIE_TITLE = Regex("""^\s*(?:(?:премьера[!.]?\s*)?)(?:х\s*[/\.]\s*ф|м\s*[/\.]\s*ф|д\s*[/\.]\s*ф|художественный фильм|документальный фильм|мультфильм|фильм)(?!\p{L})""")
        val FICTION = Regex("(?:драм|комеди|боевик|триллер|мелодрам|детектив|фантастик|фэнтези|приключени|анимаци|мультфильм|ужас|drama|comedy|thriller|fiction|animation)")
    }
}

private val broadcastGenres = Regex("(?:новост|news|информацион|публицист|ток[ -]?шоу|talk[ -]?show|телешоу|реалити|реальное тв|reality|развлекатель|познаватель|спорт|sport|погод|телевикторин|игровая программа|^игра$|^шоу$)", RegexOption.IGNORE_CASE)
internal fun broadcastGenre(value: String): Boolean = broadcastGenres.containsMatchIn(value)

private fun synopsisAgrees(epg: String?, catalogue: String?): Boolean {
    if (!hasProgrammeSynopsis(epg) || !hasProgrammeSynopsis(catalogue)) return false
    fun words(s: String) = Regex("[\\p{L}]{5,}").findAll(s.lowercase().replace('ё','е'))
        .map { it.value.take(6) }.filterNot { it in setOf("фильм", "сериал", "описан", "переда", "которы", "своего", "своими", "после", "время", "жизни") }.toSet()
    val a=words(epg!!); val b=words(catalogue!!)
    val common=a.intersect(b).size
    return common>=3 && common.toDouble()/a.size.coerceAtLeast(1)>=0.25
}

internal fun metadataCacheKey(title: String, kind: ProgrammeKind, request: ProgrammeMetadataRequest?): String =
    normalizedProgrammeTitle(title.trim().take(180)) + "|" + (request?.releaseYear ?: programmeReleaseYear(title)) + "|" + kind + "|" + request?.cacheContext.orEmpty()

internal fun ProgrammeKind.accepts(type: String?): Boolean {
    val value = type?.uppercase()?.replace('-', '_')
    if (value.isNullOrBlank() || value == "NULL") return this in setOf(ProgrammeKind.CATALOG, ProgrammeKind.FICTION, ProgrammeKind.DISCOVERY)
    val movie = value in setOf("FILM", "MOVIE", "CARTOON", "ANIME")
    val series = value in setOf("TV_SERIES", "TVSERIES", "MINI_SERIES", "MINISERIES", "ANIMATED_SERIES")
    return when (this) {
        ProgrammeKind.MOVIE -> movie
        ProgrammeKind.SERIES -> series
        ProgrammeKind.FICTION, ProgrammeKind.DISCOVERY, ProgrammeKind.CATALOG -> movie || series
        else -> false
    }
}
