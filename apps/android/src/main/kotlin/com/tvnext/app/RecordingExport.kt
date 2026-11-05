package com.tvnext.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater

/** Export is owner-checked; never deletes an original recording on failure. */
internal object RecordingExport {
    suspend fun toDownloads(context: Context, recordId: String, ownerId: String): String = withContext(Dispatchers.IO) {
        val record = LocalRecoveryStore(context).getRecording(recordId,ownerId)
            ?: error("Запись не принадлежит текущему профилю")
        val folder = File(record.folder).canonicalFile
        require(LocalRecoveryStore(context).recordingFolderOwned(folder)) { "Invalid recording folder" }
        require(folder.exists()) { "Recording is missing" }
        val fileName = "TVNext_${record.id.take(8)}.zip"
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE,"application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/TVNext")
                    put(MediaStore.MediaColumns.IS_PENDING,1)
                }) ?: error("Cannot create Download entry")
            try {
                context.contentResolver.openOutputStream(uri)?.use { archive(folder,it) }
                    ?: error("Cannot open Download entry")
                context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
                "Загрузки/TVNext/$fileName"
            } catch (e: Exception) {
                context.contentResolver.delete(uri,null,null)
                throw e
            }
        } else {
            // Android 7–9: app-specific external export avoids broad storage permissions.
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val out = File(directory,fileName)
            try { FileOutputStream(out).use { archive(folder,it) } } catch (e: Exception) { out.delete(); throw e }
            out.absolutePath
        }
    }

    suspend fun toUri(context: Context, recordId: String, ownerId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val record = LocalRecoveryStore(context).getRecording(recordId,ownerId)
            ?: error("Recording is not owned by the active profile")
        val folder = File(record.folder).canonicalFile
        require(LocalRecoveryStore(context).recordingFolderOwned(folder) && folder.exists()) { "Invalid recording folder" }
        // ACTION_CREATE_DOCUMENT creates a NEW export; never delete the source.
        // If ZIP streaming fails, remove only the incomplete export document.
        try {
            context.contentResolver.openOutputStream(uri,"w")?.use { archive(folder,it) }
                ?: error("Unable to write selected document")
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw failure
        }
    }

    private fun archive(folder: File, target: java.io.OutputStream) {
        ZipOutputStream(target.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            folder.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { file ->
                val relative = file.relativeTo(folder).invariantSeparatorsPath
                require(!relative.startsWith("../"))
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
