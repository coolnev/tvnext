package com.tvnext.app.core.player

import kotlin.math.roundToInt

/** Aspect selection tokens remain stable across profile backup/import and app upgrades.
 *  INHERIT is only meaningful as a per-channel value. AUTO honors the video SAR/DAR;
 *  STRETCH fills the whole available player area; ZOOM fills while cropping.
 */
enum class VideoAspectMode(
    val token: String,
    val displayName: String,
    val numerator: Int = 0,
    val denominator: Int = 0,
) {
    INHERIT("inherit", "Как в общих настройках"),
    AUTO("fit", "Авто"),
    STRETCH("fill", "Растянуть на экран"),
    ZOOM("zoom", "Заполнить с обрезкой"),
    RATIO_16_9("16:9", "16:9", 16, 9),
    RATIO_4_3("4:3", "4:3", 4, 3),
    RATIO_16_10("16:10", "16:10", 16, 10),
    RATIO_21_9("21:9", "21:9", 21, 9),
    RATIO_14_9("14:9", "14:9", 14, 9),
    RATIO_5_4("5:4", "5:4", 5, 4),
    RATIO_3_2("3:2", "3:2", 3, 2),
    RATIO_1_1("1:1", "1:1", 1, 1),
    RATIO_9_16("9:16", "9:16", 9, 16),
    RATIO_1_85("1.85:1", "1.85:1", 185, 100),
    RATIO_2_35("2.35:1", "2.35:1", 235, 100),
    RATIO_2_39("2.39:1", "2.39:1", 239, 100),
    RATIO_2_40("2.40:1", "2.40:1", 240, 100),
    ;

    val fixedRatio: Float?
        get() = if (numerator > 0 && denominator > 0) numerator.toFloat() / denominator else null

    companion object {
        fun fromToken(token: String?): VideoAspectMode? = entries.firstOrNull { it.token == token }

        fun global(token: String?): VideoAspectMode =
            fromToken(token)?.takeUnless { it == INHERIT } ?: AUTO

        fun channel(token: String?): VideoAspectMode = fromToken(token) ?: INHERIT

        fun resolved(channelToken: String?, globalToken: String?): VideoAspectMode {
            val channelMode = channel(channelToken)
            return if (channelMode == INHERIT) global(globalToken) else channelMode
        }
    }
}

/** Pure math for an exact forced output DAR, independent of Android View APIs. */
fun videoViewportSize(width: Int, height: Int, ratio: Float?): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || ratio == null || !ratio.isFinite() || ratio <= 0f) {
        return width to height
    }
    val availableRatio = width.toDouble() / height
    return if (availableRatio > ratio) {
        ((height * ratio.toDouble()).roundToInt().coerceIn(1, width)) to height
    } else {
        width to ((width / ratio.toDouble()).roundToInt().coerceIn(1, height))
    }
}
