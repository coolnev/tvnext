package com.tvnext.app

import android.content.Context
import android.os.Debug
import android.view.Choreographer
import com.tvnext.app.core.common.log.AndroidPerformanceCounters
import java.io.File
import kotlinx.coroutines.*

/** Foreground-only, bounded local diagnostics. No network upload and no programme/user data. */
object AndroidPerformanceDiagnostics : Choreographer.FrameCallback {
    private var active = false
    private var previousFrame = 0L
    private var frames = 0L
    private var slowFrames = 0L
    private var longestMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reportJob: Job? = null

    fun start(context: Context) {
        if (active) return
        active = true
        val app = context.applicationContext
        previousFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
        reportJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val frameCount = frames; val slow = slowFrames; val longest = longestMs
                frames = 0; slowFrames = 0; longestMs = 0
                val counters = AndroidPerformanceCounters.snapshot()
                withContext(Dispatchers.IO) {
                    val runtime = Runtime.getRuntime()
                    val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    val line = "${System.currentTimeMillis()} heap=${runtime.totalMemory() - runtime.freeMemory()} " +
                        "heapMax=${runtime.maxMemory()} native=${Debug.getNativeHeapAllocatedSize()} pssKb=${memory.totalPss} " +
                        "frames=$frameCount slowOver32ms=$slow longestFrameMs=$longest $counters\n"
                    AndroidPerformanceCounters.report(line)
                    runCatching {
                        val log = File(app.filesDir, "tvnext_performance.log")
                        if (log.length() > 512 * 1024) {
                            val previous = File(app.filesDir, "tvnext_performance.previous.log")
                            previous.delete(); log.renameTo(previous)
                        }
                        log.appendText(line)
                    }
                    android.util.Log.d("TVNextPerformance", line)
                }
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!active) return
        if (previousFrame > 0) {
            val elapsed = (frameTimeNanos - previousFrame) / 1_000_000
            frames++
            if (elapsed > 32) slowFrames++
            longestMs = maxOf(longestMs, elapsed)
        }
        previousFrame = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        active = false
        Choreographer.getInstance().removeFrameCallback(this)
        reportJob?.cancel(); reportJob = null
        previousFrame = 0
    }
}
