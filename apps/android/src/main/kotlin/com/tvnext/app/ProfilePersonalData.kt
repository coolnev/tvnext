package com.tvnext.app

import android.content.Context
import com.tvnext.app.core.player.VideoAspectMode
import com.tvnext.app.core.iptv.data.HistoryEntry
import com.tvnext.app.core.iptv.domain.WatchContentType
import org.json.JSONArray
import org.json.JSONObject

/** An Android-only, per-profile overlay. Provider playlists and original SQL rows remain untouched. */
internal data class ChannelCustomization(
    val name: String? = null,
    val category: String? = null,
    val hidden: Boolean = false,
)

/** Android-only per-channel playback preferences. */
internal data class ChannelPlaybackPreferences(
    val resize: String = "inherit",   // inherit / fit / fill / zoom / fixed ratios
    val audioLanguage: String = "",    // BCP-47 or empty (automatic)
    val subtitleLanguage: String = "", // BCP-47 or empty (automatic)
    val subtitlesEnabled: Boolean = true,
    val decodeMode: String = "inherit", // inherit/AUTO/HARDWARE/SOFTWARE
)

/**
 * Personal state is intentionally separate from the provider SQLite tables.
 * Updating an M3U cannot erase edits, and switching profiles cannot overwrite
 * another profile. All ids are stored as JSON values, never as filesystem paths.
 */
internal class ProfilePersonalData(context: Context, private val profiles: LocalRecoveryStore) {
    private val prefs = context.applicationContext.getSharedPreferences("tvnext_profile_personal_v1", Context.MODE_PRIVATE)
    // Several UI/services can create this store; all mutations must share one lock.
    private val lock = DATA_LOCK
    private companion object { val DATA_LOCK = Any() }

    fun activeId(): String = profiles.activeProfileId()
    private fun key(id: String, field: String): String = "profile.$id.$field"

    private fun loadSet(key: String): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        buildSet { for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        } }
    }.getOrDefault(emptySet())

    private fun saveSet(key: String, ids: Set<String>): Boolean =
        prefs.edit().putString(key, JSONArray().apply { ids.forEach { put(it) } }.toString()).commit()

    fun channelFavorites(seedMain: Set<String> = emptySet()): Set<String> = synchronized(lock) {
        val id = activeId()
        val key = key(id, "channel_favorites")
        if (!prefs.contains(key)) saveSet(key, if (id == "main") seedMain else emptySet())
        loadSet(key)
    }

    fun setChannelFavorite(channelId: String, favorite: Boolean): Boolean = synchronized(lock) {
        if (channelId.isBlank()) return@synchronized false
        val key = key(activeId(), "channel_favorites")
        val next = loadSet(key).toMutableSet()
        if (favorite) next.add(channelId) else next.remove(channelId)
        saveSet(key, next)
    }

    fun mediaFavorites(seedMain: Set<String> = emptySet()): Set<String> = synchronized(lock) {
        val id = activeId()
        val key = key(id, "media_favorites")
        if (!prefs.contains(key)) saveSet(key, if (id == "main") seedMain else emptySet())
        loadSet(key)
    }

    fun setMediaFavorite(itemId: String, favorite: Boolean): Boolean = synchronized(lock) {
        if (itemId.isBlank()) return@synchronized false
        val key = key(activeId(), "media_favorites")
        val next = loadSet(key).toMutableSet()
        if (favorite) next.add(itemId) else next.remove(itemId)
        saveSet(key, next)
    }

    fun channelEdits(): Map<String, ChannelCustomization> = synchronized(lock) {
        val json = runCatching { JSONObject(prefs.getString(key(activeId(), "channel_edits"), "{}")) }
            .getOrDefault(JSONObject())
        buildMap {
            val names = json.keys()
            while (names.hasNext()) {
                val id = names.next()
                val data = json.optJSONObject(id) ?: continue
                put(id, ChannelCustomization(
                    name = data.optString("name").takeIf(String::isNotBlank),
                    category = data.optString("category").takeIf(String::isNotBlank),
                    hidden = data.optBoolean("hidden", false),
                ))
            }
        }
    }

    fun editChannel(channelId: String, change: ChannelCustomization): Boolean = synchronized(lock) {
        if (channelId.isBlank()) return@synchronized false
        val changes = channelEdits().toMutableMap()
        if (change == ChannelCustomization()) changes.remove(channelId) else changes[channelId] = change
        val json = JSONObject()
        changes.forEach { (id, data) ->
            json.put(id, JSONObject().put("name", data.name.orEmpty())
                .put("category", data.category.orEmpty()).put("hidden", data.hidden))
        }
        prefs.edit().putString(key(activeId(), "channel_edits"), json.toString()).commit()
    }

    fun channelPlaybackSettings(channelId: String): ChannelPlaybackPreferences = synchronized(lock) {
        val json = runCatching { JSONObject(prefs.getString(key(activeId(), "channel_playback"), "{}")) }
            .getOrDefault(JSONObject()).optJSONObject(channelId) ?: return@synchronized ChannelPlaybackPreferences()
        return@synchronized ChannelPlaybackPreferences(
            resize = VideoAspectMode.channel(json.optString("resize", "inherit")).token,
            audioLanguage = json.optString("audio", "").take(30),
            subtitleLanguage = json.optString("text", "").take(30),
            subtitlesEnabled = json.optBoolean("subtitles", true),
            decodeMode = json.optString("decoder", "inherit").let {
                if (it == "inherit" || it in setOf("AUTO", "HARDWARE", "SOFTWARE")) it else "inherit"
            },
        )
    }

    fun setChannelPlaybackSettings(channelId: String, value: ChannelPlaybackPreferences): Boolean = synchronized(lock) {
        if (channelId.isBlank() || VideoAspectMode.fromToken(value.resize) == null ||
            value.audioLanguage.length > 30 || value.subtitleLanguage.length > 30 ||
            value.decodeMode !in setOf("inherit", "AUTO", "HARDWARE", "SOFTWARE")) return@synchronized false
        val prefKey = key(activeId(), "channel_playback")
        val json = runCatching { JSONObject(prefs.getString(prefKey, "{}")) }.getOrDefault(JSONObject())
        if (value == ChannelPlaybackPreferences()) json.remove(channelId)
        else json.put(channelId, JSONObject()
            .put("resize", value.resize).put("audio", value.audioLanguage.trim())
            .put("text", value.subtitleLanguage.trim()).put("subtitles", value.subtitlesEnabled)
            .put("decoder", value.decodeMode))
        prefs.edit().putString(prefKey, json.toString()).commit()
    }

    fun hideDuplicateChannels(): Boolean = synchronized(lock) {
        prefs.getBoolean(key(activeId(), "hide_duplicates"), false)
    }
    fun setHideDuplicateChannels(enabled: Boolean): Boolean = synchronized(lock) {
        prefs.edit().putBoolean(key(activeId(), "hide_duplicates"), enabled).commit()
    }

    fun channelOrder(): List<String> = synchronized(lock) {
        val json = runCatching { JSONArray(prefs.getString(key(activeId(), "channel_order"), "[]")) }
            .getOrDefault(JSONArray())
        (0 until json.length()).map { json.optString(it) }.filter { it.isNotBlank() }.distinct()
    }

    /** Returns false at the edge. The caller supplies the complete current provider order. */
    fun moveChannel(channelId: String, direction: Int, allCurrentIds: List<String>): Boolean = synchronized(lock) {
        if (direction != -1 && direction != 1) return@synchronized false
        val known = allCurrentIds.distinct()
        val oldOrder = channelOrder().filter { it in known }
        val order = (oldOrder + known.filterNot { it in oldOrder }).toMutableList()
        val index = order.indexOf(channelId)
        val destination = index + direction
        if (index < 0 || destination !in order.indices) return@synchronized false
        java.util.Collections.swap(order, index, destination)
        prefs.edit().putString(key(activeId(), "channel_order"), JSONArray().apply { order.forEach { put(it) } }.toString()).commit()
    }

    /** Reset provider view but keep favorites and per-channel playback settings. */
    fun resetChannelEditor(): Boolean = synchronized(lock) {
        prefs.edit().remove(key(activeId(), "channel_edits"))
            .remove(key(activeId(), "channel_order"))
            .remove(key(activeId(), "hide_duplicates")).commit()
    }

    /** null means the legacy device-wide history has not yet been migrated. */
    fun history(): List<HistoryEntry>? = synchronized(lock) {
        val key = key(activeId(), "history")
        if (!prefs.contains(key)) return@synchronized null
        val json = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        (0 until json.length()).mapNotNull { index ->
            val entry = json.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = runCatching { WatchContentType.valueOf(entry.optString("type")) }.getOrNull()
                ?: return@mapNotNull null
            HistoryEntry(id, type, entry.optLong("time"))
        }.take(20)
    }

    fun saveHistory(entries: List<HistoryEntry>): Boolean = synchronized(lock) {
        val json = JSONArray()
        entries.take(20).forEach { json.put(JSONObject().put("id", it.itemId)
            .put("type", it.contentType.name).put("time", it.watchedAt)) }
        prefs.edit().putString(key(activeId(), "history"), json.toString()).commit()
    }

    /** Empty string represents a deliberately cleared last channel. */
    fun lastChannelId(): String? = synchronized(lock) {
        prefs.getString(key(activeId(), "last_channel"), null)
    }
    fun hasLastChannelId(): Boolean = synchronized(lock) {
        prefs.contains(key(activeId(), "last_channel"))
    }
    fun setLastChannelId(id: String?): Boolean = synchronized(lock) {
        prefs.edit().putString(key(activeId(), "last_channel"), id.orEmpty()).commit()
    }

    fun style(field: String): String? = synchronized(lock) {
        require(field == "theme" || field == "accent")
        prefs.getString(key(activeId(), field), null)
    }
    fun saveStyle(theme: String, accent: String): Boolean = synchronized(lock) {
        prefs.edit().putString(key(activeId(), "theme"), theme)
            .putString(key(activeId(), "accent"), accent).commit()
    }

    /** Invoked only after a profile is deleted, never while its recordings exist. */
    fun purgeProfile(id: String) = synchronized(lock) {
        val prefix = "profile.$id."
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { edit.remove(it) }
        edit.commit()
    }
}
