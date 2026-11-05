package com.tvnext.app.core.iptv.xmltv

/**
 * Parses XMLTV timestamps, e.g. `20260701090000 +0300`, into epoch millis (UTC).
 *
 * Supported shapes:
 *  - `YYYYMMDDHHMMSS ±HHMM` (standard XMLTV)
 *  - `YYYYMMDDHHMMSS ±HH:MM` (with colon)
 *  - `YYYYMMDDHHMMSS` (treated as UTC)
 *  - `YYYYMMDDHHMMSS Z` / `YYYYMMDDHHMMSS UTC` (treated as UTC)
 *
 * Conversion is a pure Kotlin implementation (civil-date algorithm), so it is
 * testable in commonTest and identical on Android/Desktop.
 */
object XmltvTimestamp {

    /** Returns epoch millis, or `null` when the text is not a valid XMLTV timestamp. */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        val n = trimmed.length
        if (n < 14) return null

        // Find the first run of 14 consecutive digits (the old regex matched
        // anywhere in the string). Hand-rolled, because Regex.find() on every
        // programme (2 calls each, hundreds of thousands of programmes) was
        // the dominant cost of the whole XMLTV import.
        var start = -1
        var run = 0
        for (k in 0 until n) {
            val c = trimmed[k]
            if (c in '0'..'9') {
                if (run == 0) start = k
                run++
                if (run == 14) break
            } else {
                run = 0
            }
        }
        if (run < 14 || start < 0) return null

        var year = 0
        var month = 0
        var day = 0
        var hour = 0
        var minute = 0
        var second = 0
        for (k in 0 until 14) {
            val d = (trimmed[start + k] - '0')
            when (k) {
                0 -> year = d * 1000
                1 -> year += d * 100
                2 -> year += d * 10
                3 -> year += d
                4 -> month = d * 10
                5 -> month += d
                6 -> day = d * 10
                7 -> day += d
                8 -> hour = d * 10
                9 -> hour += d
                10 -> minute = d * 10
                11 -> minute += d
                12 -> second = d * 10
                else -> second += d
            }
        }

        val rest = trimmed.substring(start + 14).trim()
        var offsetSeconds = 0L
        if (rest.isNotEmpty()) {
            if (rest == "Z" || rest.equals("UTC", ignoreCase = true)) {
                offsetSeconds = 0L
            } else {
                // `±HHMM` or `±HH:MM` (the old OFFSET regex shape).
                val sign = when (rest[0]) {
                    '-' -> -1
                    '+' -> 1
                    else -> return null
                }
                val hh = digitAt(rest, 1)?.let { it * 10 + digitAt(rest, 2)!! } ?: return null
                var mmIndex = 3
                if (rest.length > 3 && rest[3] == ':') mmIndex = 4
                val mm = digitAt(rest, mmIndex)?.let { it * 10 + digitAt(rest, mmIndex + 1)!! } ?: return null
                if (hh > 23 || mm > 59) return null
                offsetSeconds = sign * (hh * 3600L + mm * 60L)
            }
        }

        if (month !in 1..12) return null
        if (day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

        val days = daysFromCivil(year, month, day)
        val secondsUtc = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
        return secondsUtc * 1_000L
    }

    private fun digitAt(text: String, index: Int): Int? {
        val c = text.getOrNull(index) ?: return null
        val d = c - '0'
        return if (d in 0..9) d else null
    }

    /**
     * Howard Hinnant's `days_from_civil` — days since 1970-01-01 for a
     * proleptic Gregorian date. Deterministic and year-agnostic.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year.toLong()
        val m = month.toLong()
        y -= if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
