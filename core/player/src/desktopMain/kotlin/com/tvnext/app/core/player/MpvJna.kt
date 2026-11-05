package com.tvnext.app.core.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/**
 * Minimal JNA binding to the libmpv client API (libmpv-2.dll).
 *
 * libmpv is licensed LGPLv2+ (when built with -Dgpl=false, which the shared
 * builds ship as) — unlike vlcj (GPLv3), it is usable in a closed commercial
 * product. The client API header itself is ISC.
 *
 * Only the functions actually needed by [MpvPlaybackController] are mapped.
 * Values of observed properties are NOT parsed from events: the controller
 * polls properties in its watchdog instead, which avoids decoding the
 * mpv_node union in JNA entirely.
 */
internal interface Mpv : Library {

    fun mpv_client_api_version(): Long
    fun mpv_error_string(error: Int): Pointer
    fun mpv_free(data: Pointer)

    fun mpv_create(): Pointer
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_flag(ctx: Pointer, name: String, flag: Boolean): Int

    /** const char** terminated by NULL — JNA marshals [args] with a NULL tail. */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int

    /** Reads a property as a string; the result must be released via [mpv_free]. */
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer

    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)

    // ---- render API (render.h) ------------------------------------------------
    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer?): Int
    fun mpv_render_context_set_update_callback(rctx: Pointer, callback: MpvRenderUpdateCallback?, cbCtx: Pointer?)
    fun mpv_render_context_update(rctx: Pointer): Long
    fun mpv_render_context_render(rctx: Pointer, params: Pointer?): Int
    fun mpv_render_context_free(rctx: Pointer)

    /** mpv_render_param: { mpv_render_param_type type; void *data; } */
    class MpvRenderParam : Structure() {
        @JvmField var type: Int = 0
        @JvmField var data: Pointer? = null

        override fun getFieldOrder(): List<String> = listOf("type", "data")
    }

    /** mpv_render_update_fn — invoked when a new frame is available. */
    interface MpvRenderUpdateCallback : Callback {
        fun invoke(cbCtx: Pointer?)
    }

    class MpvEvent : Structure() {
        @JvmField var event_id: Int = 0
        @JvmField var error: Int = 0
        @JvmField var reply_userdata: Long = 0
        @JvmField var name: Pointer? = null
        @JvmField var data: Pointer? = null

        /** Binds this structure to [pointer] and reads the event fields. */
        fun bind(pointer: Pointer): MpvEvent {
            useMemory(pointer)
            read()
            return this
        }

        override fun getFieldOrder(): List<String> =
            listOf("event_id", "error", "reply_userdata", "name", "data")
    }

    companion object {
        // mpv_event_id (stable ABI values, see client.h)
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22

        // mpv_end_file_reason (mpv_event_end_file.reason)
        const val MPV_END_FILE_REASON_EOF = 0
        const val MPV_END_FILE_REASON_STOP = 1
        const val MPV_END_FILE_REASON_QUIT = 2
        const val MPV_END_FILE_REASON_ERROR = 3

        // mpv_render_param_type (render.h)
        const val MPV_RENDER_PARAM_INVALID = 0
        const val MPV_RENDER_PARAM_API_TYPE = 1
        const val MPV_RENDER_PARAM_SW_SIZE = 17
        const val MPV_RENDER_PARAM_SW_FORMAT = 18
        const val MPV_RENDER_PARAM_SW_STRIDE = 19
        const val MPV_RENDER_PARAM_SW_POINTER = 20

        const val MPV_RENDER_API_TYPE_SW = "sw"

        // mpv_render_update_flag (render.h)
        const val MPV_RENDER_UPDATE_FRAME = 1L

        private var instance: Mpv? = null

        fun load(): Mpv {
            instance?.let { return it }
            MpvRuntimeLocator.prepare()
            // JNA maps the bare name to <name>.dll on Windows, so the shared
            // build's actual file name MUST be used ("libmpv-2" → libmpv-2.dll).
            // "mpv" would look for mpv.dll and fail even with the DLL present.
            val lib = Native.load("libmpv-2", Mpv::class.java)
            if (lib.mpv_client_api_version() == 0L) {
                throw IllegalStateException("libmpv client API did not initialize")
            }
            instance = lib
            return lib
        }
    }
}

/** Maps a libmpv error code to a readable message (for diagnostics/logs). */
internal fun mpvErrorMessage(mpv: Mpv, error: Int): String =
    mpv.mpv_error_string(error).takeIf { it != Pointer.NULL }?.getString(0)
        ?: "mpv error $error"
