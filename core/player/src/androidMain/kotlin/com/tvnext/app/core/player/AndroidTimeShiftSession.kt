package com.tvnext.app.core.player

import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.archive.ArchiveStatus
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.common.util.formatDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Resolves timestamp seeks, independently of EPG boundaries. Owned by the Android player. */
internal class AndroidTimeShiftSession(
    private val channels: ChannelsRepository,
    private val archive: ArchiveRepository,
    private val scope: CoroutineScope,
    private val current: () -> PlayerState,
    private val open: (PlaybackItem) -> Unit,
    private val localSeek: (Long) -> Boolean,
    private val pause: () -> Unit,
) {
    val state = MutableStateFlow(TimeShiftState())
    var opening = false
        private set
    private var serial = 0L
    private var job: Job? = null
    private var depthJob: Job? = null
    private var depth: Long? = null
    private var pending: Long? = null
    private var lastEndItem: PlaybackItem? = null

    fun cancel() {
        serial++
        job?.cancel(); depthJob?.cancel()
        job = null; pending = null; depth = null; lastEndItem = null
        state.value = TimeShiftState()
    }

    fun itemOpened(item: PlaybackItem) {
        lastEndItem = null
        if (item.kind == PlaybackContentKind.VOD) return
        depthJob?.cancel()
        depthJob = scope.launch {
            try {
            val channel = channels.getChannels().firstOrNull { it.id == item.id } ?: return@launch
            val value = archive.effectiveArchiveDepthMs(channel)
            if (current().item != item) return@launch
            depth = value
            tick()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Unknown depth: retain relative seek controls. */ }
        }
    }

    fun tick() {
        val player = current()
        val item = player.item ?: return
        if (item.kind == PlaybackContentKind.VOD) return
        val now = System.currentTimeMillis()
        val at = if (item.kind == PlaybackContentKind.ARCHIVE)
            (item.archiveEpgAnchorMs ?: now) + player.positionMs else now
        state.update { it.copy(oldestMs = depth?.let { d -> (now - d).coerceAtLeast(0) },
            liveMs = now, positionMs = pending ?: at.coerceAtMost(now)) }
    }

    fun seekBy(delta: Long) {
        val player = current()
        val item = player.item ?: return
        if (item.kind == PlaybackContentKind.VOD) return
        val base = pending ?: if (item.kind == PlaybackContentKind.ARCHIVE)
            (item.archiveEpgAnchorMs ?: return) + player.positionMs else System.currentTimeMillis()
        seekTo(base + delta)
    }

    fun ended() {
        val player = current()
        val item = player.item ?: return
        if (item.kind != PlaybackContentKind.ARCHIVE || lastEndItem == item || job?.isActive == true) return
        lastEndItem = item
        val duration = maxOf(player.durationMs, player.positionMs)
        if (duration <= 0) {
            state.update { it.copy(message = "Не удалось определить конец архивного потока. Выберите другое время.") }
            return
        }
        seekTo((item.archiveEpgAnchorMs ?: return) + duration)
    }

    fun seekTo(requested: Long) {
        val expected = current().item ?: return
        if (expected.kind == PlaybackContentKind.VOD) return
        val request = ++serial
        job?.cancel()
        pending = requested
        state.update { it.copy(seeking = true, message = null, positionMs = requested) }
        job = scope.launch {
            try {
                delay(180) // Accumulate held remote keys; send only the final timestamp.
                val channel = channels.getChannels().firstOrNull { it.id == expected.id } ?: return@launch
                val now = System.currentTimeMillis()
                if (requested >= now - 3_000) {
                    if (current().item != expected) return@launch
                    opening = true
                    try { open(PlaybackItem(channel.id, channel.name, channel.streamUrl)) }
                    finally { opening = false }
                    return@launch
                }
                val capability = archive.getCapability(channel)
                if (capability.status == ArchiveStatus.NONE) {
                    state.update { it.copy(message = "Провайдер не предоставляет архив этого канала.") }
                    return@launch
                }
                depth = archive.effectiveArchiveDepthMs(channel)
                // A configured limit applies even when provider depth is unknown.
                val userDays = archive.userArchiveLimitDays()
                if (userDays > 0) depth = minOf(depth ?: Long.MAX_VALUE, userDays * 86_400_000L)
                val target = boundedArchiveTarget(requested, now, depth)
                val player = current()
                if (player.item != expected) return@launch
                val offset = expected.archiveEpgAnchorMs?.let { target - it }
                if (expected.kind == PlaybackContentKind.ARCHIVE && offset != null &&
                    offset >= 0 && offset < player.durationMs - 1_000 && player.state != PlaybackState.ENDED && player.state != PlaybackState.ERROR) {
                    if (localSeek(offset)) return@launch
                }
                // Finite chunks let non-seekable providers reopen at arbitrary times;
                // the next chunk is requested on ENDED, even inside the current live show.
                val end = minOf(now, target + 2 * 3_600_000L)
                val url = archive.buildArchiveUrl(channel, target, end)
                if (url == null || end <= target || !archive.isArchiveUrlPlayable(channel, target, end)) {
                    state.update { it.copy(message = "Архив на выбранное время недоступен у провайдера.") }
                    return@launch
                }
                if (current().item != expected) return@launch
                val paused = current().state == PlaybackState.PAUSED
                opening = true
                try {
                    open(PlaybackItem(channel.id, "${channel.name} • Архив • ${formatDateTime(target)}", url,
                        PlaybackContentKind.ARCHIVE, target))
                    if (paused) pause()
                } finally { opening = false }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(message = "Не удалось открыть архив. Повторите перемотку.") }
            } finally {
                if (request == serial) {
                    pending = null
                    state.update { it.copy(seeking = false) }
                    tick()
                }
            }
        }
    }
}
