package com.tvnext.app.core.player

import android.content.Context
import org.json.JSONObject

/** Resume positions are keyed by (profile, channel, programme start), never a signed stream URL. */
internal class AndroidArchiveResumeStore(context: Context) {
    private val personal = context.applicationContext.getSharedPreferences("tvnext_profile_personal_v1", Context.MODE_PRIVATE)
    private val profiles = context.applicationContext.getSharedPreferences("tvnext_restored_local_data", Context.MODE_PRIVATE)
    private var lastSavedAt = 0L

    private fun storageKey(): String {
        val id = profiles.getString("active", "main").orEmpty().ifBlank { "main" }
        return "profile.$id.archive_positions"
    }

    private fun entryKey(item: PlaybackItem): String? =
        item.archiveEpgAnchorMs?.takeIf { it > 0 }?.let { "${item.id}@$it" }

    fun resume(item: PlaybackItem): Long? {
        val name = entryKey(item) ?: return null
        val positions = runCatching { JSONObject(personal.getString(storageKey(), "{}")) }.getOrDefault(JSONObject())
        return positions.optJSONObject(name)?.optLong("position")?.takeIf { it in 1_000..21_600_000 }
    }

    fun record(state: PlayerState) {
        val item = state.item?.takeIf { it.kind == PlaybackContentKind.ARCHIVE } ?: return
        val id = entryKey(item) ?: return
        if (state.state != PlaybackState.ENDED && state.positionMs !in 1_000..21_600_000) return
        val now = System.currentTimeMillis()
        if (state.state != PlaybackState.ENDED && now - lastSavedAt < 10_000) return
        val key = storageKey()
        val json = runCatching { JSONObject(personal.getString(key, "{}")) }.getOrDefault(JSONObject())
        if (state.state == PlaybackState.ENDED) json.remove(id)
        else json.put(id, JSONObject().put("position", state.positionMs).put("updated", now))
        // Keep the 20 latest entries, discarding older programmes.
        if (json.length() > 20) {
            val names = json.keys().asSequence().toList()
            names.sortedBy { json.optJSONObject(it)?.optLong("updated") ?: 0L }
                .take(json.length() - 20).forEach { json.remove(it) }
        }
        personal.edit().putString(key, json.toString()).apply()
        lastSavedAt = now
    }
}
