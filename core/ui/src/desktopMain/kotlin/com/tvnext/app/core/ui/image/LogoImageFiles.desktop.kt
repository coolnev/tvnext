package com.tvnext.app.core.ui.image

import java.io.File

actual fun readFileBytes(path: String): ByteArray? {
    val file = File(path)
    return if (file.isFile) file.readBytes() else null
}

actual fun writeFileBytes(path: String, bytes: ByteArray) {
    File(path).apply {
        parentFile?.mkdirs()
        writeBytes(bytes)
    }
}

actual fun deleteFile(path: String) {
    File(path).delete()
}

actual fun listFilesWithTimestamps(dir: String): List<Triple<String, Long, Long>> {
    val directory = File(dir)
    if (!directory.isDirectory) return emptyList()
    return directory.listFiles().orEmpty()
        .filter { it.isFile }
        .map { Triple(it.absolutePath, it.lastModified(), it.length()) }
}

actual fun ensureDirectory(path: String) {
    File(path).mkdirs()
}

actual fun totalFileSize(dir: String): Long {
    val directory = File(dir)
    if (!directory.isDirectory) return 0L
    return directory.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }
}
