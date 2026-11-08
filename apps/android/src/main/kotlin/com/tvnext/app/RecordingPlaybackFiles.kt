package com.tvnext.app

import android.net.Uri
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackItem
import java.io.File

internal fun SavedRecording.playbackItem(): PlaybackItem? {
    val folder = File(folder)
    val file = listOf(File(folder, "recording.m3u8"), File(folder, "capture.ts"))
        .firstOrNull { it.isFile && it.canRead() && it.length() > 0 } ?: return null
    return PlaybackItem(id, title, Uri.fromFile(file).toString(), PlaybackContentKind.VOD)
}
