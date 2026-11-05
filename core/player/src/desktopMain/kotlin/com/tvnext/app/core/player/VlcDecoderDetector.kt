package com.tvnext.app.core.player

/**
 * Detects which decoder libVLC actually uses by inspecting the native log.
 *
 * libVLC/FFmpeg announce the real decode path through log messages:
 * - `avcodec decoder: Using D3D11VA` / `avcodec decoder: Using DXVA2` → the
 *   GPU decoder is active;
 * - `... pix_fmt: d3d11va_vld` → FFmpeg runs the D3D11VA decoder (VLD);
 * - hardware init failures (`failed to init`, `no supported combination`,
 *   `failed to open ... d3d ...`) → the GPU path could not be established;
 * - software fallback markers (`Using software`, `avcodec` with a yuv420p
 *   pix_fmt) → CPU decode.
 *
 * The detector is fed from the [uk.co.caprica.vlcj.log.NativeLog] listener and
 * drives:
 * - [isHardwareConfirmed] — for AUTO: hardware is active, nothing to do;
 * - [isHardwareFailed] — for HARDWARE: report a strict error, never fall back;
 * - [decoderName] — diagnostics (`Decoder: D3D11VA` / `avcodec`).
 */
internal class VlcDecoderDetector {

    // Written from the libVLC log thread, read from the watchdog/UI threads —
    // volatile visibility is mandatory, otherwise the AUTO fallback would fire
    // even though the GPU decoder is active.
    @Volatile
    private var hardware: HardwareDecoder? = null

    @Volatile
    private var initFailure: String? = null

    @Volatile
    private var softwareSeen = false

    /**
     * Set by the AUTO fallback path: the backend was deliberately recreated as
     * SOFTWARE, so late log messages from the released hardware backend (e.g.
     * a queued "Using D3D11VA") must not flip the detector back to hardware.
     */
    @Volatile
    private var forcedSoftware = false

    fun reset() {
        hardware = null
        initFailure = null
        softwareSeen = false
        forcedSoftware = false
    }

    /**
     * Pins the detector to software. Called after an AUTO→SOFTWARE fallback so
     * the diagnostics always report the real (recreated) pipeline even if the
     * released hardware backend still delivers buffered log messages.
     */
    fun forceSoftware() {
        forcedSoftware = true
        softwareSeen = true
        hardware = null
        initFailure = null
    }

    fun onMessage(message: String) {
        if (hardware == null && !forcedSoftware) {
            when {
                message.contains("Using D3D11VA", ignoreCase = true) ->
                    hardware = HardwareDecoder.D3D11VA

                message.contains("Using DXVA2", ignoreCase = true) ->
                    hardware = HardwareDecoder.DXVA2

                message.contains("using hw decoder module \"d3d11va\"", ignoreCase = true) ||
                    message.contains("pix_fmt: d3d11va", ignoreCase = true)
                -> hardware = HardwareDecoder.D3D11VA

                message.contains("using hw decoder module \"dxva2\"", ignoreCase = true) ||
                    message.contains("pix_fmt: dxva2", ignoreCase = true)
                -> hardware = HardwareDecoder.DXVA2
            }
        }
        when {
            !forcedSoftware && message.contains("no supported combination", ignoreCase = true) ||
                (!forcedSoftware &&
                    message.contains("failed to init", ignoreCase = true) &&
                    (message.contains("d3d", ignoreCase = true) ||
                        message.contains("gl", ignoreCase = true) ||
                        message.contains("wgl", ignoreCase = true)))
            -> {
                if (initFailure == null) {
                    initFailure = message.trim().take(160)
                }
            }

            message.contains("Using software", ignoreCase = true) ||
                message.contains("software decoder", ignoreCase = true)
            -> softwareSeen = true

            else -> Unit
        }
    }

    /** True when libVLC announced an active GPU decoder for the current stream. */
    fun isHardwareConfirmed(): Boolean = hardware != null

    /**
     * True when the GPU path was requested but could not be established.
     *
     * `softwareSeen` alone is deliberately NOT a failure: libVLC's log markers
     * are not ordered, and "Using software decoder" frequently arrives BEFORE
     * "Using D3D11VA" on perfectly healthy streams (FFmpeg probes codecs
     * during open). Treating it as an instant failure produced spurious
     * "Аппаратное декодирование недоступно" errors that disappeared on retry.
     * A real "never came up" case is covered by the watchdog's
     * confirmation-timeout branch; [initFailure] still fails fast when libVLC
     * explicitly reported a hardware init error. An init failure that was
     * followed by a SUCCESSFUL GPU decoder (e.g. dxva2 after a failed d3d11
     * attempt) is not a failure. After an AUTO→SOFTWARE fallback the detector
     * is pinned ([forceSoftware]) and reports a failure by definition.
     */
    fun isHardwareFailed(): Boolean {
        if (hardware != null) return false
        return forcedSoftware || initFailure != null
    }

    /** Human-readable decoder name for diagnostics. */
    fun decoderName(): String {
        val hw = hardware
        return when {
            hw != null -> hw.name
            softwareSeen -> SOFTWARE_LABEL
            initFailure != null -> "unavailable (${initFailure})"
            else -> UNKNOWN_LABEL
        }
    }

    /** Failure detail for the HARDWARE-mode user-facing error message. */
    fun failureDetail(): String = initFailure ?: when {
        softwareSeen -> "libVLC переключился на программный декодер"
        else -> "аппаратный декодер не подтвердился"
    }

    private enum class HardwareDecoder {
        D3D11VA,
        DXVA2,
    }

    companion object {
        /** Decoder label shown by the diagnostics panel for the software path. */
        const val SOFTWARE_LABEL = "avcodec (software)"

        /** Decoder label shown when no decoder marker has arrived yet. */
        const val UNKNOWN_LABEL = "unknown"
    }
}
