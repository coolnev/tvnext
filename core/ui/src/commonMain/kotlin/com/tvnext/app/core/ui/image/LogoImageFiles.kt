package com.tvnext.app.core.ui.image

/**
 * Minimal raw file operations for the logo disk cache. Both platforms are JVM
 * so the actuals are trivial `java.io.File` wrappers; keeping them behind
 * expect/actual lets the cache logic itself stay in commonMain.
 */
expect fun readFileBytes(path: String): ByteArray?

expect fun writeFileBytes(path: String, bytes: ByteArray)

expect fun deleteFile(path: String)

/** Returns (path, lastModifiedMillis, sizeBytes) for all files in [dir], or empty list. */
expect fun listFilesWithTimestamps(dir: String): List<Triple<String, Long, Long>>

expect fun ensureDirectory(path: String)

expect fun totalFileSize(dir: String): Long
