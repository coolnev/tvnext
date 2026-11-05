package com.tvnext.app.core.iptv.data

import android.content.Context

/**
 * Optional public XMLTV source for channels without a provider programme.
 * A single guide is downloaded and matched locally by tvg-id or display name;
 * channel names and viewing activity are never submitted as search requests.
 */
class AndroidFallbackEpgSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tvnext_epg_fallback", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }

    /** One XMLTV URL per line; each feed is cached independently. */
    fun url(): String = prefs.getString("url", DEFAULT_URLS).orEmpty().trim()
    fun urls(): List<String> = url().lineSequence().map(String::trim)
        .filter(String::isNotBlank).distinct().take(MAX_SOURCES).toList()
    fun setUrl(value: String): Boolean {
        val entries = value.lineSequence().map(String::trim)
            .filter(String::isNotBlank).distinct().toList()
        if (entries.size > MAX_SOURCES || entries.any { !isValidUrl(it) }) return false
        prefs.edit().putString("url", entries.joinToString("\n")).apply()
        return true
    }

    companion object {
        // Public Russian guide, independently maintained and not guaranteed to
        // contain every channel. A user may replace or disable it in Settings.
        const val DEFAULT_URL = "https://iptv-epg.org/files/epg-ru.xml.gz"
        // Another independently maintained Russian XMLTV feed, listing hundreds
        // of channels including regional and specialist stations. Verified public
        // endpoint: https://free-epg.de/countries/ru
        const val RUSSIAN_WIDE_URL = "https://free-epg.de/api/epg/ru.xml.gz"
        // A third independent schedule source. None has special-case matching:
        // ALL missing channels are treated equally, irrespective of brand/group.
        const val OTHER_URL = "https://iptvx.one/EPG"
        const val DEFAULT_URLS = "$DEFAULT_URL\n$RUSSIAN_WIDE_URL\n$OTHER_URL"
        // Custom regional/international XMLTV feeds can be added explicitly.
        // Fetches remain sequential, on-demand and cached to protect playback.
        const val MAX_SOURCES = 8

        fun isValidUrl(url: String): Boolean =
            (url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)) &&
                url.length <= 2048 && url.none { it.isWhitespace() }
    }
}
