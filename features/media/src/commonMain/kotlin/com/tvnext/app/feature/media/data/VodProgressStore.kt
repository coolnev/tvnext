package com.tvnext.app.feature.media.data

import com.tvnext.app.core.database.AppMetaRepository

/**
 * Persists the last play position of VOD items (app_meta key-value store).
 * Used by «continue watching»: reopening an item seeks to the saved position.
 */
/** Optional Android-specific profile storage. Desktop retains the original SQL implementation. */
interface ProfileVodPositionStorage {
    /** null: no value (may migrate legacy main position); 0: explicitly completed/cleared. */
    fun get(profileId: String, itemId: String): Long?
    fun put(profileId: String, itemId: String, positionMs: Long)
}

class VodProgressStore(
    private val meta: AppMetaRepository,
    private val activeProfileId: () -> String = { "main" },
    private val profileStorage: ProfileVodPositionStorage? = null,
) {

    suspend fun get(itemId: String): Long? {
        val id = activeProfileId()
        if (profileStorage != null) {
            val value = profileStorage.get(id, itemId)
            if (value != null) return value.takeIf { it > 0L }
            if (id != "main") return null
        }
        // Read original SQLite position only for the main profile. It is
        // migrated automatically the next time the user plays/saves this item.
        return meta.getValue(key(itemId))?.toLongOrNull()?.takeIf { it > 0L }
    }

    suspend fun set(itemId: String, positionMs: Long) {
        if (positionMs <= 0L) return
        val storage = profileStorage
        if (storage != null) storage.put(activeProfileId(), itemId, positionMs)
        else meta.setValue(key(itemId), positionMs.toString())
    }

    suspend fun clear(itemId: String) {
        val storage = profileStorage
        if (storage != null) storage.put(activeProfileId(), itemId, 0L)
        else meta.setValue(key(itemId), "")
    }

    private fun key(itemId: String): String {
        // Existing shared VOD positions become the main profile's data.
        val profile = activeProfileId()
        return if (profile == "main") KEY_PREFIX + itemId
        else "vod.profile.$profile.position.$itemId"
    }

    companion object {
        const val KEY_PREFIX = "vod.position."
    }
}
