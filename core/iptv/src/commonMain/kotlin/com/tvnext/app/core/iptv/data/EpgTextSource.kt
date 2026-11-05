package com.tvnext.app.core.iptv.data

/**
 * Minimal character-stream abstraction used by the streaming XMLTV parser.
 *
 * Why not `java.io.Reader`? The parser lives in commonMain and the module is
 * compiled for both Android and desktop (JVM), but commonMain cannot reference
 * `java.io`. The platform layer opens a real reader over a temp file and hands
 * this interface to the parser, which reads in bounded chunks.
 *
 * The stream is single-pass and forward-only (like a Reader): the parser never
 * needs to rewind, so platforms may wrap a plain `BufferedReader`.
 */
interface EpgTextSource {
    /**
     * Reads up to [length] characters into [into] starting at [offset].
     * Returns the number read, or `-1` when the end of the stream is reached.
     */
    fun read(into: CharArray, offset: Int, length: Int): Int

    /** Closes the underlying resource. Safe to call more than once. */
    fun close()
}

/** In-memory adapter over a [String] (tests / small documents). */
class StringEpgTextSource(val text: String) : EpgTextSource {
    private val reader = text.reader().buffered()
    override fun read(into: CharArray, offset: Int, length: Int): Int = reader.read(into, offset, length)
    override fun close() {
        runCatching { reader.close() }
    }
}

/** Closes the source after [block], even on exceptions. */
inline fun <T> EpgTextSource.use(block: (EpgTextSource) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

/** Opens a buffered UTF-8 character source over the (temp) file at [path]. */
expect fun openEpgTextSource(path: String): EpgTextSource

/**
 * Reads the whole temp EPG document into a [String] when it is small enough
 * (≤ [maxBytes]), or returns null for the streaming fallback. The parser has
 * fast paths over in-memory strings (native `String.indexOf`), which make a
 * typical mid-size EPG import 3-5x faster — at the cost of a ~2x RAM copy of
 * the document. Desktop heaps tolerate that; Android TV heaps do not, so the
 * Android actual always returns null.
 */
expect fun readEpgFileToString(path: String, maxBytes: Long): String?

/** Deletes the temporary EPG file at [path] (best effort, never throws). */
expect fun deleteEpgTempFile(path: String)

/**
 * Directory for the lightweight EPG cache (Android: noBackupFilesDir, desktop:
 * `~/.tvnext/cache/epg`), or null when it cannot be created/resolved.
 */
expect fun platformEpgCacheDir(): String?

/** Writes the lightweight EPG cache file (best effort, never throws). */
expect fun writeEpgCacheFile(path: String, text: String)

/** Reads the lightweight EPG cache file, or null on any failure / absence. */
expect fun readEpgCacheFile(path: String): String?

/**
 * Streams the lightweight EPG cache line-by-line. [onLine] is invoked once per
 * line (without the trailing newline) and returns `true` to keep reading or
 * `false` to abort. Returns `true` only when fully consumed, `false` when
 * reading failed, the file is missing, or a callback aborts.
 *
 * The EPG cache can be hundreds of MB; a `readText()`-style whole-file load
 * OOMs on Android TV heaps, which silently disabled the disk cache and forced
 * a full re-import on every launch.
 */
expect fun readEpgCacheLines(path: String, onLine: (String) -> Boolean): Boolean

/**
 * Streams [header] + [lines] into the cache file atomically (write to .tmp,
 * then rename), without materializing the whole cache as one String. Best
 * effort, never throws; returns `true` on success.
 */
expect fun writeEpgCacheStreaming(path: String, header: String, lines: Sequence<String>): Boolean

/**
 * Device timezone's UTC offset in millis at [atEpochMs]. Used by the XMLTV
 * parser to convert offset-less ("local time") timestamps.
 */
expect fun localTimezoneOffsetMs(atEpochMs: Long): Long

/**
 * Streams the EPG document from [url] (gzip-aware) and runs [block] with a
 * live text source. The source is ONLY valid inside [block] — the HTTP response
 * is kept open for its duration, so the parser reads bytes as they arrive and
 * the first programmes appear before the whole (multi-hundred-MB) feed has
 * downloaded. Returns false when the fetch failed before any data was read.
 */
expect suspend fun withEpgStream(
    client: io.ktor.client.HttpClient,
    url: String,
    block: suspend (EpgTextSource) -> Unit,
): Boolean

/** Cooperative pacing of large XMLTV parses; Android yields CPU to playback. */
expect fun paceEpgParser()

/** Yield during large cache hydration; Android gives the player extra CPU time. */
expect fun paceEpgCacheHydration()

/** One low-priority Android worker for CPU-heavy XMLTV scanning. */
expect fun epgParseDispatcher(): kotlinx.coroutines.CoroutineDispatcher

/** The app left the foreground; keep the last complete EPG snapshot. */
class EpgImportDeferredException : IllegalStateException("EPG deferred while app is backgrounded")

/** Abort an unpublished batch when app leaves foreground during final sorting. */
expect fun checkEpgImportPriority()

/** Abort an overly large XMLTV before the Android process hits OOM. */
class EpgMemoryPressureException : IllegalStateException("EPG memory budget exceeded")

/** Android-only Java-heap budget check during cache hydration. */
expect fun epgCacheMemoryExceeded(): Boolean
