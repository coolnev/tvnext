package com.tvnext.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** SAF intents are resolved each time; never cache a TV's installed providers. */
internal object RecordingFileContracts {
    fun zipExport(context: Context, name: String): Intent? {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, name)
        }
        return if (canHandle(context,intent)) Intent.createChooser(intent,"Сохранить запись через приложение") else null
    }
    fun folder(context: Context): Intent? {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return if (canHandle(context,intent)) Intent.createChooser(intent,"Выбрать папку записи") else null
    }
    fun canHandle(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY) != null

    fun installExplorerIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.parse("market://details?id=dev.dworks.apps.anexplorer"))
}
