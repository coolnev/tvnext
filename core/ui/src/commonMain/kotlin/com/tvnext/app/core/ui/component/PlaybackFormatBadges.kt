package com.tvnext.app.core.ui.component

import com.tvnext.app.core.ui.i18n.uiText

/**
 * Display-only labels for the ACTUAL selected stream formats.
 * Unknown metadata is intentionally omitted; never infer it from a playlist name.
 */
fun playbackFormatBadges(resolution: String?, audio: String?, fps: String? = null, actualMode: String? = null): List<String> {
    val labels = mutableListOf<String>()
    when(actualMode) {
        com.tvnext.app.core.player.PlayerDiagnostics.MODE_HARDWARE -> labels.add(uiText("А"))
        com.tvnext.app.core.player.PlayerDiagnostics.MODE_SOFTWARE -> labels.add(uiText("П"))
        else -> labels.add("…")
    }
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
    fps?.replace(',', '.')?.toDoubleOrNull()?.takeIf {it.isFinite() && it in 10.0..240.0}?.let {
        var number=com.tvnext.app.core.player.frameRateNumber(it)
        if(com.tvnext.app.core.ui.i18n.UiLocale.language == com.tvnext.app.core.common.UiLanguage.RUSSIAN)number=number.replace('.',',')
        labels.add(uiText("{0} кадр/с",number))
    }
    val sound = audio?.trim()?.lowercase()?.let { description ->
        when {
            Regex("(^|[^0-9])7\\.1([^0-9]|$)").containsMatchIn(description) -> "7.1"
            Regex("(^|[^0-9])5\\.1([^0-9]|$)").containsMatchIn(description) -> "5.1"
            "стерео" in description || "stereo" in description || "立体声" in description -> "Stereo"
            "моно" in description || "mono" in description || "单声道" in description -> "Mono"
            else -> null
        }
    }
    if (sound != null) labels.add(sound)
    val codec=audio?.lowercase().orEmpty()
    when {
        "e-ac3" in codec || "eac3" in codec || "ec-3" in codec || "dolby digital plus" in codec -> labels.add("Dolby D+")
        "truehd" in codec || "true-hd" in codec || "mlp" in codec -> labels.add("Dolby TrueHD")
        "ac3" in codec || "ac-3" in codec -> labels.add("AC3")
        "dolby" in codec -> labels.add("Dolby")
    }
    return labels
}
