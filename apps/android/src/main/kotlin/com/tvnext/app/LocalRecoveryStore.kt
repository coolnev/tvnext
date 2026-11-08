package com.tvnext.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class LocalProfile(val id: String, val name: String)
internal data class SavedRecording(
    val id: String,
    val profileId: String,
    val title: String,
    val folder: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val error: String = "",
    val scheduled: Boolean = false,
)
internal data class ScheduledRecording(
    val id: String,
    val profileId: String,
    val title: String,
    val streamUrl: String,
    val startAt: Long,
    val durationMs: Long,
    val channelId: String = "",
)

/** All data belongs to this device, is written to private Android storage, and is never logged. */
internal class LocalRecoveryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tvnext_restored_local_data", Context.MODE_PRIVATE)
    private val lock = Any()
    private val appContext = context.applicationContext
    private val filesRoot = File(appContext.filesDir, "tvnext-recordings")

    fun paddingBeforeMinutes(): Int = prefs.getInt("recording_pad_before_min", 2).coerceIn(0, 60)
    fun paddingAfterMinutes(): Int = prefs.getInt("recording_pad_after_min", 5).coerceIn(0, 60)
    fun setRecordingPadding(before: Int, after: Int): Boolean =
        before in 0..60 && after in 0..60 && prefs.edit()
            .putInt("recording_pad_before_min", before).putInt("recording_pad_after_min", after).commit()

    fun limitGiB(): Int = prefs.getInt("recording_limit_gib", 4).coerceIn(1,128)
    fun setLimitGiB(amount: Int): Boolean = amount in 1..128 &&
        prefs.edit().putInt("recording_limit_gib", amount).commit()

    fun profiles(): List<LocalProfile> = synchronized(lock) {
        val stored = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        val parsed = (0 until stored.length()).mapNotNull { i ->
            runCatching { stored.getJSONObject(i).let { LocalProfile(it.getString("id"), it.getString("name")) } }.getOrNull()
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }.take(8)
        parsed.ifEmpty { listOf(LocalProfile("main", "Основной")) }
    }
    fun activeProfileId(): String = synchronized(lock) {
        val selected = prefs.getString("active", "main") ?: "main"
        selected.takeIf { id -> profiles().any { it.id == id } } ?: profiles().first().id
    }
    fun switchProfile(id: String): Boolean = synchronized(lock) {
        if (profiles().none { it.id == id }) false else prefs.edit().putString("active", id).commit()
    }
    fun addProfile(name: String): LocalProfile? = synchronized(lock) {
        val all = profiles()
        val cleaned = name.trim().take(40)
        if (cleaned.isEmpty() || all.size >= 8) return@synchronized null
        val profile = LocalProfile(UUID.randomUUID().toString(), cleaned)
        val json = JSONArray().apply { (all + profile).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }
        if (prefs.edit().putString("profiles", json.toString()).commit()) profile else null
    }

    /** Rename a profile without changing its stable ID or the owner of records. */
    fun renameProfile(id: String, newName: String): Boolean = synchronized(lock) {
        val cleaned = newName.trim().take(40)
        val all = profiles()
        if (cleaned.isEmpty() || all.none { it.id == id }) return@synchronized false
        persistProfiles(all.map { if (it.id == id) it.copy(name = cleaned) else it })
    }

    /** Never implicitly erase someone's recordings or outstanding schedules. */
    fun deleteEmptyProfile(id: String): Boolean = synchronized(lock) {
        val all = profiles()
        if (all.size < 2 || id == activeProfileId() || all.none { it.id == id } ||
            readRecordings().any { it.profileId == id } || schedules().any { it.profileId == id })
            return@synchronized false
        persistProfiles(all.filterNot { it.id == id })
    }

    /** Import only profile metadata. Do not orphan records owned by an existing profile. */
    fun restoreProfiles(restored: List<LocalProfile>, selectedId: String): Boolean = synchronized(lock) {
        if (restored.isEmpty() || restored.size > 8 || restored.none { it.id == "main" } ||
            restored.distinctBy { it.id }.size != restored.size ||
            restored.any { it.id.isBlank() || it.id.length > 80 || it.name.isBlank() || it.name.length > 40 } ||
            restored.none { it.id == selectedId }) return@synchronized false
        val ids = restored.map { it.id }.toSet()
        if (readRecordings().any { it.profileId !in ids } ||
            schedules().any { it.profileId !in ids }) return@synchronized false
        prefs.edit().putString("profiles", JSONArray().apply {
            restored.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()).putString("active", selectedId).commit()
    }

    private fun persistProfiles(all: List<LocalProfile>): Boolean =
        prefs.edit().putString("profiles", JSONArray().apply {
            all.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()).commit()

    fun availableRecordingLocations(): List<RecordingLocation> = RecordingStorageLocations.available(appContext)

    fun selectedRecordingLocation(): RecordingLocation? = synchronized(lock) {
        val selected = prefs.getString("recording_location", "app-storage://internal/recordings")
        // A disconnected USB volume must NOT silently redirect new captures to
        // internal storage; prompt the user to pick a reachable location.
        RecordingStorageLocations.get(appContext, selected.orEmpty())
    }

    fun selectRecordingLocation(token: String): Boolean = synchronized(lock) {
        val location = RecordingStorageLocations.get(appContext, token) ?: return@synchronized false
        val directory = location.root
        if (!directory.isDirectory && !directory.mkdirs()) return@synchronized false
        if (!directory.canWrite()) return@synchronized false
        prefs.edit().putString("recording_location", token).commit()
    }

    fun recordingFolderOwned(folder: File): Boolean =
        RecordingStorageLocations.ownerOf(appContext, folder) != null

    fun recordings(profileId: String = activeProfileId()): List<SavedRecording> = synchronized(lock) {
        readRecordings().filter { it.profileId == profileId }.sortedByDescending { it.startedAt }
    }
    fun getRecording(id: String, owner: String = activeProfileId()): SavedRecording? = synchronized(lock) {
        readRecordings().find { it.id == id && it.profileId == owner }
    }
    fun saveRecording(record: SavedRecording) = synchronized(lock) {
        val newList = readRecordings().filterNot { it.id == record.id } + record
        prefs.edit().putString("recordings", JSONArray().apply { newList.forEach { put(it.toJson()) } }.toString()).commit()
    }
    fun deleteRecording(id: String, owner: String = activeProfileId()): Boolean = synchronized(lock) {
        val record = getRecording(id, owner) ?: return@synchronized false
        // Never delete a path obtained from preferences until confined to the private root.
        val target = File(record.folder).canonicalFile
        if (!recordingFolderOwned(target)) return@synchronized false
        if (RecordingRuntime.active.value?.id == id) return@synchronized false
        if (!target.deleteRecursively() && target.exists()) return@synchronized false
        prefs.edit().putString("recordings", JSONArray().apply {
            readRecordings().filterNot { it.id == id }.forEach { put(it.toJson()) }
        }.toString()).commit()
    }
    fun recoverInterrupted() = synchronized(lock) {
        val all = readRecordings()
        if (all.any { it.status == "recording" }) {
            val now = System.currentTimeMillis()
            prefs.edit().putString("recordings", JSONArray().apply {
                all.map { if (it.status == "recording") it.copy(status = "partial", finishedAt = now) else it }
                    .forEach { put(it.toJson()) }
            }.toString()).commit()
        }
    }
    fun folderFor(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid recording ID" }
        val location = requireNotNull(selectedRecordingLocation()) { "Selected recording storage is disconnected" }
        val dir = File(location.root, id)
        require(dir.isDirectory || dir.mkdirs()) { "Cannot create recording directory" }
        return dir.canonicalFile
    }

    /** The device-wide quota includes all currently reachable app-owned volumes. */
    fun recordingBytes(): Long = availableRecordingLocations().sumOf { location ->
        location.root.listFiles()?.sumOf { child ->
            child.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } ?: 0L
    }

    fun schedules(): List<ScheduledRecording> = synchronized(lock) {
        val items = runCatching { JSONArray(prefs.getString("schedules", "[]")) }.getOrDefault(JSONArray())
        (0 until items.length()).mapNotNull { i -> runCatching { val x = items.getJSONObject(i)
            ScheduledRecording(x.getString("id"),x.getString("profileId"),x.getString("title"),
                x.getString("streamUrl"),x.getLong("startAt"),x.getLong("durationMs"),x.optString("channelId")) }.getOrNull() }
    }
    fun schedule(item: ScheduledRecording) = synchronized(lock) {
        saveSchedules(schedules().filterNot { it.id == item.id } + item)
    }
    fun removeSchedule(id: String) = synchronized(lock) { saveSchedules(schedules().filterNot { it.id == id }) }
    private fun saveSchedules(list: List<ScheduledRecording>) {
        prefs.edit().putString("schedules", JSONArray().apply { list.forEach {
            put(JSONObject().put("id",it.id).put("profileId",it.profileId).put("title",it.title)
                .put("streamUrl",it.streamUrl).put("startAt",it.startAt).put("durationMs",it.durationMs).put("channelId",it.channelId))
        } }.toString()).commit()
    }

    private fun readRecordings(): List<SavedRecording> {
        val json = runCatching { JSONArray(prefs.getString("recordings", "[]")) }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { i -> runCatching { val x = json.getJSONObject(i)
            SavedRecording(x.getString("id"),x.getString("profileId"),x.getString("title"),
                x.getString("folder"),x.getLong("startedAt"),x.optLong("finishedAt"),
                x.optString("status", "stopped"),x.optString("error"),x.optBoolean("scheduled",false)) }.getOrNull() }
    }
    private fun SavedRecording.toJson() = JSONObject().put("id",id).put("profileId",profileId)
        .put("title",title).put("folder",folder).put("startedAt",startedAt)
        .put("finishedAt",finishedAt).put("status",status).put("error",error).put("scheduled",scheduled)
}
