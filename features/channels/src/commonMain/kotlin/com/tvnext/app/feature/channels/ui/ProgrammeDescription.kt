package com.tvnext.app.feature.channels.ui

import androidx.compose.runtime.*
import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import com.tvnext.app.core.tmdb.ProgrammeMetadata
import com.tvnext.app.core.tmdb.ProgrammeMetadataRequest
import com.tvnext.app.core.tmdb.ProgrammeMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.koin.core.context.GlobalContext

/** Each title owns its state: old callbacks cannot paint a different selected film. */
@Composable
internal fun rememberProgrammeMetadata(programme: EpgProgramme?, debounceMs: Long = 150): ProgrammeMetadata? =
    rememberProgrammeMetadataRequest(programme?.let { ProgrammeMetadataRequest(it.title, it.category, it.description) }, debounceMs)

// Explicit catalogue helper retained for catalogue fixtures; live EPG always supplies its context.
@Composable
internal fun rememberProgrammeMetadata(title: String?, debounceMs: Long = 150): ProgrammeMetadata? =
    rememberProgrammeMetadataRequest(title?.let { ProgrammeMetadataRequest(it, category = "Фильм") }, debounceMs)

@Composable
private fun rememberProgrammeMetadataRequest(request: ProgrammeMetadataRequest?, debounceMs: Long): ProgrammeMetadata? {
    val finder = remember { GlobalContext.get().getOrNull<ProgrammeMetadataRepository>() }
    var card by remember(request, finder) { mutableStateOf(request?.let { finder?.cached(it) }) }
    LaunchedEffect(request, finder) {
        val requested = request?.takeIf { it.canEnrich && it.title.isNotBlank() } ?: return@LaunchedEffect
        if (finder == null) return@LaunchedEffect
        if (card == null) delay(debounceMs)
        try {
            finder.observe(requested).collect { fresh ->
                // The repository selects one verified identity/provider. Do not
                // splice its synopsis together with an earlier provider's frames.
                card = fresh
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep an already received synopsis for this exact film. */ }
    }
    DisposableEffect(request, finder) { onDispose { request?.let { finder?.forget(it) } } }
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
