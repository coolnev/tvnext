package com.tvnext.app

import com.tvnext.app.core.iptv.domain.Channel

/** Pure view transform: never rewrite the provider's names, tvg-id, URLs or database rows. */
internal fun projectProfileChannels(
    raw: List<Channel>,
    favorites: Set<String>,
    edits: Map<String, ChannelCustomization>,
    orderedIds: List<String>,
    includeHidden: Boolean = false,
    hideDuplicates: Boolean = false,
): List<Channel> {
    val indices = orderedIds.withIndex().associate { it.value to it.index }
    val seenStreams = HashSet<String>()
    val indexed = raw.withIndex().mapNotNull { (position, channel) ->
        val change = edits[channel.id]
        val duplicate = hideDuplicates && channel.streamUrl.isNotBlank() && !seenStreams.add(channel.streamUrl)
        if (!includeHidden && (change?.hidden == true || duplicate)) null
        else (position to channel.copy(
            name = change?.name ?: channel.name,
            category = change?.category ?: channel.category,
            isFavorite = channel.id in favorites,
        ))
    }
    if (indices.isEmpty()) return indexed.map { it.second }
    return indexed.sortedWith(compareBy<Pair<Int, Channel>>(
        { indices[it.second.id] ?: Int.MAX_VALUE }, { it.first },
    )).map { it.second }
}
