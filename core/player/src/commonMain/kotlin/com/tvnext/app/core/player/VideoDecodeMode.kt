package com.tvnext.app.core.player

/**
 * Windows video decode mode (Sprint 5).
 *
 * The setting lives in the shared player domain so both the common settings
 * model ([AppSettings] in `features:settings`) and the platform playback
 * backends can reference it without a feature→core circular dependency.
 *
 * Semantics (enforced by [DesktopPlaybackController]):
 * - [AUTO]: prefer the hardware decoder path; transparently fall back to the
 *   software path when hardware decoding cannot be established.
 * - [HARDWARE]: require the hardware decoder path; never silently fall back to
 *   software — a user-visible error is reported instead.
 * - [SOFTWARE]: always use the software decoder (libVLC callback pipeline),
 *   the proven Sprint 4 path.
 */
enum class VideoDecodeMode {
    AUTO,
    HARDWARE,
    SOFTWARE,
}

val VideoDecodeMode.displayName: String
    get() = when (this) {
        VideoDecodeMode.AUTO -> "Автоматически"
        VideoDecodeMode.HARDWARE -> "Аппаратное"
        VideoDecodeMode.SOFTWARE -> "Программное"
    }

/**
 * libVLC `--avcodec-hw` value for this mode.
 *
 * Hardware modes use **DXVA2** paired with the Direct3D9 vout. A `direct3d11`
 * vout REQUIRES the d3d11va decoder (d3d11 vout consumes frames only from
 * d3d11va, per VideoLAN docs), and that d3d11 vout+d3d11va combination froze
 * on adaptive HLS (quality switches → "Reinit context" → white screen /
 * stalled decoder / broken seek). DXVA2 + Direct3D9 is the stable hardware
 * pairing on Windows: both re-create their context cleanly across HLS seeks
 * and quality switches. If DXVA2 is unavailable for a codec, libVLC itself
 * falls back to its software decoder — the app never forces a decode-mode
 * change.
 */
internal val VideoDecodeMode.avcodecHw: String
    get() = when (this) {
        VideoDecodeMode.AUTO -> "dxva2"
        VideoDecodeMode.HARDWARE -> "dxva2"
        VideoDecodeMode.SOFTWARE -> "none"
    }

/** Whether this mode uses the HWND/D3D11 video output instead of the callback surface. */
internal val VideoDecodeMode.usesHwndOutput: Boolean
    get() = this != VideoDecodeMode.SOFTWARE
