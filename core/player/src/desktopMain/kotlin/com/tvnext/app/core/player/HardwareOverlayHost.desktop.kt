package com.tvnext.app.core.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Desktop actual: shows the player overlays in separate opaque top-level windows
 * positioned over the hardware video canvas.
 *
 * Window lifecycle (Sprint 8, fixes for "overlay floats above other apps" and
 * "overlay left in (0,0) after F11"):
 * - Each plaque is an OWNED window: `JWindow(owner = the TVNext main window)`.
 *   An owned window stays above its owner (so it covers the D3D11 video inside
 *   the owner) but NEVER floats above other applications — when the user Alt+Tabs
 *   to Chrome/Explorer those windows come on top of every TVNext window,
 *   including the owned overlays. Owned windows are also minimized/restored
 *   together with the owner. `isAlwaysOnTop` is NOT used (verified in
 *   ComposeHwSurfaceProbe: owned JWindow without alwaysOnTop renders above the
 *   video).
 * - The window is only shown once it has BOTH a measured content size and a
 *   valid canvas position; it is always `setLocation` before `setVisible`, so
 *   it can never flash in the default (0,0) position while a fullscreen
 *   round-trip recreates the UI.
 * - When the main window loses focus or is minimized the overlays are hidden
 *   immediately; on focus/restore they reappear and reposition from the
 *   current canvas geometry.
 * - Overlays are created in `remember(desktop, spacing, owner)` and destroyed
 *   in `DisposableEffect`'s onDispose — on every fullscreen round-trip the old
 *   windows are disposed and exactly three new ones are created (visible in the
 *   debug log via a live counter).
 *
 * Size handling: the ComposePanel content is wrap-content, so its real size is
 * only known after the first composition. The window is sized by the measured
 * content (onSizeChanged) and repositioned from the canvas anchor.
 */
@Composable
actual fun HardwareOverlayHost(
    controller: PlaybackController,
    title: @Composable () -> Unit,
    status: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit,
    showTitle: Boolean,
    clock: (@Composable () -> Unit)?,
    clockPosition: ClockPosition,
    showDiagnostics: Boolean,
) {
    val desktop = controller as? DesktopVideoSurface
    if (desktop == null) return
    // External overlay windows are ONLY supported by the libVLC backend. The
    // libmpv backend renders video into the same canvas but the overlay windows
    // transparently swallow mouse input after layout changes (Media Library)
    // — freezing the UI while every Java thread is healthy (measured with a
    // thread dump). For mpv, UI chrome is drawn by Compose instead, so NO
    // JWindow overlays are created here.
    if (controller !is DesktopPlaybackController) return
    val hardwareActive by desktop.hardwareSurfaceActive.collectAsState()
    if (!hardwareActive) {
        return
    }
    val playerState by controller.state.collectAsState()
    val density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }.toInt()
    val canvas = desktop.videoCanvas
    var layoutTick by remember { mutableStateOf(0) }

    // The canvas may not be attached to a displayable window yet right after a
    // fullscreen round-trip; only build the overlay windows once it is (the
    // owner is resolved from the canvas's top-level window at that point).
    var canvasReady by remember { mutableStateOf(canvas.isDisplayable) }
    DisposableEffect(canvas) {
        // Refresh immediately: when the canvas is swapped to a fresh one that is
        // ALREADY displayable, no hierarchy event fires, so a remembered false
        // would stick forever (overlays never created).
        canvasReady = canvas.isDisplayable
        val listener = HierarchyListener { canvasReady = canvas.isDisplayable }
        canvas.addHierarchyListener(listener)
        onDispose { canvas.removeHierarchyListener(listener) }
    }
    if (!canvasReady) {
        // Diagnostic (dev): why no overlays.
        val lastReported = remember { mutableStateOf(-1L) }
        val now = System.currentTimeMillis()
        if (now - lastReported.value > 2000L) {
            lastReported.value = now
            debug(
                "overlays SKIPPED hardwareActive=$hardwareActive " +
                    "canvas=${System.identityHashCode(canvas)} disp=${canvas.isDisplayable} " +
                    "size=${canvas.width}x${canvas.height}",
            )
        }
        return
    }
    val owner = SwingUtilities.getWindowAncestor(canvas)
    if (owner == null) {
        debug("overlays SKIPPED owner==null canvasDisp=${canvas.isDisplayable}")
        return
    }

    val overlays = remember(desktop, spacingPx, owner) {
        debug("overlays CREATED owner=${owner.javaClass.simpleName} canvas=${System.identityHashCode(canvas)}")
        OverlayWindowSet(canvas, owner, spacingPx, onLayoutChange = { layoutTick++ })
    }

    DisposableEffect(overlays, canvas) {
        overlays.setCanvas(canvas)
        overlays.setContent(title, status, diagnostics, clock)
        canvas.addComponentListener(overlays.resizeListener)
        canvas.addHierarchyBoundsListener(overlays.boundsListener)
        onDispose {
            canvas.removeComponentListener(overlays.resizeListener)
            canvas.removeHierarchyBoundsListener(overlays.boundsListener)
            overlays.dispose()
        }
    }

    LaunchedEffect(playerState.state, playerState.diagnostics, layoutTick, showTitle, clockPosition, showDiagnostics) {
        overlays.update(playerState, showTitle, clockPosition, showDiagnostics)
    }
}

/**
 * Manages the four overlay windows (title, status, diagnostics, clock) and
 * keeps them positioned over the hardware canvas.
 */
private class OverlayWindowSet(
    private var canvas: Component,
    private val owner: Window,
    private val spacingPx: Int,
    private val onLayoutChange: () -> Unit,
) {
    private val titleWindow = PlaqueWindow("title", owner, onWindowDeactivated = { setAppActive(false) })
    private val statusWindow = PlaqueWindow("status", owner, onWindowDeactivated = { setAppActive(false) })
    private val diagWindow = PlaqueWindow("diagnostics", owner, onWindowDeactivated = { setAppActive(false) })
    private val clockWindow = PlaqueWindow("clock", owner, onWindowDeactivated = { setAppActive(false) })

    /** Whether the main app window is focused and not minimized. */
    private var appActive = true

    /** Last clock placement so repositions (resize/move) keep the corner. */
    private var lastClockPosition: ClockPosition = ClockPosition.OFF

    // Desired visibility from the player state (before the app-active gate).
    private var titleWanted = false
    private var statusWanted = false
    private var diagWanted = false
    private var clockWanted = false

    /**
     * A fresh canvas arrives on window round-trips; the overlay windows are NOT
     * recreated (they would flicker) — only the geometry anchor is updated.
     */
    fun setCanvas(newCanvas: Component) {
        if (canvas === newCanvas) return
        canvas = newCanvas
        reposition()
    }

    val resizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) = reposition()
        override fun componentMoved(e: ComponentEvent?) = reposition()
        override fun componentShown(e: ComponentEvent?) = reposition()
        override fun componentHidden(e: ComponentEvent?) = reposition()
    }

    val boundsListener = object : HierarchyBoundsAdapter() {
        override fun ancestorMoved(e: HierarchyEvent?) = reposition()
        override fun ancestorResized(e: HierarchyEvent?) = reposition()
    }

    private val appWindowListener = object : java.awt.event.WindowAdapter() {
        override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = setAppActive(true)
        override fun windowLostFocus(e: java.awt.event.WindowEvent?) = setAppActive(false)
        override fun windowIconified(e: java.awt.event.WindowEvent?) = setAppActive(false)
        override fun windowDeiconified(e: java.awt.event.WindowEvent?) = setAppActive(true)
    }

    init {
        owner.addWindowFocusListener(appWindowListener)
        owner.addWindowStateListener(appWindowListener)
    }

    private fun setAppActive(active: Boolean) {
        if (appActive == active) return
        appActive = active
        debug("app active=$active")
        applyVisibility()
    }

    private fun applyVisibility() {
        // Owned JWindows never float above other applications, so the focus
        // gate is unnecessary and only made the overlays "live their own life"
        // (vanishing/reappearing with focus). Show whenever wanted.
        titleWindow.showIf(titleWanted)
        statusWindow.showIf(statusWanted)
        diagWindow.showIf(diagWanted)
        clockWindow.showIf(clockWanted)
    }

    fun setContent(
        title: @Composable () -> Unit,
        status: @Composable () -> Unit,
        diagnostics: @Composable () -> Unit,
        clock: (@Composable () -> Unit)?,
    ) {
        titleWindow.setContent(title)
        statusWindow.setContent(status)
        diagWindow.setContent(diagnostics)
        if (clock != null) clockWindow.setContent(clock) else clockWindow.setContent { }
    }

    fun update(playerState: PlayerState, showTitle: Boolean, clockPosition: ClockPosition, showDiagnostics: Boolean) {
        lastClockPosition = clockPosition
        titleWanted = playerState.state != PlaybackState.IDLE && showTitle
        statusWanted = playerState.state == PlaybackState.LOADING ||
            playerState.state == PlaybackState.BUFFERING ||
            playerState.state == PlaybackState.ERROR ||
            playerState.state == PlaybackState.ENDED
        diagWanted = showDiagnostics && playerState.diagnostics != null
        clockWanted = playerState.state != PlaybackState.IDLE && clockPosition != ClockPosition.OFF
        applyVisibility()
        reposition(clockPosition)
    }

    private fun reposition(clockPosition: ClockPosition = lastClockPosition) {
        if (!canvas.isDisplayable || !canvas.isShowing) {
            // Canvas not attached to a visible window yet (e.g. mid fullscreen
            // round-trip). Keep the overlays at their last valid position and
            // DO NOT flip them to hidden — the next reposition (componentShown/
            // resized/ancestor) will refresh the anchor. position() sets
            // `positioned = true` once and never clears it, so an already
            // shown overlay never disappears because of a transient state.
            return
        }
        val loc = runCatching { canvas.locationOnScreen }.getOrNull() ?: return
        val w = canvas.width
        val h = canvas.height
        if (w <= 0 || h <= 0) return
        titleWindow.position(Point(loc.x + spacingPx, loc.y + spacingPx), anchorX = 0.0, anchorY = 0.0)
        statusWindow.position(
            Point(loc.x + w / 2, loc.y + h / 2),
            anchorX = 0.5,
            anchorY = 0.5,
        )
        diagWindow.position(
            Point(loc.x + spacingPx, loc.y + h - spacingPx),
            anchorX = 0.0,
            anchorY = 1.0,
        )
        when (clockPosition) {
            ClockPosition.TOP_LEFT -> clockWindow.position(
                Point(loc.x + spacingPx, loc.y + spacingPx),
                anchorX = 0.0,
                anchorY = 0.0,
            )
            ClockPosition.TOP_RIGHT -> clockWindow.position(
                Point(loc.x + w - spacingPx, loc.y + spacingPx),
                anchorX = 1.0,
                anchorY = 0.0,
            )
            ClockPosition.OFF -> clockWindow.position(
                Point(loc.x + spacingPx, loc.y + spacingPx),
                anchorX = 0.0,
                anchorY = 0.0,
            )
        }
    }

    fun dispose() {
        owner.removeWindowFocusListener(appWindowListener)
        owner.removeWindowStateListener(appWindowListener)
        titleWindow.dispose()
        statusWindow.dispose()
        diagWindow.dispose()
        clockWindow.dispose()
    }
}

/**
 * An opaque black plaque window rendering a single Compose overlay.
 *
 * Lifecycle rules:
 * - owned by the main window (no alwaysOnTop);
 * - shown ONLY when it has a measured content size AND a valid position;
 * - always positioned before shown (never flashes at (0,0));
 * - created/disposed strictly paired (debug log keeps a live counter).
 */
private class PlaqueWindow(
    private val name: String,
    owner: Window,
    private val onWindowDeactivated: () -> Unit = {},
    /**
     * When set, the window always keeps this exact size (no dependency on the
     * wrap-content measurement, which could clip the text to "Р..." and freeze
     * that tiny size). Used for the title and the clock plaques.
     */
    private val fixedSize: Dimension? = null,
) : JWindow(owner) {
    private val panel = ComposePanel()
    private var wantedVisible = false
    private var contentSize = Dimension(0, 0)
    private var sizeKnown = false
    private var anchor = Point(0, 0)
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var positioned = false

    init {
        // Owned window, NOT always-on-top (Sprint 8). It stays above the
        // owner's D3D11 video but never above other applications.
        setFocusableWindowState(false)
        // 50% whole-window transparency; verified above D3D11 video.
        setOpacity(0.5f)
        addWindowFocusListener(object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) = onWindowDeactivated()
        })
        contentPane.layout = BorderLayout()
        contentPane.add(panel, BorderLayout.CENTER)
        panel.background = java.awt.Color.BLACK
        liveCount++
        debug("overlay created name=$name live=$liveCount")
    }

    fun setContent(content: @Composable () -> Unit) {
        panel.setContent {
            Box(
                modifier = Modifier
                    .background(Color.Black)
                    // Measure the content with UNBOUNDED constraints: the text is
                    // then never clipped by the current (possibly small) window
                    // size during measurement, so onSizeChanged always reports the
                    // FULL content size. This makes the plaque wrap correctly when
                    // a short name ("НТВ") is followed by a long one ("Первый
                    // канал") — without it the small constraint clips the text
                    // ("П...") and the size freezes.
                    .wrapContentSize(unbounded = true)
                    .onSizeChanged { size ->
                        contentSize = Dimension(size.width, size.height)
                        sizeKnown = true
                        applyShowState()
                    },
            ) {
                content()
            }
        }
    }

    fun showIf(visible: Boolean) {
        if (wantedVisible == visible) return
        wantedVisible = visible
        applyShowState()
    }

    fun position(point: Point, anchorX: Double, anchorY: Double) {
        this.anchor = point
        this.anchorX = anchorX
        this.anchorY = anchorY
        // Set once: a shown overlay must never flip back to hidden because of a
        // transient canvas state; a fresh overlay (fullscreen round-trip) stays
        // hidden until its first valid position arrives (never shows at (0,0)).
        this.positioned = true
        applyShowState()
    }

    private fun applyShowState() {
        val canShow = wantedVisible && positioned
        if (canShow) {
            if (fixedSize != null) {
                // Fixed-size plaque (title/clock): never clipped, no dependence
                // on the wrap-content measurement.
                if (width != fixedSize.width || height != fixedSize.height) {
                    setSize(fixedSize)
                }
                setLocation(
                    anchor.x - (fixedSize.width * anchorX).toInt(),
                    anchor.y - (fixedSize.height * anchorY).toInt(),
                )
            } else if (sizeKnown && (contentSize.width <= 0 || contentSize.height <= 0)) {
                // The content is empty (e.g. channel name hidden in immersive
                // mode): never show an empty plaque.
                if (isVisible) {
                    setVisible(false)
                    debug("overlay hidden (empty content) name=$name")
                }
                return
            } else if (sizeKnown) {
                val x = anchor.x - (contentSize.width * anchorX).toInt()
                val y = anchor.y - (contentSize.height * anchorY).toInt()
                if (width != contentSize.width || height != contentSize.height) {
                    setSize(contentSize)
                }
                setLocation(x, y)
            } else {
                // Content size is unknown until the first composition. The
                // content is measured UNBOUNDED (wrapContentSize above), so the
                // first onSizeChanged reports the FULL size and resizes the
                // window to it — a 1x1 start keeps the flash invisible.
                setSize(1, 1)
                setLocation(anchor.x, anchor.y)
            }
            if (!isVisible) {
                setVisible(true)
                debug("overlay shown name=$name")
            }
        } else if (isVisible) {
            setVisible(false)
            debug("overlay hidden name=$name")
        }
    }

    override fun dispose() {
        isVisible = false
        liveCount--
        debug("overlay disposed name=$name live=$liveCount")
        super.dispose()
    }

    companion object {
        @Volatile
        var liveCount = 0
    }
}

private fun debug(message: String) {
    if (System.getProperty("tvnext.player.debug") == "true") {
        println("[TVNext] $message")
    }
}
