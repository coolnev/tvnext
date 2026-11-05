package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.iptv.domain.Channel

/** Identity survives reorder/removal; a category selection explicitly requests its first row. */
internal fun resolveAndroidChannelFocus(
    visibleIds: List<String>,
    savedId: String?,
    selectedId: String?,
    forceTop: Boolean = false,
): String? = if (forceTop) visibleIds.firstOrNull()
    else savedId?.takeIf { it in visibleIds }
        ?: selectedId?.takeIf { it in visibleIds }
        ?: visibleIds.firstOrNull()

/** Explicit disabled flags ("0", "none") are not archive support. */
internal fun androidHasArchive(channel: Channel): Boolean {
    fun enabled(value: String?): Boolean = !value.isNullOrBlank() &&
        value.trim().lowercase() !in setOf("0", "false", "none", "off", "disabled")
    return !channel.catchupSource.isNullOrBlank() || enabled(channel.catchup) ||
        enabled(channel.catchupType) || enabled(channel.timeshift) || (channel.catchupDays ?: 0) > 0
}
