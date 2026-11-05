package com.tvnext.app

import android.content.Context
import android.os.Debug
import java.io.File

/**
 * Minimal on-device diagnostics for the "app silently closes ~15-25 s after
 * opening" issue on Android TV.
 *
 * The OS kills a memory-hungry process (LMK) or a native crash lands WITHOUT
 * any Java exception, so the existing crash.log handler never fires and there
 * is nothing in the app to look at. This object:
 *  - keeps a boot marker in filesDir: written at startup, deleted only on a
 *    clean exit (MainActivity.onDestroy). If the marker is still there on the
 *    next launch, the previous process was killed by the system — that is the
 *    signature of the reported bug;
 *  - writes a small text log (filesDir/tvnext_diag.log) with boot facts
 *    (heap, PSS, DB size) and kill events, so the diagnosis survives without
 *    adb access;
 *  - exposes [wasPreviousRunKilled] so the host can skip the automatic EPG
 *    import after such a kill (breaking the kill-loop: the heavy import is
 *    what usually causes the memory spike in the first place).
 */
object AndroidBootDiagnostics {

    private const val MARKER = ".boot-marker"
    private const val DIAG_LOG = "tvnext_diag.log"

    @Volatile
    private var previousRunKilled: Boolean = false

    /** True when the previous process was killed by the system (not a clean exit). */
    val wasPreviousRunKilled: Boolean
        get() = previousRunKilled

    /** Must be called once, early in TVNextApplication.onCreate. */
    fun onAppCreate(context: Context) {
        val marker = File(context.filesDir, MARKER)
        val hadMarker = runCatching { marker.isFile }.getOrDefault(false)
        if (hadMarker) {
            previousRunKilled = true
            val lastBoot = runCatching { marker.readText().toLongOrNull() }.getOrNull() ?: 0L
            log(
                context,
                "PREVIOUS RUN KILLED by system (lived ${System.currentTimeMillis() - lastBoot} ms " +
                    "since last boot marker)",
            )
        } else {
            log(context, "previous run exited cleanly (no boot marker)")
        }
        // All marker I/O is best-effort: on the low-end boxes this targets the
        // storage can be full (huge XMLTV + DB), and a boot-critical exception
        // here would make the app unstartable. Failure == "no marker".
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        log(
            context,
            "BOOT heap=${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB " +
                "maxHeap=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB " +
                "pss=${pssKb() / 1024}MB db=${databaseSizeMb(context)}MB",
        )
    }

    /**
     * Called from MainActivity.onStart: the app is in the foreground again.
     * The boot marker is (re)written so that only a FOREGROUND kill (e.g.
     * during the deferred EPG import) is classified as a crash — a background
     * reap after Home must not disable the automatic EPG refresh.
     */
    fun onEnterForeground(context: Context) {
        runCatching { File(context.filesDir, MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    /**
     * Called from MainActivity.onStop: the app left the foreground (Home /
     * exit). A kill that happens while backgrounded is ordinary Android
     * behaviour, not the reported crash, so the marker is dropped.
     */
    fun onEnterBackground(context: Context) {
        runCatching { File(context.filesDir, MARKER).delete() }
    }

    /** Called from MainActivity.onDestroy — the only "clean" user exit. */
    fun onCleanExit(context: Context) {
        runCatching { File(context.filesDir, MARKER).delete() }
        log(context, "clean exit")
    }

    /** Appends one line to filesDir/tvnext_diag.log (never throws). */
    fun log(context: Context, message: String) {
        runCatching {
            val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            val log = File(context.filesDir, DIAG_LOG)
            if (log.length() > 256 * 1024) {
                val previous = File(context.filesDir, "$DIAG_LOG.previous")
                previous.delete(); log.renameTo(previous)
            }
            log.appendText("$stamp $message\n")
        }
    }

    private fun pssKb(): Int = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss
    }.getOrDefault(0)

    private fun databaseSizeMb(context: Context): Long = runCatching {
        File(context.applicationInfo.dataDir, "databases/tvnext.db").length() / 1024 / 1024
    }.getOrDefault(0L)
}
