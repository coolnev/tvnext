package com.tvnext.app.core.tmdb

/** Live EPG must establish screen-content intent before asking a film catalogue. */
enum class ProgrammeKind { CATALOG, MOVIE, SERIES, FICTION, BROADCAST, UNKNOWN }

data class ProgrammeMetadataRequest(val title: String, val category: String? = null, val description: String? = null) {
    val kind: ProgrammeKind get() {
        val genre = category.orEmpty().lowercase().replace('ё', 'е')
        val raw = title.lowercase().replace('ё', 'е')
        if (BROADCAST.containsMatchIn(genre)) return ProgrammeKind.BROADCAST
        if (SERIES.containsMatchIn(genre) || SERIES_TITLE.containsMatchIn(raw)) return ProgrammeKind.SERIES
        if (MOVIE.containsMatchIn(genre) || MOVIE_TITLE.containsMatchIn(raw)) return ProgrammeKind.MOVIE
        if (FICTION.containsMatchIn(genre)) return ProgrammeKind.FICTION
        val text = description.orEmpty().take(1200).lowercase().replace('ё', 'е')
        if (BROADCAST_FORMAT.containsMatchIn(text)) return ProgrammeKind.BROADCAST
        if (SERIES.containsMatchIn(text.take(120))) return ProgrammeKind.SERIES
        if (MOVIE_TITLE.containsMatchIn(text)) return ProgrammeKind.MOVIE
        return ProgrammeKind.UNKNOWN
    }
    val canEnrich: Boolean get() = kind in setOf(ProgrammeKind.MOVIE, ProgrammeKind.SERIES, ProgrammeKind.FICTION)
    private companion object {
        val BROADCAST = Regex("(?:новост|информацион|публицист|ток[ -]?шоу|телешоу|реалити|развлекатель|познаватель|спорт|погод|телевикторин|игровая программа|^игра$|^шоу$)")
        val BROADCAST_FORMAT = Regex("(?:выпуск новостей|информационно[ -]аналитическая программа|информационная программа|общественно[ -]политическ|ток[ -]?шоу|телевикторина|ежедневная программа|прогноз погоды|прямой эфир|телепередача)")
        val SERIES = Regex("(?:сериал|телесериал|многосерийн|документальный цикл)")
        val SERIES_TITLE = Regex("(?:^(?:т\\s*[/\\.]\\s*с|сериал)\\b|\\b(?:сезон|серия|эпизод)\\s*\\d+|\\bs\\d{1,2}e\\d{1,3}\\b)")
        val MOVIE = Regex("(?:фильм|кино)")
        val MOVIE_TITLE = Regex("^\\s*(?:(?:премьера[!.]?\\s*)?)(?:х\\s*[/\\.]\\s*ф|м\\s*[/\\.]\\s*ф|д\\s*[/\\.]\\s*ф|художественный фильм|документальный фильм|мультфильм|фильм)(?:\\b|\\s|[.:])")
        val FICTION = Regex("(?:драм|комеди|боевик|триллер|мелодрам|детектив|фантастик|фэнтези|приключени|анимаци|мультфильм|ужас)")
    }
}

internal fun ProgrammeKind.accepts(type: String?): Boolean {
    val value = type?.uppercase()?.replace('-', '_')
    if (value.isNullOrBlank() || value == "NULL") return this == ProgrammeKind.CATALOG || this == ProgrammeKind.FICTION
    val movie = value in setOf("FILM", "MOVIE", "CARTOON", "ANIME")
    val series = value in setOf("TV_SERIES", "TVSERIES", "MINI_SERIES", "MINISERIES", "ANIMATED_SERIES")
    return when (this) {
        ProgrammeKind.MOVIE -> movie
        ProgrammeKind.SERIES -> series
        ProgrammeKind.FICTION, ProgrammeKind.CATALOG -> movie || series
        else -> false
    }
}
