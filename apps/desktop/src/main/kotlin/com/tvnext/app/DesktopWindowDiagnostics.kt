package com.tvnext.app

import com.tvnext.app.core.common.platform.DesktopAppPaths
import java.io.File
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val WINDOWS_BUILD_REVISION = "r5"

/** A bounded, asynchronous transition log; contains rendering facts, no stream URLs. */
internal object DesktopWindowDiagnostics {
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64),
        { action -> Thread(action, "window-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())

    fun record(message: String) {
        writer.execute {
            runCatching { appendWindowDiagnostic(File(DesktopAppPaths.dataDirectory, "window-transitions.log"), "${Instant.now()} build=$WINDOWS_BUILD_REVISION $message") }
        }
    }
}

internal fun appendWindowDiagnostic(file: File, message: String) {
    val line = message.replace('\n', ' ').replace('\r', ' ').take(4096) + "\n"
    file.parentFile?.mkdirs()
    if (file.length() + line.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
        val previous = File(file.parentFile, file.name + ".1")
        previous.delete()
        if (!file.renameTo(previous)) file.writeText("")
    }
    file.appendText(line, Charsets.UTF_8)
}
