package com.tvnext.app

import android.content.Context
import com.tvnext.app.feature.media.data.ProfileVodPositionStorage
import org.json.JSONObject

/** Private per-profile VOD resume data included in profile-only backup. */
internal class AndroidProfileVodPositionStore(context: Context) : ProfileVodPositionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(
        "tvnext_profile_personal_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(profileId: String) = "profile.$profileId.vod_positions"

    override fun get(profileId: String, itemId: String): Long? = synchronized(lock) {
        val json = runCatching { JSONObject(prefs.getString(key(profileId), "{}")) }
            .getOrDefault(JSONObject())
        if (json.has(itemId)) json.optLong(itemId, 0L) else null
    }

    override fun put(profileId: String, itemId: String, positionMs: Long) = synchronized(lock) {
        if (itemId.isBlank()) return@synchronized
        val storedKey = key(profileId)
        val json = runCatching { JSONObject(prefs.getString(storedKey, "{}")) }
            .getOrDefault(JSONObject())
        json.put(itemId, positionMs.coerceAtLeast(0L))
        if (json.length() > 100) {
            val names = json.keys().asSequence().toList()
            names.take(json.length() - 100).forEach(json::remove)
        }
        prefs.edit().putString(storedKey, json.toString()).apply()
    }
}
