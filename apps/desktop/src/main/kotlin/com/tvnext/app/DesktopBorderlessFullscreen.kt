package com.tvnext.app

import com.sun.jna.Native
import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

internal interface BorderlessWindowHost {
    var bounds: Rectangle
    var frameState: Int
    var resizable: Boolean
    var topmost: Boolean
    val monitorBounds: Rectangle
    fun activate()
    fun describe(): String
}

/** Fills the monitor without GraphicsDevice.setFullScreenWindow or peer recreation. */
internal class DesktopBorderlessFullscreen(
    private val host: BorderlessWindowHost,
    private val trace: (String) -> Unit = {},
) : AutoCloseable {
    private data class Saved(val bounds: Rectangle, val state: Int, val resizable: Boolean, val topmost: Boolean)
    private var saved: Saved? = null
    private var floatingBounds = Rectangle(host.bounds)
    private var detach: () -> Unit = {}
    var isFullscreen = false
        private set

    fun enter() {
        if (isFullscreen) return
        trace("borderless.enter.before ${host.describe()}")
        val state = host.frameState
        val restoreBounds = if (state and Frame.MAXIMIZED_BOTH != 0) floatingBounds else host.bounds
        saved = Saved(Rectangle(restoreBounds), state, host.resizable, host.topmost)
        val monitor = Rectangle(host.monitorBounds)
        isFullscreen = true
        host.frameState = Frame.NORMAL
        host.resizable = false
        host.bounds = monitor
        host.topmost = true
        host.activate()
        trace("borderless.enter.after ${host.describe()}")
    }

    fun exit() {
        val restore = saved ?: return
        trace("borderless.exit.before ${host.describe()}")
        isFullscreen = false
        host.frameState = Frame.NORMAL
        host.bounds = Rectangle(restore.bounds)
        host.resizable = restore.resizable
        host.topmost = restore.topmost
        host.frameState = restore.state
        floatingBounds = Rectangle(restore.bounds)
        saved = null
        trace("borderless.exit.after ${host.describe()}")
    }

    fun windowChanged() {
        if (isFullscreen) {
            val monitor = host.monitorBounds
            if (host.bounds != monitor) host.bounds = Rectangle(monitor)
        } else if (host.frameState and (Frame.MAXIMIZED_BOTH or Frame.ICONIFIED) == 0) {
            floatingBounds = Rectangle(host.bounds)
        }
    }

    fun activeChanged(active: Boolean) {
        val restore = saved ?: return
        host.topmost = if (active) true else restore.topmost
    }

    override fun close() {
        detach()
        exit()
    }

    companion object {
        @JvmOverloads
        fun forWindow(window: Frame, trace: (String) -> Unit = {}, onTopmost: (Boolean) -> Unit = {}): DesktopBorderlessFullscreen {
            val host = object : BorderlessWindowHost {
                override var bounds: Rectangle
                    get() = window.bounds
                    set(value) { window.bounds = value }
                override var frameState: Int
                    get() = window.extendedState
                    set(value) { window.extendedState = value }
                override var resizable: Boolean
                    get() = window.isResizable
                    set(value) { window.isResizable = value }
                override var topmost: Boolean
                    get() = window.isAlwaysOnTop
                    set(value) { window.isAlwaysOnTop = value; onTopmost(value) }
                override val monitorBounds: Rectangle get() = Rectangle(window.graphicsConfiguration.bounds)
                override fun activate() { window.toFront(); window.requestFocus() }
                override fun describe(): String {
                    val handle = runCatching { if (window.isDisplayable) Native.getComponentID(window).toString(16) else "none" }.getOrDefault("unavailable")
                    return "hwnd=$handle bounds=$bounds monitor=$monitorBounds state=$frameState top=$topmost resizable=$resizable exclusive=${window.graphicsConfiguration.device.fullScreenWindow === window}"
                }
            }
            val controller = DesktopBorderlessFullscreen(host, trace)
            val changes = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = controller.windowChanged()
                override fun componentResized(e: ComponentEvent) = controller.windowChanged()
            }
            val focus = object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent) = controller.activeChanged(true)
                override fun windowLostFocus(e: WindowEvent) = controller.activeChanged(false)
            }
            window.addComponentListener(changes)
            window.addWindowFocusListener(focus)
            controller.detach = {
                window.removeComponentListener(changes)
                window.removeWindowFocusListener(focus)
            }
            return controller
        }
    }
}
