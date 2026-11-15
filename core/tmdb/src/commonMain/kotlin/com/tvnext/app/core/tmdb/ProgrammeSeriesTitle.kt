package com.tvnext.app.core.tmdb

private const val EPISODE_NUMBER = """\d{1,4}(?:\s*-?\s*[аяй])?"""
private const val EPISODE_NUMBERS = """$EPISODE_NUMBER(?:\s*(?:[-–—,;/]|и)\s*$EPISODE_NUMBER)*"""
private const val EPISODE_UNIT = """(?:сери[яи]|сер\.?|с\.?|эпизод(?:ы)?)"""
private const val EPISODE_SEPARATOR = """[|¦│┆┊]"""

internal val SERIES_TITLE = Regex(
    """(?:^\s*(?:премьера[!.]?\s*)?(?:$SERIES_EPG_MARKER|(?:теле)?сериал)(?![\p{L}\p{N}])|(?<!\p{L})(?:сезон|серия|серии|эпизод)\s*\d+|(?<![\p{L}\p{N}])$EPISODE_NUMBERS\s+(?:сери[яи]|эпизод(?:ы)?)(?!\p{L})|(?<![\p{L}\p{N}])s\d{1,2}e\d{1,3}(?![\p{L}\p{N}]))""",
    RegexOption.IGNORE_CASE,
)
private val separatedEpisode = Regex(
    """\s*$EPISODE_SEPARATOR\s*(?:$EPISODE_UNIT\s*)?$EPISODE_NUMBERS(?:\s*$EPISODE_UNIT)?\s*\.?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val labelledEpisode = Regex(
    """[\s,;:.—–\-]+(?:(?:сезон|сез\.)\s*\d+\s*[,;:.—–\-]*\s*)?(?:$EPISODE_UNIT\s*$EPISODE_NUMBERS|$EPISODE_NUMBERS\s*$EPISODE_UNIT)\s*\.?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val seasonSuffix = Regex("""[\s,;:.—–\-]+(?:сезон|сез\.)\s*\d+\s*\.?\s*$""",RegexOption.IGNORE_CASE)
private val compactEpisode = Regex("""[\s,;:.—–\-]+S\d{1,2}E\d{1,3}\s*\.?\s*$""",RegexOption.IGNORE_CASE)

/** Explicit units are safe to strip; a bare provider episode needs known series context. */
internal fun stripProgrammeEpisode(title:String,series:Boolean):String {
    var result=title
    repeat(2) {
        if(series) result=result.replace(separatedEpisode, "")
        result=result.replace(labelledEpisode, "").replace(compactEpisode, "")
        if(series) result=result.replace(seasonSuffix, "")
    }
    return result
}
