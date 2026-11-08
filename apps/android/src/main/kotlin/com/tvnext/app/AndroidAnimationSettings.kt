package com.tvnext.app

import android.content.Context

internal object AndroidAnimationSettings {
    fun preferences(context: Context) = context.applicationContext.getSharedPreferences("tvnext_animation_v1",Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("video_transitions",false)
    fun setEnabled(context: Context, enabled: Boolean) = preferences(context).edit().putBoolean("video_transitions",enabled).commit()
}
