package com.tvnext.app.core.iptv.domain

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val category: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val isFavorite: Boolean = false,
    /**
     * The playlist this channel belongs to. Channels from different playlists
     * are separate entities even with identical names/tvg-ids, and EPG matching
     * is scoped to the playlist.
     */
    val playlistId: String = "",
    // ---- Catch-up / archive (timeshift) support ----------------------------
    /**
     * Provider-declared catch-up flag: `catchup="default"`, `catchup="append"`,
     * ... An empty value means the provider does not declare catch-up at all.
     */
    val catchup: String? = null,
    /** Provider-declared archive depth in days (`catchup-days="7"`). */
    val catchupDays: Long? = null,
    /**
     * Archive URL template (`catchup-source="http://.../x.m3u8?utc={utc}"`).
     * `{utc}` (and friends) are substituted with the requested start time.
     */
    val catchupSource: String? = null,
    /** Catch-up flavour: `default`, `timeshift`, `append`, `flush`. */
    val catchupType: String? = null,
    /** Legacy `timeshift` attribute (truthy flag or hour count). */
    val timeshift: String? = null,
) {
    /** True when the provider exposes ANY catch-up mechanism for this channel. */
    val hasCatchupMechanism: Boolean
        get() = !catchupSource.isNullOrBlank() ||
            !catchupType.isNullOrBlank() ||
            !catchup.isNullOrBlank() ||
            !timeshift.isNullOrBlank() ||
            (catchupDays != null && catchupDays > 0L)
}
