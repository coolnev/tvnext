package com.tvnext.app

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows-only helper that keeps the system awake while TVNext runs:
 * it prevents the screensaver from starting and the system from
 * transitioning to sleep / turning the display off.
 *
 * - [start] enables `ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED` via
 *   `SetThreadExecutionState` and temporarily disables the screensaver via
 *   `SystemParametersInfo(SPI_SETSCREENSAVEACTIVE)`.
 * - [stop] restores the previous screensaver state and clears the
 *   keep-awake request, so the user's OS settings are untouched after exit.
 *
 * On non-Windows platforms every call is a no-op. All failures are swallowed:
 * a per-user policy denying the change must never break the app.
 */
internal object KeepAwake {

    // Execution states (kernel32 SetThreadExecutionState).
    private const val ES_CONTINUOUS = 0x8000_0000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x0000_0001
    private const val ES_DISPLAY_REQUIRED = 0x0000_0002

    // SystemParametersInfo actions (user32).
    private const val SPI_GETSCREENSAVEACTIVE = 0x0010
    private const val SPI_SETSCREENSAVEACTIVE = 0x0011
    private const val SPIF_SENDCHANGE = 0x0002

    private interface User32Ex : StdCallLibrary {
        fun SystemParametersInfo(uiAction: Int, uiParam: Int, pvParam: Any?, fWinIni: Int): Boolean

        companion object {
            val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java)
        }
    }

    private var screensaverWasActive = true
    private var started = false

    /** Prevents sleep/display-off and disables the screensaver while the app runs. */
    fun start() {
        if (!isWindows() || started) return
        started = true
        try {
            Kernel32.INSTANCE.SetThreadExecutionState(
                ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED,
            )
        } catch (_: Throwable) {
            // Best effort: keep-awake is a convenience, not a hard requirement.
        }
        try {
            val previous = IntByReference()
            if (User32Ex.INSTANCE.SystemParametersInfo(SPI_GETSCREENSAVEACTIVE, 0, previous, 0)) {
                screensaverWasActive = previous.value != 0
            }
            User32Ex.INSTANCE.SystemParametersInfo(
                SPI_SETSCREENSAVEACTIVE,
                0,
                IntByReference(0),
                SPIF_SENDCHANGE,
            )
        } catch (_: Throwable) {
        }
    }

    /** Restores the screensaver setting and drops the keep-awake request. */
    fun stop() {
        if (!isWindows() || !started) return
        started = false
        try {
            User32Ex.INSTANCE.SystemParametersInfo(
                SPI_SETSCREENSAVEACTIVE,
                0,
                IntByReference(if (screensaverWasActive) 1 else 0),
                SPIF_SENDCHANGE,
            )
        } catch (_: Throwable) {
        }
        try {
            // Clear the continuous keep-awake request (other flags are not
            // explicitly re-requested, so the system returns to its own policy).
            Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS)
        } catch (_: Throwable) {
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
