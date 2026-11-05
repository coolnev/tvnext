package com.tvnext.app.core.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Rectangle

/**
 * Double-click detection over the native video surface (Sprint 5 work-on-errors).
 *
 * Experimentally established (VlcMouseProbe):
 * - mouse events over the video area are serviced by libVLC's OWN thread
 *   (the owner of the "VLC video main" child window that libVLC creates inside
 *   the canvas HWND), NOT by the AWT EDT and NOT by the window-owner thread;
 * - Java MouseListener / AWTEventListener / WindowProc chaining never see them;
 * - a WH_MOUSE hook installed on THAT thread observes the clicks before they
 *   reach libVLC.
 *
 * This installs a WH_MOUSE hook scoped to exactly that thread (via
 * GetWindowThreadProcessId of the canvas child window). It is process-local and
 * only fires for our windows, so — unlike a global WH_MOUSE_LL hook — it adds no
 * system-wide input overhead.
 *
 * NOTE: coordinates are read through a raw [Memory] buffer, not a JNA
 * Structure: a private Structure's fields cannot be reflected in the hook
 * callback context (Java 21 modules → "Exception reading field").
 */
internal class VideoDoubleClickDetector(
    private val boundsProvider: () -> Rectangle?,
    private val onDoubleClick: () -> Unit,
) {
    private var hook: Pointer? = null
    private var hookedChildHwnd: Long = 0L
    private var lastDownTime = 0L
    private var lastDownX = -1
    private var lastDownY = -1

    private val hookProc = object : MouseHookProc {
        override fun callback(nCode: Int, wParam: Long, lParam: Long): Long {
            try {
                if (nCode >= 0) {
                    val debug = System.getProperty("tvnext.player.debug") == "true"
                    when (wParam) {
                        WM_LBUTTONDOWN.toLong() -> {
                            if (debug) println("[TVNext] hook WM_LBUTTONDOWN")
                            onLeftDown()
                        }
                        WM_LBUTTONDBLCLK.toLong() -> {
                            if (debug) println("[TVNext] hook WM_LBUTTONDBLCLK")
                            if (cursorInBounds()) {
                                if (debug) println("[TVNext] double click in bounds -> toggle")
                                onDoubleClick()
                            } else {
                                if (debug) println("[TVNext] DBLCLK out of bounds")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (System.getProperty("tvnext.player.debug") == "true") {
                    println("[TVNext] hook exception: $e")
                }
            }
            return User32X.INSTANCE.CallNextHookEx(null, nCode, wParam, lParam)
        }
    }

    private fun cursorPos(): Pair<Int, Int>? {
        val mem = Memory(POINT_SIZE)
        if (!User32X.INSTANCE.GetCursorPos(mem)) return null
        return mem.getInt(0) to mem.getInt(4)
    }

    private fun cursorInBounds(): Boolean {
        val debug = System.getProperty("tvnext.player.debug") == "true"
        val pt = cursorPos()
        val bounds = boundsProvider()
        if (debug) {
            println("[TVNext] cursorInBounds pt=${pt ?: "null"} bounds=${bounds ?: "null"}")
        }
        return pt != null && bounds?.contains(pt.first, pt.second) == true
    }

    private fun onLeftDown() {
        val debug = System.getProperty("tvnext.player.debug") == "true"
        val pt = cursorPos()
        if (debug) println("[TVNext] onLeftDown pt=${pt ?: "null"}")
        if (pt == null) return
        val bounds = boundsProvider()
        if (debug) println("[TVNext] onLeftDown bounds=${bounds ?: "null"}")
        if (bounds == null || !bounds.contains(pt.first, pt.second)) {
            lastDownTime = 0L
            return
        }
        val now = System.currentTimeMillis()
        val withinTime = now - lastDownTime < DOUBLE_CLICK_TIME_MS
        val withinDistance =
            kotlin.math.abs(pt.first - lastDownX) <= 4 &&
                kotlin.math.abs(pt.second - lastDownY) <= 4
        if (withinTime && withinDistance) {
            lastDownTime = 0L
            if (debug) println("[TVNext] double click detected (two downs)")
            onDoubleClick()
        } else {
            lastDownTime = now
            lastDownX = pt.first
            lastDownY = pt.second
        }
    }

    /**
     * Installs the hook on the thread that owns the canvas child window
     * (libVLC's "VLC video main"). Returns true once installed. Call after the
     * video output created its child window (i.e. after playback started);
     * safe to retry until it succeeds.
     *
     * If the child window changed (player rebuild / fullscreen round-trip), the
     * old hook — bound to the previous (now dead) vout thread — is removed and
     * reinstalled on the new thread.
     */
    fun installIfNeeded(canvasHwnd: Long): Boolean {
        if (canvasHwnd == 0L) return false
        val child = User32X.INSTANCE.GetWindow(Pointer(canvasHwnd), GW_CHILD)
        if (child == Pointer(0L)) return false
        val childHwnd = Pointer.nativeValue(child)
        if (hook != null && hookedChildHwnd == childHwnd) return true
        // Stale hook on a previous vout thread — replace it.
        uninstall()
        val pid = IntArray(1)
        val tid = User32X.INSTANCE.GetWindowThreadProcessId(child, pid)
        if (tid == 0) return false
        val hMod = Kernel32X.INSTANCE.GetModuleHandleW(null)
        val h = User32X.INSTANCE.SetWindowsHookExW(WH_MOUSE, hookProc, hMod, tid)
        if (h != null && Pointer.nativeValue(h) != 0L) {
            hook = h
            hookedChildHwnd = childHwnd
            if (System.getProperty("tvnext.player.debug") == "true") {
                println("[TVNext] double-click hook installed child=$child tid=$tid handle=$h")
            }
            return true
        }
        return false
    }

    fun uninstall() {
        hook?.let { h -> runCatching { User32X.INSTANCE.UnhookWindowsHookEx(h) } }
        hook = null
        hookedChildHwnd = 0L
    }

    private companion object {
        const val POINT_SIZE = 8L
        const val WH_MOUSE = 7
        const val GW_CHILD = 5
        const val WM_LBUTTONDOWN = 0x0201
        const val WM_LBUTTONDBLCLK = 0x0203
        const val DOUBLE_CLICK_TIME_MS = 500L
    }
}

private interface MouseHookProc : Callback {
    fun callback(nCode: Int, wParam: Long, lParam: Long): Long
}

private interface User32X : Library {
    fun SetWindowsHookExW(idHook: Int, lpfn: MouseHookProc, hMod: Pointer?, dwThreadId: Int): Pointer
    fun CallNextHookEx(hhk: Pointer?, nCode: Int, wParam: Long, lParam: Long): Long
    fun UnhookWindowsHookEx(hhk: Pointer): Boolean
    fun GetCursorPos(lpPoint: Pointer): Boolean
    fun GetWindow(hWnd: Pointer, uCmd: Int): Pointer
    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntArray?): Int

    companion object {
        val INSTANCE: User32X = Native.load("user32", User32X::class.java)
    }
}

private interface Kernel32X : Library {
    fun GetModuleHandleW(lpModuleName: Pointer?): Pointer

    companion object {
        val INSTANCE: Kernel32X = Native.load("kernel32", Kernel32X::class.java)
    }
}
