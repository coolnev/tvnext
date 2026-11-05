package com.tvnext.app.core.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import com.tvnext.app.core.common.util.currentTimeMillis
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.common.log.AndroidPerformanceCounters
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Downloads raw image bytes for a logo URL. Wired to Ktor by the app. */
fun interface LogoFetcher {
    suspend fun fetch(url: String): ByteArray?
}

interface LogoImageLoader {
    /** Returns the decoded logo bitmap for [url], or null (never throws). */
    suspend fun load(url: String): ImageBitmap?
}

/**
 * Channel-logo loader with:
 *  - an in-memory LRU cache (never re-downloads/re-decodes during a session);
 *  - a bounded disk cache (survives restarts, capped at [maxDiskBytes]);
 *  - per-URL request deduplication;
 *  - a concurrency cap: scrolling a channel list can suddenly want dozens of
 *    logos at once — without a limit every visible row starts a parallel HTTP
 *    fetch, flooding the network and the decoder on low-end Android TVs and
 *    causing UI jank. Only [maxConcurrentLoads] downloads run at a time; the
 *    rest wait for a free slot.
 *  - local error handling: a failed download/decode returns null, it never
 *    propagates and never breaks the channel.
 */
class DefaultLogoImageLoader(
    private val fetcher: LogoFetcher,
    cacheDir: String,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
    maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
) : LogoImageLoader {

    private val diskDir = cacheDir.trimEnd('/')
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val memory = LruCache(maxMemoryEntries, if (isAndroidPlatform()) 24L * 1024 * 1024 else Long.MAX_VALUE)
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val loadGate = Semaphore(maxConcurrentLoads)
    private val admissionGate = Semaphore(maxConcurrentLoads)
    private class AndroidLoad(val task: Deferred<ImageBitmap?>, var readers: Int = 0)
    private val androidLoads = HashMap<String, AndroidLoad>()

    // Waiting for admission is cancellable: a vanished row cannot enqueue an orphan job.
    private suspend fun loadAndroid(url: String): ImageBitmap? = admissionGate.withPermit {
        mutex.withLock { memory.get(url) }?.let {
            AndroidPerformanceCounters.increment("logo.hits")
            return@withPermit it
        }
        val request = mutex.withLock {
            val active = androidLoads[url] ?: run {
                lateinit var created: AndroidLoad
                created = AndroidLoad(scope.async(start = CoroutineStart.LAZY) {
                    var result: ImageBitmap? = null
                    try {
                        result = loadInternal(url)
                        result
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                result?.let { memory.put(url, it) }
                                if (androidLoads[url] === created) androidLoads.remove(url)
                                AndroidPerformanceCounters.set("logo.inFlight", androidLoads.size.toLong())
                            }
                        }
                    }
                })
                androidLoads[url] = created
                created
            }
            active.readers++
            AndroidPerformanceCounters.set("logo.inFlight", androidLoads.size.toLong())
            active
        }
        AndroidPerformanceCounters.increment("logo.requests")
        request.task.start()
        try { request.task.await() } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    request.readers--
                    if (request.readers == 0 && !request.task.isCompleted) {
                        if (androidLoads[url] === request) androidLoads.remove(url)
                        request.task.cancel()
                        AndroidPerformanceCounters.increment("logo.cancelled")
                        AndroidPerformanceCounters.set("logo.inFlight", androidLoads.size.toLong())
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        if (isAndroidPlatform()) return loadAndroid(url)
        mutex.withLock { memory.get(url) }?.let { return it }

        var task: Deferred<ImageBitmap?>?
        mutex.withLock {
            memory.get(url)?.let { return it }
            task = inFlight[url]
            if (task == null) {
                task = scope.async { loadInternal(url) }
                inFlight[url] = task
            }
        }

        val bitmap = task!!.await()
        mutex.withLock {
            if (inFlight[url] === task) inFlight.remove(url)
            if (bitmap != null) memory.put(url, bitmap)
        }
        return bitmap
    }

    private suspend fun loadInternal(url: String): ImageBitmap? = try {
        val key = stableHash(url)
        val cachePath = "$diskDir/$key"
        val fromDisk = withContext(Dispatchers.Default) { readFileBytes(cachePath) }
        if (fromDisk != null) {
            val decoded = decodeImageBytes(fromDisk)
            if (decoded != null) return decoded
            withContext(Dispatchers.Default) { deleteFile(cachePath) }
        }
        // The network fetch + decode is the expensive part: serialize it under
        // a semaphore so a fast scroll doesn't fire N parallel downloads.
        loadGate.withPermit {
            val bytes = fetcher.fetch(url) ?: return@withPermit null
            val decoded = decodeImageBytes(bytes) ?: return@withPermit null
            withContext(Dispatchers.Default) {
                writeFileBytes(cachePath, bytes)
                evictIfNeeded()
            }
            decoded
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private var lastEvictAtMs = 0L

    private fun evictIfNeeded() {
        // Disk eviction scans the whole cache directory (a syscall per file):
        // running it on every download while scrolling is wasteful. Throttle to
        // at most once per few seconds.
        val now = currentTimeMillis()
        if (now - lastEvictAtMs < EVICT_THROTTLE_MS) return
        lastEvictAtMs = now
        ensureDirectory(diskDir)
        val total = totalFileSize(diskDir)
        if (total <= maxDiskBytes) return
        val files = listFilesWithTimestamps(diskDir).sortedBy { it.second }
        var current = total
        for ((path, _, size) in files) {
            if (current <= maxDiskBytes) break
            deleteFile(path)
            current -= size
        }
    }

    private fun stableHash(input: String): String {
        var hash: ULong = FNV_OFFSET_BASIS
        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        return hash.toString(16)
    }

    /** Simple access-order LRU map. Only accessed under [mutex]. */
    private class LruCache(private val maxEntries: Int, private val maxBytes: Long) {
        private val map = LinkedHashMap<String, ImageBitmap>()
        private var bytes = 0L
        private fun size(value: ImageBitmap) = value.width.toLong() * value.height * 4

        fun get(key: String): ImageBitmap? {
            val value = map.remove(key) ?: return null
            map[key] = value
            return value
        }

        fun put(key: String, value: ImageBitmap) {
            map.remove(key)?.let { bytes -= size(it) }
            if (size(value) <= maxBytes) { map[key] = value; bytes += size(value) }
            while (map.size > maxEntries || bytes > maxBytes) {
                map.remove(map.keys.first())?.let { bytes -= size(it) }
            }
            if (isAndroidPlatform()) {
                AndroidPerformanceCounters.set("logo.entries", map.size.toLong())
                AndroidPerformanceCounters.set("logo.bytes", bytes)
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_DISK_BYTES = 32L * 1024 * 1024
        const val DEFAULT_MAX_MEMORY_ENTRIES = 256
        // Raised from 3: on first launch a channel list can have 50-100+ rows
        // visible/prefetched at once, each wanting its logo. 3 in-flight
        // downloads meant most rows sat on the placeholder for a while even on
        // a fine connection. 6 is still a real cap (not "everything at once"),
        // it just stops being the bottleneck on anything but a very weak box.
        const val DEFAULT_MAX_CONCURRENT_LOADS = 6
        const val EVICT_THROTTLE_MS = 5_000L
        const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325UL
        const val FNV_PRIME: ULong = 0x100000001b3UL
    }
}
