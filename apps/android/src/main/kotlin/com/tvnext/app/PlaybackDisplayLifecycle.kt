package com.tvnext.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Screen standby can precede (or replace) Activity.onStop on a TV. */
internal class PlaybackDisplayLifecycle(
    private val context: Context,
    private val resume: () -> Unit,
    private val suspend: () -> Unit,
) {
    private var started = false
    private var resumed = false
    private var running: Boolean? = null
    private var interactive = context.getSystemService(PowerManager::class.java).isInteractive
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> interactive = false
                Intent.ACTION_SCREEN_ON -> interactive = true
                else -> return
            }
            update()
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun onStart() {
        started = true
        interactive = context.getSystemService(PowerManager::class.java).isInteractive
        update()
    }

    fun onResume() {
        resumed = true
        interactive = context.getSystemService(PowerManager::class.java).isInteractive
        update()
    }

    // TV system settings may cover the app without ever calling onStop.
    // Release MediaCodec and its SurfaceTexture before that hand-off too.
    fun onPause() { resumed = false; update() }
    fun onStop() { started = false; resumed = false; update() }

    fun close() {
        onStop()
        context.unregisterReceiver(receiver)
    }

    private fun update() {
        val next = started && resumed && interactive
        if (next == running) return
        running = next
        if (next) resume() else suspend()
    }
}
