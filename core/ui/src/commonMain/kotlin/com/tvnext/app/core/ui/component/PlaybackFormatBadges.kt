package com.tvnext.app.core.ui.component

import com.tvnext.app.core.ui.i18n.uiText

/**
 * Display-only labels for the ACTUAL selected stream formats.
 * Unknown metadata is intentionally omitted; never infer it from a playlist name.
 */
internal fun playbackFormatBadges(resolution: String?, audio: String?): List<String> {
    val labels = mutableListOf<String>()
    val dimensions = resolution?.trim()?.let {
        Regex("^(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})$").matchEntire(it)
    }
    val width = dimensions?.groupValues?.getOrNull(1)?.toIntOrNull()
    val height = dimensions?.groupValues?.getOrNull(2)?.toIntOrNull()
    if (width != null && height != null && width > 0 && height > 0) {
        // Use exact decoded dimensions, not guessed 720p/1080p values.
        val video = when {
            height >= 4320 && width >= 7680 -> "8K"
            height >= 2160 && width >= 3840 -> "4K"
            else -> "${height}p"
        }
        labels.add(video)
    }
    val sound = audio?.trim()?.lowercase()?.let { description ->
        when {
            Regex("(^|[^0-9])7\\.1([^0-9]|$)").containsMatchIn(description) -> "7.1"
            Regex("(^|[^0-9])5\\.1([^0-9]|$)").containsMatchIn(description) -> "5.1"
            uiText("стерео") in description || "stereo" in description -> "Stereo"
            uiText("моно") in description || "mono" in description -> "Mono"
            else -> null
        }
    }
    if (sound != null) labels.add(sound)
    return labels
}
