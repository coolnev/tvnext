package com.tvnext.app

import com.tvnext.app.core.common.platform.AppDataReset
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop (Windows) implementation of [AppDataReset].
 *
 * All user data lives in `%USERPROFILE%\.tvnext` (the SQLDelight database,
 * the channel-logo cache, backups) — deliberately OUTSIDE the install
 * directory, so an uninstall/install cycle keeps it. A "reset" therefore
 * cannot just delete files from the running process: on Windows the SQLite
 * file is locked while the JVM is alive. Instead we write a marker file and
 * restart the app; the next launch (before the database is opened) deletes
 * the whole data directory.
 */
class DesktopAppDataReset : AppDataReset {

    private val dataDir: File = File(System.getProperty("user.home"), ".tvnext")
    private val resetMarker: File = File(dataDir, ".reset-requested")

    override fun handlePendingReset() {
        if (!resetMarker.isFile) return
        resetMarker.delete()
        // The previous process may still be shutting down and holding the
        // SQLite file open — retry briefly before giving up.
        repeat(5) { attempt ->
            if (dataDir.deleteRecursively()) return
            if (attempt < 4) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    override fun requestResetAndRestart() {
        runCatching {
            dataDir.mkdirs()
            resetMarker.writeText("1")
        }
        // Restart the packaged app (jpackage layout: <install>/TVNext.exe).
        // In a dev run no exe is found and the app simply exits — the data is
        // wiped on the next manual launch.
        findExecutable()?.let { exe ->
            runCatching { ProcessBuilder(exe.absolutePath).start() }
        }
        exitProcess(0)
    }

    /**
     * Locates the packaged app executable by walking up from the running jar.
     * In a jpackage layout the jar lives in `<install>/app` and the `.exe`
     * sits in `<install>`.
     */
    private fun findExecutable(): File? {
        val jar = System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it) }
            ?.firstOrNull { it.isFile && it.name.endsWith(".jar") }
        var dir = jar?.parentFile
        repeat(3) {
            if (dir == null) return null
            File(dir, "TVNext.exe").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }
}
