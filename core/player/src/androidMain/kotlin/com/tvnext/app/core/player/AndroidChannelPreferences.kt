package com.tvnext.app.core.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import org.json.JSONObject

/** Read shared preference schema written by the Android-only channel editor. */
internal data class AndroidChannelPreferences(
    val resize: String = "inherit",
    val audio: String = "",
    val subtitles: String = "",
    val subtitlesEnabled: Boolean = true,
    val decodeMode: String = "inherit",
)

internal fun readChannelPreferences(context: Context, channelId: String): AndroidChannelPreferences {
    val id = context.getSharedPreferences("tvnext_restored_local_data", Context.MODE_PRIVATE)
        .getString("active", "main").orEmpty().ifBlank { "main" }
    val json = runCatching {
        JSONObject(context.getSharedPreferences("tvnext_profile_personal_v1", Context.MODE_PRIVATE)
            .getString("profile.$id.channel_playback", "{}"))
    }.getOrDefault(JSONObject()).optJSONObject(channelId) ?: return AndroidChannelPreferences()
    return AndroidChannelPreferences(
        resize = VideoAspectMode.channel(json.optString("resize", "inherit")).token,
        audio = json.optString("audio", ""),
        subtitles = json.optString("text", ""),
        subtitlesEnabled = json.optBoolean("subtitles", true),
        decodeMode = json.optString("decoder", "inherit"),
    )
}

/** Applied once for each channel; previous channel preferences do not leak. */
internal fun applyChannelTrackPreferences(player: Player, prefs: AndroidChannelPreferences) {
    val builder = player.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(prefs.audio.ifBlank { null })
        .setPreferredTextLanguage(prefs.subtitles.ifBlank { null })
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesEnabled)
    player.trackSelectionParameters = builder.build()
}
