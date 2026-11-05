package com.tvnext.app.core.player

import android.content.Context
import android.content.SharedPreferences

/** Android-only preference; does not change the common or Windows settings schema. */
object AndroidVideoAspectSettings {
    const val PREFS_NAME = "tvnext_android_video_aspect_v1"
    private const val KEY_ASPECT = "global_aspect"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): VideoAspectMode =
        VideoAspectMode.global(preferences(context).getString(KEY_ASPECT, VideoAspectMode.AUTO.token))

    fun set(context: Context, token: String): Boolean {
        val requested = VideoAspectMode.fromToken(token) ?: return false
        if (requested == VideoAspectMode.INHERIT) return false
        return preferences(context).edit().putString(KEY_ASPECT, requested.token).commit()
    }
}
