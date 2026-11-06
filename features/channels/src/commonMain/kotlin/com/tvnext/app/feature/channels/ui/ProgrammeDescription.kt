package com.tvnext.app.feature.channels.ui

import androidx.compose.runtime.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.tmdb.ProgrammeMetadata
import com.tvnext.app.core.tmdb.ProgrammeMetadataRepository
import com.tvnext.app.core.tmdb.programmeSynopsis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.koin.core.context.GlobalContext

/** Each title owns its state: old callbacks cannot paint a different selected film. */
@Composable
internal fun rememberProgrammeMetadata(title: String?, debounceMs: Long = 150): ProgrammeMetadata? {
    val finder = remember { GlobalContext.get().getOrNull<ProgrammeMetadataRepository>() }
    var card by remember(title, finder) { mutableStateOf(title?.let { finder?.cached(it) }) }
    LaunchedEffect(title, finder) {
        val requested = title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (finder == null) return@LaunchedEffect
        if (card == null) delay(debounceMs)
        try {
            finder.observe(requested).collect { fresh ->
                if (fresh != null) card = fresh.copy(
                    overview = programmeSynopsis(card?.overview, fresh.overview),
                    frameUrl = fresh.frameUrl ?: card?.frameUrl,
                    posterUrl = fresh.posterUrl ?: card?.posterUrl,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep an already received synopsis for this exact film. */ }
    }
    return card
}

/** EPG and browse cursors arrive independently; never fall back to another channel's programme. */
internal fun windowedDescriptionProgramme(
    playingChannelId: String,
    focusedChannelId: String?,
    timeline: ChannelTimelineState,
    preview: EpgProgramme?,
    focusedEpg: ChannelEpg,
    playingEpg: ChannelEpg,
): EpgProgramme? {
    val target = timeline.channelId ?: focusedChannelId ?: playingChannelId
    if (timeline.channelId == target && preview?.channelId == target) return preview
    fun matching(epg: ChannelEpg) = epg.current?.takeIf {
        it.channelId == target && (epg.channelId == null || epg.channelId == target)
    }
    if (target == playingChannelId) matching(playingEpg)?.let { return it }
    return matching(focusedEpg)
}
