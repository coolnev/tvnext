package com.tvnext.app.core.tmdb

// EPG providers use Cyrillic, Latin and mixed lookalikes for "телесериал".
internal const val SERIES_EPG_MARKER = """[тt]\s*[/\.]\s*[сcs]\.?(?![.\p{L}\p{N}])"""

/** Remove broadcast annotations, retaining sequel numbers and genuine subtitles. */
internal fun cleanProgrammeTitle(raw: String): String {
    var title = raw.replace('\u00a0', ' ').replace('\u202f', ' ').trim()
    repeat(3) {
        title = title.replace(Regex("""\s*[\[(]?\s*(?:0|6|12|16|18)\+\s*[\])]?\s*[.!]?\s*$"""), "")
            .replace(Regex("""\s*[\[(](?:19|20)\d{2}(?:\s*г\.?)?[\])]\s*[.!]?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:19|20)\d{2}\s*г(?:од(?:а)?)?\.?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:премьера!\s*|(?:х\s*[/\.]\s*ф\.?|$SERIES_EPG_MARKER|м\s*[/\.]\s*ф\.?|д\s*[/\.]\s*ф\.?|художественный фильм|фильм|(?:теле)?сериал)(?=$|[\s: .—–\-«"“„'])[\s: .—–\-]*)""", RegexOption.IGNORE_CASE), "")
            .trim(' ', '«', '»', '"', '“', '”', '„', '\'', '.', ':')
    }
    title = title.replace(Regex("""(?:[\s,;:.—-]+)(?:(?:сезон|сез\.)\s*\d+\s*[,;:.—-]*\s*)?(?:серия|сер\.|эпизод)\s*\d+\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[\s,;:.—-]+\d+(?:-?[аяй])?\s+серия\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+S\d{1,2}E\d{1,3}\s*$""", RegexOption.IGNORE_CASE), "")
    // EPG providers sometimes join the sequel number directly to the title.
    return title.replace(Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})"""), " ")
        .replace(Regex("""\s+"""), " ").trim(' ', '«', '»', '"', '“', '”', '.', ':')
}

/** A bare number can be part of a film's name (1917, 2012, Blade Runner 2049). */
internal fun programmeReleaseYear(raw: String): Int? =
    Regex("""[\[(]((?:19|20)\d{2})(?:\s*г\.?)?[\])]|\b((?:19|20)\d{2})\s*г(?:од(?:а)?)?\.?""", RegexOption.IGNORE_CASE)
        .find(raw)?.let { (it.groups[1]?.value ?: it.groups[2]?.value)?.toIntOrNull() }

internal fun normalizedProgrammeTitle(raw: String): String = cleanProgrammeTitle(raw)
    .lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

/** Match the complete normalized title; never replace part 2 with the first film. */
internal fun similarProgrammeTitle(candidate: String?, requested: String): Boolean =
    !candidate.isNullOrBlank() && normalizedProgrammeTitle(candidate) == normalizedProgrammeTitle(requested)

/** The same placeholder rules apply to XMLTV, remote cards and both description views. */
fun hasProgrammeSynopsis(text: String?): Boolean {
    val value = text?.replace(Regex("<[^>]+>"), " ")?.trim()?.lowercase()
        ?.replace(Regex("[\\s\\u00a0]+"), " ")?.trim('.', ' ', '…', '!') ?: return false
    val withoutLabel = value.replace(Regex("^(?:описание|информация)\\s*[:—-]\\s*"), "")
    if (withoutLabel.length < 3) return false
    val subject = "(?:описани[ея]|информаци[яи]|данны[ех])(?: (?:о |об )?(?:передаче|передачи|программе|программы|фильме|фильма|сюжете|сюжета))?"
    val missing = "(?:нет|отсутству(?:ет|ют)|(?:пока )?не ?доступн[аоы]|не (?:предоставлен[аоы]|найден[аоы]|указан[аоы]))"
    if (Regex("^(?:$subject $missing|(?:пока )?нет $subject)$").matches(withoutLabel)) return false
    if (value.length < 3) return false
    if (Regex("^(?:описание|информация)(?: (?:передачи|программы|фильма))? (?:отсутствует|недоступн[ао]|не предоставлен[ао]).*$").matches(value)) return false
    return withoutLabel !in setOf(
        "фильм", "художественный фильм", "сериал", "х/ф", "т/с", "комедия", "драма", "боевик", "триллер",
        "описание", "нет описания", "описание отсутствует", "описание недоступно", "описание не предоставлено",
        "описание передачи отсутствует", "описание передачи недоступно", "описание программы отсутствует",
        "описание программы недоступно", "нет описания передачи", "нет описания программы",
        "нет данных", "нет информации", "информация о передаче отсутствует",
        "no description", "no description available", "description not available", "null",
    )
}

/** Use the most informative usable synopsis; an EPG stub must not mask a full film story. */
fun programmeSynopsis(epg: String?, remote: String?): String? =
    listOfNotNull(epg?.trim()?.takeIf(::hasProgrammeSynopsis), remote?.trim()?.takeIf(::hasProgrammeSynopsis))
        .maxByOrNull { it.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().length }
