package com.tvnext.app

import android.content.Context
import android.content.Intent
import com.tvnext.app.core.common.platform.AppDataReset
import java.io.File
import kotlin.system.exitProcess

/**
 * Android implementation of [AppDataReset].
 *
 * Deletes the SQLDelight database and the logo cache, then restarts the app.
 * The wipe happens on the next launch (see [handlePendingReset]) because the
 * database connection is still open in the running process.
 */
class AndroidAppDataReset(private val context: Context) : AppDataReset {

    private val resetMarker: File = File(context.filesDir, ".reset-requested")

    override fun handlePendingReset() {
        if (!resetMarker.isFile) return
        resetMarker.delete()
        context.deleteDatabase("tvnext.db")
        context.deleteDatabase(File(context.noBackupFilesDir, "epg-index-v1.db").absolutePath)
        context.cacheDir.deleteRecursively()
        // Reset means reset all imported EPG as well. It is now stored outside
        // cacheDir so Android cannot evict it between ordinary app launches.
        File(context.noBackupFilesDir, "epg_cache").deleteRecursively()
    }

    override fun requestResetAndRestart() {
        runCatching { resetMarker.writeText("1") }
        // Relaunch the launcher activity with a clean task, then kill the
        // process so the database file is released before the wipe.
        runCatching {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (intent != null) context.startActivity(intent)
        }
        exitProcess(0)
    }
}
