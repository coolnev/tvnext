package com.tvnext.app.core.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** A black letterbox around PlayerView, used for exact requested display aspect ratios.
 *  Aspect enforcement is on the *outer view* and doesn't touch the player/session.
 */
internal class AndroidVideoViewport(context: Context) : FrameLayout(context) {
    val videoView: PlayerView = PlayerView(context).apply {
        useController = false
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    var aspect: VideoAspectMode = VideoAspectMode.AUTO
        set(value) {
            if (field == value) return
            field = value
            updateViewport()
        }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) updateViewport()
        }
    }

    private fun updateViewport() {
        val ratio = aspect.fixedRatio
        val target = videoViewportSize(width, height, ratio)
        val targetWidth = if (ratio != null && width > 0) target.first else LayoutParams.MATCH_PARENT
        val targetHeight = if (ratio != null && height > 0) target.second else LayoutParams.MATCH_PARENT
        val params = videoView.layoutParams as LayoutParams
        if (params.width != targetWidth || params.height != targetHeight || params.gravity != Gravity.CENTER) {
            videoView.layoutParams = LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        }
        val resize = when (aspect) {
            VideoAspectMode.AUTO, VideoAspectMode.INHERIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoAspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        if (videoView.resizeMode != resize) videoView.resizeMode = resize
    }
}
