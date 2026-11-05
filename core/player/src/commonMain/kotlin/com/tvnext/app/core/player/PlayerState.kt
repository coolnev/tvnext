package com.tvnext.app.core.player

enum class PlaybackState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
    ERROR,
}

/**
 * Kind of content being played. The UI distinguishes these (LIVE /
 * ARCHIVE / VOD — e.g. «Россия-1 • Архив • 09.08 20:00») and the desktop
 * backend applies per-kind playback options.
 */
enum class PlaybackContentKind {
    LIVE,
    ARCHIVE,
    VOD,
}

data class PlaybackItem(
    val id: String,
    val title: String,
    val url: String,
    val kind: PlaybackContentKind = PlaybackContentKind.LIVE,
    /**
     * For [PlaybackContentKind.ARCHIVE]: the start time of the archived
     * programme (epoch millis). The channel UI anchors its EPG panel to this
     * instant — "current" becomes the chosen programme and "next"/"upcoming"
     * continue from it — instead of showing the live schedule.
     */
    val archiveEpgAnchorMs: Long? = null,
)

data class PlayerState(
    val state: PlaybackState = PlaybackState.IDLE,
    val item: PlaybackItem? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isMuted: Boolean = false,
    val volume: Float = 1f,
    val error: String? = null,
    /**
     * True when the active backend renders video into a native HWND surface
     * (desktop hardware decode) instead of Compose [androidx.compose.ui.graphics.ImageBitmap] frames.
     * The UI uses this to place overlays around the video area — Compose
     * content cannot be drawn above a DirectX video surface (Windows blending
     * limitation).
     */
    val hardwareDecode: Boolean = false,
    /** Live diagnostics of the ACTUAL stream (decoder, output, resolution, bitrate). */
    val diagnostics: PlayerDiagnostics? = null,
)

/**
 * Facts about the currently playing stream, distinguishing what the user asked
 * for ([requestedMode]) from what is actually happening ([actualMode]).
 */
data class PlayerDiagnostics(
    val requestedMode: String,
    val actualMode: String,
    val fallback: Boolean,
    val decoder: String,
    val videoOutput: String,
    val codec: String?,
    val resolution: String,
    val fps: String?,
    val bitrate: String?,
    /**
     * Human-readable audio description of the stream: channel layout (Стерео,
     * 5.1, …), format and codec where known, e.g. "Стерео · AAC 48 кГц".
     * Null when the backend does not expose audio facts yet.
     */
    val audio: String? = null,
    /**
     * Human-readable transport state of the PLAYBACK PIPELINE, independent of
     * the decode mode: PLAYING / BUFFERING / LOADING / PAUSED / ERROR /
     * DECODER_STALL. Lets a diagnosis distinguish a real network buffering
     * phase from a frozen decoder without guessing from the position.
     */
    val transportState: String = "",
) {
    companion object {
        const val MODE_HARDWARE = "HARDWARE"
        const val MODE_SOFTWARE = "SOFTWARE"
        const val MODE_UNKNOWN = "UNKNOWN"
        const val MODE_ERROR = "ERROR"

        const val STATE_PLAYING = "Играет"
        const val STATE_BUFFERING = "Буферизация"
        const val STATE_BUFFERED = "Буферизация завершена"
        const val STATE_LOADING = "Загрузка"
        const val STATE_PAUSED = "Пауза"
        const val STATE_STALL = "Декодер завис"
        const val STATE_ERROR = "Ошибка"
    }
}

/**
 * Coarse playback states reported by platform backends (ExoPlayer / libVLC)
 * before the "actually playing" flag is applied. Kept as a separate type so the
 * READY → PLAYING/PAUSED transition (driven by the engine's play state) is
 * shared and unit-testable in common code.
 */
enum class PlayerBackendState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

fun PlayerBackendState.toPlaybackState(isPlaying: Boolean): PlaybackState = when (this) {
    PlayerBackendState.IDLE -> PlaybackState.IDLE
    PlayerBackendState.BUFFERING -> PlaybackState.BUFFERING
    PlayerBackendState.READY -> if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
    PlayerBackendState.ENDED -> PlaybackState.ENDED
}
