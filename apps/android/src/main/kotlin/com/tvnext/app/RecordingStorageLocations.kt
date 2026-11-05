package com.tvnext.app

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Only app-owned Android storage roots may be used for restored recordings.
 * Tokens are IDs selected from [available], NEVER paths supplied by the user.
 * On Android TV this works without a DocumentsUI provider, including mounted
 * secondary volumes that expose an app-specific directory to Android.
 */
internal data class RecordingLocation(val token: String, val title: String, val root: File)

internal object RecordingStorageLocations {
    private const val PREFIX = "app-storage://"

    fun available(context: Context): List<RecordingLocation> {
        val results = mutableListOf(
            RecordingLocation("${PREFIX}internal/recordings", "Внутренняя память приложения", File(context.filesDir, "tvnext-recordings"))
        )
        context.getExternalFilesDirs(null).forEachIndexed { index, dir ->
            if (dir == null) return@forEachIndexed
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@forEachIndexed
            // Android owns each directory. No unrestricted /storage paths.
            // Volume name is used for stable identity across reboots; if not
            // exposed, use the primary / numbered slot fallback.
            val storageId = dir.absolutePath.replace('\\', '/').substringAfter("/storage/", "")
                .substringBefore('/').takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
                ?: if (index == 0) "primary" else "volume-$index"
            val token = "${PREFIX}$storageId/recordings"
            if (results.none { it.token == token }) {
                val label = if (index == 0) "Память устройства · TVNext" else "Подключённый накопитель · $storageId"
                results += RecordingLocation(token, label, File(dir, "tvnext-recordings"))
            }
        }
        return results
    }

    fun get(context: Context, token: String): RecordingLocation? =
        available(context).firstOrNull { it.token == token }

    fun ownerOf(context: Context, folder: File): RecordingLocation? {
        val parent = runCatching { folder.canonicalFile.parentFile }.getOrNull() ?: return null
        return available(context).firstOrNull { location ->
            runCatching { location.root.canonicalFile == parent }.getOrDefault(false)
        }
    }
}
