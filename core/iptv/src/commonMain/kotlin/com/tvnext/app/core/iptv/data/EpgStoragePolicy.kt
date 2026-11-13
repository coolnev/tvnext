package com.tvnext.app.core.iptv.data

/** Used by Android's disposable guide index, never by playlists/recordings. */
data class EpgStoragePolicy(
    val maxBytes: Long = 96L * 1024 * 1024,
    val usableBytes: () -> Long = { Long.MAX_VALUE },
    val clock: () -> Long = System::currentTimeMillis,
)
